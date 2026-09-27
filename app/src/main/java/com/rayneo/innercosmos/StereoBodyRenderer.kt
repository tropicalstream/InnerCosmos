package com.rayneo.innercosmos

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Side-by-side stereo renderer for the InnerCosmos descent (OpenGL ES 2.0).
 *
 * The world is a single continuous passage: a tube that follows the rail
 * through thirteen stops and changes radius, colour and wall texture as the
 * Mote shrinks (airway → alveolus → capillary → heart → ... → nucleus → atom).
 * Every stop has a procedural landmark (cartilage rings, alveolar bubbles,
 * red cells, a slamming valve, a chasing neutrophil, a firing axon, the lipid
 * bilayer, ATP synthase rotors, the double helix, a ribosome, an electron
 * cloud, and finally a cosmos of cells). Nothing needs textures.
 *
 * Two tours share the machinery (see TourMap): every stop names its scene, ambience family and
 * body-map position, and the passage is rebuilt when the tour changes.
 *
 * The TourDirector owns pacing (setProgress in node units 0..12) and view cuts;
 * this class owns the camera, the ship's sway, the heartbeat clock, the
 * shrink-burst / scale-jump effects and the on-screen telemetry text.
 */
class StereoBodyRenderer(
    internal val audioEngine: BodyAudioEngine,
    internal val context: Context? = null
) : GLSurfaceView.Renderer {
    internal val projection = FloatArray(16)
    internal val view = FloatArray(16)
    internal val model = FloatArray(16)
    internal val mv = FloatArray(16)
    internal val mvp = FloatArray(16)
    internal val normalM = FloatArray(16)
    internal val invM = FloatArray(16)
    internal val identityM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    internal lateinit var sphere: SphereMesh
    internal lateinit var blob: SphereMesh
    internal lateinit var rbc: ParamMesh          // biconcave red cell
    internal lateinit var capsule: ParamMesh      // rod (bacteria, organelles)
    internal lateinit var cylinder: ParamMesh     // filaments, tubes, stalks
    internal lateinit var cone: ParamMesh
    internal lateinit var tunnel: TubeMesh
    internal lateinit var moteMesh: TriMesh
    internal lateinit var cockpitMesh: LineMesh
    internal lateinit var routeMesh: LineMesh
    internal lateinit var routeNodes: PointMesh
    internal lateinit var hairMesh: LineMesh
    internal lateinit var capillaryMesh: LineMesh
    internal lateinit var trabeculaeMesh: LineMesh
    internal lateinit var dendriteMesh: LineMesh
    internal lateinit var lipidMesh: PointMesh
    internal lateinit var tailMesh: LineMesh
    internal lateinit var chromatinMesh: LineMesh
    internal lateinit var helixMesh: LineMesh
    internal lateinit var mrnaMesh: LineMesh
    internal lateinit var electronMesh: PointMesh
    internal lateinit var shellMesh: LineMesh
    internal lateinit var cellCosmos: PointMesh
    internal lateinit var antibodyMesh: LineMesh
    internal lateinit var microtubuleMesh: LineMesh
    internal lateinit var canaliculiMesh: LineMesh
    internal lateinit var glomerulusMesh: LineMesh
    internal lateinit var boneMesh: LineMesh
    internal lateinit var floorLipidMesh: PointMesh
    internal lateinit var floorTailMesh: LineMesh
    internal lateinit var fibrinMesh: LineMesh
    internal lateinit var scarMesh: LineMesh
    internal var plateShader: PlateShader? = null
    /** Chapter III's pictures, loaded from assets/plates on the GL thread; empty if absent. */
    internal val plates = HashMap<String, Plate>()
    internal lateinit var litShader: LitShader
    internal lateinit var colorShader: ColorShader
    internal lateinit var wallShader: WallShader
    internal val drift = DriftField(150)
    internal val air = AirField(96)
    internal var airFlow = 0f       // signed airspeed along the rail: + = inhale (deeper), - = exhale
    internal val bodies = BodyField(44)
    internal val dynTris = DynMesh(24)          // valve leaflets
    internal val dynLines = DynMesh(64)         // action-potential ring, spindle fibres, misc

    // The tour being rendered: its rail, wall colours, scenes and ambience families. A switch is
    // requested from any thread and applied on the GL thread (the passage meshes are rebuilt there).
    internal var map: TourMap = Tours.DESCENT
    internal var nodes: List<TourNode> = map.nodes
    internal val pendingMap = java.util.concurrent.atomic.AtomicReference<TourMap?>(null)
    internal var sentinelIdx = nodes.indexOfFirst { it.scene == Scene.SENTINEL }

    internal var width = 1
    internal var height = 1
    internal var nowSeconds = 0f
    internal var fpsFrames = 0
    internal var fpsWindowStart = 0L
    @Volatile internal var fpsNow = 0f
    internal val startNanos = System.nanoTime()
    internal var lastFrameNanos = startNanos
    internal var routeProgress = 0f
    @Volatile internal var railTarget = 0f          // written by the director (10 Hz); followed on the GL thread
    internal var viewMode = VIEW_CHASE
    internal var prevViewMode = VIEW_CHASE
    internal var viewBlend = 1f
    internal var craftYaw = 0f
    internal var craftPitch = 0f
    internal var viewListener: ((Int) -> Unit)? = null
    @Volatile internal var scripted = false
    /** Two eye viewports (the X3 Pro) or one full-width view (emulator / phone testing). */
    @Volatile var stereo = true
    /** 0 = full detail, 1 = reduced (fewer bodies, no wall veins), 2 = minimal (thermal throttling). */
    @Volatile var quality = 0
    /** Title-card mode: the Mote idles outside the nose with a slow orbit and a gentle bob. */
    @Volatile var showcase = false
    internal var maxLineWidth = 1f

    // Ship: rail position + flow sway, smoothed velocity for heading.
    internal var shipX = 0f; internal var shipY = 0f; internal var shipZ = 0f
    internal var velX = 0f; internal var velY = 0f; internal var velZ = -1f
    internal var latX = 0f; internal var latY = 0f; internal var latZ = 0f
    internal var flightInit = false
    internal var dirX = 0f; internal var dirY = 0f; internal var dirZ = -1f
    internal var sideX = 1f; internal var sideY = 0f; internal var sideZ = 0f
    internal var upX = 0f; internal var upY = 1f; internal var upZ = 0f
    internal var railCx = 0f; internal var railCy = 0f; internal var railCz = 0f   // rail centre at routeProgress

    // Camera + fx.
    internal var camNowX = 0f; internal var camNowY = 0f; internal var camNowZ = 1f
    internal var lookNowX = 0f; internal var lookNowY = 0f; internal var lookNowZ = 0f
    internal var beat = 0f
    // Scale-drop feel: the world inflates about the ship for a beat while the hull dwindles.
    internal var inflateT = 99f
    internal var inflate = 1f
    internal var shipScale = 1f
    internal var growing = false            // the current scale step is a rise (tour II), not a drop
    internal var lysisClock = 0f            // the phage stop's burst cycle (seconds)
    internal val viewWorld = FloatArray(16)
    internal val inflM = FloatArray(16)
    /** Head look-around (IMU), applied to the look direction only. */
    @Volatile var gaze: GazeCamera? = null
    internal var shakeX = 0f; internal var shakeY = 0f
    internal var shakeTX = 0f; internal var shakeTY = 0f; internal var shakeTimer = 0f
    internal var shrinkBurst = 0f
    @Volatile internal var jumpOn = false
    internal var jumpIntensity = 0f
    internal var heartPhase = 0f
    internal var heartKick = 0f
    internal var wallPulse = 0f
    internal val camA = FloatArray(6)
    internal val camB = FloatArray(6)
    internal val wallCol = FloatArray(3)

    // Arm probes (0 = folded along the hull, 1 = reaching ahead).
    internal var armReach = 0f
    internal var armKick = 0f
    internal val tmpW = FloatArray(3)
    internal val tmpS = FloatArray(3)
    internal val tmpE = FloatArray(3)
    internal val tmpT = FloatArray(3)

    // Alpha multiplier for the landmark being drawn (distance fade-in).
    internal var landmarkFade = 1f

    // Sentinel (neutrophil) chase state.
    internal var sentX = 0f; internal var sentY = 0f; internal var sentZ = 0f
    internal var sentInit = false

    internal val beaconData = FloatArray(7)
    internal val beaconBuf = ByteBuffer.allocateDirect(28).order(ByteOrder.nativeOrder()).asFloatBuffer()
    internal val flashData = floatArrayOf(
        -1f, -1f, 0f, 1f, 0.55f, 0.45f, 0f,
        1f, -1f, 0f, 1f, 0.55f, 0.45f, 0f,
        1f, 1f, 0f, 1f, 0.55f, 0.45f, 0f,
        -1f, -1f, 0f, 1f, 0.55f, 0.45f, 0f,
        1f, 1f, 0f, 1f, 0.55f, 0.45f, 0f,
        -1f, 1f, 0f, 1f, 0.55f, 0.45f, 0f
    )
    internal val flashBuf = ByteBuffer.allocateDirect(flashData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    internal val streakCount = 46
    internal val streakSeeds = FloatArray(streakCount * 2) { Math.random().toFloat() }
    internal val streakData = FloatArray(streakCount * 2 * 7)
    internal val streakBuf = ByteBuffer.allocateDirect(streakData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    // ------------------------------------------------------------------ API
    fun setViewListener(listener: (Int) -> Unit) { viewListener = listener }
    fun setScripted(on: Boolean) { scripted = on }
    fun setProgress(p: Float) { railTarget = p.coerceIn(0f, nodes.lastIndex.toFloat()) }
    /** Switch tours (any thread): the passage and route are rebuilt on the GL thread before the next frame. */
    fun setMap(m: TourMap) { if (m !== map) pendingMap.set(m) }
    /** Fired on the GL thread once a new tour's passage is built (the HUD reads the map). */
    @Volatile var mapListener: ((TourMap) -> Unit)? = null
    fun setView(mode: Int) {
        val m = mode.coerceIn(0, VIEW_COUNT - 1)
        if (m != viewMode) { prevViewMode = viewMode; viewBlend = 0f }
        viewMode = m
        viewListener?.invoke(viewMode)
    }
    fun switchView() { setView((viewMode + 1) % VIEW_COUNT) }

    /** Visual beat synced to an SFX cue: brief screen flash + camera shake. */
    fun triggerBeat(intensity: Float) { beat = max(beat, intensity.coerceIn(0f, 1f)) }

    /** One power-of-ten scale step: the world balloons, the hull dwindles, streaks, ~3 s. */
    fun triggerShrink() {
        shrinkBurst = 1f
        inflateT = 0f
        growing = false
        drift.blowOut(shipX, shipY, shipZ, 1f)
        bodies.blowOut(routeProgress, 1f)
    }

    /** The reverse step (tour II climbs the ladder several times): the world contracts about the ship, the hull swells, particles rush in. */
    fun triggerGrow() {
        shrinkBurst = 1f
        inflateT = 0f
        growing = true
        drift.blowOut(shipX, shipY, shipZ, -0.6f)
        bodies.blowOut(routeProgress, -0.6f)
    }

    /** The "lysis" cue: the phage stop's infected host bursts now, in step with the sound. */
    fun triggerLysis() { lysisClock = LYSIS_PERIOD * 0.62f; beat = max(beat, 0.6f) }

    /** A scripted heartbeat cue: kick the wall pulse and restart the beat clock. */
    fun triggerHeartbeat() { heartKick = 1f; heartPhase = 0f }

    /** Scale jump (menu): continuous streaks + tremble while the director races the rail. */
    fun setJumping(on: Boolean) { jumpOn = on }

    /** A scripted touch (spark / squelch cues): the arm probes reach out for a few seconds. */
    fun triggerProbe() { armKick = 1f }

    internal fun armReachTarget(p: Float): Float {
        var r = 0f
        for (c in map.armStops) { val d = p - c; r = max(r, exp(-(d * d) / 0.02f)) }
        return max(r, armKick)
    }

    fun currentNodeName(): String = nodes[routeProgress.toInt().coerceIn(0, nodes.lastIndex)].name

    /** Extra HUD line with camera / ship geometry (adb: --ez debug true). */
    @Volatile var debugHud = false

    /** HUD text: craft, view, departed/approaching, leg, scale ladder. */
    fun telemetry(): String {
        val dbg = if (!debugHud) "" else {
            val vx = camNowX - shipX; val vy = camNowY - shipY; val vz = camNowZ - shipZ
            val g = gaze
            "\nCAM along %.2f side %.2f up %.2f  yaw %.0f pitch %.0f  blend %.2f prev %d arm %.2f  fps %.0f q%d  infl %.2f ship %.2f\nGAZE yaw %.0f pitch %.0f  (raw hdg %.0f el %.0f) %s".format(Locale.US,
                vx * dirX + vy * dirY + vz * dirZ, vx * sideX + vy * sideY + vz * sideZ, vx * upX + vy * upY + vz * upZ,
                craftYaw, craftPitch, viewBlend, prevViewMode, armReach, fpsNow, quality, inflate, shipScale,
                (g?.yaw ?: 0f) * 57.3f, (g?.pitch ?: 0f) * 57.3f, (g?.rawYaw ?: 0f) * 57.3f, (g?.rawPitch ?: 0f) * 57.3f,
                if (g == null) "no-imu" else if (g.enabled) "on" else "off")
        }
        val floor = routeProgress.toInt().coerceIn(0, nodes.lastIndex)
        val nextIdx = (floor + 1).coerceAtMost(nodes.lastIndex)
        val frac = (routeProgress - floor).coerceIn(0f, 1f)
        val lenM = shipLengthM(routeProgress)
        val mag = (12.0 / lenM).coerceAtLeast(1.0)
        val mode = VIEW_NAMES.getOrElse(viewMode) { "BRIDGE" }
        val approaching = if (nextIdx == floor) "SURFACE" else nodes[nextIdx].name
        return "M.S.V. MOTE   ${map.hudTitle}\n" +
            "VIEW $mode   STEREO ACTIVE\n" +
            "DEPARTED ${nodes[floor].name}   APPROACHING $approaching\n" +
            "LEG ${(frac * 100f).toInt()}%   MOTE LENGTH ${fmtLength(lenM)}   MAG ${fmtMag(mag)}\n" +
            scaleLadder(lenM) + "\n" +
            "HEADING ${(((craftYaw % 360f) + 360f) % 360f).toInt()} MARK   RAIL ${"%.2f".format(Locale.US, routeProgress)} / ${nodes.lastIndex}" + dbg
    }

    /**
     * The Mote's length along the rail. A breakpoint table (progress -> metres, log-linear between
     * points) that mirrors where the script's shrink cues land: the three-decade first drop just
     * before the nostril sub-stop at 0.5, one decade per transit into each later stop, the second
     * neuron drop at the synapse sub-stop (6.5), the three-decade drop into the atom, and the
     * twelve-decade re-expansion spread across the whole Look Back leg.
     */
    internal fun shipLengthM(p: Float): Double {
        val pc = p.coerceIn(map.lengthKeys.first(), map.lengthKeys.last())
        var i = 1
        while (i < map.lengthKeys.size - 1 && map.lengthKeys[i] < pc) i++
        val p0 = map.lengthKeys[i - 1]; val p1 = map.lengthKeys[i]
        val t = if (p1 > p0) ((pc - p0) / (p1 - p0)).toDouble() else 1.0
        val a = log10(map.lengthM[i - 1]); val b = log10(map.lengthM[i])
        return 10.0.pow(a + (b - a) * t)
    }

    internal fun fmtLength(m: Double): String {
        val (v, unit) = when {
            m >= 1.0 -> m to "m"
            m >= 1e-3 -> m * 1e3 to "mm"
            m >= 1e-6 -> m * 1e6 to "µm"
            m >= 1e-9 -> m * 1e9 to "nm"
            else -> m * 1e12 to "pm"
        }
        // Two significant figures: 12 m, 1.2 mm, 120 µm — never 119.99999.
        val r = roundSig(v, 2)
        return if (r < 10.0) "%.1f %s".format(Locale.US, r, unit) else "%.0f %s".format(Locale.US, r, unit)
    }

    /** Magnification to two significant figures with thousands separators: 1,000,000× not 999,999×. */
    internal fun fmtMag(mag: Double): String {
        if (mag < 10.0) return "%.1f×".format(Locale.US, mag)
        return "%,d×".format(Locale.US, Math.round(roundSig(mag, 2)))
    }

    internal fun roundSig(v: Double, sig: Int): Double {
        if (v <= 0.0) return 0.0
        val digits = floor(log10(v)).toInt() - (sig - 1)
        val unit = 10.0.pow(digits)
        return Math.round(v / unit) * unit
    }

    /** The powers-of-ten ladder with the current rung bracketed. */
    internal fun scaleLadder(lenM: Double): String {
        val cur = log10(lenM)
        var best = 0; var bestD = Double.MAX_VALUE
        LADDER_EXP.forEachIndexed { i, e -> val d = abs(e - cur); if (d < bestD) { bestD = d; best = i } }
        val sb = StringBuilder()
        LADDER_LABELS.forEachIndexed { i, l -> sb.append(if (i == best) "[$l]" else " $l ") }
        return sb.toString()
    }

    // ------------------------------------------------------------ GL setup
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.01f, 0f, 0.012f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // Some drivers only draw 1-px lines; clamp every glLineWidth to what the GPU offers.
        val range = FloatArray(2)
        GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, range, 0)
        maxLineWidth = range[1].coerceAtLeast(1f)

        litShader = LitShader()
        colorShader = ColorShader()
        wallShader = WallShader()
        sphere = SphereMesh(22, 16)
        blob = SphereMesh(12, 8)
        rbc = ParamMesh.biconcave()
        capsule = ParamMesh.capsule(0.45f)
        cylinder = ParamMesh.cylinder()
        cone = ParamMesh.cone()
        tunnel = TubeMesh(buildTunnel())
        moteMesh = TriMesh(buildMote())
        cockpitMesh = LineMesh(buildCockpitLines())
        routeMesh = LineMesh(buildRouteLines())
        routeNodes = PointMesh(buildRouteNodes())
        hairMesh = LineMesh(buildHairs())
        capillaryMesh = LineMesh(buildCapillaries())
        trabeculaeMesh = LineMesh(buildTrabeculae())
        dendriteMesh = LineMesh(buildDendrites())
        val lipids = buildLipids()
        lipidMesh = PointMesh(lipids.first)
        tailMesh = LineMesh(lipids.second)
        chromatinMesh = LineMesh(buildChromatin())
        helixMesh = LineMesh(buildHelix())
        mrnaMesh = LineMesh(buildMrna())
        electronMesh = PointMesh(buildElectronCloud())
        shellMesh = LineMesh(buildShells())
        cellCosmos = PointMesh(buildCellCosmos())
        antibodyMesh = LineMesh(buildAntibodies())
        microtubuleMesh = LineMesh(buildMicrotubule())
        canaliculiMesh = LineMesh(buildCanaliculi())
        glomerulusMesh = LineMesh(buildGlomerulus())
        boneMesh = LineMesh(buildBoneLattice())
        val floorLipids = buildFloorLipids()
        floorLipidMesh = PointMesh(floorLipids.first)
        floorTailMesh = LineMesh(floorLipids.second)
        fibrinMesh = LineMesh(buildFibrin())
        scarMesh = LineMesh(buildScarRing())
        loadPlates()
        flashBuf.position(0); flashBuf.put(flashData); flashBuf.position(0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
    }

    /** GL thread: adopt a new tour — rebuild the passage and the route, forget the old chase state. */
    internal fun applyMap(m: TourMap) {
        map = m; nodes = m.nodes
        sentinelIdx = nodes.indexOfFirst { it.scene == Scene.SENTINEL }
        tunnel.release(); routeMesh.release(); routeNodes.release()
        tunnel = TubeMesh(buildTunnel())
        routeMesh = LineMesh(buildRouteLines())
        routeNodes = PointMesh(buildRouteNodes())
        routeProgress = railTarget.coerceIn(0f, nodes.lastIndex.toFloat())
        sentInit = false; flightInit = false
        drift.reset(); bodies.reset(); air.reset()
        lysisClock = 0f
        mapListener?.invoke(m)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val now = System.nanoTime()
        val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrameNanos = now
        val seconds = (now - startNanos) / 1_000_000_000f
        nowSeconds = seconds
        fpsFrames++
        if (now - fpsWindowStart > 1_000_000_000L) {
            fpsNow = fpsFrames * 1e9f / (now - fpsWindowStart).coerceAtLeast(1L)
            fpsFrames = 0; fpsWindowStart = now
        }
        pendingMap.getAndSet(null)?.let { applyMap(it) }
        updateFlight(dt, seconds)
        updateFx(dt)
        updateCamera(dt)
        val node = routeProgress.toInt().coerceIn(0, nodes.lastIndex)
        val amb = nodes[node].amb
        val spread = tunnelRadius(routeProgress) * 0.85f
        // Breathing in the airway: the air (and its dust) moves deeper on the inhale and back
        // toward the nose on the exhale, in step with the breath the ambience is playing.
        val breath = if (audioEngine.isRunning()) audioEngine.breathPhase01 else (seconds * 0.21f) % 1f
        val inAir = routeProgress < map.airEnd
        airFlow = if (inAir) sin(breath * 2f * PI.toFloat()) * 2.6f else 0f
        // DriftField's flow is along +z (toward the nose on this rail): negative on the inhale.
        // DriftField advects along world +z (back toward the nose): the fluid runs deeper, WITH the
        // craft, so its flow is negative; only the breath reverses.
        val dustFlow = if (amb == Amb.AIR) -airFlow * 0.7f else -flowSpeed(amb) * 0.8f
        drift.update(shipX, shipY, shipZ, spread, amb, dustFlow, dt)
        val stopIdx = (routeProgress + 0.5f).toInt().coerceIn(0, nodes.lastIndex)
        bodies.update(routeProgress, stopIdx, driftFor(stopIdx), airFlow * 0.7f, dt)
        if (inAir) air.update(shipX, shipY, shipZ, dirX, dirY, dirZ, sideX, sideY, sideZ, upX, upY, upZ, spread, airFlow, dt)

        // Fixed FOV: on a head-worn display the rendered field must stay matched to the optics.
        // The shrink burst is a short camera dolly + streaks (see updateCamera), never a zoom.
        if (stereo) {
            val halfWidth = width / 2
            Matrix.perspectiveM(projection, 0, 58f, halfWidth.toFloat() / height.toFloat(), 0.15f, 220f)
            drawEye(0, halfWidth, -EYE_OFFSET, seconds)
            drawEye(halfWidth, width - halfWidth, EYE_OFFSET, seconds)
        } else {
            Matrix.perspectiveM(projection, 0, 58f, width.toFloat() / height.toFloat(), 0.15f, 220f)
            drawEye(0, width, 0f, seconds)
        }
    }

    internal fun drawEye(x: Int, viewportWidth: Int, eyeOffset: Float, seconds: Float) {
        GLES20.glViewport(x, 0, viewportWidth, height)
        val ex = camNowX + sideX * eyeOffset; val ey = camNowY + sideY * eyeOffset; val ez = camNowZ + sideZ * eyeOffset
        val lx = lookNowX + sideX * eyeOffset * 0.35f
        val ly = lookNowY + sideY * eyeOffset * 0.35f
        val lz = lookNowZ + sideZ * eyeOffset * 0.35f
        Matrix.setLookAtM(view, 0, ex, ey, ez, lx, ly, lz, 0f, 1f, 0f)

        // The scale drop: the world (not the ship) is scaled about the ship's position, so walls
        // rush outward and everything ahead recedes; identical to the plain view when inflate = 1.
        if (abs(inflate - 1f) > 0.0005f) {
            Matrix.setIdentityM(inflM, 0)
            Matrix.translateM(inflM, 0, shipX, shipY, shipZ)
            Matrix.scaleM(inflM, 0, inflate, inflate, inflate)
            Matrix.translateM(inflM, 0, -shipX, -shipY, -shipZ)
            Matrix.multiplyMM(viewWorld, 0, view, 0, inflM, 0)
            System.arraycopy(view, 0, inflM, 0, 16)          // keep the plain view for the ship
            System.arraycopy(viewWorld, 0, view, 0, 16)
        } else {
            System.arraycopy(view, 0, inflM, 0, 16)
        }
        drawTunnel(seconds)
        drawRoute()
        drawLandmarks(seconds)
        drawBodies(seconds)
        drawDrift()
        drawAir()
        drawBeacon(seconds)
        System.arraycopy(inflM, 0, view, 0, 16)
        when (viewMode) {
            VIEW_BRIDGE -> drawCockpit()
            VIEW_CHASE -> drawMote(seconds)
            VIEW_ENGINEERING -> drawDriveCore(seconds)
            VIEW_OBSERVATION -> drawMote(seconds)
        }
        drawStreaks(seconds)
        drawFlash()
    }

    // ---------------------------------------------------------- simulation
    internal fun flowSpeed(amb: Amb): Float = when (amb) {
        Amb.AIR -> 2.2f
        Amb.BLOOD -> 3.2f
        Amb.NEURAL -> 1.6f
        Amb.CYTO -> 0.9f
        Amb.ATOM -> 0.15f
        Amb.LOOKBACK -> 0.6f
        Amb.GUT -> 1.3f
        Amb.MUSCLE -> 0.5f
        Amb.MOTOR -> 0.7f
    }

    internal fun updateFlight(dt: Float, seconds: Float) {
        if (!scripted) {
            // Free drift for testing without the director: ~25 s per node.
            routeProgress = (routeProgress + dt / 25f).coerceIn(0f, nodes.lastIndex.toFloat())
        } else {
            // Follow the director's 10 Hz value with a critically damped lag so the walls glide
            // instead of stepping; a large gap (menu pick, resume) is an intentional teleport.
            val target = railTarget
            if (abs(target - routeProgress) > 0.5f) routeProgress = target
            else routeProgress += (target - routeProgress) * (1f - exp(-dt * 8f))
        }
        val f = frameAt(routeProgress)
        dirX = f.dx; dirY = f.dy; dirZ = f.dz
        sideX = f.sx; sideY = f.sy; sideZ = f.sz
        upX = f.ux; upY = f.uy; upZ = f.uz
        railCx = f.cx; railCy = f.cy; railCz = f.cz
        val node = routeProgress.toInt().coerceIn(0, nodes.lastIndex)
        val amb = nodes[node].amb

        // Flow sway: the Mote is carried, not flown. Two slow sines inside the passage,
        // plus a surge on each heartbeat in the vessels.
        val r = tunnelRadius(routeProgress)
        val swayA = 0.20f * r * sin(seconds * 0.55f)
        val swayB = 0.14f * r * sin(seconds * 0.83f + 1.3f)
        val surge = if (amb == Amb.BLOOD) exp(-heartPhase * 5f) * 0.18f else if (amb == Amb.AIR) airFlow * 0.05f else if (amb == Amb.MUSCLE) 0.06f * sin(seconds * 1.6f) else 0f
        val bob = if (showcase) 0.18f * sin(seconds * 0.8f) else 0f
        val tx = f.sx * swayA + f.ux * (swayB + bob) + f.dx * surge
        val ty = f.sy * swayA + f.uy * (swayB + bob) + f.dy * surge
        val tz = f.sz * swayA + f.uz * (swayB + bob) + f.dz * surge
        val k = 1f - exp(-dt * 1.4f)
        latX += (tx - latX) * k; latY += (ty - latY) * k; latZ += (tz - latZ) * k

        var sx = f.cx + latX; var sy = f.cy + latY; var sz = f.cz + latZ
        // Clearance from the Sentinel while it chases: an eased nudge through the sway offset,
        // never a one-frame snap (the cameras hang off the ship position).
        if (sentInit) {
            val rx = sx - sentX; val ry = sy - sentY; val rz = sz - sentZ
            val d = sqrt(rx * rx + ry * ry + rz * rz)
            val clr = 1.9f
            if (d < clr && d > 1e-3f) {
                val push = (clr - d) * (1f - exp(-dt * 6f))
                val nx = rx / d; val ny = ry / d; val nz = rz / d
                latX += nx * push; latY += ny * push; latZ += nz * push
                sx += nx * push; sy += ny * push; sz += nz * push
            }
        }
        if (flightInit) {
            velX += ((sx - shipX) - velX) * 0.25f
            velY += ((sy - shipY) - velY) * 0.25f
            velZ += ((sz - shipZ) - velZ) * 0.25f
        }
        shipX = sx; shipY = sy; shipZ = sz; flightInit = true

        // Heading: the hull is locked to the rail direction and only leans a little into the
        // flow sway (a craft carried by a current, not one spinning in it).
        // rotateM about +Y maps the nose (0,0,-1) to (-sin yaw, -cos yaw): yaw = atan2(-dx, -dz).
        val railYaw = atan2(-dirX, -dirZ) * 180f / PI.toFloat()
        val vs = velX * sideX + velY * sideY + velZ * sideZ
        val vu = velX * upX + velY * upY + velZ * upZ
        val vd = (velX * dirX + velY * dirY + velZ * dirZ).coerceAtLeast(1e-3f)
        val swayYaw = (atan2(vs, vd) * 180f / PI.toFloat()).coerceIn(-12f, 12f)
        val swayPitch = (atan2(vu, vd) * 180f / PI.toFloat()).coerceIn(-8f, 8f)
        run {
            var delta = (railYaw - swayYaw) - craftYaw
            while (delta > 180f) delta -= 360f
            while (delta < -180f) delta += 360f
            craftYaw += delta * (1f - exp(-dt * 3f))
        }
        val railPitch = atan2(dirY, sqrt(dirX * dirX + dirZ * dirZ).coerceAtLeast(1e-4f)) * 180f / PI.toFloat()
        craftPitch += ((railPitch + swayPitch) - craftPitch) * (1f - exp(-dt * 3f))

        // Arm probes: fold along the hull, reach out where the crew touches the world.
        armKick = (armKick - dt / 3f).coerceAtLeast(0f)
        val armTarget = if (showcase) 0.22f + 0.16f * sin(seconds * 0.9f) else armReachTarget(routeProgress)
        armReach += (armTarget - armReach) * (1f - exp(-dt * 2f))

        // Heartbeat clock (visual): phase-locked to the audio engine's beat so the wall pulse
        // and the audible lub-dub coincide; free-runs at the same period if audio is stopped.
        if (audioEngine.isRunning()) {
            heartPhase = audioEngine.beatPhaseSec
        } else {
            heartPhase += dt
            if (heartPhase >= HEART_PERIOD) heartPhase -= HEART_PERIOD
        }
        heartKick = (heartKick - dt * 2.5f).coerceAtLeast(0f)
        val heartOn = if (amb == Amb.BLOOD) 1f else 0f
        wallPulse = heartOn * exp(-heartPhase * 6f) * 0.9f + heartKick

        // Sentinel: waits at its node, then chases the Mote through the vessel (tours that have one).
        if (sentinelIdx < 0) return
        val sn = nodes[sentinelIdx]
        val si = sentinelIdx.toFloat()
        if (!sentInit) { sentX = sn.x + 1.2f; sentY = sn.y - 0.6f; sentZ = sn.z + 2f; sentInit = true }
        val chase = routeProgress in (si - 0.45f)..(si + 0.75f)
        val tgtX: Float; val tgtY: Float; val tgtZ: Float
        if (chase) {
            tgtX = shipX + dirX * 3.4f + sideX * (1.3f * sin(seconds * 0.9f)) + upX * (0.5f * sin(seconds * 1.3f))
            tgtY = shipY + dirY * 3.4f + sideY * (1.3f * sin(seconds * 0.9f)) + upY * (0.5f * sin(seconds * 1.3f))
            tgtZ = shipZ + dirZ * 3.4f + sideZ * (1.3f * sin(seconds * 0.9f)) + upZ * (0.5f * sin(seconds * 1.3f))
        } else if (routeProgress > si + 0.75f) {
            // Chase over: it drops behind and hugs the wall, never crossing the Mote's lane.
            tgtX = shipX - dirX * 5f + sideX * 2.4f - upX * 0.6f
            tgtY = shipY - dirY * 5f + sideY * 2.4f - upY * 0.6f
            tgtZ = shipZ - dirZ * 5f + sideZ * 2.4f - upZ * 0.6f
        } else {
            tgtX = sn.x + 1.2f; tgtY = sn.y - 0.6f; tgtZ = sn.z + 2f
        }
        val sk = 1f - exp(-dt * (if (chase) 1.1f else 2.5f))
        sentX += (tgtX - sentX) * sk; sentY += (tgtY - sentY) * sk; sentZ += (tgtZ - sentZ) * sk
    }

    internal fun smooth01(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    internal fun updateFx(dt: Float) {
        beat = (beat - dt * 3.2f).coerceAtLeast(0f)
        shrinkBurst = (shrinkBurst - dt / SHRINK_SEC).coerceAtLeast(0f)
        // The drop: everything around the ship swells to ~2.6x within half a second, then the
        // camera "catches up" as the swell relaxes over a few seconds under the streaks; the
        // hull itself shrinks to a third in the external view and grows back as we settle.
        inflateT += dt
        val attack = smooth01(inflateT / 0.45f)
        val relax = exp(-(inflateT - 0.45f).coerceAtLeast(0f) / 2.2f)
        val swell = attack * relax
        val dwindle = smooth01(inflateT / 0.5f) * exp(-(inflateT - 0.6f).coerceAtLeast(0f) / 1.3f)
        // A rise (tour II) is the mirror image: the world contracts about the ship and the hull swells.
        inflate = if (growing) 1f / (1f + 1.2f * swell) else 1f + 1.6f * swell
        shipScale = if (growing) 1f + 0.9f * dwindle else 1f - 0.62f * dwindle
        lysisClock += dt
        if (lysisClock >= LYSIS_PERIOD) lysisClock -= LYSIS_PERIOD
        jumpIntensity = if (jumpOn) (jumpIntensity + dt * 2.2f).coerceAtMost(1f) else (jumpIntensity - dt * 2.2f).coerceAtLeast(0f)
        val tremble = max(beat, max(jumpIntensity * 0.45f, sin(shrinkBurst * PI.toFloat()) * 0.35f))
        // Low-passed tremble (new target ~12 times a second, eased), capped small for the HMD.
        shakeTimer += dt
        if (shakeTimer > 1f / 12f) {
            shakeTimer = 0f
            shakeTX = ((Math.random().toFloat() - 0.5f) * tremble * 0.06f).coerceIn(-0.03f, 0.03f)
            shakeTY = ((Math.random().toFloat() - 0.5f) * tremble * 0.06f).coerceIn(-0.03f, 0.03f)
        }
        val k = 1f - exp(-dt * 25f)
        shakeX += (shakeTX - shakeX) * k
        shakeY += (shakeTY - shakeY) * k
    }

    /** Camera position (0..2) and look-at (3..5) for a view mode. */
    internal fun camForMode(mode: Int, out: FloatArray) {
        val px = shipX; val py = shipY; val pz = shipZ
        when (mode) {
            VIEW_CHASE -> {
                // A slow orbit around the stern (about 40 s per sweep) with a gentle bob: a
                // lingering stop still reads as a camera move, never a freeze-frame.
                val a = sin(nowSeconds * (if (showcase) 0.24f else 0.16f)) * 0.62f
                val back = 3.3f * cos(a); val swing = 3.3f * sin(a)
                val lift = 0.95f + 0.22f * sin(nowSeconds * 0.11f + 1f)
                out[0] = px - dirX * back + sideX * swing + upX * lift
                out[1] = py - dirY * back + sideY * swing + upY * lift
                out[2] = pz - dirZ * back + sideZ * swing + upZ * lift
                out[3] = px + dirX * 2.5f; out[4] = py + dirY * 2.5f; out[5] = pz + dirZ * 2.5f
            }
            VIEW_ENGINEERING -> {   // inside the hull, aft of the core, looking forward through it
                out[0] = px - dirX * 0.34f + upX * 0.05f; out[1] = py - dirY * 0.34f + upY * 0.05f; out[2] = pz - dirZ * 0.34f + upZ * 0.05f
                out[3] = px + dirX * 2.5f; out[4] = py + dirY * 2.5f; out[5] = pz + dirZ * 2.5f
            }
            VIEW_OBSERVATION -> {
                // Lateral offsets scale with the passage so the deck never pokes through a capillary wall.
                val r = tunnelRadius(routeProgress)
                val so = min(1.35f, 0.45f * r); val uo = min(0.55f, 0.18f * r)
                val along = -1.1f + 0.5f * sin(nowSeconds * 0.07f)         // slow dolly along the hull
                out[0] = px + dirX * along + sideX * so + upX * uo
                out[1] = py + dirY * along + sideY * so + upY * uo
                out[2] = pz + dirZ * along + sideZ * so + upZ * uo
                out[3] = px + dirX * 3.5f; out[4] = py + dirY * 3.5f; out[5] = pz + dirZ * 3.5f
            }
            else -> {   // bridge: behind the porthole (drawn 0.36 behind the ship), looking ahead
                out[0] = px - dirX * 0.60f + upX * 0.16f; out[1] = py - dirY * 0.60f + upY * 0.16f; out[2] = pz - dirZ * 0.60f + upZ * 0.16f
                out[3] = px + dirX * 4f; out[4] = py + dirY * 4f + 0.05f; out[5] = pz + dirZ * 4f
            }
        }
        clampToTube(out)
    }

    /** Keep a camera inside the passage: limit its lateral distance from the rail centre. */
    internal fun clampToTube(out: FloatArray) {
        val r = tunnelRadius(routeProgress) * 0.72f
        val vx = out[0] - railCx; val vy = out[1] - railCy; val vz = out[2] - railCz
        val along = vx * dirX + vy * dirY + vz * dirZ
        val lx = vx - along * dirX; val ly = vy - along * dirY; val lz = vz - along * dirZ
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        if (ll > r && ll > 1e-4f) {
            val s = r / ll
            out[0] = railCx + along * dirX + lx * s
            out[1] = railCy + along * dirY + ly * s
            out[2] = railCz + along * dirZ + lz * s
        }
    }

    internal fun updateCamera(dt: Float) {
        viewBlend = (viewBlend + dt / VIEW_TRANSITION_SEC).coerceAtMost(1f)
        val t = viewBlend * viewBlend * (3f - 2f * viewBlend)
        camForMode(prevViewMode, camA)
        camForMode(viewMode, camB)
        // A shrink is felt as a short push forward along the rail (plus streaks), not a zoom.
        val dolly = sin(shrinkBurst * PI.toFloat()) * (if (growing) -0.35f else 0.45f)
        camNowX = camA[0] + (camB[0] - camA[0]) * t + shakeX + dirX * dolly
        camNowY = camA[1] + (camB[1] - camA[1]) * t + shakeY + dirY * dolly
        camNowZ = camA[2] + (camB[2] - camA[2]) * t + dirZ * dolly
        lookNowX = camA[3] + (camB[3] - camA[3]) * t + dirX * dolly
        lookNowY = camA[4] + (camB[4] - camA[4]) * t + dirY * dolly
        lookNowZ = camA[5] + (camB[5] - camA[5]) * t + dirZ * dolly
        // Head look-around: rotate the look direction by the gaze offset (yaw about world up,
        // pitch about the camera's right), leaving the camera position and the rail alone.
        val g = gaze
        if (g != null && (abs(g.yaw) > 1e-4f || abs(g.pitch) > 1e-4f)) {
            var fx = lookNowX - camNowX; var fy = lookNowY - camNowY; var fz = lookNowZ - camNowZ
            val len = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-4f)
            fx /= len; fy /= len; fz /= len
            var rx = -fz; var ry = 0f; var rz = fx                       // right = f x up(0,1,0) = (-fz, 0, fx)
            val rl = sqrt(rx * rx + rz * rz).coerceAtLeast(1e-4f); rx /= rl; rz /= rl
            val ux = ry * fz - rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy - ry * fx   // up = r x f
            val cy = cos(g.yaw); val sy = sin(g.yaw); val cp = cos(g.pitch); val sp = sin(g.pitch)
            val nx = (fx * cy + rx * sy) * cp + ux * sp
            val ny = (fy * cy + ry * sy) * cp + uy * sp
            val nz = (fz * cy + rz * sy) * cp + uz * sp
            lookNowX = camNowX + nx * len; lookNowY = camNowY + ny * len; lookNowZ = camNowZ + nz * len
        }
    }

    // The Mote's bow lamp lights the world: just ahead of the ship.
    internal fun lampX() = shipX + dirX * 0.7f
    internal fun lampY() = shipY + dirY * 0.7f
    internal fun lampZ() = shipZ + dirZ * 0.7f

    // ---------------------------------------------------------- draw: world
    internal fun drawTunnel(seconds: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        // Time wraps at a common period of every sin(uTime * k) in the shader (k = 1.5, 0.3) so the
        // argument stays small for half-precision GPUs without a visible seam.
        // At the look-back the craft has left the body: the passage wall dissolves so the whole
        // figure can be seen (and so the figure is never buried in the wall).
        val wallAlpha = lookBackWallAlpha()
        if (wallAlpha < 0.01f) { GLES20.glEnable(GLES20.GL_CULL_FACE); return }
        if (wallAlpha < 0.999f) GLES20.glDepthMask(false)
        wallShader.use(mvp, model, lampX(), lampY(), lampZ(), seconds % TIME_WRAP, wallPulse, 0.02f, wallAlpha, if (quality == 0) 1f else 0f)
        tunnel.draw(wallShader.positionHandle, wallShader.normalHandle, wallShader.colorHandle)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    /** Height of a 1.7 m person in scene units at the craft's current length (1.5 units = the Mote). */
    internal fun personHeightUnits(): Float = (1.7 / shipLengthM(routeProgress) * 1.5).toFloat()

    /** 1 inside the body; fades to 0 as the whole person comes into view at the look-back. */
    internal fun lookBackWallAlpha(): Float {
        if (nodes.last().scene != Scene.LOOKBACK || routeProgress < nodes.lastIndex - 1.2f) return 1f
        val h = personHeightUnits()
        return ((h - 45f) / 60f).coerceIn(0f, 1f)
    }

    internal fun drawRoute() {
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 1f)
        routeMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
        colorShader.use(mvp, 6f, points = true)
        routeNodes.draw(colorShader.positionHandle, colorShader.colorHandle)
    }

    internal fun drawDrift() {
        GLES20.glDepthMask(false)
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 3.2f, points = true)
        drift.draw(colorShader.positionHandle, colorShader.colorHandle)
        GLES20.glDepthMask(true)
    }

    /** Airflow streaks (nodes 0-2), fading out as the ride leaves the lungs. */
    internal fun drawAir() {
        val fade = ((map.airEnd - routeProgress) / 0.5f).coerceIn(0f, 1f)
        if (fade <= 0f) return
        GLES20.glDepthMask(false)
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.globalFade = fade
        colorShader.use(mvp, 1f)
        lineWidth(2f)
        air.draw(colorShader.positionHandle, colorShader.colorHandle)
        lineWidth(1f)
        colorShader.globalFade = 1f
        GLES20.glDepthMask(true)
    }

    /** The drift spec for one stop of the current tour: the tour's own table, else the ambience default. */
    internal fun driftFor(stop: Int): DriftSpec {
        val table = when (map.id) { 1 -> DESCENT_DRIFT; 2 -> MACHINE_DRIFT; else -> BETHUNE_DRIFT }
        return table[stop] ?: DriftSpec.forAmb(nodes[stop.coerceIn(0, nodes.lastIndex)].amb)
    }

    /** µm per scene unit right now: the Mote (1.5 units) is shipLengthM long. */
    internal fun umPerUnit(): Float = (shipLengthM(routeProgress) * 1e6 / 1.5).toFloat()

    internal fun drawBodies(seconds: Float) {
        val n = bodies.live(quality)
        if (n == 0 || bodies.fade <= 0.01f) return
        val upu = umPerUnit()
        for (i in 0 until n) {
            val kind = bodies.kind[i]
            if (kind == BodyField.NONE) continue
            val rad = BodyField.DIAMETER_UM[kind] * 0.5f / upu * bodies.jitter[i]
            val pr = bodies.p[i]
            val tr = tunnelRadius(pr)
            // Too small to see, or too big to fit this passage: not drawn at this magnification.
            if (rad < 0.02f || rad > tr * 0.45f) continue
            val f = frameAt(pr)
            val rr = (tr - rad) * bodies.r[i]
            val ca = cos(bodies.a[i]); val sa = sin(bodies.a[i])
            val x = f.cx + (f.sx * ca + f.ux * sa) * rr
            val y = f.cy + (f.sy * ca + f.uy * sa) * rr
            val z = f.cz + (f.sz * ca + f.uz * sa) * rr
            if ((x - camNowX) * dirX + (y - camNowY) * dirY + (z - camNowZ) * dirZ < -1f) continue
            // A body almost touching the lens fills the frame with a meaningless blur: skip it.
            val cdx = x - camNowX; val cdy = y - camNowY; val cdz = z - camNowZ
            if (cdx * cdx + cdy * cdy + cdz * cdz < (rad * 2.2f + 0.45f) * (rad * 2.2f + 0.45f)) continue
            val al = bodies.fade
            val tb = bodies.tumble[i]
            when (kind) {
                BodyField.RED_CELL -> {
                    // Red cells in flow tumble in 3D: the disc normal wanders around the rail axis and
                    // tilts toward and away from it, so the biconcave faces turn to the viewer.
                    val tilt = 0.9f * sin(tb * 0.7f + bodies.spin[i] * 6.3f)
                    val ct = cos(tilt); val st = sin(tilt)
                    val nx = (f.sx * cos(tb) + f.ux * sin(tb)) * ct + f.dx * st
                    val ny = (f.sy * cos(tb) + f.uy * sin(tb)) * ct + f.dy * st
                    val nz = (f.sz * cos(tb) + f.uz * sin(tb)) * ct + f.dz * st
                    // (the basis' z just needs to be any direction not parallel to the normal)
                    drawBasis(x, y, z, f.dx * ct - (f.sx * cos(tb) + f.ux * sin(tb)) * st, f.dy * ct - (f.sy * cos(tb) + f.uy * sin(tb)) * st,
                        f.dz * ct - (f.sz * cos(tb) + f.uz * sin(tb)) * st, nx, ny, nz, rad, rad, rad, rbc,
                        if (bodies.oxy) COL_RBC_OXY else COL_RBC_DEOXY, COL_RBC_RIM, al, 0f, 0f)
                }
                BodyField.PLATELET -> {
                    val nx = f.sx * cos(tb) + f.ux * sin(tb); val ny = f.sy * cos(tb) + f.uy * sin(tb); val nz = f.sz * cos(tb) + f.uz * sin(tb)
                    drawBasis(x, y, z, f.dx, f.dy, f.dz, nx, ny, nz, rad, rad * 0.35f, rad * 0.8f, blob, COL_PLATELET, COL_LAMP, al, 0.4f, 0f)
                }
                BodyField.WHITE_CELL -> drawSphereAt(x, y, z, rad, rad * 0.95f, rad, COL_WHITE_CELL, COL_WHITE_CELL_DARK, 0.95f * al, tb * 20f, 0f, 1f, 0.3f, sphere, 1f)
                BodyField.DUST -> drawSphereAt(x, y, z, rad, rad * 0.7f, rad * 0.85f, COL_DUST, COL_DUST, 0.9f * al, tb * 30f, 0.3f, 1f, 0.5f, blob)
                BodyField.POLLEN -> drawSphereAt(x, y, z, rad, rad, rad, COL_POLLEN, COL_LAMP, al, tb * 20f, 0f, 1f, 0f, blob, 1f)
                BodyField.PROTEIN -> drawSphereAt(x, y, z, rad, rad * 0.8f, rad * 1.2f, COL_PROTEIN, COL_LAMP, al, tb * 40f, 0.5f, 1f, 0.5f, blob, 1f)
                BodyField.VESICLE -> drawSphereAt(x, y, z, rad, rad, rad, COL_VESICLE, COL_LAMP, 0.45f * al, 0f, 0f, 1f, 0f, blob)
                BodyField.TRANSMITTER -> drawSphereAt(x, y, z, rad, rad, rad, COL_TRANSMITTER, COL_LAMP, al, 0f, 0f, 1f, 0f, blob, 0f, 0.5f)
                BodyField.BACTERIUM -> drawBasis(x, y, z, f.sx * ca + f.ux * sa, f.sy * ca + f.uy * sa, f.sz * ca + f.uz * sa,
                    f.dx, f.dy, f.dz, rad * 0.4f, rad * 0.4f, rad, capsule, COL_BACTERIUM, COL_BACTERIUM_DARK, al, 0.3f, 0f)
                BodyField.CHYLE -> drawSphereAt(x, y, z, rad, rad, rad, COL_CHYLE, COL_LAMP, 0.7f * al, 0f, 0f, 1f, 0f, blob, 0f, 0.2f)
            }
        }
    }

    /**
     * Draw [mesh] at (x,y,z) with its local z axis along (zx,zy,zz) and local y along (yx,yy,yz)
     * (x completes a right-handed basis), scaled (sx,sy,sz) in local axes. The general way to
     * orient a shaped primitive: a red cell face-on to the flow, a filament along its track, a
     * cone pointing at a target.
     */
    internal fun drawBasis(
        x: Float, y: Float, z: Float, zx: Float, zy: Float, zz: Float, yx0: Float, yy0: Float, yz0: Float,
        sx: Float, sy: Float, sz: Float, mesh: LitMesh, base: FloatArray, accent: FloatArray,
        alpha: Float, pattern: Float, glow: Float
    ) {
        var zl = sqrt(zx * zx + zy * zy + zz * zz).coerceAtLeast(1e-6f)
        val Zx = zx / zl; val Zy = zy / zl; val Zz = zz / zl
        // Make y orthogonal to z.
        val d = yx0 * Zx + yy0 * Zy + yz0 * Zz
        var Yx = yx0 - d * Zx; var Yy = yy0 - d * Zy; var Yz = yz0 - d * Zz
        zl = sqrt(Yx * Yx + Yy * Yy + Yz * Yz)
        if (zl < 1e-5f) { Yx = if (abs(Zy) < 0.9f) 0f else 1f; Yy = if (abs(Zy) < 0.9f) 1f else 0f; Yz = 0f
            val d2 = Yx * Zx + Yy * Zy + Yz * Zz; Yx -= d2 * Zx; Yy -= d2 * Zy; Yz -= d2 * Zz; zl = sqrt(Yx * Yx + Yy * Yy + Yz * Yz) }
        Yx /= zl; Yy /= zl; Yz /= zl
        val Xx = Yy * Zz - Yz * Zy; val Xy = Yz * Zx - Yx * Zz; val Xz = Yx * Zy - Yy * Zx
        model[0] = Xx * sx; model[1] = Xy * sx; model[2] = Xz * sx; model[3] = 0f
        model[4] = Yx * sy; model[5] = Yy * sy; model[6] = Yz * sy; model[7] = 0f
        model[8] = Zx * sz; model[9] = Zy * sz; model[10] = Zz * sz; model[11] = 0f
        model[12] = x; model[13] = y; model[14] = z; model[15] = 1f
        drawLitModel(mesh, base, accent, alpha * landmarkFade, pattern, glow)
    }

    internal fun drawBeacon(seconds: Float) {
        val idx = (routeProgress.toInt() + 1).coerceIn(0, nodes.lastIndex)
        val frac = routeProgress - routeProgress.toInt()
        if (frac < 0.45f || idx == routeProgress.toInt()) return        // only once we are truly under way
        val b = nodes[idx]
        val pulse = 0.5f + 0.5f * sin(seconds * 3f)
        beaconData[0] = b.x; beaconData[1] = b.y + 0.6f; beaconData[2] = b.z
        beaconData[3] = 1f; beaconData[4] = 0.77f; beaconData[5] = 0.42f; beaconData[6] = 0.10f + 0.22f * pulse
        beaconBuf.position(0); beaconBuf.put(beaconData); beaconBuf.position(0)
        GLES20.glDepthMask(false)
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 4f + 3f * pulse, points = true)
        beaconBuf.position(0)
        GLES20.glVertexAttribPointer(colorShader.positionHandle, 3, GLES20.GL_FLOAT, false, 28, beaconBuf)
        GLES20.glEnableVertexAttribArray(colorShader.positionHandle)
        beaconBuf.position(3)
        GLES20.glVertexAttribPointer(colorShader.colorHandle, 4, GLES20.GL_FLOAT, false, 28, beaconBuf)
        GLES20.glEnableVertexAttribArray(colorShader.colorHandle)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
        GLES20.glDisableVertexAttribArray(colorShader.positionHandle)
        GLES20.glDisableVertexAttribArray(colorShader.colorHandle)
        GLES20.glDepthMask(true)
    }

    // ----------------------------------------------------- draw: landmarks
    internal fun drawLandmarks(seconds: Float) {
        for (i in nodes.indices) {
            val n = nodes[i]
            // Reveal each stop's landmark only as the Mote gets close (full at 0.8 node away,
            // gone beyond 1.3), so the next stop never hangs as a bright target down the passage.
            val reach = (if (n.scene == Scene.MEMBRANE) 1.0f else 1.3f) - 0.25f * quality
            val fade = ((reach - abs(routeProgress - i)) / 0.5f).coerceIn(0f, 1f)
            if (fade <= 0f) continue
            // Landmarks well behind the camera cost draw calls and show nothing — but a few scenes
            // reach a long way past their node (the mouth's teeth and tongue, the phage's second
            // host), so the test uses the scene's deepest part, not the node origin.
            val deep = when (n.scene) { Scene.MOUTH -> 1.0f; Scene.PHAGE -> 0.8f; Scene.GUT -> 0.4f; else -> 0f }
            val c = if (deep > 0f) frameAt(i + deep) else null
            val ox = c?.cx ?: n.x; val oy = c?.cy ?: n.y; val oz = c?.cz ?: n.z
            if ((ox - camNowX) * dirX + (oy - camNowY) * dirY + (oz - camNowZ) * dirZ < -8f) continue
            landmarkFade = fade
            colorShader.globalFade = fade
            when (n.scene) {
                Scene.THRESHOLD -> drawThreshold(n, i, seconds)
                Scene.AIRWAY -> drawAirway(n, i, seconds)
                Scene.ALVEOLUS -> drawAlveolus(n, i, seconds)
                Scene.BLOOD -> drawBloodstream(n, i, seconds)
                Scene.HEART -> drawHeart(n, i, seconds)
                Scene.SENTINEL -> drawSentinel(n, i, seconds)
                Scene.NEURON -> drawNeuron(n, i, seconds)
                Scene.MEMBRANE -> drawMembrane(n, i, seconds)
                Scene.MITOCHONDRION -> drawMitochondrion(n, i, seconds)
                Scene.NUCLEUS -> drawNucleus(n, i, seconds)
                Scene.RIBOSOME -> drawRibosome(n, i, seconds)
                Scene.ATOM -> drawAtom(n, i, seconds)
                Scene.LOOKBACK -> drawLookBack(n, i, seconds)
                Scene.MOUTH -> drawMouth(n, i, seconds)
                Scene.GUT -> drawGut(n, i, seconds)
                Scene.PHAGE -> drawPhage(n, i, seconds)
                Scene.LIVER -> drawLiver(n, i, seconds)
                Scene.KIDNEY -> drawKidney(n, i, seconds)
                Scene.MUSCLE -> drawMuscle(n, i, seconds)
                Scene.MARROW -> drawMarrow(n, i, seconds)
                Scene.VDJ -> drawVdj(n, i, seconds)
                Scene.HIGHWAY -> drawHighway(n, i, seconds)
                Scene.FACTORY -> drawFactory(n, i, seconds)
                Scene.MOTOR -> drawMotor(n, i, seconds)
                Scene.DIVISION -> drawDivision(n, i, seconds)
                Scene.CAVITY -> drawCavity(n, i, seconds)
                Scene.DONOR -> drawDonor(n, i, seconds)
                Scene.STORED -> drawStored(n, i, seconds)
                Scene.WOUND -> drawWound(n, i, seconds)
                Scene.SUTURE -> drawSuture(n, i, seconds)
                Scene.SEPSIS -> drawSepsis(n, i, seconds)
                Scene.TRANSFUSION -> drawTransfusion(n, i, seconds)
                Scene.STUDENTS -> drawStudents(n, i, seconds)
                Scene.CUT -> drawCut(n, i, seconds)
            }
        }
        landmarkFade = 1f
        colorShader.globalFade = 1f
    }














    // ------------------------------------------------ draw: tour II landmarks
    // Frame-relative helpers: (along, side, up) offsets from a rail frame; along > 0 is deeper.
    internal fun ca(f: Frame) = f.ux
    internal fun sa(f: Frame) = f.uz
    internal fun fx(f: Frame, a: Float, s: Float, u: Float) = f.cx + f.dx * a + f.sx * s + f.ux * u
    internal fun fy(f: Frame, a: Float, s: Float, u: Float) = f.cy + f.dy * a + f.sy * s + f.uy * u
    internal fun fz(f: Frame, a: Float, s: Float, u: Float) = f.cz + f.dz * a + f.sz * s + f.uz * u

    internal fun blobAt(
        f: Frame, a: Float, s: Float, u: Float, sx: Float, sy: Float, sz: Float, base: FloatArray, accent: FloatArray,
        alpha: Float = 1f, rotDeg: Float = 0f, ax: Float = 0f, ay: Float = 1f, az: Float = 0f,
        mesh: LitMesh = blob, pattern: Float = 0f, glow: Float = 0f
    ) = drawSphereAt(fx(f, a, s, u), fy(f, a, s, u), fz(f, a, s, u), sx, sy, sz, base, accent, alpha, rotDeg, ax, ay, az, mesh, pattern, glow)

    internal fun strutAt(f: Frame, a0: Float, s0: Float, u0: Float, a1: Float, s1: Float, u1: Float, radius: Float, base: FloatArray, accent: FloatArray, glow: Float = 0f) =
        drawStrut(fx(f, a0, s0, u0), fy(f, a0, s0, u0), fz(f, a0, s0, u0), fx(f, a1, s1, u1), fy(f, a1, s1, u1), fz(f, a1, s1, u1), radius, base, accent, glow)














    // ---------------------------------------------------------- picture plates
    /**
     * Chapter III shows three real pictures of Bethune. They are the only bitmaps in the whole
     * app — everything else is procedural — so the loader is deliberately forgiving: if the
     * assets are missing (or a device refuses them) the chapter simply runs without them.
     */
    internal fun loadPlates() {
        val ctx = context ?: return
        plateShader = PlateShader()
        for (name in PLATE_FILES) {
            try {
                val bmp = ctx.assets.open("plates/$name.jpg").use { BitmapFactory.decodeStream(it) } ?: continue
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                plates[name] = Plate(ids[0], bmp.width.toFloat() / bmp.height.toFloat())
                bmp.recycle()
            } catch (e: Exception) {
                android.util.Log.w("ICPlate", "no plate $name", e)
            }
        }
    }

    internal class Plate(val texture: Int, val aspect: Float)

    /**
     * Hang one picture in the passage: a lit frame around it, square to the rail and turned a
     * little toward the lane, so the crew fly past it the way you walk past a picture on a wall.
     */
    internal fun drawPlate(name: String, f: Frame, side: Float, up: Float, height: Float, seconds: Float) {
        val plate = plates[name] ?: return
        val sh = plateShader ?: return
        val h = height; val w = height * plate.aspect
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, fx(f, 0f, side, up), fy(f, 0f, side, up), fz(f, 0f, side, up))
        applyFrameRotation(f)
        Matrix.rotateM(model, 0, if (side < 0f) 28f else -28f, 0f, 1f, 0f)   // angled toward the passage
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        // The picture is the point of the stop, so it is drawn over the world rather than into it:
        // in a passage only a few units wide the far half of the plate would otherwise be buried in
        // the wall and the person in it sliced off. Depth is neither tested nor written, so the
        // Mote (drawn later, with depth) still passes in front of it correctly.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        colorShader.use(mvp, 1f)
        val fr = 0.06f + 0.012f * sin(seconds * 1.1f)
        plateFrame(w * 0.5f + fr, h * 0.5f + fr)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        sh.use(mvp, plate.texture, landmarkFade, 0.55f + 0.45f * (0.5f + 0.5f * sin(seconds * 0.7f)))
        plateQuad(w * 0.5f, h * 0.5f, sh)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    internal val plateBuf = ByteBuffer.allocateDirect(6 * 5 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    internal val frameBuf = ByteBuffer.allocateDirect(8 * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    internal fun plateQuad(hw: Float, hh: Float, sh: PlateShader) {
        val d = floatArrayOf(
            -hw, -hh, 0f, 0f, 1f,   hw, -hh, 0f, 1f, 1f,   hw, hh, 0f, 1f, 0f,
            -hw, -hh, 0f, 0f, 1f,   hw, hh, 0f, 1f, 0f,   -hw, hh, 0f, 0f, 0f)
        plateBuf.position(0); plateBuf.put(d); plateBuf.position(0)
        GLES20.glVertexAttribPointer(sh.positionHandle, 3, GLES20.GL_FLOAT, false, 20, plateBuf)
        GLES20.glEnableVertexAttribArray(sh.positionHandle)
        plateBuf.position(3)
        GLES20.glVertexAttribPointer(sh.uvHandle, 2, GLES20.GL_FLOAT, false, 20, plateBuf)
        GLES20.glEnableVertexAttribArray(sh.uvHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(sh.positionHandle)
        GLES20.glDisableVertexAttribArray(sh.uvHandle)
    }

    internal fun plateFrame(hw: Float, hh: Float) {
        val c = COL_LAMP
        val d = FloatArray(8 * 7)
        val pts = floatArrayOf(-hw, -hh, hw, -hh, hw, -hh, hw, hh, hw, hh, -hw, hh, -hw, hh, -hw, -hh)
        for (k in 0 until 8) {
            val o = k * 7
            d[o] = pts[k * 2]; d[o + 1] = pts[k * 2 + 1]; d[o + 2] = 0f
            d[o + 3] = c[0]; d[o + 4] = c[1]; d[o + 5] = c[2]; d[o + 6] = 0.85f * landmarkFade
        }
        frameBuf.position(0); frameBuf.put(d); frameBuf.position(0)
        lineWidth(3f)
        GLES20.glVertexAttribPointer(colorShader.positionHandle, 3, GLES20.GL_FLOAT, false, 28, frameBuf)
        GLES20.glEnableVertexAttribArray(colorShader.positionHandle)
        frameBuf.position(3)
        GLES20.glVertexAttribPointer(colorShader.colorHandle, 4, GLES20.GL_FLOAT, false, 28, frameBuf)
        GLES20.glEnableVertexAttribArray(colorShader.colorHandle)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 8)
        GLES20.glDisableVertexAttribArray(colorShader.positionHandle)
        GLES20.glDisableVertexAttribArray(colorShader.colorHandle)
        lineWidth(1f)
    }







    // ------------------------------------------------------- draw: the ship
    /** Local hull coordinates (x right, y up, -z forward) to world, through the hull's yaw and pitch. */
    internal fun shipToWorld(lx0: Float, ly0: Float, lz0: Float, out: FloatArray) {
        val lx = lx0 * shipScale; val ly = ly0 * shipScale; val lz = lz0 * shipScale
        val cp = cos(craftPitch * DEG); val sp = sin(craftPitch * DEG)
        val cy = cos(craftYaw * DEG); val sy = sin(craftYaw * DEG)
        val y1 = ly * cp - lz * sp; val z1 = ly * sp + lz * cp        // Rx(pitch)
        val x2 = lx * cy + z1 * sy; val z2 = -lx * sy + z1 * cy       // Ry(yaw)
        out[0] = shipX + x2; out[1] = shipY + y1; out[2] = shipZ + z2
    }

    /** A rod between two world points (an elongated sphere aligned with the segment). */
    internal fun drawStrut(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, radius: Float, base: FloatArray, accent: FloatArray, glow: Float = 0f) {
        val dx = bx - ax; val dy = by - ay; val dz = bz - az
        val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
        val nx = dx / len; val ny = dy / len; val nz = dz / len
        // rotate local +z onto n: axis = z x n = (-ny, nx, 0), angle = acos(nz)
        val axX = -ny; val axY = nx
        val al = sqrt(axX * axX + axY * axY)
        val ang = acos(nz.coerceIn(-1f, 1f)) * 180f / PI.toFloat()
        if (al < 1e-4f) drawSphereAt((ax + bx) * 0.5f, (ay + by) * 0.5f, (az + bz) * 0.5f, radius, radius, len * 0.5f, base, accent, 1f, 0f, 0f, 1f, 0f, blob, 0f, glow)
        else drawSphereAt((ax + bx) * 0.5f, (ay + by) * 0.5f, (az + bz) * 0.5f, radius, radius, len * 0.5f, base, accent, 1f, ang, axX / al, axY / al, 0f, blob, 0f, glow)
    }

    internal fun drawMote(seconds: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, shipX, shipY, shipZ)
        Matrix.rotateM(model, 0, craftYaw, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, craftPitch, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, shipScale, shipScale, shipScale)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 5.5f)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        moteMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        val pulse = 0.5f + 0.5f * sin(seconds * 4f)
        // Hover pads: six glowing discs under the pontoons.
        for (sgn in floatArrayOf(-1f, 1f)) for (k in 0 until 3) {
            shipToWorld(0.34f * sgn, -0.17f, -0.38f + 0.38f * k, tmpW)
            drawSphereAt(tmpW[0], tmpW[1], tmpW[2], 0.10f, 0.022f, 0.10f, COL_PAD, COL_PAD, 0.95f, craftYaw, 0f, 1f, 0f, blob, 0f, 0.35f + 0.3f * pulse)
        }
        // Bow lamp, twin exhausts, and the drive ring turning around the stern.
        shipToWorld(0f, 0.02f, -0.78f, tmpW)
        drawSphereAt(tmpW[0], tmpW[1], tmpW[2], 0.06f, 0.06f, 0.06f, COL_LAMP, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob, 0f, 2.5f)
        for (sgn in floatArrayOf(-1f, 1f)) {
            shipToWorld(0.12f * sgn, -0.02f, 0.75f, tmpW)
            drawSphereAt(tmpW[0], tmpW[1], tmpW[2], 0.032f, 0.032f, 0.02f, COL_DRIVE_DIM, COL_DRIVE, 1f, craftYaw, 0f, 1f, 0f, blob, 0f, 0.5f + 0.3f * pulse)
        }
        val spin = seconds * 240f * DEG
        for (k in 0 until 10) {
            val a = 2f * PI.toFloat() * k / 10f + spin
            shipToWorld(cos(a) * 0.20f, sin(a) * 0.14f, 0.68f, tmpW)
            drawSphereAt(tmpW[0], tmpW[1], tmpW[2], 0.014f, 0.014f, 0.014f, COL_DRIVE, COL_DRIVE, 0.9f, 0f, 0f, 1f, 0f, blob, 0f, 0.6f + 0.5f * sin(seconds * 6f + k))
        }
        drawArms(seconds)
    }

    /** Two articulated probes: shoulder at the bow mounts, elbow, and a glowing sensor tip. */
    internal fun drawArms(seconds: Float) {
        val r = armReach * armReach * (3f - 2f * armReach)
        val wob = sin(seconds * 1.7f) * 0.03f
        for (sgn in floatArrayOf(-1f, 1f)) {
            val sx = 0.24f * sgn; val sy = -0.06f; val sz = -0.60f
            // folded: back along the pontoon; reaching: forward and outward, tips ahead of the bow
            val ex = sx + lerp(0.06f * sgn, 0.22f * sgn, r); val ey = sy + lerp(-0.02f, 0.05f + wob, r); val ez = sz + lerp(0.33f, -0.27f, r)
            val tx = ex + lerp(0.02f * sgn, 0.04f * sgn, r); val ty = ey + lerp(0f, -0.04f - wob, r); val tz = ez + lerp(0.29f, -0.30f, r)
            shipToWorld(sx, sy, sz, tmpS); shipToWorld(ex, ey, ez, tmpE); shipToWorld(tx, ty, tz, tmpT)
            drawStrut(tmpS[0], tmpS[1], tmpS[2], tmpE[0], tmpE[1], tmpE[2], 0.028f, COL_HULL_DARK, COL_LAMP)
            drawSphereAt(tmpE[0], tmpE[1], tmpE[2], 0.04f, 0.04f, 0.04f, COL_HULL, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob)
            drawStrut(tmpE[0], tmpE[1], tmpE[2], tmpT[0], tmpT[1], tmpT[2], 0.022f, COL_HULL_DARK, COL_LAMP)
            drawSphereAt(tmpT[0], tmpT[1], tmpT[2], 0.036f, 0.036f, 0.036f, COL_LAMP, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob, 0f, 0.4f + 1.8f * r)
        }
    }

    /** The engine room: inside the hull, the scale drive core with its rotor ring and stator struts. */
    internal fun drawDriveCore(seconds: Float) {
        // The hull around us (its inner faces), without the outboard fittings that would show
        // through the near-plane gap in the roof.
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, shipX, shipY, shipZ)
        Matrix.rotateM(model, 0, craftYaw, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, craftPitch, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, shipScale, shipScale, shipScale)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 5.5f)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        moteMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        val pulse = 0.55f + 0.45f * (0.5f + 0.5f * sin(seconds * 3.2f))
        shipToWorld(0f, 0f, -0.12f, tmpW)          // local -z is forward: the core sits just ahead of centre
        val cx = tmpW[0]; val cy = tmpW[1]; val cz = tmpW[2]
        drawSphereAt(cx, cy, cz, 0.07f, 0.07f, 0.07f, floatArrayOf(0.55f * pulse, 0.42f * pulse, 1f, 1f), COL_DRIVE, 1f, 0f, 0f, 1f, 0f, blob, 0f, 0.7f)
        // The rotor: a ring of beads turning like ATP synthase, held by four stator struts.
        val spin = seconds * 300f * DEG
        for (k in 0 until 12) {
            val a = 2f * PI.toFloat() * k / 12f + spin
            val rr = 0.20f
            val ox = sideX * cos(a) + upX * sin(a); val oy = sideY * cos(a) + upY * sin(a); val oz = sideZ * cos(a) + upZ * sin(a)
            drawSphereAt(cx + ox * rr, cy + oy * rr, cz + oz * rr, 0.016f, 0.016f, 0.03f, COL_DRIVE, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob, 0f, 0.4f)
        }
        for (k in 0 until 4) {
            val a = PI.toFloat() * 0.5f * k
            val ox = sideX * cos(a) + upX * sin(a); val oy = sideY * cos(a) + upY * sin(a); val oz = sideZ * cos(a) + upZ * sin(a)
            drawStrut(cx + ox * 0.10f, cy + oy * 0.10f, cz + oz * 0.10f, cx + ox * 0.30f, cy + oy * 0.30f, cz + oz * 0.30f, 0.012f, COL_STATOR, COL_LAMP, 0.3f)
        }
    }

    internal fun drawCockpit() {
        // A head-locked frame: drawn at the eye, facing the heading, proportioned so the porthole
        // (0.55 x 0.40 at 1.0 ahead) and the console sit inside the 58-degree frustum. A stable
        // foreground frame is the comfort anchor the build guide asks for.
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, camNowX - shakeX, camNowY - shakeY, camNowZ)
        // Face the camera's own look direction (not the hull's sway yaw) so the frame stays square.
        val lx = lookNowX - camNowX; val ly = lookNowY - camNowY; val lz = lookNowZ - camNowZ
        val lookYaw = atan2(-lx, -lz) * 180f / PI.toFloat()
        val lookPitch = atan2(ly, sqrt(lx * lx + lz * lz).coerceAtLeast(1e-4f)) * 180f / PI.toFloat()
        Matrix.rotateM(model, 0, lookYaw, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, lookPitch, 1f, 0f, 0f)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 3.5f)
        lineWidth(2f)
        cockpitMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
        lineWidth(1f)
    }

    // --------------------------------------------------------- draw: overlays
    internal fun drawStreaks(seconds: Float) {
        val burst = sin(shrinkBurst * PI.toFloat())
        val intensity = max(jumpIntensity, burst)
        if (intensity < 0.02f) return
        var k = 0
        for (i in 0 until streakCount) {
            val a = streakSeeds[i * 2] * 6.2832f
            val seed = streakSeeds[i * 2 + 1]
            val rush = (seconds * (1.6f + seed * 2.4f) + seed * 7f) % 1f
            val r0 = 0.08f + rush * 0.9f
            val len = (0.25f + seed * 0.55f) * intensity
            val ca = cos(a); val sa = sin(a)
            val alpha = intensity * (0.25f + 0.55f * seed) * (1f - rush * 0.6f)
            streakData[k++] = ca * r0; streakData[k++] = sa * r0; streakData[k++] = 0f
            streakData[k++] = if (growing) 0.6f else 1f; streakData[k++] = if (growing) 0.95f else 0.72f; streakData[k++] = if (growing) 0.85f else 0.62f; streakData[k++] = alpha
            val r1 = r0 + len
            streakData[k++] = ca * r1; streakData[k++] = sa * r1; streakData[k++] = 0f
            streakData[k++] = if (growing) 0.4f else 0.75f; streakData[k++] = if (growing) 0.7f else 0.45f; streakData[k++] = 0.8f; streakData[k++] = alpha * 0.4f
        }
        streakBuf.position(0); streakBuf.put(streakData); streakBuf.position(0)
        GLES20.glDepthMask(false); GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        lineWidth(3f)
        colorShader.use(identityM, 1f)
        streakBuf.position(0)
        GLES20.glVertexAttribPointer(colorShader.positionHandle, 3, GLES20.GL_FLOAT, false, 28, streakBuf)
        GLES20.glEnableVertexAttribArray(colorShader.positionHandle)
        streakBuf.position(3)
        GLES20.glVertexAttribPointer(colorShader.colorHandle, 4, GLES20.GL_FLOAT, false, 28, streakBuf)
        GLES20.glEnableVertexAttribArray(colorShader.colorHandle)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, streakCount * 2)
        GLES20.glDisableVertexAttribArray(colorShader.positionHandle)
        GLES20.glDisableVertexAttribArray(colorShader.colorHandle)
        lineWidth(1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(true)
    }

    internal fun lineWidth(w: Float) = GLES20.glLineWidth(min(w, maxLineWidth))

    internal fun drawFlash() {
        if (beat < 0.01f) return
        val a = beat * 0.28f
        var i = 6
        while (i < flashData.size) { flashData[i] = a; i += 7 }
        flashBuf.position(0); flashBuf.put(flashData); flashBuf.position(0)
        GLES20.glDepthMask(false); GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        colorShader.use(identityM, 1f)
        flashBuf.position(0)
        GLES20.glVertexAttribPointer(colorShader.positionHandle, 3, GLES20.GL_FLOAT, false, 28, flashBuf)
        GLES20.glEnableVertexAttribArray(colorShader.positionHandle)
        flashBuf.position(3)
        GLES20.glVertexAttribPointer(colorShader.colorHandle, 4, GLES20.GL_FLOAT, false, 28, flashBuf)
        GLES20.glEnableVertexAttribArray(colorShader.colorHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(colorShader.positionHandle)
        GLES20.glDisableVertexAttribArray(colorShader.colorHandle)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(true)
    }

    // ------------------------------------------------------------- helpers
    internal fun drawSphereAt(
        x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float,
        base: FloatArray, accent: FloatArray, alpha: Float = 1f,
        rotDeg: Float = 0f, ax: Float = 0f, ay: Float = 1f, az: Float = 0f,
        mesh: LitMesh = sphere, pattern: Float = 0f, glow: Float = 0f
    ) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        if (rotDeg != 0f) Matrix.rotateM(model, 0, rotDeg, ax, ay, az)
        Matrix.scaleM(model, 0, sx, sy, sz)
        drawLitModel(mesh, base, accent, alpha * landmarkFade, pattern, glow)
    }

    /** Draw [mesh] with the current model matrix through the lit shader. */
    internal fun drawLitModel(mesh: LitMesh, base: FloatArray, accent: FloatArray, alpha: Float, pattern: Float, glow: Float) {
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        // Normal matrix = transpose(inverse(model)). transposeM must not run in place (it would
        // symmetrise the matrix instead of transposing it), so invert into a scratch first.
        if (!Matrix.invertM(invM, 0, model, 0)) Matrix.setIdentityM(invM, 0)
        Matrix.transposeM(normalM, 0, invM, 0)
        litShader.use(mvp, model, normalM, base, accent, alpha, pattern, glow, lampX(), lampY(), lampZ(), camNowX, camNowY, camNowZ)
        mesh.draw(litShader.positionHandle, litShader.normalHandle)
    }

    internal fun drawLinesAt(mesh: LineMesh, x: Float, y: Float, z: Float, scale: Float, rotDeg: Float, ax: Float, ay: Float, az: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        if (rotDeg != 0f) Matrix.rotateM(model, 0, rotDeg, ax, ay, az)
        Matrix.scaleM(model, 0, scale, scale, scale)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 4f)
        mesh.draw(colorShader.positionHandle, colorShader.colorHandle)
    }

    /** Rotate the model matrix so local +x = side, +y = up, +z = -dir (rail forward). */
    internal fun applyFrameRotation(f: Frame) {
        val rot = FloatArray(16)
        rot[0] = f.sx; rot[1] = f.sy; rot[2] = f.sz; rot[3] = 0f
        rot[4] = f.ux; rot[5] = f.uy; rot[6] = f.uz; rot[7] = 0f
        rot[8] = -f.dx; rot[9] = -f.dy; rot[10] = -f.dz; rot[11] = 0f
        rot[12] = 0f; rot[13] = 0f; rot[14] = 0f; rot[15] = 1f
        val tmp = FloatArray(16)
        Matrix.multiplyMM(tmp, 0, model, 0, rot, 0)
        System.arraycopy(tmp, 0, model, 0, 16)
    }

    internal fun yawOf(f: Frame): Float = atan2(-f.dx, -f.dz) * 180f / PI.toFloat()

    internal class Frame(
        val cx: Float, val cy: Float, val cz: Float,
        val dx: Float, val dy: Float, val dz: Float,
        val sx: Float, val sy: Float, val sz: Float,
        val ux: Float, val uy: Float, val uz: Float
    )

    /** Catmull-Rom position on the rail at node-units p. */
    internal fun curvePoint(p: Float, out: FloatArray) {
        val n = nodes.size
        val pc = p.coerceIn(0f, (n - 1).toFloat())
        val i = min(pc.toInt(), n - 2)
        val t = pc - i
        val p0 = nodes[max(i - 1, 0)]; val p1 = nodes[i]; val p2 = nodes[i + 1]; val p3 = nodes[min(i + 2, n - 1)]
        fun cr(a: Float, b: Float, c: Float, d: Float): Float =
            0.5f * ((2f * b) + (-a + c) * t + (2f * a - 5f * b + 4f * c - d) * t * t + (-a + 3f * b - 3f * c + d) * t * t * t)
        out[0] = cr(p0.x, p1.x, p2.x, p3.x); out[1] = cr(p0.y, p1.y, p2.y, p3.y); out[2] = cr(p0.z, p1.z, p2.z, p3.z)
    }

    internal val tmpA = FloatArray(3)
    internal val tmpB = FloatArray(3)
    internal fun frameAt(p: Float): Frame {
        curvePoint(p, tmpA)
        val cx = tmpA[0]; val cy = tmpA[1]; val cz = tmpA[2]
        curvePoint(p - 0.02f, tmpA); curvePoint(p + 0.02f, tmpB)
        var dx = tmpB[0] - tmpA[0]; var dy = tmpB[1] - tmpA[1]; var dz = tmpB[2] - tmpA[2]
        var l = sqrt(dx * dx + dy * dy + dz * dz)
        if (l < 1e-5f) { dx = 0f; dy = 0f; dz = -1f; l = 1f }
        dx /= l; dy /= l; dz /= l
        // side = normalize(cross(d, up)), up2 = cross(side, d)
        var sx = dy * 0f - dz * 1f; var sy = dz * 0f - dx * 0f; var sz = dx * 1f - dy * 0f
        var sl = sqrt(sx * sx + sy * sy + sz * sz)
        if (sl < 1e-4f) { sx = 1f; sy = 0f; sz = 0f; sl = 1f }
        sx /= sl; sy /= sl; sz /= sl
        val ux = sy * dz - sz * dy; val uy = sz * dx - sx * dz; val uz = sx * dy - sy * dx
        return Frame(cx, cy, cz, dx, dy, dz, sx, sy, sz, ux, uy, uz)
    }

    internal fun nodeLerp(p: Float, f: (TourNode) -> Float): Float {
        val pc = p.coerceIn(0f, nodes.lastIndex.toFloat())
        val i = min(pc.toInt(), nodes.lastIndex - 1)
        val t = pc - i
        val s = t * t * (3f - 2f * t)
        return f(nodes[i]) + (f(nodes[i + 1]) - f(nodes[i])) * s
    }

    internal fun tunnelRadius(p: Float): Float = nodeLerp(p) { it.radius }

    // ------------------------------------------------------ mesh builders
    internal fun buildTunnel(): FloatArray {
        val segs = 14
        val step = 0.08f
        val rings = ArrayList<FloatArray>()
        var p = 0f
        while (p <= nodes.lastIndex + 1e-4f) {
            val f = frameAt(p)
            val r = tunnelRadius(p)
            val ring = FloatArray(segs * 10)
            val cr = nodeLerp(p) { it.wall[0] }; val cg = nodeLerp(p) { it.wall[1] }; val cb = nodeLerp(p) { it.wall[2] }
            for (k in 0 until segs) {
                val a = 2f * PI.toFloat() * k / segs
                val ox = f.sx * cos(a) + f.ux * sin(a); val oy = f.sy * cos(a) + f.uy * sin(a); val oz = f.sz * cos(a) + f.uz * sin(a)
                val bump = 1f + 0.07f * sin(k * 3.1f + p * 9.3f) + 0.04f * sin(k * 7.7f + p * 21f)
                val o = k * 10
                ring[o] = f.cx + ox * r * bump; ring[o + 1] = f.cy + oy * r * bump; ring[o + 2] = f.cz + oz * r * bump
                ring[o + 3] = -ox; ring[o + 4] = -oy; ring[o + 5] = -oz
                val shade = 0.9f + 0.1f * sin(k * 2.3f + p * 5f)
                ring[o + 6] = cr * shade; ring[o + 7] = cg * shade; ring[o + 8] = cb * shade; ring[o + 9] = 1f
            }
            rings.add(ring)
            p += step
        }
        val out = FloatArray((rings.size - 1) * segs * 6 * 10)
        var w = 0
        fun put(ring: FloatArray, k: Int) { val o = (k % segs) * 10; for (q in 0 until 10) out[w++] = ring[o + q] }
        for (i in 0 until rings.size - 1) {
            val a = rings[i]; val b = rings[i + 1]
            for (k in 0 until segs) {
                put(a, k); put(b, k); put(b, k + 1)
                put(a, k); put(b, k + 1); put(a, k + 1)
            }
        }
        return out
    }

    internal fun buildRouteLines(): FloatArray = buildList {
        val color = floatArrayOf(1f, 0.6f, 0.55f, 0.22f)
        nodes.zipWithNext().forEach { (a, b) -> addLine(a.x, a.y, a.z, b.x, b.y, b.z, color) }
    }.toFloatArray()

    internal fun buildRouteNodes(): FloatArray = buildList {
        nodes.forEach { addPoint(it.x, it.y, it.z, 1f, 0.77f, 0.42f, 0.45f) }
    }.toFloatArray()

    // The M.S.V. Mote: an original industrial hovercraft. Faceted, wider-than-tall hull, a raised
    // cockpit pod forward, a dorsal spine with antenna masts, side pontoons that carry the hover
    // pads, an aft engine block, and two arm-probe mounts at the bow. Faces -Z. Length 1.5.
    internal fun buildMote(): FloatArray = buildList {
        val top = floatArrayOf(0.64f, 0.67f, 0.74f, 1f)
        val flank = floatArrayOf(0.50f, 0.53f, 0.60f, 1f)
        val belly = floatArrayOf(0.32f, 0.34f, 0.40f, 1f)
        val dark = floatArrayOf(0.30f, 0.32f, 0.38f, 1f)
        val glass = floatArrayOf(0.40f, 0.85f, 0.95f, 1f)
        val rust = floatArrayOf(0.50f, 0.37f, 0.30f, 1f)
        fun tri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, c: FloatArray, shade: Float) {
            addPoint(ax, ay, az, c[0] * shade, c[1] * shade, c[2] * shade, c[3])
            addPoint(bx, by, bz, c[0] * shade, c[1] * shade, c[2] * shade, c[3])
            addPoint(cx, cy, cz, c[0] * shade, c[1] * shade, c[2] * shade, c[3])
        }
        fun quad(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float, c: FloatArray, shade: Float) {
            tri(ax, ay, az, bx, by, bz, cx, cy, cz, c, shade); tri(ax, ay, az, cx, cy, cz, dx, dy, dz, c, shade)
        }
        fun box(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float, col: FloatArray, cap: FloatArray) {
            val x0 = cx - hx; val x1 = cx + hx; val y0 = cy - hy; val y1 = cy + hy; val z0 = cz - hz; val z1 = cz + hz
            quad(x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, cap, 1f)        // front (-z)
            quad(x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, col, 0.8f)      // back
            quad(x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, col, 0.9f)      // left
            quad(x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, col, 0.9f)      // right
            quad(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, col, 1.05f)     // top
            quad(x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, col, 0.65f)     // bottom
        }
        // Hull: an eight-facet lathe, squashed (wider than tall), flat top and belly facets.
        val prof = floatArrayOf(-0.75f, 0.06f, -0.62f, 0.16f, -0.40f, 0.24f, -0.10f, 0.27f, 0.25f, 0.27f, 0.50f, 0.22f, 0.66f, 0.15f, 0.75f, 0.05f)
        val segs = 8
        for (i in 0 until prof.size / 2 - 1) {
            val z0 = prof[i * 2]; val r0 = prof[i * 2 + 1]; val z1 = prof[i * 2 + 2]; val r1 = prof[i * 2 + 3]
            for (k in 0 until segs) {
                val a0 = 2f * PI.toFloat() * (k + 0.5f) / segs; val a1 = 2f * PI.toFloat() * (k + 1.5f) / segs
                val ny = (sin(a0) + sin(a1)) * 0.5f
                val col = if (ny > 0.5f) top else if (ny < -0.5f) belly else flank
                val shade = 0.72f + 0.28f * ((ny + 1f) * 0.5f)
                quad(cos(a0) * r0 * 1.25f, sin(a0) * r0 * 0.72f, z0, cos(a1) * r0 * 1.25f, sin(a1) * r0 * 0.72f, z0,
                     cos(a1) * r1 * 1.25f, sin(a1) * r1 * 0.72f, z1, cos(a0) * r1 * 1.25f, sin(a0) * r1 * 0.72f, z1, col, shade)
            }
        }
        // Cockpit pod (raised, forward) with a glass face; dorsal spine; two antenna masts.
        box(0f, 0.20f, -0.44f, 0.13f, 0.08f, 0.15f, dark, glass)
        box(0f, 0.25f, 0.12f, 0.05f, 0.03f, 0.34f, dark, dark)
        box(0.10f, 0.34f, -0.30f, 0.012f, 0.10f, 0.012f, dark, dark)
        box(-0.14f, 0.32f, 0.04f, 0.012f, 0.08f, 0.012f, dark, dark)
        // Engine block at the stern; side pontoons that carry the hover pads; arm mounts at the bow.
        box(0f, -0.02f, 0.60f, 0.24f, 0.13f, 0.14f, dark, rust)
        box(-0.34f, -0.10f, -0.05f, 0.08f, 0.05f, 0.42f, flank, dark)
        box(0.34f, -0.10f, -0.05f, 0.08f, 0.05f, 0.42f, flank, dark)
        box(-0.24f, -0.06f, -0.60f, 0.05f, 0.05f, 0.06f, rust, dark)
        box(0.24f, -0.06f, -0.60f, 0.05f, 0.05f, 0.06f, rust, dark)
    }.toFloatArray()

    internal fun buildCockpitLines(): FloatArray = buildList {
        val glass = floatArrayOf(0.98f, 0.78f, 0.66f, 0.75f)
        // Porthole: an octagonal frame 1.0 ahead of the eye (about 23 x 17 degrees of view).
        for (k in 0 until 8) {
            val a0 = 2f * PI.toFloat() * k / 8f + PI.toFloat() / 8f; val a1 = 2f * PI.toFloat() * (k + 1) / 8f + PI.toFloat() / 8f
            addLine(cos(a0) * 0.42f, 0.02f + sin(a0) * 0.30f, -1.0f, cos(a1) * 0.42f, 0.02f + sin(a1) * 0.30f, -1.0f, glass)
        }
        // Console bar below the window + two struts up to the frame.
        addLine(-0.50f, -0.30f, -0.60f, 0.50f, -0.30f, -0.60f, glass)
        addLine(-0.46f, -0.30f, -0.60f, -0.39f, -0.10f, -1.0f, glass)
        addLine(0.46f, -0.30f, -0.60f, 0.39f, -0.10f, -1.0f, glass)
        // Indicator stubs (rose left, amber right) and a centre reticle.
        addLine(-0.20f, -0.30f, -0.60f, -0.12f, -0.38f, -0.60f, floatArrayOf(1f, 0.36f, 0.48f, 0.8f))
        addLine(0.20f, -0.30f, -0.60f, 0.12f, -0.38f, -0.60f, floatArrayOf(1f, 0.77f, 0.42f, 0.8f))
        addLine(-0.04f, 0.02f, -1.0f, 0.04f, 0.02f, -1.0f, floatArrayOf(1f, 0.77f, 0.42f, 0.55f))
        addLine(0f, -0.02f, -1.0f, 0f, 0.06f, -1.0f, floatArrayOf(1f, 0.77f, 0.42f, 0.55f))
    }.toFloatArray()

    internal fun buildHairs(): FloatArray = buildList {
        val rnd = java.util.Random(3)
        val c = floatArrayOf(0.30f, 0.16f, 0.12f, 0.9f)
        for (i in 0 until 52) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val r = 1.05f + rnd.nextFloat() * 0.25f
            val z = -0.4f - rnd.nextFloat() * 1.6f
            val len = 0.45f + rnd.nextFloat() * 0.5f
            val inward = 0.8f + rnd.nextFloat() * 0.3f
            addLine(cos(a) * r, sin(a) * r, z, cos(a) * (r - len * inward), sin(a) * (r - len * inward), z - 0.2f + rnd.nextFloat() * 0.4f, c)
        }
    }.toFloatArray()

    internal fun buildCapillaries(): FloatArray = buildList {
        val c = floatArrayOf(0.85f, 0.15f, 0.2f, 0.85f)
        for (k in 0 until 7) {
            val a = 2f * PI.toFloat() * k / 7f + 0.4f
            val d = 3.4f + 0.6f * sin(k * 2.1f)
            val cx = cos(a) * d; val cy = sin(a) * d; val cz = (k - 3) * 0.9f
            val r = 1.3f + 0.35f * (k % 3)
            for (s in 0 until 40) {
                val t0 = s / 40f; val t1 = (s + 1) / 40f
                fun px(t: Float) = cx + cos(t * 5f * PI.toFloat()) * r * sin(t * PI.toFloat())
                fun py(t: Float) = cy + cos(t * PI.toFloat()) * r
                fun pz(t: Float) = cz + sin(t * 5f * PI.toFloat()) * r * sin(t * PI.toFloat())
                addLine(px(t0), py(t0), pz(t0), px(t1), py(t1), pz(t1), c)
            }
        }
    }.toFloatArray()

    internal fun buildTrabeculae(): FloatArray = buildList {
        val rnd = java.util.Random(9)
        val c = floatArrayOf(0.85f, 0.35f, 0.4f, 0.6f)
        for (i in 0 until 30) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val r = 5.2f + rnd.nextFloat() * 0.6f
            val z0 = -6f + rnd.nextFloat() * 12f
            addLine(cos(a) * r, sin(a) * r, z0, cos(a + 0.5f) * (r - 1.2f), sin(a + 0.5f) * (r - 1.2f), z0 + 1.5f, c)
        }
    }.toFloatArray()

    internal fun buildDendrites(): FloatArray {
        val list = ArrayList<Float>()
        val c = floatArrayOf(0.75f, 0.65f, 1f, 0.8f)
        val rnd = java.util.Random(21)
        fun branch(x: Float, y: Float, z: Float, dx: Float, dy: Float, dz: Float, len: Float, depth: Int) {
            if (depth == 0 || len < 0.25f) return
            val ex = x + dx * len; val ey = y + dy * len; val ez = z + dz * len
            list.addLine(x, y, z, ex, ey, ez, c)
            for (k in 0 until 2) {
                val nx = dx + (rnd.nextFloat() - 0.5f) * 1.1f; val ny = dy + (rnd.nextFloat() - 0.5f) * 1.1f; val nz = dz + (rnd.nextFloat() - 0.5f) * 1.1f
                val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.01f)
                branch(ex, ey, ez, nx / l, ny / l, nz / l, len * 0.68f, depth - 1)
            }
        }
        for (k in 0 until 6) {
            val a = 2f * PI.toFloat() * k / 6f
            branch(cos(a) * 1.3f, 0.6f, sin(a) * 1.3f, cos(a), 0.55f, sin(a), 1.6f, 4)
        }
        return list.toFloatArray()
    }

    /** Two sheets of lipid heads (points) with tails (lines), a hole in the middle for the passage. Local frame: x side, y up, z back. */
    internal fun buildLipids(): Pair<FloatArray, FloatArray> {
        val heads = ArrayList<Float>(); val tails = ArrayList<Float>()
        val headC = floatArrayOf(1f, 0.78f, 0.45f, 0.95f)
        val tailC = floatArrayOf(0.35f, 0.85f, 0.8f, 0.6f)
        val n = 22; val spacing = 0.26f
        for (i in 0 until n) for (j in 0 until n) {
            val x = (i - n / 2f) * spacing; val y = (j - n / 2f) * spacing
            if (sqrt(x * x + y * y) < 1.15f) continue          // the opening
            if (sqrt(x * x + y * y) > 2.9f) continue
            heads.addPoint(x, y, -0.32f, headC[0], headC[1], headC[2], headC[3])
            heads.addPoint(x, y, 0.32f, headC[0], headC[1], headC[2], headC[3])
            tails.addLine(x, y, -0.28f, x + 0.04f, y, -0.05f, tailC)
            tails.addLine(x, y, 0.28f, x - 0.04f, y, 0.05f, tailC)
        }
        return heads.toFloatArray() to tails.toFloatArray()
    }

    internal fun buildChromatin(): FloatArray = buildList {
        val rnd = java.util.Random(5)
        val c = floatArrayOf(0.8f, 0.75f, 1f, 0.55f)
        for (f in 0 until 6) {
            var x = (rnd.nextFloat() - 0.5f) * 4f; var y = (rnd.nextFloat() - 0.5f) * 4f; var z = (rnd.nextFloat() - 0.5f) * 6f
            for (s in 0 until 60) {
                val nx = x + (rnd.nextFloat() - 0.5f) * 0.5f; val ny = y + (rnd.nextFloat() - 0.5f) * 0.5f; val nz = z + (rnd.nextFloat() - 0.5f) * 0.5f
                addLine(x, y, z, nx, ny, nz, c)
                x = nx.coerceIn(-2.6f, 2.6f); y = ny.coerceIn(-2.6f, 2.6f); z = nz.coerceIn(-4f, 4f)
            }
        }
    }.toFloatArray()

    /** Double helix along local z: two strands 3.4 units per turn, 10 rungs per turn. */
    internal fun buildHelix(): FloatArray {
        val list = ArrayList<Float>()
        val strandA = floatArrayOf(0.95f, 0.55f, 0.75f, 0.95f)
        val strandB = floatArrayOf(0.55f, 0.75f, 1f, 0.95f)
        val steps = 160
        val len = 8f; val r = 0.42f
        for (s in 0 until steps) {
            val t0 = s / steps.toFloat(); val t1 = (s + 1) / steps.toFloat()
            val z0 = -len / 2f + t0 * len; val z1 = -len / 2f + t1 * len
            val a0 = t0 * len / 3.4f * 2f * PI.toFloat(); val a1 = t1 * len / 3.4f * 2f * PI.toFloat()
            list.addLine(cos(a0) * r, sin(a0) * r, z0, cos(a1) * r, sin(a1) * r, z1, strandA)
            list.addLine(-cos(a0) * r, -sin(a0) * r, z0, -cos(a1) * r, -sin(a1) * r, z1, strandB)
            if (s % 7 == 0) {
                val rung = if ((s / 7) % 2 == 0) floatArrayOf(1f, 0.77f, 0.42f, 0.8f) else floatArrayOf(0.4f, 0.9f, 0.8f, 0.8f)
                list.addLine(cos(a0) * r, sin(a0) * r, z0, -cos(a0) * r, -sin(a0) * r, z0, rung)
            }
        }
        return list.toFloatArray()
    }

    internal fun buildMrna(): FloatArray = buildList {
        val c = floatArrayOf(1f, 0.6f, 0.5f, 0.9f)
        for (s in 0 until 80) {
            val t0 = s / 80f; val t1 = (s + 1) / 80f
            fun px(t: Float) = (t - 0.5f) * 9f
            fun py(t: Float) = 1.15f + 0.15f * sin(t * 18f)      // the seam between the two subunits
            fun pz(t: Float) = 0.2f * cos(t * 18f)
            addLine(px(t0), py(t0), pz(t0), px(t1), py(t1), pz(t1), c)
        }
    }.toFloatArray()

    internal fun buildElectronCloud(): FloatArray = buildList {
        val rnd = java.util.Random(77)
        for (i in 0 until 700) {
            // Radial density ~ shells: most points near r=2.2 and r=4.2.
            val shell = if (rnd.nextFloat() < 0.35f) 2.2f else 4.3f
            val r = shell + (rnd.nextGaussian().toFloat()) * 0.45f
            val u = rnd.nextFloat() * 2f - 1f; val a = rnd.nextFloat() * 2f * PI.toFloat()
            val s = sqrt(1f - u * u)
            val alpha = 0.18f + rnd.nextFloat() * 0.35f
            addPoint(cos(a) * s * r, u * r, sin(a) * s * r, 0.65f, 0.8f, 1f, alpha)
        }
    }.toFloatArray()

    internal fun buildShells(): FloatArray = buildList {
        val c = floatArrayOf(0.5f, 0.65f, 1f, 0.22f)
        // Carbon: two occupied shells (K: 2 electrons, L: 4), matching the two cloud densities.
        for (sh in 0 until 2) {
            val r = 2.2f + sh * 2.1f
            for (k in 0 until 48) {
                val a0 = 2f * PI.toFloat() * k / 48f; val a1 = 2f * PI.toFloat() * (k + 1) / 48f
                val tilt = sh * 0.6f
                addLine(cos(a0) * r, sin(a0) * r * cos(tilt), sin(a0) * r * sin(tilt), cos(a1) * r, sin(a1) * r * cos(tilt), sin(a1) * r * sin(tilt), c)
            }
        }
    }.toFloatArray()

    internal fun buildCellCosmos(): FloatArray = buildList {
        val rnd = java.util.Random(2013)
        for (i in 0 until 1400) {
            val r = 12f + rnd.nextFloat() * 48f
            val u = rnd.nextFloat() * 2f - 1f; val a = rnd.nextFloat() * 2f * PI.toFloat()
            val s = sqrt(1f - u * u)
            val warm = rnd.nextFloat()
            addPoint(cos(a) * s * r, u * r, sin(a) * s * r, 1f, 0.7f + 0.3f * warm, 0.55f + 0.45f * warm, 0.35f + rnd.nextFloat() * 0.6f)
        }
    }.toFloatArray()

    internal fun buildAntibodies(): FloatArray = buildList {
        val rnd = java.util.Random(33)
        val c = floatArrayOf(0.85f, 0.95f, 0.75f, 0.9f)
        for (i in 0 until 8) {
            val x = (rnd.nextFloat() - 0.5f) * 4f; val y = (rnd.nextFloat() - 0.5f) * 3f; val z = (rnd.nextFloat() - 0.5f) * 5f
            addLine(x, y, z, x, y - 0.5f, z, c)
            addLine(x, y, z, x - 0.3f, y + 0.4f, z, c)
            addLine(x, y, z, x + 0.3f, y + 0.4f, z, c)
        }
    }.toFloatArray()


    /** A microtubule along local z: thirteen protofilament lines around a 0.15 radius with faint tubulin rings. */
    internal fun buildMicrotubule(): FloatArray = buildList {
        val c = floatArrayOf(0.55f, 0.9f, 0.7f, 0.8f)
        val ring = floatArrayOf(0.4f, 0.7f, 0.55f, 0.35f)
        val len = 9.5f; val r = 0.15f
        for (k in 0 until 13) {
            val a = 2f * PI.toFloat() * k / 13f
            addLine(cos(a) * r, sin(a) * r, -len / 2f, cos(a) * r, sin(a) * r, len / 2f, c)
        }
        var z = -len / 2f
        while (z < len / 2f) {
            for (k in 0 until 8) {
                val a0 = 2f * PI.toFloat() * k / 8f; val a1 = 2f * PI.toFloat() * (k + 1) / 8f
                addLine(cos(a0) * r, sin(a0) * r, z, cos(a1) * r, sin(a1) * r, z, ring)
            }
            z += 0.4f
        }
    }.toFloatArray()

    /** Bile canaliculi: thin green channels zigzagging between the hepatocyte plates on both walls. */
    internal fun buildCanaliculi(): FloatArray = buildList {
        val c = floatArrayOf(0.55f, 0.9f, 0.35f, 0.8f)
        val rnd = java.util.Random(41)
        for (side in 0 until 2) {
            val sgn = if (side == 0) 1f else -1f
            var y = -0.3f; var z = -6f
            while (z < 6f) {
                val ny = (y + (rnd.nextFloat() - 0.5f) * 0.9f).coerceIn(-1.2f, 1.2f); val nz = z + 0.5f + rnd.nextFloat() * 0.4f
                addLine(sgn * 2.0f, y, z, sgn * 2.0f, ny, nz, c)
                y = ny; z = nz
            }
        }
    }.toFloatArray()

    /** The glomerulus: a knot of capillary loops (random walks kept inside a 1.2 sphere). */
    internal fun buildGlomerulus(): FloatArray = buildList {
        val rnd = java.util.Random(13)
        val c = floatArrayOf(0.9f, 0.2f, 0.25f, 0.9f)
        for (loop in 0 until 5) {
            var x = (rnd.nextFloat() - 0.5f) * 2f; var y = (rnd.nextFloat() - 0.5f) * 2f; var z = (rnd.nextFloat() - 0.5f) * 2f
            for (s in 0 until 70) {
                var nx = x + (rnd.nextFloat() - 0.5f) * 0.5f; var ny = y + (rnd.nextFloat() - 0.5f) * 0.5f; var nz = z + (rnd.nextFloat() - 0.5f) * 0.5f
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                if (l > 1.2f) { nx *= 1.2f / l; ny *= 1.2f / l; nz *= 1.2f / l }
                addLine(x, y, z, nx, ny, nz, c)
                x = nx; y = ny; z = nz
            }
        }
    }.toFloatArray()

    /** Trabecular bone: a cream lattice of struts just inside the marrow cavity wall. */
    internal fun buildBoneLattice(): FloatArray = buildList {
        val rnd = java.util.Random(57)
        val c = floatArrayOf(0.95f, 0.9f, 0.78f, 0.75f)
        for (i in 0 until 60) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val r = 2.85f + rnd.nextFloat() * 0.25f
            val z0 = -7f + rnd.nextFloat() * 14f
            val da = (rnd.nextFloat() - 0.5f) * 1.2f
            addLine(cos(a) * r, sin(a) * r, z0, cos(a + da) * (r - 0.6f * rnd.nextFloat()), sin(a + da) * (r - 0.6f * rnd.nextFloat()), z0 + (rnd.nextFloat() - 0.5f) * 2.5f, c)
        }
    }.toFloatArray()

    /** A flat bilayer patch (no hole): heads as points either side, tails between. Built in x/y with z the normal. */
    internal fun buildFloorLipids(): Pair<FloatArray, FloatArray> {
        val heads = ArrayList<Float>(); val tails = ArrayList<Float>()
        val headC = floatArrayOf(1f, 0.78f, 0.45f, 0.95f)
        val tailC = floatArrayOf(0.35f, 0.85f, 0.8f, 0.6f)
        val n = 20; val spacing = 0.3f
        for (i in 0 until n) for (j in 0 until n) {
            val x = (i - n / 2f) * spacing; val y = (j - n / 2f) * spacing
            heads.addPoint(x, y, -0.26f, headC[0], headC[1], headC[2], headC[3])
            heads.addPoint(x, y, 0.26f, headC[0], headC[1], headC[2], headC[3])
            tails.addLine(x, y, -0.22f, x + 0.04f, y, -0.04f, tailC)
            tails.addLine(x, y, 0.22f, x - 0.04f, y, 0.04f, tailC)
        }
        return heads.toFloatArray() to tails.toFloatArray()
    }


    /** Fibrin: a tangle of fine strands bridging a wound gap, built in a 3-unit box. */
    internal fun buildFibrin(): FloatArray = buildList {
        val rnd = java.util.Random(71)
        val c = floatArrayOf(0.95f, 0.9f, 0.85f, 0.55f)
        for (strand in 0 until 26) {
            var x = (rnd.nextFloat() - 0.5f) * 3.4f; var y = (rnd.nextFloat() - 0.5f) * 3.4f; var z = (rnd.nextFloat() - 0.5f) * 2.2f
            for (s in 0 until 7) {
                val nx = x + (rnd.nextFloat() - 0.5f) * 1.1f
                val ny = y + (rnd.nextFloat() - 0.5f) * 1.1f
                val nz = z + (rnd.nextFloat() - 0.5f) * 0.7f
                addLine(x, y, z, nx, ny, nz, c)
                x = nx.coerceIn(-2f, 2f); y = ny.coerceIn(-2f, 2f); z = nz.coerceIn(-1.4f, 1.4f)
            }
        }
    }.toFloatArray()

    /** The fibrous rim that walls off a tuberculous cavity: a ragged ring of scar in the x/y plane. */
    internal fun buildScarRing(): FloatArray = buildList {
        val rnd = java.util.Random(83)
        val c = floatArrayOf(0.92f, 0.86f, 0.80f, 0.8f)
        val steps = 60
        for (s in 0 until steps) {
            val a0 = 2f * PI.toFloat() * s / steps; val a1 = 2f * PI.toFloat() * (s + 1) / steps
            val r0 = 1.95f + rnd.nextFloat() * 0.22f; val r1 = 1.95f + rnd.nextFloat() * 0.22f
            addLine(cos(a0) * r0, sin(a0) * r0, 0f, cos(a1) * r1, sin(a1) * r1, 0f, c)
            if (s % 4 == 0) addLine(cos(a0) * r0, sin(a0) * r0, 0f,
                cos(a0) * (r0 + 0.5f + rnd.nextFloat() * 0.5f), sin(a0) * (r0 + 0.5f), (rnd.nextFloat() - 0.5f) * 0.6f, c)
        }
    }.toFloatArray()

    companion object {
        const val VIEW_COUNT = 4
        const val VIEW_BRIDGE = 0        // the helm, looking ahead through the porthole
        const val VIEW_CHASE = 1         // external camera trailing the Mote
        const val VIEW_ENGINEERING = 2   // beside the scale drive core
        const val VIEW_OBSERVATION = 3   // the observation deck, calm and wide
        val VIEW_NAMES = arrayOf("BRIDGE - HELM", "EXTERNAL - CHASE", "SCALE DRIVE CORE", "OBSERVATION DECK")

        // Ladder rungs (log10 of the Mote's length in metres) and their labels, one per decade

    }

    internal fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    internal fun MutableList<Float>.addPoint(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
        add(x); add(y); add(z); add(r); add(g); add(b); add(a)
    }

    internal fun MutableList<Float>.addLine(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, c: FloatArray) {
        addPoint(ax, ay, az, c[0], c[1], c[2], c[3])
        addPoint(bx, by, bz, c[0], c[1], c[2], c[3])
    }
}

