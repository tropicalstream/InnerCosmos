package com.rayneo.innercosmos

import android.opengl.GLES20
import android.opengl.GLSurfaceView
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
class StereoBodyRenderer(private val audioEngine: BodyAudioEngine) : GLSurfaceView.Renderer {
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)
    private val normalM = FloatArray(16)
    private val invM = FloatArray(16)
    private val identityM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private lateinit var sphere: SphereMesh
    private lateinit var blob: SphereMesh
    private lateinit var tunnel: TubeMesh
    private lateinit var moteMesh: TriMesh
    private lateinit var cockpitMesh: LineMesh
    private lateinit var routeMesh: LineMesh
    private lateinit var routeNodes: PointMesh
    private lateinit var hairMesh: LineMesh
    private lateinit var capillaryMesh: LineMesh
    private lateinit var trabeculaeMesh: LineMesh
    private lateinit var dendriteMesh: LineMesh
    private lateinit var lipidMesh: PointMesh
    private lateinit var tailMesh: LineMesh
    private lateinit var chromatinMesh: LineMesh
    private lateinit var helixMesh: LineMesh
    private lateinit var mrnaMesh: LineMesh
    private lateinit var electronMesh: PointMesh
    private lateinit var shellMesh: LineMesh
    private lateinit var cellCosmos: PointMesh
    private lateinit var antibodyMesh: LineMesh
    private lateinit var microtubuleMesh: LineMesh
    private lateinit var canaliculiMesh: LineMesh
    private lateinit var glomerulusMesh: LineMesh
    private lateinit var boneMesh: LineMesh
    private lateinit var floorLipidMesh: PointMesh
    private lateinit var floorTailMesh: LineMesh
    private lateinit var litShader: LitShader
    private lateinit var colorShader: ColorShader
    private lateinit var wallShader: WallShader
    private val drift = DriftField(150)
    private val air = AirField(96)
    private var airFlow = 0f       // signed airspeed along the rail: + = inhale (deeper), - = exhale
    private val bodies = BodyField(20)
    private val dynTris = DynMesh(24)          // valve leaflets
    private val dynLines = DynMesh(64)         // action-potential ring, spindle fibres, misc

    // The tour being rendered: its rail, wall colours, scenes and ambience families. A switch is
    // requested from any thread and applied on the GL thread (the passage meshes are rebuilt there).
    private var map: TourMap = Tours.DESCENT
    private var nodes: List<TourNode> = map.nodes
    private val pendingMap = java.util.concurrent.atomic.AtomicReference<TourMap?>(null)
    private var sentinelIdx = nodes.indexOfFirst { it.scene == Scene.SENTINEL }

    private var width = 1
    private var height = 1
    private var nowSeconds = 0f
    private var fpsFrames = 0
    private var fpsWindowStart = 0L
    @Volatile private var fpsNow = 0f
    private val startNanos = System.nanoTime()
    private var lastFrameNanos = startNanos
    private var routeProgress = 0f
    @Volatile private var railTarget = 0f          // written by the director (10 Hz); followed on the GL thread
    private var viewMode = VIEW_CHASE
    private var prevViewMode = VIEW_CHASE
    private var viewBlend = 1f
    private var craftYaw = 0f
    private var craftPitch = 0f
    private var viewListener: ((Int) -> Unit)? = null
    @Volatile private var scripted = false
    /** Two eye viewports (the X3 Pro) or one full-width view (emulator / phone testing). */
    @Volatile var stereo = true
    /** 0 = full detail, 1 = reduced (fewer bodies, no wall veins), 2 = minimal (thermal throttling). */
    @Volatile var quality = 0
    /** Title-card mode: the Mote idles outside the nose with a slow orbit and a gentle bob. */
    @Volatile var showcase = false
    private var maxLineWidth = 1f

    // Ship: rail position + flow sway, smoothed velocity for heading.
    private var shipX = 0f; private var shipY = 0f; private var shipZ = 0f
    private var velX = 0f; private var velY = 0f; private var velZ = -1f
    private var latX = 0f; private var latY = 0f; private var latZ = 0f
    private var flightInit = false
    private var dirX = 0f; private var dirY = 0f; private var dirZ = -1f
    private var sideX = 1f; private var sideY = 0f; private var sideZ = 0f
    private var upX = 0f; private var upY = 1f; private var upZ = 0f
    private var railCx = 0f; private var railCy = 0f; private var railCz = 0f   // rail centre at routeProgress

    // Camera + fx.
    private var camNowX = 0f; private var camNowY = 0f; private var camNowZ = 1f
    private var lookNowX = 0f; private var lookNowY = 0f; private var lookNowZ = 0f
    private var beat = 0f
    // Scale-drop feel: the world inflates about the ship for a beat while the hull dwindles.
    private var inflateT = 99f
    private var inflate = 1f
    private var shipScale = 1f
    private var growing = false            // the current scale step is a rise (tour II), not a drop
    private var lysisClock = 0f            // the phage stop's burst cycle (seconds)
    private val viewWorld = FloatArray(16)
    private val inflM = FloatArray(16)
    /** Head look-around (IMU), applied to the look direction only. */
    @Volatile var gaze: GazeCamera? = null
    private var shakeX = 0f; private var shakeY = 0f
    private var shakeTX = 0f; private var shakeTY = 0f; private var shakeTimer = 0f
    private var shrinkBurst = 0f
    @Volatile private var jumpOn = false
    private var jumpIntensity = 0f
    private var heartPhase = 0f
    private var heartKick = 0f
    private var wallPulse = 0f
    private val camA = FloatArray(6)
    private val camB = FloatArray(6)
    private val wallCol = FloatArray(3)

    // Arm probes (0 = folded along the hull, 1 = reaching ahead).
    private var armReach = 0f
    private var armKick = 0f
    private val tmpW = FloatArray(3)
    private val tmpS = FloatArray(3)
    private val tmpE = FloatArray(3)
    private val tmpT = FloatArray(3)

    // Alpha multiplier for the landmark being drawn (distance fade-in).
    private var landmarkFade = 1f

    // Sentinel (neutrophil) chase state.
    private var sentX = 0f; private var sentY = 0f; private var sentZ = 0f
    private var sentInit = false

    private val beaconData = FloatArray(7)
    private val beaconBuf = ByteBuffer.allocateDirect(28).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val flashData = floatArrayOf(
        -1f, -1f, 0f, 1f, 0.55f, 0.45f, 0f,
        1f, -1f, 0f, 1f, 0.55f, 0.45f, 0f,
        1f, 1f, 0f, 1f, 0.55f, 0.45f, 0f,
        -1f, -1f, 0f, 1f, 0.55f, 0.45f, 0f,
        1f, 1f, 0f, 1f, 0.55f, 0.45f, 0f,
        -1f, 1f, 0f, 1f, 0.55f, 0.45f, 0f
    )
    private val flashBuf = ByteBuffer.allocateDirect(flashData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val streakCount = 46
    private val streakSeeds = FloatArray(streakCount * 2) { Math.random().toFloat() }
    private val streakData = FloatArray(streakCount * 2 * 7)
    private val streakBuf = ByteBuffer.allocateDirect(streakData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

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
        bodies.blowOut(shipX, shipY, shipZ, 1f)
    }

    /** The reverse step (tour II climbs the ladder several times): the world contracts about the ship, the hull swells, particles rush in. */
    fun triggerGrow() {
        shrinkBurst = 1f
        inflateT = 0f
        growing = true
        drift.blowOut(shipX, shipY, shipZ, -0.6f)
        bodies.blowOut(shipX, shipY, shipZ, -0.6f)
    }

    /** The "lysis" cue: the phage stop's infected host bursts now, in step with the sound. */
    fun triggerLysis() { lysisClock = LYSIS_PERIOD * 0.62f; beat = max(beat, 0.6f) }

    /** A scripted heartbeat cue: kick the wall pulse and restart the beat clock. */
    fun triggerHeartbeat() { heartKick = 1f; heartPhase = 0f }

    /** Scale jump (menu): continuous streaks + tremble while the director races the rail. */
    fun setJumping(on: Boolean) { jumpOn = on }

    /** A scripted touch (spark / squelch cues): the arm probes reach out for a few seconds. */
    fun triggerProbe() { armKick = 1f }

    private fun armReachTarget(p: Float): Float {
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
    private fun shipLengthM(p: Float): Double {
        val pc = p.coerceIn(map.lengthKeys.first(), map.lengthKeys.last())
        var i = 1
        while (i < map.lengthKeys.size - 1 && map.lengthKeys[i] < pc) i++
        val p0 = map.lengthKeys[i - 1]; val p1 = map.lengthKeys[i]
        val t = if (p1 > p0) ((pc - p0) / (p1 - p0)).toDouble() else 1.0
        val a = log10(map.lengthM[i - 1]); val b = log10(map.lengthM[i])
        return 10.0.pow(a + (b - a) * t)
    }

    private fun fmtLength(m: Double): String {
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
    private fun fmtMag(mag: Double): String {
        if (mag < 10.0) return "%.1f×".format(Locale.US, mag)
        return "%,d×".format(Locale.US, Math.round(roundSig(mag, 2)))
    }

    private fun roundSig(v: Double, sig: Int): Double {
        if (v <= 0.0) return 0.0
        val digits = floor(log10(v)).toInt() - (sig - 1)
        val unit = 10.0.pow(digits)
        return Math.round(v / unit) * unit
    }

    /** The powers-of-ten ladder with the current rung bracketed. */
    private fun scaleLadder(lenM: Double): String {
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
        flashBuf.position(0); flashBuf.put(flashData); flashBuf.position(0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
    }

    /** GL thread: adopt a new tour — rebuild the passage and the route, forget the old chase state. */
    private fun applyMap(m: TourMap) {
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
        val dustFlow = if (amb == Amb.AIR) -airFlow * 0.7f else flowSpeed(amb)
        drift.update(shipX, shipY, shipZ, spread, amb, dustFlow, dt)
        bodies.update(shipX, shipY, shipZ, spread, amb, flowSpeed(amb), dt)
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

    private fun drawEye(x: Int, viewportWidth: Int, eyeOffset: Float, seconds: Float) {
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
    private fun flowSpeed(amb: Amb): Float = when (amb) {
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

    private fun updateFlight(dt: Float, seconds: Float) {
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

    private fun smooth01(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    private fun updateFx(dt: Float) {
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
    private fun camForMode(mode: Int, out: FloatArray) {
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
    private fun clampToTube(out: FloatArray) {
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

    private fun updateCamera(dt: Float) {
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
    private fun lampX() = shipX + dirX * 0.7f
    private fun lampY() = shipY + dirY * 0.7f
    private fun lampZ() = shipZ + dirZ * 0.7f

    // ---------------------------------------------------------- draw: world
    private fun drawTunnel(seconds: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        // Time wraps at a common period of every sin(uTime * k) in the shader (k = 1.5, 0.3) so the
        // argument stays small for half-precision GPUs without a visible seam.
        wallShader.use(mvp, model, lampX(), lampY(), lampZ(), seconds % TIME_WRAP, wallPulse, 0.02f, 1f, if (quality == 0) 1f else 0f)
        tunnel.draw(wallShader.positionHandle, wallShader.normalHandle, wallShader.colorHandle)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    private fun drawRoute() {
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 1f)
        routeMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
        colorShader.use(mvp, 6f, points = true)
        routeNodes.draw(colorShader.positionHandle, colorShader.colorHandle)
    }

    private fun drawDrift() {
        GLES20.glDepthMask(false)
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 3.2f, points = true)
        drift.draw(colorShader.positionHandle, colorShader.colorHandle)
        GLES20.glDepthMask(true)
    }

    /** Airflow streaks (nodes 0-2), fading out as the ride leaves the lungs. */
    private fun drawAir() {
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

    private fun drawBodies(seconds: Float) {
        val n = when (quality) { 0 -> bodies.count; 1 -> bodies.count / 2; else -> bodies.count / 3 }
        for (i in 0 until n) {
            val kind = bodies.kind[i]
            // Skip anything already behind the camera.
            if ((bodies.px[i] - camNowX) * dirX + (bodies.py[i] - camNowY) * dirY + (bodies.pz[i] - camNowZ) * dirZ < -1f) continue
            val s = bodies.size[i]
            val spin = seconds * 40f + bodies.spin[i] * 360f
            when (kind) {
                BodyField.RED_CELL -> drawSphereAt(bodies.px[i], bodies.py[i], bodies.pz[i], s, s * 0.32f, s, COL_RED_CELL, COL_RED_CELL_DARK, 1f, spin, 0.4f, 1f, 0.2f, blob, 1f)
                BodyField.PLATELET -> drawSphereAt(bodies.px[i], bodies.py[i], bodies.pz[i], s, s * 0.45f, s * 0.8f, COL_PLATELET, COL_LAMP, 1f, spin, 1f, 0.3f, 0f, blob)
                BodyField.DUST -> drawSphereAt(bodies.px[i], bodies.py[i], bodies.pz[i], s, s, s, COL_DUST, COL_DUST, 0.9f, 0f, 0f, 1f, 0f, blob)
                BodyField.POLLEN -> drawSphereAt(bodies.px[i], bodies.py[i], bodies.pz[i], s, s, s, COL_POLLEN, COL_LAMP, 1f, spin, 0f, 1f, 0f, blob, 1f)
                BodyField.PROTEIN -> drawSphereAt(bodies.px[i], bodies.py[i], bodies.pz[i], s, s * 0.7f, s * 1.3f, COL_PROTEIN, COL_LAMP, 1f, spin, 0.5f, 1f, 0.5f, blob, 1f)
                BodyField.VESICLE -> drawSphereAt(bodies.px[i], bodies.py[i], bodies.pz[i], s, s, s, COL_VESICLE, COL_LAMP, 0.45f, 0f, 0f, 1f, 0f, blob)
                BodyField.TRANSMITTER -> drawSphereAt(bodies.px[i], bodies.py[i], bodies.pz[i], s, s, s, COL_TRANSMITTER, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob, 0f, 0.5f)
                BodyField.WHITE_CELL -> drawSphereAt(bodies.px[i], bodies.py[i], bodies.pz[i], s, s * 0.9f, s, COL_WHITE_CELL, COL_WHITE_CELL_DARK, 0.95f, spin * 0.3f, 0f, 1f, 0.3f, sphere, 1f)
                BodyField.BACTERIUM -> drawSphereAt(bodies.px[i], bodies.py[i], bodies.pz[i], s * 0.42f, s * 0.42f, s * 1.2f, COL_BACTERIUM, COL_BACTERIUM_DARK, 1f, spin * 0.5f, 0.3f, 1f, 0.5f, blob, 1f)
                BodyField.CHYLE -> drawSphereAt(bodies.px[i], bodies.py[i], bodies.pz[i], s, s, s, COL_CHYLE, COL_LAMP, 0.7f, 0f, 0f, 1f, 0f, blob, 0f, 0.2f)
            }
        }
    }

    private fun drawBeacon(seconds: Float) {
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
    private fun drawLandmarks(seconds: Float) {
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
                Scene.BLOOD -> {}                                   // red cells come from the BodyField
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
            }
        }
        landmarkFade = 1f
        colorShader.globalFade = 1f
    }

    /** Node 0: the nostril as a cave mouth (a ring of flesh) with a forest of nasal hairs behind a warm bay glow. */
    private fun drawThreshold(n: TourNode, i: Int, seconds: Float) {
        val f = frameAt(i + 0.55f)
        // The bay light: a big warm sphere behind the start.
        drawSphereAt(n.x, n.y + 2.5f, n.z + 14f, 5f, 5f, 5f, COL_BAY, COL_LAMP, 0.35f, 0f, 0f, 1f, 0f, sphere, 0f, 0.8f)
        for (k in 0 until 12) {
            val a = 2f * PI.toFloat() * k / 12f
            val ox = f.sx * cos(a) + f.ux * sin(a); val oy = f.sy * cos(a) + f.uy * sin(a); val oz = f.sz * cos(a) + f.uz * sin(a)
            val rr = 3.1f + 0.25f * sin(k * 1.7f + seconds * 0.6f)
            drawSphereAt(f.cx + ox * rr, f.cy + oy * rr, f.cz + oz * rr, 1.0f, 0.78f, 1.0f, COL_SKIN, COL_SKIN_DARK, 1f, 0f, 0f, 1f, 0f, blob, 1f)
        }
        drawLinesAt(hairMesh, f.cx, f.cy, f.cz, 2.5f, 0f, 0f, 1f, 0f)
    }

    /** Node 1: C-shaped cartilage rings down the trachea (beaded), open at the back. */
    private fun drawAirway(n: TourNode, i: Int, seconds: Float) {
        for (ring in 0 until 5) {
            val p = i - 0.28f + ring * 0.13f
            val f = frameAt(p)
            val rr = tunnelRadius(p) * 0.88f
            for (k in 0 until 10) {
                if (k in 7..8) continue                      // the C's gap: a quarter turn, centred at -up (the oesophagus side)
                val a = 2f * PI.toFloat() * k / 10f
                val ox = f.sx * cos(a) + f.ux * sin(a); val oy = f.sy * cos(a) + f.uy * sin(a); val oz = f.sz * cos(a) + f.uz * sin(a)
                drawSphereAt(f.cx + ox * rr, f.cy + oy * rr, f.cz + oz * rr, 0.46f, 0.46f, 0.55f, COL_CARTILAGE, COL_SKIN, 1f, 0f, 0f, 1f, 0f, blob)
            }
        }
    }

    /** Node 2: a cluster of translucent air sacs wrapped in capillaries. */
    private fun drawAlveolus(n: TourNode, i: Int, seconds: Float) {
        val f = frameAt(i.toFloat())
        GLES20.glDepthMask(false)
        for (k in 0 until 7) {
            val a = 2f * PI.toFloat() * k / 7f + 0.4f
            val d = 3.4f + 0.6f * sin(k * 2.1f)
            val ox = f.sx * cos(a) + f.ux * sin(a); val oy = f.sy * cos(a) + f.uy * sin(a); val oz = f.sz * cos(a) + f.uz * sin(a)
            val breathe = 1f + 0.08f * sin(seconds * 1.3f + k)
            val r = (1.3f + 0.35f * (k % 3)) * breathe
            drawSphereAt(f.cx + ox * d + f.dx * (k - 3) * 0.9f, f.cy + oy * d + f.dy * (k - 3) * 0.9f, f.cz + oz * d + f.dz * (k - 3) * 0.9f,
                r, r, r, COL_ALVEOLUS, COL_RED_CELL, 0.42f, 0f, 0f, 1f, 0f, sphere, 0f, 0.25f)
        }
        GLES20.glDepthMask(true)
        drawLinesAt(capillaryMesh, f.cx, f.cy, f.cz, 1f, seconds * 4f, 0f, 1f, 0f)
    }

    /** Node 4: the mitral valve slamming with every beat inside a chamber laced with trabeculae. */
    private fun drawHeart(n: TourNode, i: Int, seconds: Float) {
        val f = frameAt(i.toFloat())
        drawLinesAt(trabeculaeMesh, f.cx, f.cy, f.cz, 1f, seconds * 2f, 0f, 0f, 1f)
        // Mitral valve: shut at S1 (heartPhase 0, the "lub" = mitral closure, start of systole),
        // swings open just after S2 for diastole. Leaflets are hinged on the wall and open DOWNSTREAM.
        val open = ((heartPhase - 0.36f) / 0.14f).coerceIn(0f, 1f)
        val ang = (6f + open * open * 78f) * PI.toFloat() / 180f
        val rr = tunnelRadius(i.toFloat()) * 0.98f
        // Fade the leaflets out as the camera passes through the valve plane (no full-screen flashes).
        val dAlong = (camNowX - f.cx) * f.dx + (camNowY - f.cy) * f.dy + (camNowZ - f.cz) * f.dz
        val leafAlpha = ((abs(dAlong) - 0.6f) / 1.2f).coerceIn(0f, 1f) * 0.92f
        if (leafAlpha < 0.02f) return
        val l = rr * 0.95f
        for (side in 0 until 2) {
            val sgn = if (side == 0) 1f else -1f
            // Frame-local (x = side, y = up, z = back): hinge on the wall, leaflet swinging from the
            // axis (closed) toward down-flow (open) about the side axis.
            val cxL = 0f; val cyL = sgn * (rr - 0.5f * l * cos(ang)); val czL = -0.5f * l * sin(ang)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, f.cx, f.cy, f.cz)
            applyFrameRotation(f)
            Matrix.translateM(model, 0, cxL, cyL, czL)
            Matrix.rotateM(model, 0, sgn * ang * 180f / PI.toFloat(), 1f, 0f, 0f)
            Matrix.scaleM(model, 0, rr * 0.9f, l * 0.5f, 0.07f)
            drawLitModel(sphere, COL_VALVE, COL_VALVE_EDGE, leafAlpha, 1f, 0f)
        }
    }

    /** Node 5: a neutrophil that notices the Mote (pulsing blob + pseudopods reaching for the ship), antibodies and a macrophage. */
    private fun drawSentinel(n: TourNode, i: Int, seconds: Float) {
        val pulse = 1f + 0.08f * sin(seconds * 2.7f)
        drawSphereAt(sentX, sentY, sentZ, 1.35f * pulse, 1.2f * pulse, 1.35f * pulse, COL_NEUTROPHIL, COL_NEUTROPHIL_DARK, 0.96f, seconds * 15f, 0.2f, 1f, 0.3f, sphere, 1f)
        // Pseudopods: elongated lobes pointing at the ship.
        var tx = shipX - sentX; var ty = shipY - sentY; var tz = shipZ - sentZ
        val dl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(0.001f); tx /= dl; ty /= dl; tz /= dl
        for (k in 0 until 5) {
            val wob = sin(seconds * 1.9f + k * 1.3f)
            val reach = 1.1f + 0.5f * wob
            val ax = tx + 0.35f * sin(k * 2.1f + seconds * 0.7f); val ay = ty + 0.35f * cos(k * 1.7f + seconds * 0.5f); val az = tz + 0.3f * sin(k * 1.1f)
            val al = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(0.001f)
            val cx = sentX + ax / al * reach * 0.8f; val cy = sentY + ay / al * reach * 0.8f; val cz = sentZ + az / al * reach * 0.8f
            val yaw = atan2(ax, az) * 180f / PI.toFloat()
            drawSphereAt(cx, cy, cz, 0.28f, 0.28f, reach * 0.6f, COL_NEUTROPHIL, COL_NEUTROPHIL_DARK, 0.9f, yaw, 0f, 1f, 0f, blob)
        }
        // Antibodies (Y shapes) drifting near the node, and a macrophage waiting further on.
        val f = frameAt(i + 0.4f)
        drawLinesAt(antibodyMesh, f.cx, f.cy, f.cz, 1f, seconds * 9f, 0.3f, 1f, 0.2f)
        drawSphereAt(f.cx + f.sx * 2.0f, f.cy + f.sy * 2.0f - 0.4f, f.cz + f.sz * 2.0f, 2.0f, 1.7f, 2.1f, COL_MACROPHAGE, COL_NEUTROPHIL_DARK, 0.95f, seconds * 6f, 0f, 1f, 0f, sphere, 1f)
    }

    /** Node 6: soma + dendrite tree beside the axon, myelin beads, an action potential racing past, then vesicles at the synapse. */
    private fun drawNeuron(n: TourNode, i: Int, seconds: Float) {
        val f = frameAt(i.toFloat())
        val somaX = f.cx + f.sx * 3.6f + f.ux * 1.2f; val somaY = f.cy + f.sy * 3.6f + f.uy * 1.2f; val somaZ = f.cz + f.sz * 3.6f + f.uz * 1.2f
        drawSphereAt(somaX, somaY, somaZ, 1.5f, 1.3f, 1.5f, COL_SOMA, COL_SOMA_LIGHT, 1f, 0f, 0f, 1f, 0f, sphere, 1f)
        drawLinesAt(dendriteMesh, somaX, somaY, somaZ, 1f, 0f, 0f, 1f, 0f)
        // Myelin sheaths: pale beaded rings with gaps (nodes of Ranvier).
        for (ring in 0 until 4) {
            val p = i + 0.15f + ring * 0.16f
            val fr = frameAt(p); val rr = tunnelRadius(p) * 0.9f
            for (k in 0 until 8) {
                val a = 2f * PI.toFloat() * k / 8f
                val ox = fr.sx * cos(a) + fr.ux * sin(a); val oy = fr.sy * cos(a) + fr.uy * sin(a); val oz = fr.sz * cos(a) + fr.uz * sin(a)
                drawSphereAt(fr.cx + ox * rr, fr.cy + oy * rr, fr.cz + oz * rr, 0.5f, 0.5f, 1.0f, COL_MYELIN, COL_SOMA_LIGHT, 0.9f, 0f, 0f, 1f, 0f, blob)
            }
        }
        // Action potential: a bright ring sweeping along the axon every 2.4 s.
        val ap = ((seconds / 2.4f) % 1f)
        val fp = frameAt(i - 0.25f + ap * 1.15f)
        val arr = dynLines.data
        var k = 0
        val rr = tunnelRadius(i - 0.25f + ap * 1.15f) * 0.96f
        for (s in 0 until 24) {
            val a0 = 2f * PI.toFloat() * s / 24f; val a1 = 2f * PI.toFloat() * (s + 1) / 24f
            for (a in floatArrayOf(a0, a1)) {
                val ox = fp.sx * cos(a) + fp.ux * sin(a); val oy = fp.sy * cos(a) + fp.uy * sin(a); val oz = fp.sz * cos(a) + fp.uz * sin(a)
                arr[k++] = fp.cx + ox * rr; arr[k++] = fp.cy + oy * rr; arr[k++] = fp.cz + oz * rr
                arr[k++] = 0.85f; arr[k++] = 0.9f; arr[k++] = 1f; arr[k++] = 0.9f
            }
        }
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 1f)
        lineWidth(3f)
        dynLines.draw(colorShader.positionHandle, colorShader.colorHandle, GLES20.GL_LINES, 48)
        lineWidth(1f)
        // Synapse: vesicles at the terminal, popping toward the cleft.
        val fs = frameAt(i + 0.8f)
        for (v in 0 until 8) {
            val a = 2f * PI.toFloat() * v / 8f
            val pop = ((seconds * 0.7f + v * 0.37f) % 1f)
            val rad = tunnelRadius(i + 0.8f) * (0.85f - pop * 0.5f)
            val ox = fs.sx * cos(a) + fs.ux * sin(a); val oy = fs.sy * cos(a) + fs.uy * sin(a); val oz = fs.sz * cos(a) + fs.uz * sin(a)
            val s = 0.22f * (1f - pop * 0.6f)
            drawSphereAt(fs.cx + ox * rad + fs.dx * pop * 1.5f, fs.cy + oy * rad + fs.dy * pop * 1.5f, fs.cz + oz * rad + fs.dz * pop * 1.5f,
                s, s, s, COL_VESICLE, COL_TRANSMITTER, 0.8f, 0f, 0f, 1f, 0f, blob, 0f, 0.4f)
        }
    }

    /** Node 7: the bilayer as two sheets of lipid heads with tails between, a channel protein ringing the gap the Mote slips through. */
    private fun drawMembrane(n: TourNode, i: Int, seconds: Float) {
        val f = frameAt(i.toFloat())
        // Sheets are built in a local frame (x=side, y=up, z=dir); rotate to match the rail.
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, f.cx, f.cy, f.cz)
        applyFrameRotation(f)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 7f, points = true)
        lipidMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
        colorShader.use(mvp, 1f)
        tailMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
        // Channel protein: six columns around the opening.
        for (k in 0 until 6) {
            val a = 2f * PI.toFloat() * k / 6f + seconds * 0.15f
            val rr = 1.25f
            val ox = f.sx * cos(a) + f.ux * sin(a); val oy = f.sy * cos(a) + f.uy * sin(a); val oz = f.sz * cos(a) + f.uz * sin(a)
            drawSphereAt(f.cx + ox * rr, f.cy + oy * rr, f.cz + oz * rr, 0.3f, 0.3f, 0.75f, COL_CHANNEL, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob, 1f)
        }
    }

    /** Node 8: cristae ridges and ATP synthase rotors turning on the inner membrane. */
    private fun drawMitochondrion(n: TourNode, i: Int, seconds: Float) {
        for (ridge in 0 until 5) {
            val p = i - 0.3f + ridge * 0.14f
            val fr = frameAt(p); val rr = tunnelRadius(p)
            val sgn = if (ridge % 2 == 0) 1f else -1f
            // A folded shelf reaching from one wall toward the middle, leaving the passage open.
            drawSphereAt(fr.cx + fr.sx * sgn * rr * 0.55f, fr.cy + fr.sy * sgn * rr * 0.55f, fr.cz + fr.sz * sgn * rr * 0.55f,
                rr * 0.5f, rr * 0.95f, 0.12f, COL_CRISTAE, COL_LAMP, 0.9f, 90f - yawOf(fr), 0f, 1f, 0f, sphere, 1f)
            // ATP synthase: sits perpendicular to the crista membrane, stalk through it and the
            // F1 head protruding into the matrix (toward the passage centre).
            val px0 = fr.cx + fr.sx * sgn * rr * 0.55f + fr.ux * 0.35f; val py0 = fr.cy + fr.sy * sgn * rr * 0.55f + fr.uy * 0.35f; val pz0 = fr.cz + fr.sz * sgn * rr * 0.55f + fr.uz * 0.35f
            val plateYaw = 90f - yawOf(fr)
            drawSphereAt(px0 - fr.sx * sgn * 0.17f, py0 - fr.sy * sgn * 0.17f, pz0 - fr.sz * sgn * 0.17f, 0.06f, 0.06f, 0.35f, COL_ATP_STALK, COL_LAMP, 1f, plateYaw, 0f, 1f, 0f, blob)
            drawSphereAt(px0 - fr.sx * sgn * 0.42f, py0 - fr.sy * sgn * 0.42f, pz0 - fr.sz * sgn * 0.42f, 0.32f, 0.32f, 0.16f, COL_ATP_HEAD, COL_LAMP, 1f, plateYaw, 0f, 1f, 0f, blob, 1f)
            // Three knobs turning around the head: the rotor at ~100 revolutions a second, slowed to be seen.
            for (kn in 0 until 3) {
                val a = seconds * 6.5f + kn * 2.094f
                val kx = px0 - fr.sx * sgn * 0.42f + fr.ux * 0.30f * cos(a) + fr.dx * 0.30f * sin(a)
                val ky = py0 - fr.sy * sgn * 0.42f + fr.uy * 0.30f * cos(a) + fr.dy * 0.30f * sin(a)
                val kz = pz0 - fr.sz * sgn * 0.42f + fr.uz * 0.30f * cos(a) + fr.dz * 0.30f * sin(a)
                drawSphereAt(kx, ky, kz, 0.07f, 0.07f, 0.07f, COL_LAMP, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob, 0f, 0.6f)
            }
        }
    }

    /** Node 9: the nuclear pore, chromatin, and the double helix with a polymerase crawling along it. */
    private fun drawNucleus(n: TourNode, i: Int, seconds: Float) {
        val fp = frameAt(i - 0.15f); val rr = tunnelRadius(i - 0.15f) * 0.8f
        for (k in 0 until 8) {
            val a = 2f * PI.toFloat() * k / 8f
            val ox = fp.sx * cos(a) + fp.ux * sin(a); val oy = fp.sy * cos(a) + fp.uy * sin(a); val oz = fp.sz * cos(a) + fp.uz * sin(a)
            drawSphereAt(fp.cx + ox * rr, fp.cy + oy * rr, fp.cz + oz * rr, 0.42f, 0.42f, 0.55f, COL_PORE, COL_NUCLEUS_LIGHT, 1f, 0f, 0f, 1f, 0f, blob, 1f)
        }
        val f = frameAt(i + 0.1f)
        drawLinesAt(chromatinMesh, f.cx, f.cy, f.cz, 1f, seconds * 1.5f, 0f, 1f, 0f)
        // The helix lies beside the path, slowly turning.
        val hx = f.cx + f.sx * 1.6f; val hy = f.cy + f.sy * 1.6f + 0.2f; val hz = f.cz + f.sz * 1.6f
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, hx, hy, hz)
        applyFrameRotation(f)
        Matrix.rotateM(model, 0, seconds * 12f, 0f, 0f, 1f)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 1f)
        lineWidth(2f)
        helixMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
        lineWidth(1f)
        // RNA polymerase: a blob sliding along the helix axis.
        val slide = ((seconds * 0.12f) % 1f) * 8f - 4f
        drawSphereAt(hx + f.dx * slide, hy + f.dy * slide, hz + f.dz * slide, 0.55f, 0.5f, 0.6f, COL_POLYMERASE, COL_LAMP, 1f, seconds * 30f, 0f, 1f, 0f, blob, 1f)
    }

    /** Node 10: two ribosomal subunits with mRNA threading through, tRNAs docking, and a polypeptide chain growing out. */
    private fun drawRibosome(n: TourNode, i: Int, seconds: Float) {
        val f = frameAt(i.toFloat())
        val bx = f.cx + f.sx * 1.9f - f.ux * 0.3f; val by = f.cy + f.sy * 1.9f - f.uy * 0.3f; val bz = f.cz + f.sz * 1.9f - f.uz * 0.3f
        drawSphereAt(bx, by, bz, 1.5f, 1.1f, 1.4f, COL_RIBO_LARGE, COL_RIBO_LIGHT, 1f, 20f, 0f, 1f, 0.4f, sphere, 1f)
        // Small subunit above the large one; the mRNA threads through the seam between them.
        drawSphereAt(bx + f.ux * 1.6f, by + f.uy * 1.6f, bz + f.uz * 1.6f, 1.0f, 0.7f, 1.1f, COL_RIBO_SMALL, COL_RIBO_LIGHT, 1f, -15f, 0f, 1f, 0.2f, sphere, 1f)
        drawLinesAt(mrnaMesh, bx, by, bz, 1f, 0f, 0f, 1f, 0f)
        // tRNAs shuttling in.
        for (k in 0 until 3) {
            val ph = ((seconds * 0.5f + k * 0.33f) % 1f)
            val tx = bx + f.dx * (3f - ph * 3f) + f.ux * (1.2f + 0.8f * (1f - ph)); val ty = by + f.dy * (3f - ph * 3f) + f.uy * (1.2f + 0.8f * (1f - ph)); val tz = bz + f.dz * (3f - ph * 3f) + f.uz * (1.2f + 0.8f * (1f - ph))
            drawSphereAt(tx, ty, tz, 0.16f, 0.28f, 0.16f, COL_TRNA, COL_LAMP, 1f, ph * 200f, 0f, 1f, 0f, blob)
        }
        // Growing polypeptide: a spiral of beads emerging from the large subunit.
        val beads = 4 + (((seconds * 0.35f) % 1f) * 10f).toInt()
        for (k in 0 until beads) {
            val a = k * 0.9f
            val px = bx - f.ux * (1.3f + k * 0.14f) + f.sx * 0.35f * cos(a) + f.dx * 0.35f * sin(a)
            val py = by - f.uy * (1.3f + k * 0.14f) + f.sy * 0.35f * cos(a) + f.dy * 0.35f * sin(a)
            val pz = bz - f.uz * (1.3f + k * 0.14f) + f.sz * 0.35f * cos(a) + f.dz * 0.35f * sin(a)
            drawSphereAt(px, py, pz, 0.13f, 0.13f, 0.13f, if (k % 2 == 0) COL_AMINO_A else COL_AMINO_B, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob)
        }
    }

    /** Node 11: the electron cloud (a haze of points), carbon's two shells (2 + 4 electrons), and the nucleus: a bright mote in a vast emptiness. */
    private fun drawAtom(n: TourNode, i: Int, seconds: Float) {
        GLES20.glDepthMask(false)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, n.x, n.y, n.z)
        Matrix.rotateM(model, 0, seconds * 9f, 0.3f, 1f, 0.2f)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 2.6f + 1.2f * sin(seconds * 5f), points = true)
        electronMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
        colorShader.use(mvp, 1f)
        shellMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
        GLES20.glDepthMask(true)
        val pulse = 0.85f + 0.15f * sin(seconds * 7f)
        drawSphereAt(n.x, n.y, n.z, 0.09f * pulse, 0.09f * pulse, 0.09f * pulse, COL_NUCLEON, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob, 0f, 1.5f)
    }

    /** Node 12: the body as a cosmos of cells (a starfield of points) around a warm world ahead. */
    private fun drawLookBack(n: TourNode, i: Int, seconds: Float) {
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, n.x, n.y, n.z)
        Matrix.rotateM(model, 0, seconds * 1.5f, 0f, 1f, 0f)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 3.6f, points = true)
        cellCosmos.draw(colorShader.positionHandle, colorShader.colorHandle)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        // The warm world ahead appears only once the re-expansion is under way (not from inside the atom).
        if (routeProgress > i - 0.4f) drawSphereAt(n.x, n.y + 0.4f, n.z - 9f, 3.2f, 3.2f, 3.2f, COL_WORLD, COL_LAMP, 1f, seconds * 4f, 0f, 1f, 0f, sphere, 1f, 0.35f)
    }


    // ------------------------------------------------ draw: tour II landmarks
    // Frame-relative helpers: (along, side, up) offsets from a rail frame; along > 0 is deeper.
    private fun fx(f: Frame, a: Float, s: Float, u: Float) = f.cx + f.dx * a + f.sx * s + f.ux * u
    private fun fy(f: Frame, a: Float, s: Float, u: Float) = f.cy + f.dy * a + f.sy * s + f.uy * u
    private fun fz(f: Frame, a: Float, s: Float, u: Float) = f.cz + f.dz * a + f.sz * s + f.uz * u

    private fun blobAt(
        f: Frame, a: Float, s: Float, u: Float, sx: Float, sy: Float, sz: Float, base: FloatArray, accent: FloatArray,
        alpha: Float = 1f, rotDeg: Float = 0f, ax: Float = 0f, ay: Float = 1f, az: Float = 0f,
        mesh: SphereMesh = blob, pattern: Float = 0f, glow: Float = 0f
    ) = drawSphereAt(fx(f, a, s, u), fy(f, a, s, u), fz(f, a, s, u), sx, sy, sz, base, accent, alpha, rotDeg, ax, ay, az, mesh, pattern, glow)

    private fun strutAt(f: Frame, a0: Float, s0: Float, u0: Float, a1: Float, s1: Float, u1: Float, radius: Float, base: FloatArray, accent: FloatArray, glow: Float = 0f) =
        drawStrut(fx(f, a0, s0, u0), fy(f, a0, s0, u0), fz(f, a0, s0, u0), fx(f, a1, s1, u1), fy(f, a1, s1, u1), fz(f, a1, s1, u1), radius, base, accent, glow)

    /** Tour II stop 1: the lips as a wide oval of flesh, an upper and a lower arch of teeth with a
     *  dark gape between them, the tongue below, the uvula above, daylight behind. */
    private fun drawMouth(n: TourNode, i: Int, seconds: Float) {
        val b = i.toFloat()
        val f = frameAt(b + 0.55f); val rr = tunnelRadius(b + 0.55f)
        drawSphereAt(n.x, n.y + 2.5f, n.z + 14f, 5f, 5f, 5f, COL_BAY, COL_LAMP, 0.35f, 0f, 0f, 1f, 0f, sphere, 0f, 0.8f)
        // A mouth is far wider than it is tall: the lips ring an oval, not a circle.
        for (k in 0 until 16) {
            val a = 2f * PI.toFloat() * k / 16f
            blobAt(f, 0f, cos(a) * rr * 0.98f, sin(a) * rr * 0.52f, 0.95f, 0.6f, 1.0f, COL_LIP, COL_SKIN_DARK, 1f, 0f, 0f, 1f, 0f, blob, 1f)
        }
        // Two dental arches, each a U curving away from us in the horizontal plane, with a dark
        // gape between them the Mote flies through — never a ring of teeth around the opening.
        val ft = frameAt(b + 0.72f)
        val gape = rr * (0.36f + 0.03f * sin(seconds * 0.5f))
        val halfWidth = rr * 0.66f
        for (row in 0 until 2) {
            val sgn = if (row == 0) 1f else -1f
            for (k in 0 until 7) {
                val a = (k - 3f) / 3f * 1.0f
                val h = if (k in 2..4) 0.44f else 0.32f        // incisors at the front, shorter teeth at the sides
                blobAt(ft, (1f - cos(a)) * 1.2f, sin(a) * halfWidth, sgn * (gape + h * 0.5f), 0.20f, h, 0.16f, COL_TOOTH, COL_TOOTH)
            }
        }
        val fg = frameAt(b + 0.85f)
        blobAt(fg, 0f, 0f, -rr * 0.66f, rr * 0.52f, 0.45f, 2.4f, COL_TONGUE, COL_LIP, 1f, yawOf(fg), 0f, 1f, 0f, sphere, 1f)
        blobAt(fg, 0.9f, 0f, rr * 0.55f - 0.2f * sin(seconds * 0.7f), 0.22f, 0.5f, 0.22f, COL_LIP, COL_SKIN_DARK)
    }

    /** Tour II stop 2: villi (finger-like folds waving in the flow) lining the small intestine; the microbiome drifts by from the BodyField. */
    private fun drawGut(n: TourNode, i: Int, seconds: Float) {
        val b = i.toFloat()
        val rings = if (quality == 0) 4 else 2
        for (ring in 0 until rings) {
            val p = b - 0.32f + ring * 0.2f
            val f = frameAt(p); val rr = tunnelRadius(p)
            for (k in 0 until 8) {
                val a = 2f * PI.toFloat() * (k + 0.5f * (ring % 2)) / 8f
                val sway = 0.12f * sin(seconds * 1.1f + k * 1.7f + ring)
                val len = 0.34f * rr
                val cs = cos(a); val sn = sin(a)
                strutAt(f, 0f, cs * rr * 0.98f, sn * rr * 0.98f, sway, cs * (rr - len), sn * (rr - len), 0.14f, COL_VILLUS, COL_VILLUS_TIP)
                blobAt(f, sway, cs * (rr - len), sn * (rr - len), 0.16f, 0.16f, 0.16f, COL_VILLUS_TIP, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob, 0f, 0.2f)
            }
        }
    }

    /** Tour II stop 3: phages landing on a bacterium (head, tail, splayed fibres) and a second host bursting on a cycle (or on the "lysis" cue). */
    private fun drawPhage(n: TourNode, i: Int, seconds: Float) {
        val b = i.toFloat()
        val f = frameAt(b + 0.25f); val rr = tunnelRadius(b + 0.25f)
        val hs = rr * 0.48f; val hu = -rr * 0.15f
        strutAt(f, -1.4f, hs, hu, 1.4f, hs, hu, 0.55f, COL_BACTERIUM, COL_BACTERIUM_DARK)
        if (quality == 0) for (seg in 0 until 6) {   // a flagellum whipping behind the rod
            val t0 = seg / 6f; val t1 = (seg + 1) / 6f
            strutAt(f, 1.4f + t0 * 2.2f, hs + 0.25f * sin(t0 * 9f - seconds * 6f), hu + 0.25f * cos(t0 * 9f - seconds * 6f),
                    1.4f + t1 * 2.2f, hs + 0.25f * sin(t1 * 9f - seconds * 6f), hu + 0.25f * cos(t1 * 9f - seconds * 6f), 0.03f, COL_BACTERIUM_DARK, COL_BACTERIUM)
        }
        for (k in 0 until 4) {   // phages: three landed, one still descending
            val along = -0.9f + k * 0.6f
            val land = if (k == 3) ((seconds * 0.08f) % 1f) else 1f
            val lift = 0.55f + (1f - land) * 1.6f
            // Landed phages ride the wall-facing half of the rod (top, outer flank, underside) so
            // none reaches into the Mote's lane; the one still descending keeps its approach.
            val a = if (k == 3) 3.9f else 1.2f - k * 1.25f
            val ds = cos(a); val du = sin(a)
            val tailLen = 0.36f
            val bx = hs + ds * lift; val bu = hu + du * lift
            strutAt(f, along, bx, bu, along, bx + ds * tailLen, bu + du * tailLen, 0.05f, COL_PHAGE_TAIL, COL_LAMP)
            blobAt(f, along, bx + ds * (tailLen + 0.16f), bu + du * (tailLen + 0.16f), 0.17f, 0.2f, 0.17f, COL_PHAGE, COL_PHAGE_LIGHT, 1f, seconds * 20f + k * 50f, ds, 0f, du, blob, 1f,
                if (k == 1) 0.4f * (0.5f + 0.5f * sin(seconds * 8f)) else 0f)
            if (quality > 1) continue
            for (leg in 0 until 6) {
                val la = 2f * PI.toFloat() * leg / 6f
                val spread = 0.22f * land
                val sa = cos(la) * spread; val su = sin(la) * spread
                strutAt(f, along, bx, bu, along + sa, bx + du * su * 0.5f - ds * 0.05f, bu - ds * su * 0.5f - du * 0.05f, 0.02f, COL_PHAGE_TAIL, COL_LAMP)
            }
        }
        // Lysis: across the passage a hijacked host swells, bursts into debris and new phages, and reforms.
        val ph = lysisClock / LYSIS_PERIOD
        val f2 = frameAt(b + 0.75f); val r2 = tunnelRadius(b + 0.75f)
        val s2 = -r2 * 0.5f; val u2 = r2 * 0.2f
        if (ph < 0.62f) {
            val swell = 1f + 0.35f * smooth01((ph - 0.3f) / 0.32f)
            strutAt(f2, -1.2f * swell, s2, u2, 1.2f * swell, s2, u2, 0.5f * swell, COL_BACTERIUM, COL_BACTERIUM_DARK, 0.6f * smooth01((ph - 0.5f) / 0.12f))
        } else {
            val t = (ph - 0.62f) / 0.38f
            val alpha = (1f - t).coerceIn(0f, 1f)
            if (alpha > 0.03f) for (k in 0 until (if (quality == 0) 14 else 7)) {
                val a = k * 2.4f; val el = k * 1.1f
                val d = 0.4f + t * 3.2f
                val ds = cos(a) * cos(el); val du = sin(a) * cos(el); val da = sin(el)
                val sz = if (k % 3 == 0) 0.13f else 0.07f
                blobAt(f2, da * d, s2 + ds * d, u2 + du * d, sz, sz, sz, if (k % 3 == 0) COL_PHAGE else COL_BACTERIUM, COL_LAMP, alpha, 0f, 0f, 1f, 0f, blob, 0f, 0.5f * alpha)
            }
        }
    }

    /** Tour II stop 4: plates of hepatocytes walling a sinusoid, bile canaliculi glowing between them, a Kupffer cell on the wall. */
    private fun drawLiver(n: TourNode, i: Int, seconds: Float) {
        val b = i.toFloat()
        val rows = if (quality == 0) 7 else 4
        for (row in 0 until rows) {
            val p = b - 0.36f + row * (0.84f / rows)
            val f = frameAt(p); val rr = tunnelRadius(p)
            for (side in 0 until 2) {
                val sgn = if (side == 0) 1f else -1f
                for (k in 0 until 2) {
                    val u = (k - 0.5f) * 0.9f
                    blobAt(f, 0f, sgn * rr * 0.86f, u, 0.44f, 0.42f, 0.5f, COL_HEPATOCYTE, COL_HEPATOCYTE_DARK, 1f, row * 37f, 0f, 1f, 0f, blob, 1f)
                }
            }
        }
        val fc = frameAt(b)
        drawLinesAt(canaliculiMesh, fc.cx, fc.cy, fc.cz, 1f, 0f, 0f, 1f, 0f)
        val fk = frameAt(b + 0.2f); val rk = tunnelRadius(b + 0.2f)
        blobAt(fk, 0f, 0f, rk * 0.72f, 0.8f, 0.45f, 0.9f, COL_MACROPHAGE, COL_NEUTROPHIL_DARK, 1f, seconds * 5f, 0f, 1f, 0f, sphere, 1f)
    }

    /** Tour II stop 5: the glomerulus — a knot of capillaries inside Bowman's capsule, podocytes wrapping it — with filtrate dripping into the tubule. */
    private fun drawKidney(n: TourNode, i: Int, seconds: Float) {
        val b = i.toFloat()
        val f = frameAt(b + 0.15f); val rr = tunnelRadius(b + 0.15f)
        // The knot sits low on the wall: the Mote's sway lane (about 1.0 from the rail) and the
        // chase camera's band both stay outside Bowman's capsule, so nothing flies through it.
        val gs = rr * 0.72f; val gu = -rr * 0.22f
        val gx = fx(f, 0f, gs, gu); val gy = fy(f, 0f, gs, gu); val gz = fz(f, 0f, gs, gu)
        drawLinesAt(glomerulusMesh, gx, gy, gz, 0.6f, seconds * 6f, 0.2f, 1f, 0.1f)
        for (k in 0 until 5) {
            val a = k * 1.26f + seconds * 0.1f
            drawSphereAt(gx + f.sx * cos(a) * 0.65f + f.ux * sin(a) * 0.65f, gy + f.sy * cos(a) * 0.65f + f.uy * sin(a) * 0.65f, gz + f.sz * cos(a) * 0.65f + f.uz * sin(a) * 0.65f,
                0.18f, 0.13f, 0.18f, COL_PODOCYTE, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob, 1f)
        }
        GLES20.glDepthMask(false)
        drawSphereAt(gx, gy, gz, 0.85f, 0.85f, 0.85f, COL_CAPSULE, COL_LAMP, 0.2f, 0f, 0f, 1f, 0f, sphere, 0f, 0.2f)
        GLES20.glDepthMask(true)
        for (k in 0 until 10) {   // filtrate dripping out of the capsule and down the tubule ahead
            val t = ((seconds * 0.35f + k * 0.1f) % 1f)
            val s = gs * (1f - t) - rr * 0.3f * t; val u = gu * (1f - t) - rr * 0.5f * t
            blobAt(f, 0.4f * sin(k * 1.9f) + t * 2.5f, s, u, 0.09f, 0.09f, 0.09f, COL_FILTRATE, COL_LAMP, 0.9f, 0f, 0f, 1f, 0f, blob, 0f, 0.6f)
        }
    }

    /** Tour II stop 6: sarcomeres — thick myosin and thin actin filaments in bands along the fibre, sliding together on every twitch. */
    private fun drawMuscle(n: TourNode, i: Int, seconds: Float) {
        val b = i.toFloat()
        val contract = 0.5f + 0.5f * sin(seconds * 1.6f)
        val pitch = 0.9f - 0.28f * contract
        val bands = if (quality == 0) 4 else 2
        for (k in 0 until bands) {
            val z0 = (k - bands / 2f) * pitch
            val p = b + z0 * 0.03f
            val f = frameAt(p); val rr = tunnelRadius(p)
            for (m in 0 until 6) {
                val a = 2f * PI.toFloat() * m / 6f
                val cs = cos(a) * rr * 0.8f; val sn = sin(a) * rr * 0.8f
                // Z-disc bead, a thin actin filament spanning the sarcomere, the thick myosin in its middle.
                blobAt(f, z0, cs, sn, 0.09f, 0.09f, 0.09f, COL_ZDISC, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob, 0f, 0.4f)
                strutAt(f, z0, cs, sn, z0 + pitch, cs, sn, 0.022f, COL_ACTIN, COL_LAMP)
                strutAt(f, z0 + pitch * 0.25f, cs * 0.94f, sn * 0.94f, z0 + pitch * 0.75f, cs * 0.94f, sn * 0.94f, 0.06f, COL_MYOSIN, COL_MYOSIN_HEAD)
            }
        }
    }

    /** Tour II stop 7: bone marrow — a lattice of trabecular bone around the space, a megakaryocyte shedding platelets, a stem cell dividing. */
    private fun drawMarrow(n: TourNode, i: Int, seconds: Float) {
        val b = i.toFloat()
        val f = frameAt(b); val rr = tunnelRadius(b)
        drawLinesAt(boneMesh, f.cx, f.cy, f.cz, 1f, 0f, 0f, 1f, 0f)
        val fm = frameAt(b - 0.15f)
        val ms = rr * 0.55f; val mu = -rr * 0.35f
        blobAt(fm, 0f, ms, mu, 1.3f, 1.0f, 1.4f, COL_MEGAKARYO, COL_MEGAKARYO_DARK, 1f, seconds * 4f, 0f, 1f, 0f, sphere, 1f)
        for (k in 0 until 3) blobAt(fm, (k - 1) * 0.5f, ms + 0.3f * cos(k * 2.1f), mu + 0.45f, 0.42f, 0.42f, 0.42f, COL_MEGAKARYO_DARK, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob, 1f)
        for (k in 0 until (if (quality == 0) 8 else 4)) {   // proplatelet beads streaming off into the flow
            val t = ((seconds * 0.25f + k * 0.125f) % 1f)
            val a = k * 0.8f
            blobAt(fm, t * 3.5f, ms - t * ms * 0.8f + 0.3f * sin(a), mu - t * mu * 0.9f + 0.3f * cos(a), 0.12f, 0.06f, 0.12f, COL_PLATELET, COL_LAMP, 1f, seconds * 90f + k * 40f, 0f, 1f, 0f, blob)
        }
        val fs = frameAt(b + 0.3f)
        val cyc = ((seconds * 0.06f) % 1f)
        val sep = smooth01((cyc - 0.4f) / 0.4f) * 0.9f
        blobAt(fs, -sep * 0.5f, -rr * 0.55f, rr * 0.3f, 0.55f, 0.55f, 0.55f, COL_STEM, COL_STEM_LIGHT, 1f, 0f, 0f, 1f, 0f, sphere, 1f)
        blobAt(fs, sep * 0.5f, -rr * 0.55f, rr * 0.3f, 0.55f, 0.55f, 0.55f, COL_STEM, COL_STEM_LIGHT, 1f, 0f, 0f, 1f, 0f, sphere, 1f)
    }

    /** Tour II stop 8: V(D)J recombination — gene segments as coloured beads on a chromatin thread; RAG picks one V, one D, one J, loops out the rest and stitches them. */
    private fun drawVdj(n: TourNode, i: Int, seconds: Float) {
        val b = i.toFloat()
        val f = frameAt(b); val rr = tunnelRadius(b)
        val side = rr * 0.4f; val up = 0.1f
        val cyc = ((seconds / 30f) % 1f)
        val round = (seconds / 30f).toInt()
        val nV = 12; val nD = 6; val nJ = 4
        val total = nV + nD + nJ + 1
        val span = 8.6f
        val pickV = (round * 7) % nV; val pickD = nV + (round * 5) % nD; val pickJ = nV + nD + (round * 3) % nJ
        val join = smooth01((cyc - 0.45f) / 0.35f)
        val flash = if (cyc > 0.8f) 1f - (cyc - 0.8f) / 0.2f else 0f
        val alongJ = -span / 2f + pickJ / (total - 1f) * span            // the chosen J stays put; V and D come to it
        var prevX = 0f; var prevY = 0f; var prevZ = 0f
        for (k in 0 until total) {
            val t = k / (total - 1f)
            var along = -span / 2f + t * span
            val col = when { k < nV -> COL_SEG_V; k < nV + nD -> COL_SEG_D; k < nV + nD + nJ -> COL_SEG_J; else -> COL_SEG_C }
            val chosen = k == pickV || k == pickD || k == pickJ
            var loopOut = 0f
            if (k >= pickV && k <= pickJ) {
                // The stretch from the chosen V to the chosen J is drawn together: the unchosen
                // segments bulge out as a loop (later cut away) while V, D and J meet in a row.
                val u = (k - pickV).toFloat() / (pickJ - pickV).coerceAtLeast(1)
                val dest = when (k) { pickV -> alongJ - 0.8f; pickD -> alongJ - 0.4f; pickJ -> alongJ; else -> alongJ - 0.4f }
                along += (dest - along) * join
                if (!chosen) loopOut = join * 1.5f * sin(u * PI.toFloat())
            }
            val sz = if (chosen) 0.17f + 0.05f * flash else 0.12f
            val glow = if (chosen) 0.5f + 1.2f * flash else 0f
            val px = fx(f, along, side + loopOut * 0.4f, up + loopOut * 0.8f); val py = fy(f, along, side + loopOut * 0.4f, up + loopOut * 0.8f); val pz = fz(f, along, side + loopOut * 0.4f, up + loopOut * 0.8f)
            drawSphereAt(px, py, pz, sz, sz, sz, col, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob, 0f, glow)
            if (k > 0) drawStrut(prevX, prevY, prevZ, px, py, pz, 0.022f, COL_THREAD, COL_THREAD)
            prevX = px; prevY = py; prevZ = pz
        }
        // RAG1/2: a two-lobed enzyme riding the thread, parked on the joint while it cuts and the cell pastes.
        val ragAlong = -span / 2f + span * (0.15f + 0.65f * smooth01(cyc / 0.45f))
        blobAt(f, ragAlong, side, up + 0.28f, 0.3f, 0.24f, 0.3f, COL_RAG, COL_LAMP, 1f, seconds * 30f, 0f, 1f, 0f, blob, 1f, 0.3f * flash)
        blobAt(f, ragAlong + 0.25f, side + 0.1f, up + 0.24f, 0.24f, 0.2f, 0.24f, COL_RAG_B, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob, 1f)
    }

    /**
     * Tour II stop 9: kinesin walking a microtubule, hauling a vesicle many times its size, with the
     * cell's organelles lining the road. Each step is 8 nm (0.1 here); the walk runs at ~2 steps a
     * second, slowed ~50x so the hand-over-hand gait reads: the rear foot lifts, swings past the
     * planted one and lands 16 nm ahead.
     */
    private fun drawHighway(n: TourNode, i: Int, seconds: Float) {
        val b = i.toFloat()
        val f = frameAt(b); val rr = tunnelRadius(b)
        val ts = rr * 0.42f; val tu = -rr * 0.38f
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, fx(f, 0f, ts, tu), fy(f, 0f, ts, tu), fz(f, 0f, ts, tu))
        applyFrameRotation(f)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 1f)
        lineWidth(2f)
        microtubuleMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
        lineWidth(1f)
        val stepLen = 0.1f
        val walk = (seconds * 2f) % 1f
        val stepNo = (seconds * 2f).toInt()
        val head = (stepNo * stepLen + 4f) % 9f - 4.5f
        val swing = smooth01(walk)
        val lift = sin(swing * PI.toFloat()) * 0.06f
        val footA = head; val footB = head - stepLen + 2f * stepLen * swing
        val bodyAlong = (footA + footB) * 0.5f
        val trackTop = tu + 0.15f
        blobAt(f, footA, ts, trackTop, 0.09f, 0.08f, 0.11f, COL_KINESIN, COL_KINESIN_LIGHT, 1f, 0f, 0f, 1f, 0f, blob, 0f, 0.25f)
        blobAt(f, footB, ts, trackTop + lift, 0.09f, 0.08f, 0.11f, COL_KINESIN, COL_KINESIN_LIGHT, 1f, 0f, 0f, 1f, 0f, blob, 0f, 0.25f)
        val hip = trackTop + 0.26f
        strutAt(f, footA, ts, trackTop, bodyAlong, ts, hip, 0.03f, COL_KINESIN, COL_KINESIN_LIGHT)
        strutAt(f, footB, ts, trackTop + lift, bodyAlong, ts, hip, 0.03f, COL_KINESIN, COL_KINESIN_LIGHT)
        val sway = 0.03f * sin(seconds * 2f * PI.toFloat())
        val cargoAlong = bodyAlong - 0.6f; val cargoUp = hip + 1.05f; val cargoSide = ts + sway
        strutAt(f, bodyAlong, ts, hip, cargoAlong + 0.1f, cargoSide, cargoUp - 0.7f, 0.03f, COL_KINESIN, COL_KINESIN_LIGHT)
        blobAt(f, cargoAlong + 0.1f, cargoSide, cargoUp - 0.7f, 0.08f, 0.08f, 0.08f, COL_KINESIN_LIGHT, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob, 0f, 0.4f)
        for (k in 0 until (if (quality == 0) 6 else 3)) {   // the cargo inside the vesicle
            val a = k * 1.05f + seconds * 0.3f
            blobAt(f, cargoAlong + 0.35f * cos(a), cargoSide + 0.35f * sin(a), cargoUp + 0.3f * sin(a * 1.7f), 0.07f, 0.07f, 0.07f, COL_PROTEIN, COL_LAMP)
        }
        GLES20.glDepthMask(false)
        blobAt(f, cargoAlong, cargoSide, cargoUp, 0.8f, 0.8f, 0.8f, COL_CARGO, COL_LAMP, 0.5f, seconds * 10f, 0f, 1f, 0f, sphere, 0f, 0.25f)
        GLES20.glDepthMask(true)
        // Dynein hauling the other way on the far side of the track: a smaller walker heading for the nucleus.
        val dyn = 4.5f - ((seconds * 0.15f + 0.5f) % 1f) * 9f
        blobAt(f, dyn, ts + 0.22f, trackTop + 0.1f, 0.09f, 0.12f, 0.09f, COL_DYNEIN, COL_KINESIN_LIGHT, 1f, 0f, 0f, 1f, 0f, blob, 1f)
        blobAt(f, dyn + 0.25f, ts + 0.3f, trackTop + 0.55f, 0.3f, 0.3f, 0.3f, COL_CARGO, COL_LAMP, 0.6f, 0f, 0f, 1f, 0f, sphere, 0f, 0.15f)
        // The road's landmarks: a mitochondrion, a Golgi stack, rough ER sheets studded with ribosomes.
        blobAt(f, 2.6f, -rr * 0.68f, -rr * 0.45f, 0.4f, 0.4f, 1.0f, COL_CRISTAE, COL_LAMP, 1f, yawOf(f), 0f, 1f, 0f, sphere, 1f)
        // (kept close to the wall: the chase camera swings ±1.9 to the side and lifts ~1 above the rail)
        for (k in 0 until 5) blobAt(f, 3.0f + k * 0.16f, -rr * 0.74f, rr * 0.12f, 0.6f - 0.05f * k, 0.5f, 0.05f, COL_GOLGI, COL_LAMP, 0.95f, yawOf(f), 0f, 1f, 0f, sphere)
        for (k in 0 until 3) blobAt(f, 0.4f + k * 0.9f, rr * 0.78f, rr * 0.1f, 0.1f, 0.45f, 0.6f, COL_ER, COL_LAMP, 0.95f, 0f, 0f, 1f, 0f, sphere)
        if (quality == 0) for (k in 0 until 8) blobAt(f, 0.2f + k * 0.34f, rr * 0.78f - 0.14f, rr * 0.1f + 0.35f * sin(k * 1.3f), 0.07f, 0.07f, 0.07f, COL_RIBO_LARGE, COL_RIBO_LIGHT, 1f, 0f, 0f, 1f, 0f, blob, 1f)
    }

    /** Tour II stop 10: the protein factory — a ribosome on the rough ER threading a chain into the lumen, vesicles budding to the Golgi and out through the membrane. */
    private fun drawFactory(n: TourNode, i: Int, seconds: Float) {
        val b = i.toFloat()
        val f = frameAt(b); val rr = tunnelRadius(b)
        val es = -rr * 0.7f
        for (k in 0 until 4) blobAt(f, -2.4f + k * 1.3f, es, 0.2f * sin(k * 2f), 0.12f, 0.75f, 0.7f, COL_ER, COL_LAMP, 0.95f, 0f, 0f, 1f, 0f, sphere)
        for (k in 0 until (if (quality == 0) 10 else 4)) blobAt(f, -2.6f + k * 0.55f, es + 0.18f, 0.5f * sin(k * 1.3f), 0.09f, 0.09f, 0.09f, COL_RIBO_LARGE, COL_RIBO_LIGHT, 1f, 0f, 0f, 1f, 0f, blob, 1f)
        val bx = fx(f, -0.5f, es + 0.35f, 0.3f); val by = fy(f, -0.5f, es + 0.35f, 0.3f); val bz = fz(f, -0.5f, es + 0.35f, 0.3f)
        drawSphereAt(bx, by, bz, 0.55f, 0.42f, 0.5f, COL_RIBO_LARGE, COL_RIBO_LIGHT, 1f, 20f, 0f, 1f, 0.4f, sphere, 1f)
        drawSphereAt(bx + f.ux * 0.55f, by + f.uy * 0.55f, bz + f.uz * 0.55f, 0.38f, 0.28f, 0.4f, COL_RIBO_SMALL, COL_RIBO_LIGHT, 1f, -15f, 0f, 1f, 0.2f, sphere, 1f)
        val beads = 5 + (((seconds * 0.3f) % 1f) * 9f).toInt()
        for (k in 0 until beads) {   // the chain threads through the ER membrane and folds inside
            val a = k * 0.9f + seconds * 0.5f
            blobAt(f, -0.5f + 0.18f * sin(a), es - 0.1f - k * 0.05f, 0.3f + 0.16f * cos(a), 0.06f, 0.06f, 0.06f, if (k % 2 == 0) COL_AMINO_A else COL_AMINO_B, COL_LAMP)
        }
        val gs = rr * 0.55f
        for (k in 0 until 5) blobAt(f, 0.6f + k * 0.16f, gs, -0.2f, 0.85f - 0.09f * k, 0.6f, 0.05f, COL_GOLGI, COL_LAMP, 0.95f, yawOf(f), 0f, 1f, 0f, sphere)
        for (k in 0 until 3) {   // vesicles: ER -> Golgi -> the outer membrane (the wall ahead), then the payload spills out
            val t = ((seconds * 0.05f + k / 3f) % 1f)
            val alongV: Float; val sideV: Float; val upV: Float; val size: Float; var glowV = 0f
            when {
                t < 0.35f -> { val u = t / 0.35f; alongV = -0.2f + u * 0.9f; sideV = es + 0.3f + (gs - es - 0.3f) * u; upV = 0.2f; size = 0.12f + 0.1f * smooth01(u * 3f) }
                t < 0.7f -> { val u = (t - 0.35f) / 0.35f; alongV = 0.7f + u * 1.2f; sideV = gs; upV = -0.2f + u * (rr * 0.85f + 0.2f); size = 0.22f }
                else -> { val u = (t - 0.7f) / 0.3f; alongV = 1.9f + u * 0.3f; sideV = gs; upV = rr * 0.85f; size = 0.22f * (1f - u); glowV = 1.5f * u }
            }
            blobAt(f, alongV, sideV, upV, size, size, size, COL_VESICLE, COL_LAMP, 0.7f, 0f, 0f, 1f, 0f, blob, 0f, glowV)
            if (t > 0.7f) for (m in 0 until 5) {
                val u = (t - 0.7f) / 0.3f; val a = m * 1.26f
                blobAt(f, alongV + 0.5f * u * cos(a), sideV + 0.5f * u * sin(a), upV + 0.2f * u, 0.05f, 0.05f, 0.05f, COL_AMINO_A, COL_LAMP, 1f - u, 0f, 0f, 1f, 0f, blob, 0f, 0.8f)
            }
        }
    }

    /** Tour II stop 11: one ATP synthase, big as a building — the c-ring turning in the membrane below, the stalk, the F1 head, protons pouring through, ATP spat out. */
    private fun drawMotor(n: TourNode, i: Int, seconds: Float) {
        val b = i.toFloat()
        val f = frameAt(b); val rr = tunnelRadius(b)
        val ms = -rr * 0.55f; val cu = -rr * 0.65f
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, fx(f, 0f, 0f, cu), fy(f, 0f, 0f, cu), fz(f, 0f, 0f, cu))
        applyFrameRotation(f)
        Matrix.rotateM(model, 0, 90f, 1f, 0f, 0f)        // the sheet is built in x/y with z its normal: lay it flat
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        GLES20.glDepthMask(false)
        colorShader.use(mvp, 6f, points = true)
        floorLipidMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
        colorShader.use(mvp, 1f)
        floorTailMesh.draw(colorShader.positionHandle, colorShader.colorHandle)
        GLES20.glDepthMask(true)
        val ang = seconds * 4.2f
        for (k in 0 until 10) {   // the c-ring: ten subunits turning in the membrane
            val a = ang + 2f * PI.toFloat() * k / 10f
            blobAt(f, 0.42f * cos(a), ms + 0.42f * sin(a), cu, 0.12f, 0.36f, 0.12f, COL_ATP_STALK, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob, 0f, if (k == 0) 0.6f else 0f)
        }
        strutAt(f, 0f, ms, cu + 0.3f, 0.08f * cos(ang), ms + 0.08f * sin(ang), cu + 1.25f, 0.07f, COL_ATP_STALK, COL_LAMP, 0.3f)     // central stalk, turning
        strutAt(f, 0.55f, ms, cu + 0.1f, 0.55f, ms, cu + 1.5f, 0.05f, COL_STATOR, COL_LAMP)                                          // stator arm
        strutAt(f, 0.55f, ms, cu + 1.5f, 0.15f, ms, cu + 1.6f, 0.05f, COL_STATOR, COL_LAMP)
        val hu = cu + 1.45f
        for (k in 0 until 3) {   // F1 head: three αβ pairs, the site under load glowing
            val a = 2f * PI.toFloat() * k / 3f + 0.3f
            val hot = 0.5f + 0.5f * cos(ang - a)
            blobAt(f, 0.34f * cos(a), ms + 0.34f * sin(a), hu, 0.26f, 0.32f, 0.26f, COL_ATP_HEAD, COL_LAMP, 1f, 0f, 0f, 1f, 0f, sphere, 1f, 0.5f * hot)
            blobAt(f, 0.34f * cos(a + 1.05f), ms + 0.34f * sin(a + 1.05f), hu + 0.05f, 0.24f, 0.3f, 0.24f, COL_ATP_HEAD_B, COL_LAMP, 1f, 0f, 0f, 1f, 0f, sphere, 1f)
        }
        for (k in 0 until (if (quality == 0) 12 else 6)) {   // protons pouring down through the ring
            val t = ((seconds * 0.9f + k * 0.083f) % 1f)
            val a = k * 0.52f
            blobAt(f, (1.6f - t * 1.2f) * cos(a), ms + (1.6f - t * 1.2f) * sin(a), cu + 0.9f - t * 1.4f, 0.04f, 0.04f, 0.04f, COL_PROTON, COL_PROTON, 1f, 0f, 0f, 1f, 0f, blob, 0f, 1.5f)
        }
        val atpT = (ang / 2.094f) % 1f
        val atpA = floor(ang / 2.094f) * 2.094f + 0.3f
        blobAt(f, (0.5f + atpT * 1.6f) * cos(atpA), ms + (0.5f + atpT * 1.6f) * sin(atpA), hu + atpT * 0.8f, 0.09f, 0.09f, 0.09f, COL_ATP, COL_LAMP, 1f - atpT * 0.6f, seconds * 200f, 0f, 1f, 0f, blob, 0f, 1.2f)
    }

    /** Tour II stop 12: a cell dividing — chromosomes line up, split, ride the spindle to the poles, and the cell pinches in two (a 36 s cycle). */
    private fun drawDivision(n: TourNode, i: Int, seconds: Float) {
        val b = i.toFloat()
        val f = frameAt(b); val rr = tunnelRadius(b)
        val cs = rr * 0.6f; val cu = rr * 0.1f
        val cyc = ((seconds / 36f) % 1f)
        val poleD = 1.6f
        val line = smooth01(cyc / 0.3f)
        val split = smooth01((cyc - 0.5f) / 0.25f)
        val pinch = smooth01((cyc - 0.72f) / 0.28f)
        var v = 0
        val arr = dynLines.data
        for (k in 0 until 6) {   // six chromosomes, each two sister chromatids until anaphase
            val a = k * 1.047f + 0.5f
            val scS = 0.9f * cos(a * 2.3f); val scU = 0.9f * sin(a * 1.7f); val scA = 0.8f * sin(a * 3.1f)
            val plS = 0.85f * cos(a); val plU = 0.85f * sin(a)
            val s = cs + scS + (plS - scS) * line; val u = cu + scU + (plU - scU) * line; val a0 = scA * (1f - line)
            for (half in 0 until 2) {
                val sgn = if (half == 0) 1f else -1f
                val along = a0 + sgn * (0.06f + split * poleD * (1f - 0.15f * pinch))
                val tilt = 0.18f * (1f - split)
                strutAt(f, along, s - tilt * sgn, u - 0.2f, along - sgn * 0.12f * split, s + tilt * sgn, u + 0.2f, 0.055f, COL_CHROMOSOME, COL_CHROMOSOME_LIGHT, 0.2f)
                if (line > 0.6f && pinch < 0.5f) {
                    arr[v++] = fx(f, along, s, u); arr[v++] = fy(f, along, s, u); arr[v++] = fz(f, along, s, u)
                    arr[v++] = 0.8f; arr[v++] = 0.95f; arr[v++] = 0.9f; arr[v++] = 0.45f * (line - 0.6f) / 0.4f
                    arr[v++] = fx(f, sgn * poleD, cs, cu); arr[v++] = fy(f, sgn * poleD, cs, cu); arr[v++] = fz(f, sgn * poleD, cs, cu)
                    arr[v++] = 0.8f; arr[v++] = 0.95f; arr[v++] = 0.9f; arr[v++] = 0.1f
                }
            }
        }
        if (v > 0) {
            Matrix.setIdentityM(model, 0)
            Matrix.multiplyMM(mv, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
            colorShader.use(mvp, 1f)
            dynLines.draw(colorShader.positionHandle, colorShader.colorHandle, GLES20.GL_LINES, v / 7)
        }
        for (sgn in SIGNS) blobAt(f, sgn * poleD * (0.6f + 0.4f * line), cs, cu, 0.14f, 0.14f, 0.14f, COL_CENTROSOME, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob, 0f, 0.5f)
        GLES20.glDepthMask(false)
        if (pinch < 0.55f) {
            val q = pinch / 0.55f
            blobAt(f, 0f, cs, cu, 2.0f * (1f - 0.25f * q), 2.0f * (1f - 0.25f * q), 2.0f * (1f + 0.3f * q), COL_CELL, COL_CELL_EDGE, 0.2f, yawOf(f), 0f, 1f, 0f, sphere, 0f, 0.15f)
            if (q > 0.02f) for (k in 0 until (if (quality == 0) 12 else 6)) {   // the contractile ring tightening at the equator
                val a = 2f * PI.toFloat() * k / 12f
                val r = 2.0f * (1f - 0.25f * q) * (1f - 0.75f * q)
                blobAt(f, 0f, cs + r * cos(a), cu + r * sin(a), 0.07f, 0.07f, 0.07f, COL_ACTIN, COL_LAMP, q, 0f, 0f, 1f, 0f, blob, 0f, 0.6f)
            }
        } else {
            val q = (pinch - 0.55f) / 0.45f
            for (sgn in SIGNS) blobAt(f, sgn * (1.0f + 0.5f * q), cs, cu, 1.5f, 1.5f, 1.5f, COL_CELL, COL_CELL_EDGE, 0.2f, 0f, 0f, 1f, 0f, sphere, 0f, 0.15f)
        }
        GLES20.glDepthMask(true)
    }

    // ------------------------------------------------------- draw: the ship
    /** Local hull coordinates (x right, y up, -z forward) to world, through the hull's yaw and pitch. */
    private fun shipToWorld(lx0: Float, ly0: Float, lz0: Float, out: FloatArray) {
        val lx = lx0 * shipScale; val ly = ly0 * shipScale; val lz = lz0 * shipScale
        val cp = cos(craftPitch * DEG); val sp = sin(craftPitch * DEG)
        val cy = cos(craftYaw * DEG); val sy = sin(craftYaw * DEG)
        val y1 = ly * cp - lz * sp; val z1 = ly * sp + lz * cp        // Rx(pitch)
        val x2 = lx * cy + z1 * sy; val z2 = -lx * sy + z1 * cy       // Ry(yaw)
        out[0] = shipX + x2; out[1] = shipY + y1; out[2] = shipZ + z2
    }

    /** A rod between two world points (an elongated sphere aligned with the segment). */
    private fun drawStrut(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, radius: Float, base: FloatArray, accent: FloatArray, glow: Float = 0f) {
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

    private fun drawMote(seconds: Float) {
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
    private fun drawArms(seconds: Float) {
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
    private fun drawDriveCore(seconds: Float) {
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

    private fun drawCockpit() {
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
    private fun drawStreaks(seconds: Float) {
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

    private fun lineWidth(w: Float) = GLES20.glLineWidth(min(w, maxLineWidth))

    private fun drawFlash() {
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
    private fun drawSphereAt(
        x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float,
        base: FloatArray, accent: FloatArray, alpha: Float = 1f,
        rotDeg: Float = 0f, ax: Float = 0f, ay: Float = 1f, az: Float = 0f,
        mesh: SphereMesh = sphere, pattern: Float = 0f, glow: Float = 0f
    ) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        if (rotDeg != 0f) Matrix.rotateM(model, 0, rotDeg, ax, ay, az)
        Matrix.scaleM(model, 0, sx, sy, sz)
        drawLitModel(mesh, base, accent, alpha * landmarkFade, pattern, glow)
    }

    /** Draw [mesh] with the current model matrix through the lit shader. */
    private fun drawLitModel(mesh: SphereMesh, base: FloatArray, accent: FloatArray, alpha: Float, pattern: Float, glow: Float) {
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        // Normal matrix = transpose(inverse(model)). transposeM must not run in place (it would
        // symmetrise the matrix instead of transposing it), so invert into a scratch first.
        if (!Matrix.invertM(invM, 0, model, 0)) Matrix.setIdentityM(invM, 0)
        Matrix.transposeM(normalM, 0, invM, 0)
        litShader.use(mvp, model, normalM, base, accent, alpha, pattern, glow, lampX(), lampY(), lampZ(), camNowX, camNowY, camNowZ)
        mesh.draw(litShader.positionHandle, litShader.normalHandle)
    }

    private fun drawLinesAt(mesh: LineMesh, x: Float, y: Float, z: Float, scale: Float, rotDeg: Float, ax: Float, ay: Float, az: Float) {
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
    private fun applyFrameRotation(f: Frame) {
        val rot = FloatArray(16)
        rot[0] = f.sx; rot[1] = f.sy; rot[2] = f.sz; rot[3] = 0f
        rot[4] = f.ux; rot[5] = f.uy; rot[6] = f.uz; rot[7] = 0f
        rot[8] = -f.dx; rot[9] = -f.dy; rot[10] = -f.dz; rot[11] = 0f
        rot[12] = 0f; rot[13] = 0f; rot[14] = 0f; rot[15] = 1f
        val tmp = FloatArray(16)
        Matrix.multiplyMM(tmp, 0, model, 0, rot, 0)
        System.arraycopy(tmp, 0, model, 0, 16)
    }

    private fun yawOf(f: Frame): Float = atan2(-f.dx, -f.dz) * 180f / PI.toFloat()

    private class Frame(
        val cx: Float, val cy: Float, val cz: Float,
        val dx: Float, val dy: Float, val dz: Float,
        val sx: Float, val sy: Float, val sz: Float,
        val ux: Float, val uy: Float, val uz: Float
    )

    /** Catmull-Rom position on the rail at node-units p. */
    private fun curvePoint(p: Float, out: FloatArray) {
        val n = nodes.size
        val pc = p.coerceIn(0f, (n - 1).toFloat())
        val i = min(pc.toInt(), n - 2)
        val t = pc - i
        val p0 = nodes[max(i - 1, 0)]; val p1 = nodes[i]; val p2 = nodes[i + 1]; val p3 = nodes[min(i + 2, n - 1)]
        fun cr(a: Float, b: Float, c: Float, d: Float): Float =
            0.5f * ((2f * b) + (-a + c) * t + (2f * a - 5f * b + 4f * c - d) * t * t + (-a + 3f * b - 3f * c + d) * t * t * t)
        out[0] = cr(p0.x, p1.x, p2.x, p3.x); out[1] = cr(p0.y, p1.y, p2.y, p3.y); out[2] = cr(p0.z, p1.z, p2.z, p3.z)
    }

    private val tmpA = FloatArray(3)
    private val tmpB = FloatArray(3)
    private fun frameAt(p: Float): Frame {
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

    private fun nodeLerp(p: Float, f: (TourNode) -> Float): Float {
        val pc = p.coerceIn(0f, nodes.lastIndex.toFloat())
        val i = min(pc.toInt(), nodes.lastIndex - 1)
        val t = pc - i
        val s = t * t * (3f - 2f * t)
        return f(nodes[i]) + (f(nodes[i + 1]) - f(nodes[i])) * s
    }

    private fun tunnelRadius(p: Float): Float = nodeLerp(p) { it.radius }

    // ------------------------------------------------------ mesh builders
    private fun buildTunnel(): FloatArray {
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

    private fun buildRouteLines(): FloatArray = buildList {
        val color = floatArrayOf(1f, 0.6f, 0.55f, 0.22f)
        nodes.zipWithNext().forEach { (a, b) -> addLine(a.x, a.y, a.z, b.x, b.y, b.z, color) }
    }.toFloatArray()

    private fun buildRouteNodes(): FloatArray = buildList {
        nodes.forEach { addPoint(it.x, it.y, it.z, 1f, 0.77f, 0.42f, 0.45f) }
    }.toFloatArray()

    // The M.S.V. Mote: an original industrial hovercraft. Faceted, wider-than-tall hull, a raised
    // cockpit pod forward, a dorsal spine with antenna masts, side pontoons that carry the hover
    // pads, an aft engine block, and two arm-probe mounts at the bow. Faces -Z. Length 1.5.
    private fun buildMote(): FloatArray = buildList {
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

    private fun buildCockpitLines(): FloatArray = buildList {
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

    private fun buildHairs(): FloatArray = buildList {
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

    private fun buildCapillaries(): FloatArray = buildList {
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

    private fun buildTrabeculae(): FloatArray = buildList {
        val rnd = java.util.Random(9)
        val c = floatArrayOf(0.85f, 0.35f, 0.4f, 0.6f)
        for (i in 0 until 30) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val r = 5.2f + rnd.nextFloat() * 0.6f
            val z0 = -6f + rnd.nextFloat() * 12f
            addLine(cos(a) * r, sin(a) * r, z0, cos(a + 0.5f) * (r - 1.2f), sin(a + 0.5f) * (r - 1.2f), z0 + 1.5f, c)
        }
    }.toFloatArray()

    private fun buildDendrites(): FloatArray {
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
    private fun buildLipids(): Pair<FloatArray, FloatArray> {
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

    private fun buildChromatin(): FloatArray = buildList {
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
    private fun buildHelix(): FloatArray {
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

    private fun buildMrna(): FloatArray = buildList {
        val c = floatArrayOf(1f, 0.6f, 0.5f, 0.9f)
        for (s in 0 until 80) {
            val t0 = s / 80f; val t1 = (s + 1) / 80f
            fun px(t: Float) = (t - 0.5f) * 9f
            fun py(t: Float) = 1.15f + 0.15f * sin(t * 18f)      // the seam between the two subunits
            fun pz(t: Float) = 0.2f * cos(t * 18f)
            addLine(px(t0), py(t0), pz(t0), px(t1), py(t1), pz(t1), c)
        }
    }.toFloatArray()

    private fun buildElectronCloud(): FloatArray = buildList {
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

    private fun buildShells(): FloatArray = buildList {
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

    private fun buildCellCosmos(): FloatArray = buildList {
        val rnd = java.util.Random(2013)
        for (i in 0 until 1400) {
            val r = 12f + rnd.nextFloat() * 48f
            val u = rnd.nextFloat() * 2f - 1f; val a = rnd.nextFloat() * 2f * PI.toFloat()
            val s = sqrt(1f - u * u)
            val warm = rnd.nextFloat()
            addPoint(cos(a) * s * r, u * r, sin(a) * s * r, 1f, 0.7f + 0.3f * warm, 0.55f + 0.45f * warm, 0.35f + rnd.nextFloat() * 0.6f)
        }
    }.toFloatArray()

    private fun buildAntibodies(): FloatArray = buildList {
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
    private fun buildMicrotubule(): FloatArray = buildList {
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
    private fun buildCanaliculi(): FloatArray = buildList {
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
    private fun buildGlomerulus(): FloatArray = buildList {
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
    private fun buildBoneLattice(): FloatArray = buildList {
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
    private fun buildFloorLipids(): Pair<FloatArray, FloatArray> {
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

    companion object {
        const val VIEW_COUNT = 4
        const val VIEW_BRIDGE = 0        // the helm, looking ahead through the porthole
        const val VIEW_CHASE = 1         // external camera trailing the Mote
        const val VIEW_ENGINEERING = 2   // beside the scale drive core
        const val VIEW_OBSERVATION = 3   // the observation deck, calm and wide
        val VIEW_NAMES = arrayOf("BRIDGE - HELM", "EXTERNAL - CHASE", "SCALE DRIVE CORE", "OBSERVATION DECK")
        private const val EYE_OFFSET = 0.035f
        private const val VIEW_TRANSITION_SEC = 1.0f
        private const val SHRINK_SEC = 3.2f
        private const val HEART_PERIOD = 0.92f
        private const val LYSIS_PERIOD = 24f     // the phage stop's burst cycle (seconds)
        private val SIGNS = floatArrayOf(-1f, 1f)
        private const val TIME_WRAP = (20.0 * PI).toFloat()

        // Ladder rungs (log10 of the Mote's length in metres) and their labels, one per decade
        // the drive can step through (the atom drop passes 1.2 nm and 120 pm on its way to 12 pm).
        private val LADDER_EXP = doubleArrayOf(1.08, -1.92, -2.92, -3.92, -4.92, -5.92, -6.92, -7.92, -8.92, -9.92, -10.92)
        private val LADDER_LABELS = arrayOf("12m", "12mm", "1.2mm", "120µ", "12µ", "1.2µ", "120n", "12n", "1.2n", "120p", "12p")

        private const val DEG = (PI / 180.0).toFloat()
        private val COL_LAMP = floatArrayOf(1f, 0.77f, 0.42f, 1f)
        private val COL_HULL = floatArrayOf(0.52f, 0.55f, 0.62f, 1f)
        private val COL_HULL_DARK = floatArrayOf(0.30f, 0.32f, 0.38f, 1f)
        private val COL_PAD = floatArrayOf(0.55f, 0.75f, 1f, 1f)
        private val COL_DRIVE = floatArrayOf(0.62f, 0.5f, 1f, 1f)
        private val COL_DRIVE_DIM = floatArrayOf(0.34f, 0.26f, 0.6f, 1f)
        private val COL_STATOR = floatArrayOf(0.55f, 0.58f, 0.7f, 1f)
        private val COL_BAY = floatArrayOf(1f, 0.85f, 0.7f, 1f)
        private val COL_SKIN = floatArrayOf(0.96f, 0.62f, 0.56f, 1f)
        private val COL_SKIN_DARK = floatArrayOf(0.72f, 0.38f, 0.36f, 1f)
        private val COL_CARTILAGE = floatArrayOf(0.93f, 0.9f, 0.85f, 1f)
        private val COL_ALVEOLUS = floatArrayOf(0.95f, 0.8f, 0.8f, 1f)
        private val COL_RED_CELL = floatArrayOf(0.85f, 0.12f, 0.14f, 1f)
        private val COL_RED_CELL_DARK = floatArrayOf(0.45f, 0.05f, 0.07f, 1f)
        private val COL_PLATELET = floatArrayOf(0.95f, 0.8f, 0.5f, 1f)
        private val COL_DUST = floatArrayOf(0.7f, 0.68f, 0.62f, 1f)
        private val COL_POLLEN = floatArrayOf(0.95f, 0.85f, 0.3f, 1f)
        private val COL_PROTEIN = floatArrayOf(0.35f, 0.8f, 0.75f, 1f)
        private val COL_VESICLE = floatArrayOf(0.75f, 0.9f, 0.95f, 1f)
        private val COL_TRANSMITTER = floatArrayOf(0.8f, 0.7f, 1f, 1f)
        private val COL_WHITE_CELL = floatArrayOf(0.9f, 0.92f, 0.82f, 1f)
        private val COL_WHITE_CELL_DARK = floatArrayOf(0.6f, 0.62f, 0.5f, 1f)
        private val COL_VALVE = floatArrayOf(0.85f, 0.45f, 0.5f, 1f)
        private val COL_VALVE_EDGE = floatArrayOf(1f, 0.75f, 0.7f, 1f)
        private val COL_NEUTROPHIL = floatArrayOf(0.88f, 0.9f, 0.78f, 1f)
        private val COL_NEUTROPHIL_DARK = floatArrayOf(0.55f, 0.5f, 0.7f, 1f)
        private val COL_MACROPHAGE = floatArrayOf(0.78f, 0.82f, 0.7f, 1f)
        private val COL_SOMA = floatArrayOf(0.5f, 0.38f, 0.85f, 1f)
        private val COL_SOMA_LIGHT = floatArrayOf(0.8f, 0.72f, 1f, 1f)
        private val COL_MYELIN = floatArrayOf(0.9f, 0.88f, 0.98f, 1f)
        private val COL_CHANNEL = floatArrayOf(0.95f, 0.6f, 0.35f, 1f)
        private val COL_CRISTAE = floatArrayOf(0.95f, 0.55f, 0.25f, 1f)
        private val COL_ATP_STALK = floatArrayOf(0.9f, 0.9f, 0.7f, 1f)
        private val COL_ATP_HEAD = floatArrayOf(0.55f, 0.9f, 0.85f, 1f)
        private val COL_PORE = floatArrayOf(0.6f, 0.55f, 0.95f, 1f)
        private val COL_NUCLEUS_LIGHT = floatArrayOf(0.85f, 0.8f, 1f, 1f)
        private val COL_POLYMERASE = floatArrayOf(0.95f, 0.75f, 0.35f, 1f)
        private val COL_RIBO_LARGE = floatArrayOf(0.3f, 0.65f, 0.7f, 1f)
        private val COL_RIBO_SMALL = floatArrayOf(0.35f, 0.75f, 0.65f, 1f)
        private val COL_RIBO_LIGHT = floatArrayOf(0.7f, 0.95f, 0.9f, 1f)
        private val COL_TRNA = floatArrayOf(0.95f, 0.55f, 0.6f, 1f)
        private val COL_AMINO_A = floatArrayOf(1f, 0.77f, 0.42f, 1f)
        private val COL_AMINO_B = floatArrayOf(0.5f, 0.9f, 0.8f, 1f)
        private val COL_NUCLEON = floatArrayOf(1f, 0.95f, 0.85f, 1f)
        private val COL_WORLD = floatArrayOf(0.95f, 0.7f, 0.6f, 1f)
        // Tour II palette.
        private val COL_LIP = floatArrayOf(0.85f, 0.42f, 0.45f, 1f)
        private val COL_TOOTH = floatArrayOf(0.97f, 0.95f, 0.88f, 1f)
        private val COL_TONGUE = floatArrayOf(0.9f, 0.45f, 0.5f, 1f)
        private val COL_VILLUS = floatArrayOf(0.95f, 0.58f, 0.6f, 1f)
        private val COL_VILLUS_TIP = floatArrayOf(1f, 0.78f, 0.72f, 1f)
        private val COL_BACTERIUM = floatArrayOf(0.55f, 0.8f, 0.45f, 1f)
        private val COL_BACTERIUM_DARK = floatArrayOf(0.3f, 0.5f, 0.25f, 1f)
        private val COL_CHYLE = floatArrayOf(0.95f, 0.9f, 0.6f, 1f)
        private val COL_PHAGE = floatArrayOf(0.75f, 0.7f, 1f, 1f)
        private val COL_PHAGE_LIGHT = floatArrayOf(0.9f, 0.88f, 1f, 1f)
        private val COL_PHAGE_TAIL = floatArrayOf(0.8f, 0.8f, 0.9f, 1f)
        private val COL_HEPATOCYTE = floatArrayOf(0.72f, 0.3f, 0.25f, 1f)
        private val COL_HEPATOCYTE_DARK = floatArrayOf(0.45f, 0.15f, 0.12f, 1f)
        private val COL_CAPSULE = floatArrayOf(0.9f, 0.75f, 0.7f, 1f)
        private val COL_FILTRATE = floatArrayOf(0.85f, 0.95f, 1f, 1f)
        private val COL_PODOCYTE = floatArrayOf(0.8f, 0.55f, 0.75f, 1f)
        private val COL_ZDISC = floatArrayOf(0.95f, 0.9f, 0.6f, 1f)
        private val COL_ACTIN = floatArrayOf(0.9f, 0.75f, 0.7f, 1f)
        private val COL_MYOSIN = floatArrayOf(0.55f, 0.2f, 0.25f, 1f)
        private val COL_MYOSIN_HEAD = floatArrayOf(0.9f, 0.4f, 0.45f, 1f)
        private val COL_MEGAKARYO = floatArrayOf(0.85f, 0.7f, 0.85f, 1f)
        private val COL_MEGAKARYO_DARK = floatArrayOf(0.5f, 0.35f, 0.6f, 1f)
        private val COL_STEM = floatArrayOf(0.8f, 0.85f, 0.95f, 1f)
        private val COL_STEM_LIGHT = floatArrayOf(0.95f, 0.97f, 1f, 1f)
        private val COL_SEG_V = floatArrayOf(0.95f, 0.45f, 0.6f, 1f)
        private val COL_SEG_D = floatArrayOf(0.5f, 0.9f, 0.55f, 1f)
        private val COL_SEG_J = floatArrayOf(0.5f, 0.7f, 1f, 1f)
        private val COL_SEG_C = floatArrayOf(0.75f, 0.55f, 0.95f, 1f)
        private val COL_THREAD = floatArrayOf(0.7f, 0.65f, 0.9f, 1f)
        private val COL_RAG = floatArrayOf(0.95f, 0.75f, 0.35f, 1f)
        private val COL_RAG_B = floatArrayOf(0.95f, 0.6f, 0.3f, 1f)
        private val COL_KINESIN = floatArrayOf(0.95f, 0.55f, 0.25f, 1f)
        private val COL_KINESIN_LIGHT = floatArrayOf(1f, 0.8f, 0.5f, 1f)
        private val COL_DYNEIN = floatArrayOf(0.6f, 0.75f, 0.95f, 1f)
        private val COL_CARGO = floatArrayOf(0.7f, 0.9f, 1f, 1f)
        private val COL_GOLGI = floatArrayOf(0.85f, 0.7f, 0.35f, 1f)
        private val COL_ER = floatArrayOf(0.45f, 0.7f, 0.75f, 1f)
        private val COL_ATP_HEAD_B = floatArrayOf(0.35f, 0.7f, 0.7f, 1f)
        private val COL_PROTON = floatArrayOf(1f, 0.95f, 0.6f, 1f)
        private val COL_ATP = floatArrayOf(1f, 0.85f, 0.3f, 1f)
        private val COL_CELL = floatArrayOf(0.6f, 0.85f, 0.9f, 1f)
        private val COL_CELL_EDGE = floatArrayOf(0.8f, 0.95f, 1f, 1f)
        private val COL_CHROMOSOME = floatArrayOf(0.55f, 0.35f, 0.85f, 1f)
        private val COL_CHROMOSOME_LIGHT = floatArrayOf(0.85f, 0.75f, 1f, 1f)
        private val COL_CENTROSOME = floatArrayOf(1f, 0.85f, 0.5f, 1f)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun MutableList<Float>.addPoint(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
        add(x); add(y); add(z); add(r); add(g); add(b); add(a)
    }

    private fun MutableList<Float>.addLine(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, c: FloatArray) {
        addPoint(ax, ay, az, c[0], c[1], c[2], c[3])
        addPoint(bx, by, bz, c[0], c[1], c[2], c[3])
    }
}

// =============================================================== meshes

/** Uploads static vertex data once; every draw then binds the VBO instead of copying a client array. */
private fun makeVbo(data: FloatArray): Int {
    val ids = IntArray(1)
    GLES20.glGenBuffers(1, ids, 0)
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
    GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, data.toFloatBuffer(), GLES20.GL_STATIC_DRAW)
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    return ids[0]
}

private class SphereMesh(stacks: Int, slices: Int) {
    private val vbo: Int
    private val vertexCount: Int

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

    fun draw(positionHandle: Int, normalHandle: Int) {
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

    private fun addSphereVertex(data: MutableList<Float>, phi: Float, theta: Float) {
        val x = sin(phi) * cos(theta)
        val y = cos(phi)
        val z = sin(phi) * sin(theta)
        data.add(x); data.add(y); data.add(z)
        data.add(x); data.add(y); data.add(z)
    }
}

/** Static triangle mesh in a VBO: position(3) normal(3) color(4). */
private class TubeMesh(data: FloatArray) {
    private val vbo: Int
    private val count = data.size / 10

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
private open class ColorVboMesh(data: FloatArray, private val mode: Int) {
    private val vbo = makeVbo(data)
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

private class PointMesh(data: FloatArray) : ColorVboMesh(data, GLES20.GL_POINTS)
private class TriMesh(data: FloatArray) : ColorVboMesh(data, GLES20.GL_TRIANGLES)
private class LineMesh(data: FloatArray) : ColorVboMesh(data, GLES20.GL_LINES)

/** Small per-frame mesh (position + color) for things rebuilt every frame. */
private class DynMesh(maxVerts: Int) {
    val data = FloatArray(maxVerts * 7)
    private val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

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
private class DriftField(private val count: Int) {
    private val px = FloatArray(count); private val py = FloatArray(count); private val pz = FloatArray(count)
    private val vx = FloatArray(count); private val vy = FloatArray(count); private val vz = FloatArray(count)
    private val data = FloatArray(count * 7)
    private val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val rnd = java.util.Random(7)
    private var seeded = false

    private fun respawn(i: Int, cx: Float, cy: Float, cz: Float, spread: Float) {
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
private class AirField(private val count: Int) {
    private val along = FloatArray(count)      // position along the rail, relative to the ship
    private val lu = FloatArray(count); private val lv = FloatArray(count)   // lateral offsets (side, up)
    private val data = FloatArray(count * 2 * 7)
    private val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val rnd = java.util.Random(19)
    private var seeded = false

    private fun respawn(i: Int, spread: Float, upstream: Boolean) {
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
private class BodyField(val count: Int) {
    val px = FloatArray(count); val py = FloatArray(count); val pz = FloatArray(count)
    private val vx = FloatArray(count); private val vy = FloatArray(count); private val vz = FloatArray(count)
    val kind = IntArray(count); val size = FloatArray(count); val spin = FloatArray(count)
    private val rnd = java.util.Random(11)
    private var seeded = false
    private var lastAmb: Amb? = null

    companion object {
        const val RED_CELL = 0; const val PLATELET = 1; const val DUST = 2; const val POLLEN = 3
        const val PROTEIN = 4; const val VESICLE = 5; const val TRANSMITTER = 6; const val WHITE_CELL = 7
        const val BACTERIUM = 8; const val CHYLE = 9; const val NONE = 10
    }

    fun reset() { seeded = false; lastAmb = null }

    private fun kindFor(amb: Amb): Int {
        val r = rnd.nextFloat()
        return when (amb) {
            Amb.AIR -> if (r < 0.7f) DUST else if (r < 0.9f) POLLEN else NONE
            Amb.BLOOD -> if (r < 0.78f) RED_CELL else if (r < 0.94f) PLATELET else WHITE_CELL
            Amb.NEURAL -> if (r < 0.7f) TRANSMITTER else VESICLE
            Amb.CYTO -> if (r < 0.65f) PROTEIN else VESICLE
            Amb.ATOM -> NONE
            Amb.GUT -> if (r < 0.55f) BACTERIUM else if (r < 0.8f) CHYLE else DUST
            Amb.MUSCLE -> if (r < 0.3f) PROTEIN else NONE
            Amb.MOTOR -> NONE
            Amb.LOOKBACK -> if (r < 0.4f) RED_CELL else NONE
        }
    }

    private fun respawn(i: Int, cx: Float, cy: Float, cz: Float, spread: Float, amb: Amb) {
        kind[i] = kindFor(amb)
        size[i] = when (kind[i]) {
            RED_CELL -> 0.30f + rnd.nextFloat() * 0.12f
            PLATELET -> 0.10f + rnd.nextFloat() * 0.05f
            DUST -> 0.05f + rnd.nextFloat() * 0.06f
            POLLEN -> 0.09f + rnd.nextFloat() * 0.06f
            PROTEIN -> 0.12f + rnd.nextFloat() * 0.14f
            VESICLE -> 0.22f + rnd.nextFloat() * 0.18f
            TRANSMITTER -> 0.05f + rnd.nextFloat() * 0.04f
            WHITE_CELL -> 0.50f + rnd.nextFloat() * 0.15f
            BACTERIUM -> 0.55f + rnd.nextFloat() * 0.25f
            CHYLE -> 0.14f + rnd.nextFloat() * 0.12f
            else -> 0f
        }
        spin[i] = rnd.nextFloat()
        px[i] = cx + (rnd.nextFloat() - 0.5f) * 1.7f * spread
        py[i] = cy + (rnd.nextFloat() - 0.5f) * 1.7f * spread
        pz[i] = cz - 4f - rnd.nextFloat() * 22f
        vx[i] = (rnd.nextFloat() - 0.5f) * 0.3f
        vy[i] = (rnd.nextFloat() - 0.5f) * 0.3f
        vz[i] = 0.5f + rnd.nextFloat() * 0.9f
    }

    fun blowOut(cx: Float, cy: Float, cz: Float, sign: Float) {
        for (i in 0 until count) {
            val dx = px[i] - cx; val dy = py[i] - cy; val dz = pz[i] - cz
            val d = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.2f)
            vx[i] += dx / d * 4f * sign; vy[i] += dy / d * 4f * sign; vz[i] += 3f * sign
        }
    }

    fun update(cx: Float, cy: Float, cz: Float, spread: Float, amb: Amb, flow: Float, dt: Float) {
        if (!seeded) { for (i in 0 until count) respawn(i, cx, cy, cz, spread, amb); seeded = true }
        if (amb != lastAmb) {
            lastAmb = amb
            // Inside the atom, the look back and the motor nothing drifts: clear the stragglers.
            if (amb == Amb.ATOM || amb == Amb.LOOKBACK || amb == Amb.MOTOR) for (i in 0 until count) kind[i] = NONE
        }
        for (i in 0 until count) {
            px[i] += vx[i] * dt; py[i] += vy[i] * dt; pz[i] += vz[i] * flow * dt
            vx[i] *= 0.985f; vy[i] *= 0.985f
            if (pz[i] > cz + 3f || abs(px[i] - cx) > 12f || abs(py[i] - cy) > 12f) respawn(i, cx, cy, cz, spread, amb)
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
private const val FRAG_PRECISION = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
"""

/** Point-lit sphere shader: the Mote's lamp lights everything; rim glow in the accent colour; optional mottling. */
private class LitShader {
    private val program = compileProgram(
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
    private val mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
    private val modelHandle = GLES20.glGetUniformLocation(program, "uModel")
    private val normalMatrixHandle = GLES20.glGetUniformLocation(program, "uNormal")
    private val baseHandle = GLES20.glGetUniformLocation(program, "uBase")
    private val accentHandle = GLES20.glGetUniformLocation(program, "uAccent")
    private val alphaHandle = GLES20.glGetUniformLocation(program, "uAlpha")
    private val patternHandle = GLES20.glGetUniformLocation(program, "uPattern")
    private val glowHandle = GLES20.glGetUniformLocation(program, "uGlow")
    private val lampHandle = GLES20.glGetUniformLocation(program, "uLamp")
    private val eyeHandle = GLES20.glGetUniformLocation(program, "uEye")

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
private class WallShader {
    private val program = compileProgram(
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
    private val mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
    private val modelHandle = GLES20.glGetUniformLocation(program, "uModel")
    private val lampHandle = GLES20.glGetUniformLocation(program, "uLamp")
    private val timeHandle = GLES20.glGetUniformLocation(program, "uTime")
    private val pulseHandle = GLES20.glGetUniformLocation(program, "uPulse")
    private val fogHandle = GLES20.glGetUniformLocation(program, "uFog")
    private val alphaHandle = GLES20.glGetUniformLocation(program, "uAlpha")
    private val detailHandle = GLES20.glGetUniformLocation(program, "uDetail")

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

private class ColorShader {
    private val program = compileProgram(
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
    private val mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
    private val pointSizeHandle = GLES20.glGetUniformLocation(program, "uPointSize")
    private val pointHandle = GLES20.glGetUniformLocation(program, "uPoint")
    private val fadeHandle = GLES20.glGetUniformLocation(program, "uFade")

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

private fun FloatArray.toFloatBuffer(): FloatBuffer {
    val buffer = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    buffer.put(this)
    buffer.position(0)
    return buffer
}

private fun List<Float>.toFloatBuffer(): FloatBuffer = toFloatArray().toFloatBuffer()

private fun compileProgram(vertexSource: String, fragmentSource: String): Int {
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

private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source.trimIndent())
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    require(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
    return shader
}