// =============================================================== meshes

/** Uploads static vertex data once; every draw then binds the VBO instead of copying a client array. */
internal fun makeVbo(data: FloatArray): Int {
    val ids = IntArray(1)
    GLES20.glGenBuffers(1, ids, 0)
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
    GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, data.toFloatBuffer(), GLES20.GL_STATIC_DRAW)
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    return ids[0]
}

/** Anything the lit shader can draw: position(3) + normal(3), one VBO. */
internal abstract class LitMesh {
    abstract fun draw(positionHandle: Int, normalHandle: Int)
}

/**
 * A parametric surface p(u, v), u,v in [0,1], tessellated as triangle strips with normals taken
 * from the cross product of the partial derivatives. Drawn double-sided (culling off) so the
 * winding of an arbitrary parametrisation never matters. This is what lets a schematic have real
 * shapes — biconcave red cells, C-shaped cartilage, rod-shaped bacteria — instead of squashed
 * spheres.
 */
internal class ParamMesh(stacks: Int, slices: Int, fn: (Float, Float, FloatArray) -> Unit) : LitMesh() {
    internal val vbo: Int
    internal val vertexCount: Int
    internal val stripLen = (slices + 1) * 2
    internal val strips = stacks

    init {
        val data = ArrayList<Float>((stacks) * (slices + 1) * 12)
        val p = FloatArray(3); val pu = FloatArray(3); val pv = FloatArray(3)
        val e = 1e-3f
        fun vert(u: Float, v: Float) {
            fn(u, v, p)
            val px = p[0]; val py = p[1]; val pz = p[2]
            fn((u + e).coerceAtMost(1f), v, pu); fn((u - e).coerceAtLeast(0f), v, pv)
            val ux = pu[0] - pv[0]; val uy = pu[1] - pv[1]; val uz = pu[2] - pv[2]
            fn(u, (v + e).coerceAtMost(1f), pu); fn(u, (v - e).coerceAtLeast(0f), pv)
            val vx = pu[0] - pv[0]; val vy = pu[1] - pv[1]; val vz = pu[2] - pv[2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 1e-9f) { nx /= l; ny /= l; nz /= l } else { nx = 0f; ny = 1f; nz = 0f }
            data.add(px); data.add(py); data.add(pz); data.add(nx); data.add(ny); data.add(nz)
        }
        for (i in 0 until stacks) {
            val u0 = i.toFloat() / stacks; val u1 = (i + 1).toFloat() / stacks
            for (j in 0..slices) { val v = j.toFloat() / slices; vert(u1, v); vert(u0, v) }
        }
        vertexCount = data.size / 6
        vbo = makeVbo(data.toFloatArray())
    }

    override fun draw(positionHandle: Int, normalHandle: Int) {
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glEnableVertexAttribArray(normalHandle)
        for (k in 0 until strips) GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, k * stripLen, stripLen)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    companion object {
        /** Red blood cell, Evans-Fung profile: a biconcave disc of radius 1 in x/z, thin along y. */
        fun biconcave(): ParamMesh = ParamMesh(14, 24) { u, v, out ->
            // u runs rim -> centre on the top face then centre -> rim underneath.
            val top = u < 0.5f
            val rr = if (top) 1f - u * 2f else (u - 0.5f) * 2f
            val r = rr.coerceIn(0f, 0.999f)
            val q = r * r
            // Evans & Fung (1972): half-thickness 0.5*sqrt(1-x^2)*(0.81 + 7.83x^2 - 4.39x^4) um for a
            // 7.82 um cell, divided by its 3.91 um radius: ~0.8 um at the centre, ~2.5 um at the rim.
            val h = 0.5f * sqrt(1f - q) * (0.81f + 7.83f * q - 4.39f * q * q) / 3.91f
            val a = v * 2f * PI.toFloat()
            out[0] = cos(a) * r; out[2] = sin(a) * r; out[1] = if (top) h else -h
        }

        /** A torus arc of major radius 1 and minor radius [minor] in the x/y plane, sweeping [arc] of a turn. */
        fun torusArc(minor: Float, arc: Float, stacks: Int = 20): ParamMesh = ParamMesh(stacks, 10) { u, v, out ->
            val a = (u - 0.5f) * arc * 2f * PI.toFloat() - PI.toFloat() / 2f   // centred on -y
            val b = v * 2f * PI.toFloat()
            val rr = 1f + minor * cos(b)
            out[0] = cos(a) * rr; out[1] = sin(a) * rr; out[2] = minor * sin(b)
        }

        /** A capsule (rod with hemispherical ends) along z, total length 2, radius [radius]. */
        fun capsule(radius: Float): ParamMesh = ParamMesh(18, 12) { u, v, out ->
            val half = 1f - radius
            val t = u * 2f - 1f                        // -1..1 along the axis
            val z: Float; val rr: Float
            val cap = radius / (half + radius)
            if (t < -1f + cap) { val k = (t + 1f) / cap; val ang = (1f - k) * PI.toFloat() / 2f; z = -half - sin(ang) * radius; rr = cos(ang) * radius }
            else if (t > 1f - cap) { val k = (1f - t) / cap; val ang = (1f - k) * PI.toFloat() / 2f; z = half + sin(ang) * radius; rr = cos(ang) * radius }
            else { z = t / (1f - cap) * half; rr = radius }
            val a = v * 2f * PI.toFloat()
            out[0] = cos(a) * rr; out[1] = sin(a) * rr; out[2] = z
        }

        /** An open cylinder of radius 1 along z from -1 to 1 (for filaments, tubes, stalks). */
        fun cylinder(): ParamMesh = ParamMesh(2, 14) { u, v, out ->
            val a = v * 2f * PI.toFloat()
            out[0] = cos(a); out[1] = sin(a); out[2] = u * 2f - 1f
        }

        /** A cone (apex at z=+1, base radius 1 at z=-1), for teeth cusps, villus tips, pseudopods. */
        fun cone(): ParamMesh = ParamMesh(6, 14) { u, v, out ->
            val a = v * 2f * PI.toFloat(); val r = 1f - u
            out[0] = cos(a) * r; out[1] = sin(a) * r; out[2] = u * 2f - 1f
        }
    }
}

internal class SphereMesh(stacks: Int, slices: Int) : LitMesh() {
    internal val vbo: Int
    internal val vertexCount: Int

    init {
        val data = mutableListOf<Float>()
        for (stack in 0 until stacks) {
            val phi0 = PI.toFloat() * stack / stacks
            val phi1 = PI.toFloat() * (stack + 1) / stacks
            for (slice in 0..slices) {
                val theta = 2f * PI.toFloat() * slice / slices
                // Lower ring first: with phi increasing downward and theta counter-clockwise about +y,
                // this strip is CCW seen from OUTSIDE, which GL_CULL_FACE (front = CCW) requires.
                addSphereVertex(data, phi1, theta)
                addSphereVertex(data, phi0, theta)
            }
        }
        vertexCount = data.size / 6
        vbo = makeVbo(data.toFloatArray())
    }

    override fun draw(positionHandle: Int, normalHandle: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    internal fun addSphereVertex(data: MutableList<Float>, phi: Float, theta: Float) {
        val x = sin(phi) * cos(theta)
        val y = cos(phi)
        val z = sin(phi) * sin(theta)
        data.add(x); data.add(y); data.add(z)
        data.add(x); data.add(y); data.add(z)
    }
}

/** Static triangle mesh in a VBO: position(3) normal(3) color(4). */
internal class TubeMesh(data: FloatArray) {
    internal val vbo: Int
    internal val count = data.size / 10

    init {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, data.toFloatBuffer(), GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun release() = GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)

    fun draw(positionHandle: Int, normalHandle: Int, colorHandle: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 40, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 40, 12)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 40, 24)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}

/** Static coloured vertices (position 3 + colour 4) in a VBO, drawn with one primitive mode. */
internal open class ColorVboMesh(data: FloatArray, internal val mode: Int) {
    internal val vbo = makeVbo(data)
    protected val count = data.size / 7

    fun release() = GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)

    fun draw(positionHandle: Int, colorHandle: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 28, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 28, 12)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glDrawArrays(mode, 0, count)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}

internal class PointMesh(data: FloatArray) : ColorVboMesh(data, GLES20.GL_POINTS)
internal class TriMesh(data: FloatArray) : ColorVboMesh(data, GLES20.GL_TRIANGLES)
internal class LineMesh(data: FloatArray) : ColorVboMesh(data, GLES20.GL_LINES)

/** Small per-frame mesh (position + color) for things rebuilt every frame. */
internal class DynMesh(maxVerts: Int) {
    val data = FloatArray(maxVerts * 7)
    internal val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun draw(positionHandle: Int, colorHandle: Int, mode: Int, verts: Int) {
        buffer.position(0); buffer.put(data, 0, verts * 7); buffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 28, buffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        buffer.position(3)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 28, buffer)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glDrawArrays(mode, 0, verts)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }
}

/** Fine drift: plasma proteins, ions, dust, water — points whose colour follows the node. */
internal class DriftField(internal val count: Int) {
    internal val px = FloatArray(count); internal val py = FloatArray(count); internal val pz = FloatArray(count)
    internal val vx = FloatArray(count); internal val vy = FloatArray(count); internal val vz = FloatArray(count)
    internal val data = FloatArray(count * 7)
    internal val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    internal val rnd = java.util.Random(7)
    internal var seeded = false

    internal fun respawn(i: Int, cx: Float, cy: Float, cz: Float, spread: Float) {
        px[i] = cx + (rnd.nextFloat() - 0.5f) * 2f * spread
        py[i] = cy + (rnd.nextFloat() - 0.5f) * 2f * spread
        pz[i] = cz - 6f - rnd.nextFloat() * 26f
        vx[i] = (rnd.nextFloat() - 0.5f) * 0.4f
        vy[i] = (rnd.nextFloat() - 0.5f) * 0.4f
        vz[i] = 0.6f + rnd.nextFloat() * 1.2f
    }

    /** A scale drop (sign > 0): every mote is flung outward from the ship, as if the world burst open; a rise (sign < 0) pulls them in. */
    fun blowOut(cx: Float, cy: Float, cz: Float, sign: Float) {
        for (i in 0 until count) {
            val dx = px[i] - cx; val dy = py[i] - cy; val dz = pz[i] - cz
            val d = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.2f)
            vx[i] += dx / d * 6f * sign; vy[i] += dy / d * 6f * sign; vz[i] += (dz / d * 3f + 4f) * sign
        }
    }

    /** A new tour: reseed around the ship on the next update. */
    fun reset() { seeded = false }

    fun update(cx: Float, cy: Float, cz: Float, spread: Float, amb: Amb, flow: Float, dt: Float) {
        if (!seeded) { for (i in 0 until count) respawn(i, cx, cy, cz, spread); seeded = true }
        val r: Float; val g: Float; val b: Float
        when (amb) {
            Amb.AIR -> { r = 0.9f; g = 0.88f; b = 0.8f }        // dust in air
            Amb.BLOOD -> { r = 1f; g = 0.85f; b = 0.6f }        // plasma proteins
            Amb.NEURAL -> { r = 0.75f; g = 0.9f; b = 1f }       // ions
            Amb.CYTO -> { r = 0.55f; g = 0.95f; b = 0.85f }     // water + small molecules
            Amb.ATOM -> { r = 0.4f; g = 0.5f; b = 0.9f }
            Amb.GUT -> { r = 0.85f; g = 0.75f; b = 0.45f }      // chyme
            Amb.MUSCLE -> { r = 0.6f; g = 0.9f; b = 1f }        // calcium ions
            Amb.MOTOR -> { r = 1f; g = 0.92f; b = 0.55f }       // protons
            Amb.LOOKBACK -> { r = 1f; g = 0.8f; b = 0.7f }
        }
        for (i in 0 until count) {
            px[i] += vx[i] * dt; py[i] += vy[i] * dt; pz[i] += vz[i] * flow * dt
            vx[i] *= 0.985f; vy[i] *= 0.985f                                       // a blow-out settles
            if (pz[i] > cz + 4f || abs(px[i] - cx) > 14f || abs(py[i] - cy) > 14f) respawn(i, cx, cy, cz, spread)
            else if (pz[i] < cz - 34f) { respawn(i, cx, cy, cz, spread); pz[i] = cz + 1f + rnd.nextFloat() * 3f }   // flowing away (inhale): re-enter behind
            val o = i * 7
            data[o] = px[i]; data[o + 1] = py[i]; data[o + 2] = pz[i]
            data[o + 3] = r; data[o + 4] = g; data[o + 5] = b; data[o + 6] = 0.5f + 0.4f * ((i * 37) % 10) / 10f
        }
        buffer.position(0); buffer.put(data); buffer.position(0)
    }

    fun draw(positionHandle: Int, colorHandle: Int) {
        buffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 28, buffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        buffer.position(3)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 28, buffer)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }
}

/**
 * Airflow: motes that ride the breath along the passage, drawn as short streaks whose length
 * and direction follow the signed airspeed (+ = deeper on the inhale, - = out on the exhale).
 * They live in a window around the camera and respawn on the upstream side.
 */
internal class AirField(internal val count: Int) {
    internal val along = FloatArray(count)      // position along the rail, relative to the ship
    internal val lu = FloatArray(count); internal val lv = FloatArray(count)   // lateral offsets (side, up)
    internal val data = FloatArray(count * 2 * 7)
    internal val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    internal val rnd = java.util.Random(19)
    internal var seeded = false

    internal fun respawn(i: Int, spread: Float, upstream: Boolean) {
        val a = rnd.nextFloat() * 2f * PI.toFloat(); val r = spread * sqrt(rnd.nextFloat())
        lu[i] = cos(a) * r; lv[i] = sin(a) * r
        // Upstream band (-5.5, -4]: inside the kill bounds and behind every camera (chase sits at -2.7..-3.3).
        along[i] = if (upstream) -4.0f - rnd.nextFloat() * 1.5f else 14f + rnd.nextFloat() * 14f
    }

    fun reset() { seeded = false }

    fun update(cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float, sx: Float, sy: Float, sz: Float,
               ux: Float, uy: Float, uz: Float, spread: Float, flow: Float, dt: Float) {
        if (!seeded) {
            for (i in 0 until count) { respawn(i, spread, false); along[i] = rnd.nextFloat() * 28f }
            seeded = true
        }
        val len = (0.48f * abs(flow)).coerceAtLeast(0.06f)          // streak length follows airspeed
        val bright = (0.35f + 0.5f * abs(flow) / 2.6f)
        for (i in 0 until count) {
            along[i] += flow * dt
            if (along[i] > 30f) respawn(i, spread, true)         // carried deep: re-enter behind us
            else if (along[i] < -6f) respawn(i, spread, false)   // blown out past us: re-enter ahead
            val hx = cx + dx * along[i] + sx * lu[i] + ux * lv[i]
            val hy = cy + dy * along[i] + sy * lu[i] + uy * lv[i]
            val hz = cz + dz * along[i] + sz * lu[i] + uz * lv[i]
            val sgn = if (flow >= 0f) 1f else -1f
            val o = i * 14
            data[o] = hx; data[o + 1] = hy; data[o + 2] = hz
            data[o + 3] = 0.85f; data[o + 4] = 0.95f; data[o + 5] = 1f; data[o + 6] = bright
            data[o + 7] = hx - dx * len * sgn; data[o + 8] = hy - dy * len * sgn; data[o + 9] = hz - dz * len * sgn
            data[o + 10] = 0.7f; data[o + 11] = 0.9f; data[o + 12] = 1f; data[o + 13] = 0f
        }
        buffer.position(0); buffer.put(data); buffer.position(0)
    }

    fun draw(positionHandle: Int, colorHandle: Int) {
        buffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 28, buffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        buffer.position(3)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 28, buffer)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, count * 2)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }
}

/** Coarse drift: red cells, platelets, dust, pollen, proteins, vesicles — drawn as shaded shapes. */
/**
 * What drifts past at one stop: a weighted mix of body kinds, how many, and how the fluid moves.
 * Each tour keeps its own table (DESCENT_DRIFT, MACHINE_DRIFT, BETHUNE_DRIFT keyed by stop index);
 * stops without an entry fall back to [DriftSpec.forAmb].
 */
internal class DriftSpec(
    /** Relative weight per BodyField kind (indexed by the kind constants). */
    val mix: FloatArray,
    /** Fraction of the pool in use, 0..1. */
    val density: Float = 1f,
    /** Peak speed along the rail in scene units/s (+ = deeper, with the craft). NaN = ride the breath. */
    val flow: Float = 1f,
    /** Red cells oxygenated (bright) rather than deoxygenated (dark). */
    val oxy: Boolean = true,
    /** Seconds over which the population ramps up from 15% after arriving (a vessel refilling). */
    val fill: Float = 0f,
) {
    companion object {
        fun of(vararg w: Pair<Int, Float>, density: Float = 1f, flow: Float = 1f, oxy: Boolean = true, fill: Float = 0f): DriftSpec {
            val m = FloatArray(BodyField.KINDS); for ((k, v) in w) m[k] = v
            return DriftSpec(m, density, flow, oxy, fill)
        }
        val NONE = of(density = 0f)
        fun forAmb(a: Amb): DriftSpec = when (a) {
            Amb.AIR -> of(BodyField.DUST to 0.8f, BodyField.POLLEN to 0.2f, density = 0.45f, flow = Float.NaN)
            // Real proportions are ~600 red cells : 40 platelets : 1 white cell.
            Amb.BLOOD -> of(BodyField.RED_CELL to 0.94f, BodyField.PLATELET to 0.055f, BodyField.WHITE_CELL to 0.005f, flow = 1.2f)
            Amb.NEURAL -> of(BodyField.VESICLE to 1f, density = 0.3f, flow = 0.15f)
            Amb.CYTO -> of(BodyField.PROTEIN to 0.6f, BodyField.VESICLE to 0.4f, density = 0.5f, flow = 0.12f)
            Amb.GUT -> of(BodyField.BACTERIUM to 0.6f, BodyField.CHYLE to 0.4f, density = 0.6f, flow = 0.6f)
            Amb.ATOM, Amb.LOOKBACK, Amb.MOTOR, Amb.MUSCLE -> NONE
        }
    }
}

/**
 * Coarse drifting bodies (cells, particles, organelles) in RAIL coordinates: each has a rail
 * parameter p (node units), a radial fraction r of the passage radius and an angle a around the
 * axis, so they stay inside the passage however it curves. Sizes are real diameters in µm,
 * converted to scene units for the craft's current length (1.5 units = the Mote), so a red cell
 * is always 7.5 µm across whatever the magnification. The population is re-seeded (with a short
 * cross-fade) whenever the nearest stop changes, so nothing carries over from the last stop.
 */
internal class BodyField(val count: Int) {
    val p = FloatArray(count); val r = FloatArray(count); val a = FloatArray(count)
    val kind = IntArray(count); val jitter = FloatArray(count); val spin = FloatArray(count)
    val tumble = FloatArray(count)
    private val vp = FloatArray(count)
    private val rnd = java.util.Random(11)
    private var stop = -1
    private var spec: DriftSpec = DriftSpec.NONE
    private var arrivedAt = 0f
    private var clock = 0f
    /** 0..1 global alpha (cross-fade between stops). */
    var fade = 1f; private set
    private var pending: Pair<Int, DriftSpec>? = null
    private var seeded = false

    companion object {
        const val RED_CELL = 0; const val PLATELET = 1; const val DUST = 2; const val POLLEN = 3
        const val PROTEIN = 4; const val VESICLE = 5; const val TRANSMITTER = 6; const val WHITE_CELL = 7
        const val BACTERIUM = 8; const val CHYLE = 9; const val NONE = 10
        const val KINDS = 11
        /** Real diameters (µm): red cell 7.5, platelet 2.5, neutrophil 12, dust 6, pollen 25,
         *  a globular protein 8 nm, a transport vesicle 100 nm, a small molecule 1 nm, E. coli 2 µm
         *  long, a chylomicron 0.5 µm. */
        val DIAMETER_UM = floatArrayOf(7.5f, 2.5f, 6f, 25f, 0.008f, 0.1f, 0.001f, 12f, 2f, 0.5f, 0f)
    }

    fun reset() { seeded = false; stop = -1 }
    val oxy get() = spec.oxy

    /** How many bodies are live right now (density, quality, and the refill ramp). */
    fun live(quality: Int): Int {
        val q = when (quality) { 0 -> 1f; 1 -> 0.6f; else -> 0.4f }
        val ramp = if (spec.fill > 0f) (0.15f + 0.85f * ((clock - arrivedAt) / spec.fill).coerceIn(0f, 1f)) else 1f
        return (count * spec.density * q * ramp).toInt().coerceIn(0, count)
    }

    private fun pickKind(): Int {
        var total = 0f; for (w in spec.mix) total += w
        if (total <= 0f) return NONE
        var x = rnd.nextFloat() * total
        for (k in spec.mix.indices) { x -= spec.mix[k]; if (x <= 0f) return k }
        return NONE
    }

    private fun spawn(i: Int, rp: Float, anywhere: Boolean, ahead: Boolean) {
        kind[i] = pickKind()
        jitter[i] = 0.85f + 0.3f * rnd.nextFloat()
        spin[i] = rnd.nextFloat()
        tumble[i] = rnd.nextFloat() * 6.283f
        // Uniform over the cross-section: r = sqrt(u).
        r[i] = 0.82f * sqrt(rnd.nextFloat())
        a[i] = rnd.nextFloat() * 6.283f
        p[i] = when {
            anywhere -> rp - 0.28f + rnd.nextFloat() * 1.35f
            ahead -> rp + 0.95f + rnd.nextFloat() * 0.12f
            else -> rp - 0.30f + rnd.nextFloat() * 0.08f
        }
        vp[i] = 0f
    }

    /** A scale step: bodies are flung outward from the craft (sign > 0) or drawn in (sign < 0). */
    fun blowOut(rp: Float, sign: Float) {
        for (i in 0 until count) vp[i] += sign * (p[i] - rp) * 0.6f
    }

    /**
     * @param rp rail progress of the craft; @param stopIdx nearest stop; @param newSpec its spec;
     * @param breath signed airspeed (units/s, + = deeper) used when the spec rides the breath.
     */
    fun update(rp: Float, stopIdx: Int, newSpec: DriftSpec, breath: Float, dt: Float) {
        clock += dt
        if (!seeded) { stop = stopIdx; spec = newSpec; arrivedAt = clock; for (i in 0 until count) spawn(i, rp, true, false); seeded = true; fade = 1f }
        if (stopIdx != stop && pending?.first != stopIdx) pending = stopIdx to newSpec
        val pend = pending
        if (pend != null) {
            fade = (fade - dt / 0.5f).coerceAtLeast(0f)
            if (fade <= 0f) {
                stop = pend.first; spec = pend.second; arrivedAt = clock; pending = null
                for (i in 0 until count) spawn(i, rp, true, false)
            }
        } else fade = (fade + dt / 0.5f).coerceAtMost(1f)
        val vmax = if (spec.flow.isNaN()) breath else spec.flow
        for (i in 0 until count) {
            // Poiseuille profile: fastest on the axis, still at the wall.
            val v = vmax * (1f - r[i] * r[i] / 0.7f).coerceAtLeast(0.05f)
            p[i] += (v + vp[i]) / 16f * dt
            vp[i] *= 0.97f
            tumble[i] += dt * (0.25f + 0.35f * spin[i])
            if (p[i] > rp + 1.1f) spawn(i, rp, false, false)
            else if (p[i] < rp - 0.32f) spawn(i, rp, false, true)
        }
    }
}

// ============================================================== shaders

/**
 * Precision header for the fragment shaders. World positions run to z = -194 and the shaders take
 * sin() of multiples of them, which turns to static in fp16; use highp wherever the GPU offers it.
 * The vertex stage (always highp) also pre-computes the lamp/eye vectors so the fragment stage
 * only ever sees small numbers.
 */
internal const val FRAG_PRECISION = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
"""

/** Point-lit sphere shader: the Mote's lamp lights everything; rim glow in the accent colour; optional mottling. */
internal class LitShader {
    internal val program = compileProgram(
        """
        attribute vec3 aPosition;
        attribute vec3 aNormal;
        uniform mat4 uMvp;
        uniform mat4 uModel;
        uniform mat4 uNormal;
        uniform vec3 uLamp;
        uniform vec3 uEye;
        varying vec3 vNormal;
        varying vec3 vToLamp;
        varying vec3 vToEye;
        varying vec3 vLocal;
        void main() {
            vNormal = normalize((uNormal * vec4(aNormal, 0.0)).xyz);
            vec3 world = (uModel * vec4(aPosition, 1.0)).xyz;
            vToLamp = uLamp - world;
            vToEye = uEye - world;
            vLocal = aNormal;
            gl_Position = uMvp * vec4(aPosition, 1.0);
        }
        """,
        FRAG_PRECISION + """
        uniform vec4 uBase;
        uniform vec4 uAccent;
        uniform float uAlpha;
        uniform float uPattern;
        uniform float uGlow;
        varying vec3 vNormal;
        varying vec3 vToLamp;
        varying vec3 vToEye;
        varying vec3 vLocal;
        void main() {
            vec3 N = normalize(vNormal);
            vec3 L = vToLamp;
            float d = length(L);
            L /= max(d, 0.001);
            float diffuse = max(dot(N, L), 0.0) / (1.0 + d * d * 0.010);
            vec3 V = normalize(vToEye);
            float rim = pow(1.0 - max(dot(N, V), 0.0), 2.5);
            float spots = smoothstep(0.35, 0.8, sin(vLocal.x * 11.0 + vLocal.y * 7.0) * sin(vLocal.z * 9.0 + vLocal.x * 5.0));
            vec3 color = mix(uBase.rgb, uAccent.rgb, spots * uPattern * 0.6);
            color = color * (0.24 + 0.76 * diffuse) + uAccent.rgb * rim * 0.45 + color * uGlow;
            gl_FragColor = vec4(color, uBase.a * uAlpha);
        }
        """
    )
    val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
    val normalHandle = GLES20.glGetAttribLocation(program, "aNormal")
    internal val mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
    internal val modelHandle = GLES20.glGetUniformLocation(program, "uModel")
    internal val normalMatrixHandle = GLES20.glGetUniformLocation(program, "uNormal")
    internal val baseHandle = GLES20.glGetUniformLocation(program, "uBase")
    internal val accentHandle = GLES20.glGetUniformLocation(program, "uAccent")
    internal val alphaHandle = GLES20.glGetUniformLocation(program, "uAlpha")
    internal val patternHandle = GLES20.glGetUniformLocation(program, "uPattern")
    internal val glowHandle = GLES20.glGetUniformLocation(program, "uGlow")
    internal val lampHandle = GLES20.glGetUniformLocation(program, "uLamp")
    internal val eyeHandle = GLES20.glGetUniformLocation(program, "uEye")

    fun use(
        mvp: FloatArray, model: FloatArray, normal: FloatArray, base: FloatArray, accent: FloatArray,
        alpha: Float, pattern: Float, glow: Float, lx: Float, ly: Float, lz: Float, ex: Float, ey: Float, ez: Float
    ) {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(modelHandle, 1, false, model, 0)
        GLES20.glUniformMatrix4fv(normalMatrixHandle, 1, false, normal, 0)
        GLES20.glUniform4fv(baseHandle, 1, base, 0)
        GLES20.glUniform4fv(accentHandle, 1, accent, 0)
        GLES20.glUniform1f(alphaHandle, alpha)
        GLES20.glUniform1f(patternHandle, pattern)
        GLES20.glUniform1f(glowHandle, glow)
        GLES20.glUniform3f(lampHandle, lx, ly, lz)
        GLES20.glUniform3f(eyeHandle, ex, ey, ez)
    }
}

/** Passage walls: vertex colour, lit by the lamp with distance fog, a slow organic ripple and a heartbeat pulse. */
internal class WallShader {
    internal val program = compileProgram(
        """
        attribute vec3 aPosition;
        attribute vec3 aNormal;
        attribute vec4 aColor;
        uniform mat4 uMvp;
        uniform mat4 uModel;
        uniform vec3 uLamp;
        uniform float uTime;
        varying vec3 vNormal;
        varying vec3 vWorld;
        varying vec3 vToLamp;
        varying vec4 vColor;
        varying float vRipple;
        void main() {
            vNormal = aNormal;
            vWorld = (uModel * vec4(aPosition, 1.0)).xyz;
            vToLamp = uLamp - vWorld;
            vColor = aColor;
            // The slow organic ripple is smooth enough to evaluate per vertex (highp, cheap).
            vRipple = 0.5 + 0.5 * sin(vWorld.z * 1.7 + uTime * 1.5 + vWorld.x * 0.9 + vWorld.y * 1.3);
            gl_Position = uMvp * vec4(aPosition, 1.0);
        }
        """,
        FRAG_PRECISION + """
        uniform float uTime;
        uniform float uPulse;
        uniform float uFog;
        uniform float uAlpha;
        uniform float uDetail;
        varying vec3 vNormal;
        varying vec3 vWorld;
        varying vec3 vToLamp;
        varying vec4 vColor;
        varying float vRipple;
        void main() {
            vec3 L = vToLamp;
            float d = length(L);
            L /= max(d, 0.001);
            float diffuse = max(dot(normalize(vNormal), L), 0.0);
            float att = 1.0 / (1.0 + d * d * uFog);
            vec3 col = vColor.rgb * (0.10 + 0.90 * diffuse * att) * (0.85 + 0.15 * vRipple) * (1.0 + 0.30 * uPulse);
            if (uDetail > 0.5) {
                float veins = smoothstep(0.92, 1.0, sin(vWorld.z * 2.3 + vWorld.x * 1.7) * sin(vWorld.y * 2.1 - uTime * 0.3));
                col += vColor.rgb * veins * 0.35 * att;
            }
            gl_FragColor = vec4(col, vColor.a * uAlpha);
        }
        """
    )
    val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
    val normalHandle = GLES20.glGetAttribLocation(program, "aNormal")
    val colorHandle = GLES20.glGetAttribLocation(program, "aColor")
    internal val mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
    internal val modelHandle = GLES20.glGetUniformLocation(program, "uModel")
    internal val lampHandle = GLES20.glGetUniformLocation(program, "uLamp")
    internal val timeHandle = GLES20.glGetUniformLocation(program, "uTime")
    internal val pulseHandle = GLES20.glGetUniformLocation(program, "uPulse")
    internal val fogHandle = GLES20.glGetUniformLocation(program, "uFog")
    internal val alphaHandle = GLES20.glGetUniformLocation(program, "uAlpha")
    internal val detailHandle = GLES20.glGetUniformLocation(program, "uDetail")

    fun use(mvp: FloatArray, model: FloatArray, lx: Float, ly: Float, lz: Float, time: Float, pulse: Float, fog: Float, alpha: Float, detail: Float) {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(modelHandle, 1, false, model, 0)
        GLES20.glUniform3f(lampHandle, lx, ly, lz)
        GLES20.glUniform1f(timeHandle, time)
        GLES20.glUniform1f(pulseHandle, pulse)
        GLES20.glUniform1f(fogHandle, fog)
        GLES20.glUniform1f(alphaHandle, alpha)
        GLES20.glUniform1f(detailHandle, detail)
    }
}

/** The one textured surface in the app: a picture plate for chapter III. */
internal class PlateShader {
    internal val program = compileProgram(
        """
        attribute vec3 aPosition;
        attribute vec2 aUv;
        uniform mat4 uMvp;
        varying vec2 vUv;
        void main() {
            vUv = aUv;
            gl_Position = uMvp * vec4(aPosition, 1.0);
        }
        """,
        """
        precision mediump float;
        uniform sampler2D uTex;
        uniform float uAlpha;
        uniform float uLift;
        varying vec2 vUv;
        void main() {
            vec3 c = texture2D(uTex, vUv).rgb;
            // The waveguides swallow dark tones and there is no white point out here, so the
            // plate is lifted and warmed a little rather than shown flat.
            c = pow(c, vec3(0.85)) * (0.75 + 0.45 * uLift);
            gl_FragColor = vec4(c, uAlpha);
        }
        """
    )
    val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
    val uvHandle = GLES20.glGetAttribLocation(program, "aUv")
    internal val mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
    internal val texHandle = GLES20.glGetUniformLocation(program, "uTex")
    internal val alphaHandle = GLES20.glGetUniformLocation(program, "uAlpha")
    internal val liftHandle = GLES20.glGetUniformLocation(program, "uLift")

    fun use(mvp: FloatArray, texture: Int, alpha: Float, lift: Float) {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(texHandle, 0)
        GLES20.glUniform1f(alphaHandle, alpha)
        GLES20.glUniform1f(liftHandle, lift)
    }
}

internal class ColorShader {
    internal val program = compileProgram(
        """
        attribute vec3 aPosition;
        attribute vec4 aColor;
        uniform mat4 uMvp;
        uniform float uPointSize;
        varying vec4 vColor;
        void main() {
            vColor = aColor;
            gl_Position = uMvp * vec4(aPosition, 1.0);
            gl_PointSize = uPointSize;
        }
        """,
        """
        precision mediump float;
        uniform float uPoint;
        uniform float uFade;
        varying vec4 vColor;
        void main() {
            vec4 c = vColor;
            c.a *= uFade;
            if (uPoint > 0.5) {
                // Round, soft-edged point sprites instead of hard squares.
                float d = length(gl_PointCoord - vec2(0.5));
                if (d > 0.5) discard;
                c.a *= smoothstep(0.5, 0.12, d);
            }
            gl_FragColor = c;
        }
        """
    )
    val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
    val colorHandle = GLES20.glGetAttribLocation(program, "aColor")
    internal val mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
    internal val pointSizeHandle = GLES20.glGetUniformLocation(program, "uPointSize")
    internal val pointHandle = GLES20.glGetUniformLocation(program, "uPoint")
    internal val fadeHandle = GLES20.glGetUniformLocation(program, "uFade")

    /** Alpha multiplier applied to everything drawn until changed (landmark distance fade). */
    var globalFade = 1f

    /** [points] = true when the next draw is GL_POINTS (enables the round sprite look). */
    fun use(mvp: FloatArray, pointSize: Float, points: Boolean = false) {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniform1f(pointSizeHandle, pointSize)
        GLES20.glUniform1f(pointHandle, if (points) 1f else 0f)
        GLES20.glUniform1f(fadeHandle, globalFade)
    }
}

internal fun FloatArray.toFloatBuffer(): FloatBuffer {
    val buffer = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    buffer.put(this)
    buffer.position(0)
    return buffer
}

internal fun List<Float>.toFloatBuffer(): FloatBuffer = toFloatArray().toFloatBuffer()

internal fun compileProgram(vertexSource: String, fragmentSource: String): Int {
    val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertex)
    GLES20.glAttachShader(program, fragment)
    GLES20.glLinkProgram(program)
    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    require(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(program) }
    GLES20.glDeleteShader(vertex)
    GLES20.glDeleteShader(fragment)
    return program
}

internal fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source.trimIndent())
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    require(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
    return shader
}
