package com.rayneo.innercosmos

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*

// Tour I — The Descent: nose to carbon atom.
// Landmark scenes, drawn by StereoBodyRenderer.drawLandmarks via the stop's Scene. Every structure
// is sized from its real dimension at the stop's stated craft length (1.5 units = the Mote), so the
// schematic is to scale by construction:
//   nose, airway, heart: 12 mm Mote -> 1 unit = 8 mm      alveolus: 120 um -> 1 unit = 80 um
//   bloodstream, sentinel, neuron: 12 um -> 1 unit = 8 um  synapse, membrane, mitochondrion: 120 nm -> 80 nm
//   nucleus, ribosome: 12 nm -> 1 unit = 8 nm              atom: 12 pm -> 1 unit = 8 pm
// Frame-local positions are (along the rail, side, up); meshes built in frame-local space store
// (x = side, y = up, z = -along) so the frame basis stays right-handed.

private typealias T1F = StereoBodyRenderer.Frame
private const val PI_F = PI.toFloat()
private const val TAU = (2.0 * PI).toFloat()

/** What drifts past at each stop of this tour, by stop index; stops not listed use DriftSpec.forAmb. */
internal val DESCENT_DRIFT: Map<Int, DriftSpec> = mapOf(
    // Nose, airway, alveolus: inhaled dust is far below a pixel at these scales.
    0 to DriftSpec.NONE,
    1 to DriftSpec.NONE,
    2 to DriftSpec.NONE,
    // Pulmonary venule: fresh from the alveolus, so oxygenated; ~600 red cells : 40 platelets : 1 white cell.
    // (its red cells are drawn by the landmark, tumbling in 3D so their biconcave faces show)
    3 to DriftSpec.of(BodyField.PLATELET to 1f, density = 0.07f, flow = 1.4f, oxy = true),
    // Heart at the 12 mm rung: single cells are 0.001 units, invisible.
    4 to DriftSpec.NONE,
    // Systemic post-capillary venule: deoxygenated; sparse enough to see the sentinel.
    // (red cells drawn by the landmark, as at stop 3)
    5 to DriftSpec.of(BodyField.PLATELET to 1f, density = 0.06f, flow = 0.9f, oxy = false),
    // Brain: the vessel's own red cells are drawn in the landmark; nothing floats in neural tissue.
    6 to DriftSpec.NONE,
    // Extracellular fluid, cytosol, mitochondrial matrix, nucleoplasm: soluble proteins only.
    7 to DriftSpec.of(BodyField.PROTEIN to 1f, density = 0.25f, flow = 0.1f),
    8 to DriftSpec.of(BodyField.PROTEIN to 1f, density = 0.3f, flow = 0.1f),
    // At 12 nm a soluble protein is a full unit across: left out so the chromatin and the ribosome read.
    9 to DriftSpec.NONE,
    10 to DriftSpec.NONE,
    11 to DriftSpec.NONE
)

// ------------------------------------------------------------------ Tour I palette
internal val T1_SKIN = floatArrayOf(0.84f, 0.62f, 0.50f, 1f)
internal val T1_SKIN_RIM = floatArrayOf(1f, 0.84f, 0.72f, 1f)
internal val T1_VESTIBULE = floatArrayOf(0.90f, 0.56f, 0.52f, 1f)
internal val T1_HOLE = floatArrayOf(0.015f, 0.01f, 0.012f, 1f)
internal val T1_DERMIS = floatArrayOf(0.93f, 0.60f, 0.60f, 1f)
internal val T1_FAT = floatArrayOf(0.98f, 0.88f, 0.52f, 1f)
internal val T1_MUCOSA = floatArrayOf(0.95f, 0.60f, 0.62f, 1f)
internal val T1_MUCOSA_RIM = floatArrayOf(1f, 0.82f, 0.80f, 1f)
internal val T1_CARTILAGE = floatArrayOf(0.97f, 0.84f, 0.82f, 1f)
internal val T1_CART_RIM = floatArrayOf(0.85f, 0.92f, 1f, 1f)
internal val T1_TRACHEALIS = floatArrayOf(0.74f, 0.30f, 0.33f, 1f)
internal val T1_VOCAL = floatArrayOf(0.97f, 0.95f, 0.91f, 1f)
internal val T1_EPIGLOTTIS = floatArrayOf(0.96f, 0.80f, 0.76f, 1f)
internal val T1_SEPTUM = floatArrayOf(0.97f, 0.82f, 0.82f, 1f)
internal val T1_FILM = floatArrayOf(0.92f, 0.96f, 1f, 1f)
internal val T1_TYPE2 = floatArrayOf(0.98f, 0.74f, 0.60f, 1f)
internal val T1_MACRO = floatArrayOf(0.88f, 0.80f, 0.70f, 1f)
internal val T1_ENDO_NUC = floatArrayOf(0.64f, 0.27f, 0.33f, 1f)
internal val T1_MYO = floatArrayOf(0.68f, 0.16f, 0.20f, 1f)
internal val T1_TRAB = floatArrayOf(0.56f, 0.11f, 0.15f, 1f)
internal val T1_MYO_RIM = floatArrayOf(0.98f, 0.50f, 0.50f, 1f)
internal val T1_VALVE = floatArrayOf(0.96f, 0.89f, 0.80f, 1f)
internal val T1_VALVE_P = floatArrayOf(0.95f, 0.82f, 0.74f, 1f)
internal val T1_VALVE_RIM = floatArrayOf(1f, 0.97f, 0.92f, 1f)
internal val T1_INTIMA = floatArrayOf(0.93f, 0.70f, 0.66f, 1f)
internal val T1_NEUT = floatArrayOf(0.93f, 0.91f, 0.86f, 1f)
internal val T1_NEUT_NUC = floatArrayOf(0.56f, 0.36f, 0.82f, 1f)
internal val T1_MONO = floatArrayOf(0.74f, 0.80f, 0.93f, 1f)
internal val T1_MONO_NUC = floatArrayOf(0.48f, 0.42f, 0.80f, 1f)
internal val T1_BACT = floatArrayOf(0.56f, 0.84f, 0.46f, 1f)
internal val T1_VESSEL = floatArrayOf(0.88f, 0.62f, 0.66f, 1f)
internal val T1_ENDFOOT = floatArrayOf(0.70f, 0.80f, 1f, 1f)
internal val T1_SOMA = floatArrayOf(0.64f, 0.50f, 0.95f, 1f)
internal val T1_SOMA_NUC = floatArrayOf(0.46f, 0.36f, 0.84f, 1f)
internal val T1_MYELIN = floatArrayOf(0.97f, 0.97f, 0.93f, 1f)
internal val T1_AP = floatArrayOf(1f, 0.92f, 0.55f, 1f)
internal val T1_BOUTON = floatArrayOf(0.62f, 0.52f, 0.95f, 1f)
internal val T1_SPINE = floatArrayOf(0.70f, 0.50f, 0.85f, 1f)
internal val T1_PSD = floatArrayOf(0.62f, 0.44f, 0.82f, 1f)
internal val T1_SYN_VES = floatArrayOf(0.86f, 0.92f, 1f, 1f)
internal val T1_BILAYER = floatArrayOf(0.35f, 0.80f, 0.76f, 1f)
internal val T1_PUMP = floatArrayOf(0.98f, 0.62f, 0.34f, 1f)
internal val T1_RECEPTOR = floatArrayOf(0.92f, 0.56f, 0.78f, 1f)
internal val T1_DYNAMIN = floatArrayOf(1f, 0.62f, 0.30f, 1f)
internal val T1_MITO_OUT = floatArrayOf(0.96f, 0.64f, 0.36f, 1f)
internal val T1_MITO_IN = floatArrayOf(0.98f, 0.56f, 0.30f, 1f)
internal val T1_CRISTA = floatArrayOf(0.97f, 0.58f, 0.30f, 1f)
internal val T1_F1_A = floatArrayOf(0.55f, 0.92f, 0.86f, 1f)
internal val T1_F1_B = floatArrayOf(0.34f, 0.72f, 0.84f, 1f)
internal val T1_FO = floatArrayOf(0.95f, 0.92f, 0.62f, 1f)
internal val T1_STATOR = floatArrayOf(0.80f, 0.84f, 0.95f, 1f)
internal val T1_ETC = floatArrayOf(0.55f, 0.72f, 0.98f, 1f)
internal val T1_ENVELOPE = floatArrayOf(0.70f, 0.64f, 0.98f, 1f)
internal val T1_NPC = floatArrayOf(0.66f, 0.62f, 1f, 1f)
internal val T1_HISTONE = floatArrayOf(0.74f, 0.58f, 0.98f, 1f)
internal val T1_POL = floatArrayOf(0.98f, 0.76f, 0.38f, 1f)
internal val T1_RRNA_L = floatArrayOf(0.42f, 0.62f, 0.98f, 1f)
internal val T1_RRNA_S = floatArrayOf(0.98f, 0.82f, 0.42f, 1f)
internal val T1_RPROT = floatArrayOf(0.74f, 0.76f, 0.82f, 1f)
internal val T1_TRNA = floatArrayOf(0.96f, 0.42f, 0.52f, 1f)
internal val T1_EFTU = floatArrayOf(0.50f, 0.90f, 0.55f, 1f)
internal val T1_FOLD = floatArrayOf(0.95f, 0.75f, 0.45f, 1f)
internal val T1_WHITE = floatArrayOf(1f, 1f, 1f, 1f)

// ============================================================ helpers (file-private)

/**
 * Meshes built on the GL thread on first use. Keyed on the renderer's own sphere mesh: a new GL
 * context (onSurfaceCreated) builds a new one, and every cached VBO is rebuilt with it.
 */
private val t1Cache = HashMap<String, Any>()
private var t1CacheOwner: Any? = null

@Suppress("UNCHECKED_CAST")
private fun <T : Any> StereoBodyRenderer.t1Mesh(key: String, build: () -> T): T {
    if (t1CacheOwner !== sphere) { t1Cache.clear(); t1CacheOwner = sphere }
    return t1Cache.getOrPut(key) { build() } as T
}

private fun t1Smooth(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun t1Hash(k: Int): Float {
    var h = k * 374761393 + 668265263
    h = (h xor (h ushr 13)) * 1274126177
    return ((h xor (h ushr 16)) and 0xffffff) / 16777215f
}

/** Model = T(frame point a, s, u) * R(side, up, -dir) * scale. */
private fun StereoBodyRenderer.t1Model(f: T1F, a: Float, s: Float, u: Float, sc: Float = 1f) {
    model[0] = f.sx * sc; model[1] = f.sy * sc; model[2] = f.sz * sc; model[3] = 0f
    model[4] = f.ux * sc; model[5] = f.uy * sc; model[6] = f.uz * sc; model[7] = 0f
    model[8] = -f.dx * sc; model[9] = -f.dy * sc; model[10] = -f.dz * sc; model[11] = 0f
    model[12] = fx(f, a, s, u); model[13] = fy(f, a, s, u); model[14] = fz(f, a, s, u); model[15] = 1f
}

/** Draw a lit mesh built in frame-local space at frame point (a, s, u), scaled per local axis. */
private fun StereoBodyRenderer.t1Lit(
    mesh: LitMesh, f: T1F, a: Float, s: Float, u: Float, base: FloatArray, accent: FloatArray,
    alpha: Float = 1f, glow: Float = 0f, sx: Float = 1f, sy: Float = sx, sz: Float = sx, pattern: Float = 0f
) {
    t1Model(f, a, s, u)
    if (sx != 1f || sy != 1f || sz != 1f) Matrix.scaleM(model, 0, sx, sy, sz)
    drawLitModel(mesh, base, accent, alpha * landmarkFade, pattern, glow)
}

/** Draw a lit mesh built in world space, translated by (ox, oy, oz). */
private fun StereoBodyRenderer.t1LitWorld(mesh: LitMesh, base: FloatArray, accent: FloatArray, alpha: Float = 1f, glow: Float = 0f,
                                          ox: Float = 0f, oy: Float = 0f, oz: Float = 0f) {
    Matrix.setIdentityM(model, 0)
    model[12] = ox; model[13] = oy; model[14] = oz
    drawLitModel(mesh, base, accent, alpha * landmarkFade, 0f, glow)
}

/**
 * Draw [mesh] at frame point (a, s, u) with its local z along (za, zs, zu) and local y along
 * (ya, ys, yu), both given in (along, side, up) components; scaled (sx, sy, sz).
 */
private fun StereoBodyRenderer.t1Shape(
    mesh: LitMesh, f: T1F, a: Float, s: Float, u: Float,
    za: Float, zs: Float, zu: Float, ya: Float, ys: Float, yu: Float,
    sx: Float, sy: Float, sz: Float, base: FloatArray, accent: FloatArray, alpha: Float = 1f, glow: Float = 0f
) {
    drawBasis(
        fx(f, a, s, u), fy(f, a, s, u), fz(f, a, s, u),
        f.dx * za + f.sx * zs + f.ux * zu, f.dy * za + f.sy * zs + f.uy * zu, f.dz * za + f.sz * zs + f.uz * zu,
        f.dx * ya + f.sx * ys + f.ux * yu, f.dy * ya + f.sy * ys + f.uy * yu, f.dz * ya + f.sz * ys + f.uz * yu,
        sx, sy, sz, mesh, base, accent, alpha, 0f, glow
    )
}

/** An ellipsoid rod between two frame points (a0,s0,u0) -> (a1,s1,u1). */
private fun StereoBodyRenderer.t1Rod(
    f: T1F, a0: Float, s0: Float, u0: Float, a1: Float, s1: Float, u1: Float, r: Float,
    base: FloatArray, accent: FloatArray, alpha: Float = 1f, glow: Float = 0f, mesh: LitMesh = blob
) {
    val da = a1 - a0; val ds = s1 - s0; val du = u1 - u0
    val len = sqrt(da * da + ds * ds + du * du).coerceAtLeast(1e-4f)
    val ya: Float; val yu: Float
    if (abs(du) > 0.9f * len) { ya = 1f; yu = 0f } else { ya = 0f; yu = 1f }
    t1Shape(mesh, f, (a0 + a1) * 0.5f, (s0 + s1) * 0.5f, (u0 + u1) * 0.5f, da, ds, du, ya, 0f, yu, r, r, len * 0.5f, base, accent, alpha, glow)
}

/** Draw a coloured line/point mesh: frame-local when [f] is given, else world space (translated by o). */
private fun StereoBodyRenderer.t1Color(
    mesh: ColorVboMesh, f: T1F?, a: Float, s: Float, u: Float, size: Float, points: Boolean,
    alpha: Float = 1f, depthWrite: Boolean = true, ox: Float = 0f, oy: Float = 0f, oz: Float = 0f
) {
    if (alpha <= 0.004f) return
    if (f == null) { Matrix.setIdentityM(model, 0); model[12] = ox; model[13] = oy; model[14] = oz } else t1Model(f, a, s, u)
    Matrix.multiplyMM(mv, 0, view, 0, model, 0)
    Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    val keep = colorShader.globalFade
    colorShader.globalFade = keep * alpha
    if (!depthWrite) GLES20.glDepthMask(false)
    if (points) colorShader.use(mvp, size, points = true) else { colorShader.use(mvp, 1f); lineWidth(size) }
    GLES20.glDisable(GLES20.GL_CULL_FACE)          // ribbon meshes are drawn from either side
    mesh.draw(colorShader.positionHandle, colorShader.colorHandle)
    GLES20.glEnable(GLES20.GL_CULL_FACE)
    if (!points) lineWidth(1f)
    if (!depthWrite) GLES20.glDepthMask(true)
    colorShader.globalFade = keep
}

/** Per-frame points/lines in world space. */
private val t1Dyn = DynMesh(640)
private fun StereoBodyRenderer.t1DynDraw(verts: Int, mode: Int, size: Float, alpha: Float = 1f, depthWrite: Boolean = true) {
    if (verts <= 0 || alpha <= 0.004f) return
    Matrix.setIdentityM(model, 0)
    Matrix.multiplyMM(mv, 0, view, 0, model, 0)
    Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    val keep = colorShader.globalFade
    colorShader.globalFade = keep * alpha
    if (!depthWrite) GLES20.glDepthMask(false)
    if (mode == GLES20.GL_POINTS) colorShader.use(mvp, size, points = true) else { colorShader.use(mvp, 1f); lineWidth(size) }
    t1Dyn.draw(colorShader.positionHandle, colorShader.colorHandle, mode, verts)
    if (mode != GLES20.GL_POINTS) lineWidth(1f)
    if (!depthWrite) GLES20.glDepthMask(true)
    colorShader.globalFade = keep
}

private fun t1Put(arr: FloatArray, k: Int, x: Float, y: Float, z: Float, c: FloatArray, al: Float): Int {
    var w = k * 7
    arr[w++] = x; arr[w++] = y; arr[w++] = z; arr[w++] = c[0]; arr[w++] = c[1]; arr[w++] = c[2]; arr[w] = al
    return k + 1
}

/**
 * The passage wall's actual radius at rail progress [p] and angle [ang] (radians from +side
 * toward +up): the tunnel mesh's bumps (rings every 0.08, 14 facets), interpolated the way the
 * mesh is. Things that should sit on the wall use it instead of the nominal radius.
 */
private fun StereoBodyRenderer.t1WallR(p: Float, ang: Float): Float {
    val segs = 14
    val step = 0.08f
    val n0 = floor(p / step).toInt().coerceAtLeast(0)
    val p0 = n0 * step; val p1 = p0 + step
    val w = ((p - p0) / step).coerceIn(0f, 1f)
    var aa = ang % TAU; if (aa < 0f) aa += TAU
    val kf = aa / TAU * segs
    val k0 = floor(kf).toInt().coerceIn(0, segs - 1); val t = kf - k0
    val half = PI_F / segs
    val chord = cos(half) / cos(half - 2f * half * t)
    fun rr(k: Int, pp: Float) = tunnelRadius(pp) * (1f + 0.07f * sin(k * 3.1f + pp * 9.3f) + 0.04f * sin(k * 7.7f + pp * 21f))
    fun ring(pp: Float) = (rr(k0, pp) * (1f - t) + rr((k0 + 1) % segs, pp) * t) * chord
    return ring(p0) * (1f - w) + ring(p1) * w
}

/** World point at rail progress p, angle ang, distance r from the rail centre. */
private fun StereoBodyRenderer.t1RailPoint(p: Float, ang: Float, r: Float, out: FloatArray) {
    val f = frameAt(p)
    val c = cos(ang); val s = sin(ang)
    out[0] = f.cx + (f.sx * c + f.ux * s) * r
    out[1] = f.cy + (f.sy * c + f.uy * s) * r
    out[2] = f.cz + (f.sz * c + f.uz * s) * r
}

/** The craft's lateral offset from the rail centre in frame f's (side, up) axes. */
private val t1OffBuf = FloatArray(2)
private fun StereoBodyRenderer.t1ShipOff(f: T1F): FloatArray {
    val vx = shipX - railCx; val vy = shipY - railCy; val vz = shipZ - railCz
    t1OffBuf[0] = vx * f.sx + vy * f.sy + vz * f.sz
    t1OffBuf[1] = vx * f.ux + vy * f.uy + vz * f.uz
    return t1OffBuf
}

/** The craft's lateral offset as a world vector (the along-rail surge removed). */
private val t1OffW = FloatArray(3)
private fun StereoBodyRenderer.t1ShipOffWorld(): FloatArray {
    val vx = shipX - railCx; val vy = shipY - railCy; val vz = shipZ - railCz
    val d = vx * dirX + vy * dirY + vz * dirZ
    t1OffW[0] = vx - d * dirX; t1OffW[1] = vy - d * dirY; t1OffW[2] = vz - d * dirZ
    return t1OffW
}

/** Along-rail distance of a world point in front of (+) or behind (-) frame f's centre. */
private fun t1Along(f: T1F, x: Float, y: Float, z: Float) = (x - f.cx) * f.dx + (y - f.cy) * f.dy + (z - f.cz) * f.dz

private fun StereoBodyRenderer.t1Breath(seconds: Float): Float =
    if (audioEngine.isRunning()) audioEngine.breathPhase01 else (seconds * 0.21f) % 1f

/** A flat unit disc in local x/y (normal +z). */
private fun StereoBodyRenderer.t1Disc(): ParamMesh = t1Mesh("disc") { ParamMesh(2, 28) { u, v, out ->
    val a = v * TAU; out[0] = cos(a) * u; out[1] = sin(a) * u; out[2] = 0f } }

/** A full torus of major radius 1 and tube [minor], in local x/y. */
private fun StereoBodyRenderer.t1Ring(minor: Float): ParamMesh = t1Mesh("ring$minor") { ParamMesh.torusArc(minor, 1f, 32) }

// ------------------------------------------------------------------ mesh classes

/** A lit surface re-tessellated every frame (valve leaflets, a dimpling membrane). */
private class T1DynSurface(private val stacks: Int, private val slices: Int) : LitMesh() {
    private val stripLen = (slices + 1) * 2
    private val data = FloatArray(stacks * stripLen * 6)
    private val buf: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val p = FloatArray(3); private val pa = FloatArray(3); private val pb = FloatArray(3)

    fun update(fn: (Float, Float, FloatArray) -> Unit) {
        var w = 0
        val e = 1e-3f
        for (i in 0 until stacks) {
            for (j in 0..slices) {
                for (h in 0..1) {
                    val u = (i + 1 - h).toFloat() / stacks; val v = j.toFloat() / slices
                    fn(u, v, p)
                    fn((u + e).coerceAtMost(1f), v, pa); fn((u - e).coerceAtLeast(0f), v, pb)
                    val ux = pa[0] - pb[0]; val uy = pa[1] - pb[1]; val uz = pa[2] - pb[2]
                    fn(u, (v + e).coerceAtMost(1f), pa); fn(u, (v - e).coerceAtLeast(0f), pb)
                    val vx = pa[0] - pb[0]; val vy = pa[1] - pb[1]; val vz = pa[2] - pb[2]
                    var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
                    val l = sqrt(nx * nx + ny * ny + nz * nz)
                    if (l > 1e-12f) { nx /= l; ny /= l; nz /= l } else { nx = 0f; ny = 0f; nz = 1f }
                    data[w++] = p[0]; data[w++] = p[1]; data[w++] = p[2]; data[w++] = nx; data[w++] = ny; data[w++] = nz
                }
            }
        }
        buf.position(0); buf.put(data); buf.position(0)
    }

    override fun draw(positionHandle: Int, normalHandle: Int) {
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        buf.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 24, buf)
        GLES20.glEnableVertexAttribArray(positionHandle)
        buf.position(3)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 24, buf)
        GLES20.glEnableVertexAttribArray(normalHandle)
        for (k in 0 until stacks) GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, k * stripLen, stripLen)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
        buf.position(0)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }
}

/** Many small lit shapes merged into one VBO: one draw call for a whole family of structures. */
private class T1Batch(data: FloatArray, private val cullBack: Boolean = false) : LitMesh() {
    private val vbo = makeVbo(data)
    private val count = data.size / 6
    override fun draw(positionHandle: Int, normalHandle: Int) {
        if (count == 0) return
        if (cullBack) GLES20.glEnable(GLES20.GL_CULL_FACE) else GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }
}

private class T1Builder {
    private val d = ArrayList<Float>(16384)
    private val lp = FloatArray(3); private val la = FloatArray(3); private val lb = FloatArray(3)

    private fun put(pos: FloatArray, nor: FloatArray, k: Int) {
        d.add(pos[k]); d.add(pos[k + 1]); d.add(pos[k + 2]); d.add(nor[k]); d.add(nor[k + 1]); d.add(nor[k + 2])
    }

    private fun emit(stacks: Int, slices: Int, pos: FloatArray, nor: FloatArray) {
        val row = slices + 1
        for (i in 0 until stacks) for (j in 0 until slices) {
            val a = (i * row + j) * 3; val b = ((i + 1) * row + j) * 3; val c = ((i + 1) * row + j + 1) * 3; val e = (i * row + j + 1) * 3
            put(pos, nor, a); put(pos, nor, b); put(pos, nor, c); put(pos, nor, a); put(pos, nor, c); put(pos, nor, e)
        }
    }

    /** Any surface fn(u, v) in final coordinates, normals by finite differences. */
    fun surface(stacks: Int, slices: Int, fn: (Float, Float, FloatArray) -> Unit) {
        val n = (stacks + 1) * (slices + 1)
        val pos = FloatArray(n * 3); val nor = FloatArray(n * 3)
        val e = 1e-3f
        for (i in 0..stacks) for (j in 0..slices) {
            val u = i.toFloat() / stacks; val v = j.toFloat() / slices
            fn(u, v, lp)
            fn((u + e).coerceAtMost(1f), v, la); fn((u - e).coerceAtLeast(0f), v, lb)
            val ux = la[0] - lb[0]; val uy = la[1] - lb[1]; val uz = la[2] - lb[2]
            fn(u, (v + e).coerceAtMost(1f), la); fn(u, (v - e).coerceAtLeast(0f), lb)
            val vx = la[0] - lb[0]; val vy = la[1] - lb[1]; val vz = la[2] - lb[2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 1e-12f) { nx /= l; ny /= l; nz /= l } else { nx = 0f; ny = 1f; nz = 0f }
            val k = (i * (slices + 1) + j) * 3
            pos[k] = lp[0]; pos[k + 1] = lp[1]; pos[k + 2] = lp[2]; nor[k] = nx; nor[k + 1] = ny; nor[k + 2] = nz
        }
        emit(stacks, slices, pos, nor)
    }

    /** An ellipsoid centred at (cx,cy,cz) with (scaled) semi-axes ex, ey, ez; exact normals. */
    fun ellipsoid(cx: Float, cy: Float, cz: Float, ex: FloatArray, ey: FloatArray, ez: FloatArray, stacks: Int = 7, slices: Int = 10) {
        val ax = ey[1] * ez[2] - ey[2] * ez[1]; val ay = ey[2] * ez[0] - ey[0] * ez[2]; val az = ey[0] * ez[1] - ey[1] * ez[0]
        val bx = ez[1] * ex[2] - ez[2] * ex[1]; val by = ez[2] * ex[0] - ez[0] * ex[2]; val bz = ez[0] * ex[1] - ez[1] * ex[0]
        val qx = ex[1] * ey[2] - ex[2] * ey[1]; val qy = ex[2] * ey[0] - ex[0] * ey[2]; val qz = ex[0] * ey[1] - ex[1] * ey[0]
        val n = (stacks + 1) * (slices + 1)
        val pos = FloatArray(n * 3); val nor = FloatArray(n * 3)
        for (i in 0..stacks) for (j in 0..slices) {
            val ph = PI_F * i / stacks; val th = TAU * j / slices
            val lx = sin(ph) * cos(th); val ly = cos(ph); val lz = sin(ph) * sin(th)
            val px = ex[0] * lx + ey[0] * ly + ez[0] * lz; val py = ex[1] * lx + ey[1] * ly + ez[1] * lz; val pz = ex[2] * lx + ey[2] * ly + ez[2] * lz
            var nx = ax * lx + bx * ly + qx * lz; var ny = ay * lx + by * ly + qy * lz; var nz = az * lx + bz * ly + qz * lz
            val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9f)
            nx /= l; ny /= l; nz /= l
            if (nx * px + ny * py + nz * pz < 0f) { nx = -nx; ny = -ny; nz = -nz }
            val k = (i * (slices + 1) + j) * 3
            pos[k] = cx + px; pos[k + 1] = cy + py; pos[k + 2] = cz + pz; nor[k] = nx; nor[k + 1] = ny; nor[k + 2] = nz
        }
        emit(stacks, slices, pos, nor)
    }

    /** A round rod (ellipsoid) from p0 to p1 of radius r. */
    fun rod(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, stacks: Int = 6, slices: Int = 8) {
        val zx = (x1 - x0) * 0.5f; val zy = (y1 - y0) * 0.5f; val zz = (z1 - z0) * 0.5f
        val zl = sqrt(zx * zx + zy * zy + zz * zz).coerceAtLeast(1e-5f)
        // a perpendicular
        var px: Float; var py: Float; var pz: Float
        if (abs(zy) < 0.9f * zl) { px = zz; py = 0f; pz = -zx } else { px = 0f; py = zz; pz = -zy }
        val pl = sqrt(px * px + py * py + pz * pz).coerceAtLeast(1e-6f); px /= pl; py /= pl; pz /= pl
        val qx = (zy * pz - zz * py) / zl; val qy = (zz * px - zx * pz) / zl; val qz = (zx * py - zy * px) / zl
        ellipsoid((x0 + x1) * 0.5f, (y0 + y1) * 0.5f, (z0 + z1) * 0.5f,
            floatArrayOf(px * r, py * r, pz * r), floatArrayOf(zx, zy, zz), floatArrayOf(qx * r, qy * r, qz * r), stacks, slices)
    }

    fun build(cullBack: Boolean = false): T1Batch = T1Batch(d.toFloatArray(), cullBack)
}

/** Endothelial lining of a vessel wall: zig-zag cell borders on the wall, and one flat bulging nucleus per cell. */
private fun StereoBodyRenderer.t1BuildEndothelium(p0: Float, p1: Float, around: Int, cellLen: Float, seed: Int): Pair<LineMesh, T1Batch> {
    val rnd = java.util.Random(seed.toLong())
    val lines = ArrayList<Float>()
    val col = floatArrayOf(1f, 0.84f, 0.80f, 0.85f)
    val a = FloatArray(3); val b = FloatArray(3)
    val stepP = cellLen / 16f
    val rows = ((p1 - p0) / stepP).toInt()
    val dth = TAU / around
    val nuc = T1Builder()
    fun wall(p: Float, th: Float, out: FloatArray, k: Float = 0.97f) = t1RailPoint(p, th, t1WallR(p, th) * k, out)
    fun line(p: Float, q: Float, th: Float, th2: Float) {
        wall(p, th, a); wall(q, th2, b)
        lines.add(a[0]); lines.add(a[1]); lines.add(a[2]); lines.add(col[0]); lines.add(col[1]); lines.add(col[2]); lines.add(col[3])
        lines.add(b[0]); lines.add(b[1]); lines.add(b[2]); lines.add(col[0]); lines.add(col[1]); lines.add(col[2]); lines.add(col[3])
    }
    for (j in 0..rows) {
        val pj = p0 + j * stepP
        // circumferential border (a zig-zag ring)
        val segs = around * 8
        for (k in 0 until segs) {
            val t0 = TAU * k / segs; val t1 = TAU * (k + 1) / segs
            val z0 = if (k % 2 == 0) 0.004f else -0.004f
            line(pj + z0, pj - z0, t0, t1)
        }
        if (j == rows) break
        val off = if (j % 2 == 0) 0f else dth * 0.5f
        for (m in 0 until around) {
            val th = off + m * dth + (rnd.nextFloat() - 0.5f) * dth * 0.25f
            // longitudinal border
            val n = 7
            for (k in 0 until n) {
                val q0 = pj + stepP * k / n; val q1 = pj + stepP * (k + 1) / n
                val w0 = if (k % 2 == 0) 0.03f else -0.03f
                line(q0, q1, th + w0, th - w0)
            }
            // nucleus at the cell centre
            val pc = pj + stepP * (0.4f + 0.2f * rnd.nextFloat()); val tc = th + dth * 0.5f
            val f = frameAt(pc)
            val r = t1WallR(pc, tc) * 0.965f
            val c = cos(tc); val s = sin(tc)
            val rx = f.sx * c + f.ux * s; val ry = f.sy * c + f.uy * s; val rz = f.sz * c + f.uz * s
            val tx = -f.sx * s + f.ux * c; val ty = -f.sy * s + f.uy * c; val tz = -f.sz * s + f.uz * c
            nuc.ellipsoid(f.cx + rx * r, f.cy + ry * r, f.cz + rz * r,
                floatArrayOf(tx * 0.32f, ty * 0.32f, tz * 0.32f), floatArrayOf(rx * 0.1f, ry * 0.1f, rz * 0.1f),
                floatArrayOf(f.dx * 0.75f, f.dy * 0.75f, f.dz * 0.75f), 6, 10)
        }
    }
    return LineMesh(lines.toFloatArray()) to nuc.build()
}

private fun StereoBodyRenderer.t1DrawEndothelium(key: String, p0: Float, p1: Float, around: Int, cellLen: Float, seed: Int) {
    val m = t1Mesh(key) { t1BuildEndothelium(p0, p1, around, cellLen, seed) }
    t1LitWorld(m.second, T1_ENDO_NUC, T1_MUCOSA_RIM, 1f, 0.04f)
    t1Color(m.first, null, 0f, 0f, 0f, 2f, false, 0.9f)
}


// ================================================================ stop 0: THE THRESHOLD (nose, 12 mm rung)
// The nasal base seen from below, 1 unit = 8 mm: two teardrop nostrils (1.4 x 0.8 cm) either side
// of a 7 mm columella, alar rims, the tip lobule, the upper lip; pores and vellus hairs on the skin,
// vibrissae sloping out of the vestibule, and a 4 mm punch biopsy lifted out of the lip to show the
// layers: a paper-thin epidermis (melanocytes along its base), dermis, and subcutaneous fat. The
// craft's nostril is centred on its lane (the nose is placed about the craft, so it enters cleanly).
// Face coordinates: X (local side) runs from the lip (-) to the nose tip (+), Y (local up) left-right.

private const val T1_FACE_P = 0.62f
private const val T1_FACE_R = 4.3f
private const val T1_TILT = -26f * DEG
private val T1_TILT_K = tan(26f * DEG)

private fun t1NostrilRim(th: Float, out: FloatArray) {
    val w = 0.5f * (1f - 0.28f * cos(th))           // teardrop: narrower toward the tip
    out[0] = 0.85f * cos(th); out[1] = w * sin(th)
}

private fun t1Q4(x: Float) = x * x * x * x

/** Skin depth along the rail (negative = toward the viewer) at face coordinates (X, Y). */
private fun t1FaceDepth(X: Float, Y: Float): Float {
    var h = 0.02f * (X * X + Y * Y)                   // the face curves away
    val xp = X - 0.4f; val yp = Y - 0.95f
    val wx = (2.1f - 0.35f * xp).coerceAtLeast(0.5f)
    h -= 1.7f * exp(-(t1Q4(xp / 2.3f) + t1Q4(yp / wx)))               // the nasal base projects ~1.5 cm
    h -= 0.5f * exp(-((X - 2.0f) * (X - 2.0f) / 0.5f + yp * yp / 0.6f))  // lobule (tip)
    for (k in 0..1) {                                                  // alar rims round each nostril
        val cy = if (k == 0) 0f else 1.9f
        val e = sqrt((X / 0.85f) * (X / 0.85f) + ((Y - cy) / 0.55f) * ((Y - cy) / 0.55f))
        val q = (e - 1.25f) / 0.22f
        h -= 0.14f * exp(-q * q)
    }
    return h
}

private fun t1OtherNostril(X: Float, Y: Float): Float =
    sqrt((X / 0.85f) * (X / 0.85f) + ((Y - 1.9f) / 0.52f) * ((Y - 1.9f) / 0.52f))

private fun t1FacePoint(u: Float, v: Float, tmp: FloatArray, out: FloatArray) {
    t1NostrilRim(v * TAU, tmp)
    val r0 = sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1])
    val r = r0 + (T1_FACE_R - r0) * u.pow(1.7f)
    val X = tmp[0] / r0 * r; val Y = tmp[1] / r0 * r
    var h = t1FaceDepth(X, Y)
    h += 1.5f * t1Smooth(1.0f, 0.72f, t1OtherNostril(X, Y))           // the other nostril: a pit (dark plug below)
    if (u < 0.07f) { val k = 1f - u / 0.07f; h += 0.4f * k * k }       // our rim rolls into the vestibule
    val rr = sqrt(X * X + Y * Y); if (rr > 3.3f) { val e = (rr - 3.3f) / 1.0f; h += 0.9f * e * e }   // the specimen's edge curves away
    out[0] = X; out[1] = Y; out[2] = -h
}

private fun StereoBodyRenderer.t1FaceMeshes(): Array<ColorVboMesh> {
    val tmp = FloatArray(2)
    val rnd = java.util.Random(31)
    // Vibrissae: rooted in the vestibule wall 1-6 mm in, sloping out toward the opening.
    val hair = ArrayList<Float>()
    val hc = floatArrayOf(0.56f, 0.43f, 0.33f, 1f)
    fun add(list: ArrayList<Float>, x: Float, y: Float, z: Float, c: FloatArray) { list.add(x); list.add(y); list.add(z); list.add(c[0]); list.add(c[1]); list.add(c[2]); list.add(c[3]) }
    for (k in 0 until 64) {
        val th = rnd.nextFloat() * TAU
        t1NostrilRim(th, tmp)
        val u0 = 0.1f + rnd.nextFloat() * 0.55f
        val sh = (1f - 0.15f * u0) * 0.97f
        val rx = tmp[0] * sh; val ry = tmp[1] * sh
        val bx = -T1_TILT_K * 1.7f * u0
        val z0 = -(t1FaceDepth(tmp[0], tmp[1]) + 0.4f + 1.7f * u0)
        val rl = sqrt(rx * rx + ry * ry)
        val ix = -rx / rl; val iy = -ry / rl
        val len = 0.5f + rnd.nextFloat() * 0.55f
        val inward = 0.35f + rnd.nextFloat() * 0.25f
        val tw = (rnd.nextFloat() - 0.5f) * 0.3f
        val mx = rx + (ix * inward + -iy * tw) * len * 0.5f; val my = ry + (iy * inward + ix * tw) * len * 0.5f; val mz = z0 + 0.85f * len * 0.5f
        val ex = mx + (ix * inward * 0.6f + -iy * tw) * len * 0.5f; val ey = my + (iy * inward * 0.6f + ix * tw) * len * 0.5f; val ez = mz + 0.95f * len * 0.5f
        add(hair, rx + bx, ry, z0, hc); add(hair, mx + bx, my, mz, hc); add(hair, mx + bx, my, mz, hc); add(hair, ex + bx, ey, ez, hc)
    }
    // Pores (0.1-0.3 mm: sub-pixel dots, denser on the nose) and vellus hairs (1-2 mm) on the skin.
    val pores = ArrayList<Float>(); val vellus = ArrayList<Float>()
    val pc = floatArrayOf(0.46f, 0.29f, 0.24f, 0.95f); val vc = floatArrayOf(0.93f, 0.83f, 0.70f, 0.9f)
    var placed = 0; var tries = 0
    while (placed < 600 && tries < 20000) {
        tries++
        val X = (rnd.nextFloat() * 2f - 1f) * 3.9f; val Y = (rnd.nextFloat() * 2f - 1f) * 3.9f
        val rr = sqrt(X * X + Y * Y); if (rr > 3.9f) continue
        t1NostrilRim(atan2(Y, X), tmp)
        if (rr < sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1]) * 1.3f) continue
        if (t1OtherNostril(X, Y) < 1.3f) continue
        val xp = X - 0.4f; val yp = Y - 0.95f
        val m = exp(-(t1Q4(xp / 2.3f) + t1Q4(yp / (2.1f - 0.35f * xp).coerceAtLeast(0.5f))))
        if (rnd.nextFloat() > 0.3f + 0.7f * m) continue
        val h = t1FaceDepth(X, Y) + (if (rr > 3.3f) 0.9f * ((rr - 3.3f) / 1.0f).pow(2) else 0f)
        add(pores, X, Y, -h + 0.02f, pc)
        placed++
        if (rnd.nextFloat() < 0.18f) {
            val e = 0.01f
            val gx = (t1FaceDepth(X + e, Y) - t1FaceDepth(X - e, Y)) / (2f * e)
            val gy = (t1FaceDepth(X, Y + e) - t1FaceDepth(X, Y - e)) / (2f * e)
            val nl = sqrt(gx * gx + gy * gy + 1f)
            val ta = rnd.nextFloat() * TAU
            val len = 0.07f + rnd.nextFloat() * 0.07f
            val hx = 0.55f * gx / nl + 0.8f * cos(ta); val hy = 0.55f * gy / nl + 0.8f * sin(ta); val hz = 0.55f / nl
            add(vellus, X, Y, -h + 0.02f, vc); add(vellus, X + hx * len, Y + hy * len, -h + 0.02f + hz * len, vc)
        }
    }
    // The punch biopsy's cut edge: the epidermis as a thin tan line round the top of the core, with
    // melanocytes (dots) along its base.
    val cut = ArrayList<Float>(); val mel = ArrayList<Float>()
    val core = t1CoreFrame()
    val ec = floatArrayOf(0.80f, 0.56f, 0.40f, 1f); val mc = floatArrayOf(0.52f, 0.32f, 0.18f, 1f)
    for (k in 0 until 32) {
        for (q in 0..1) {
            val a = TAU * (k + q) / 32
            val c = cos(a) * 0.362f; val s = sin(a) * 0.362f
            add(cut, core[6] + core[0] * c + core[3] * s + core[9] * -0.004f, core[7] + core[1] * c + core[4] * s + core[10] * -0.004f,
                core[8] + core[2] * c + core[5] * s + core[11] * -0.004f, ec)
        }
        val a = TAU * k / 32
        val c = cos(a) * 0.364f; val s = sin(a) * 0.364f
        add(mel, core[6] + core[0] * c + core[3] * s - core[9] * 0.0125f, core[7] + core[1] * c + core[4] * s - core[10] * 0.0125f,
            core[8] + core[2] * c + core[5] * s - core[11] * 0.0125f, mc)
    }
    return arrayOf(LineMesh(hair.toFloatArray()), PointMesh(pores.toFloatArray()), LineMesh(vellus.toFloatArray()),
        LineMesh(cut.toFloatArray()), PointMesh(mel.toFloatArray()))
}

/** The biopsy core in face coordinates: e1(0..2), e2(3..5), top centre(6..8), axis k(9..11), surface point(12..14). */
private fun t1CoreFrame(): FloatArray {
    val Xc = -2.05f; val Yc = -0.35f
    val sz = -t1FaceDepth(Xc, Yc)
    var kx = -0.4f; var ky = 0.15f; var kz = 1f
    val kl = sqrt(kx * kx + ky * ky + kz * kz); kx /= kl; ky /= kl; kz /= kl
    // e1 = normalize(Y x k), e2 = k x e1
    var e1x = kz; var e1y = 0f; var e1z = -kx
    val l1 = sqrt(e1x * e1x + e1y * e1y + e1z * e1z); e1x /= l1; e1y /= l1; e1z /= l1
    val e2x = ky * e1z - kz * e1y; val e2y = kz * e1x - kx * e1z; val e2z = kx * e1y - ky * e1x
    val lift = 0.55f + 0.45f
    return floatArrayOf(e1x, e1y, e1z, e2x, e2y, e2z, Xc + kx * lift, Yc + ky * lift, sz + kz * lift, kx, ky, kz, Xc, Yc, sz)
}

/** Node 0: the nose. */
internal fun StereoBodyRenderer.drawThreshold(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp > 1.0f) return
    val f = frameAt(T1_FACE_P)
    // centred in the passage while far off; lined up on the craft as it closes on the nostril
    val off = t1ShipOff(f); val fol = t1Smooth(0.25f, 0.5f, rp); val so = off[0] * fol; val uo = off[1] * fol
    val aCam = t1Along(f, camNowX, camNowY, camNowZ)
    // the nasal base as in a basal view - tip up, nostrils side by side - tilted 26 degrees so the tip
    // comes toward us; the craft's nostril stays on its lane
    val ct = cos(T1_TILT); val st = -sin(T1_TILT)
    val fT = StereoBodyRenderer.Frame(f.cx + f.sx * so + f.ux * uo, f.cy + f.sy * so + f.uy * uo, f.cz + f.sz * so + f.uz * uo,
        f.dx * ct + f.ux * st, f.dy * ct + f.uy * st, f.dz * ct + f.uz * st,
        f.ux * ct - f.dx * st, f.uy * ct - f.dy * st, f.uz * ct - f.dz * st,
        -f.sx, -f.sy, -f.sz)
    val face = ((-aCam - 0.3f) / 1.6f).coerceIn(0f, 1f)
    if (face > 0.01f) {
        val tmp = FloatArray(2)
        val skin = t1Mesh("face") { ParamMesh(56, 96) { u, v, out -> t1FacePoint(u, v, tmp, out) } }
        val vest = t1Mesh("vestibule") { ParamMesh(10, 48) { u, v, out ->
            t1NostrilRim(v * TAU, tmp)
            val sh = 1f - 0.15f * u
            out[0] = tmp[0] * sh - T1_TILT_K * 1.7f * u; out[1] = tmp[1] * sh; out[2] = -(t1FaceDepth(tmp[0], tmp[1]) + 0.4f + 1.7f * u)
        } }
        val lines = t1Mesh("face.lines") { t1FaceMeshes() }
        if (face < 0.999f) GLES20.glDepthMask(false)
        t1Lit(skin, fT, 0f, 0f, 0f, T1_SKIN, T1_SKIN_RIM, face, 0.12f)
        t1Lit(vest, fT, 0f, 0f, 0f, T1_VESTIBULE, T1_MUCOSA_RIM, face, 0.12f)
        // the other nostril's dark lumen
        t1Lit(t1Disc(), fT, t1FaceDepth(0f, 1.9f) + 0.45f, 0f, 1.9f, T1_HOLE, T1_HOLE, face, 0f, 0.8f, 0.48f, 1f)
        // biopsy core: fat, dermis, epidermis top, and the dark hole it came out of
        val c = t1CoreFrame()
        val kA = -c[11]; val kS = c[9]; val kU = c[10]
        val e1A = -c[2]; val e1S = c[0]; val e1U = c[1]
        fun at(t: Float, out: FloatArray) { out[0] = -(c[8] - c[11] * t); out[1] = c[6] - c[9] * t; out[2] = c[7] - c[10] * t }
        val q = FloatArray(3)
        val cyl = cylinder; val disc = t1Disc()
        at(0.35f, q); t1Shape(cyl, fT, q[0], q[1], q[2], kA, kS, kU, e1A, e1S, e1U, 0.36f, 0.36f, 0.1f, T1_FAT, T1_FAT, face, 0.15f)
        at(0.45f, q); t1Shape(disc, fT, q[0], q[1], q[2], kA, kS, kU, e1A, e1S, e1U, 0.36f, 0.36f, 1f, T1_FAT, T1_FAT, face, 0.15f)
        at(0.1285f, q); t1Shape(cyl, fT, q[0], q[1], q[2], kA, kS, kU, e1A, e1S, e1U, 0.36f, 0.36f, 0.1215f, T1_DERMIS, T1_DERMIS, face, 0.15f)
        at(0f, q); t1Shape(disc, fT, q[0], q[1], q[2], kA, kS, kU, e1A, e1S, e1U, 0.36f, 0.36f, 1f, T1_SKIN, T1_SKIN_RIM, face, 0.12f)
        t1Shape(disc, fT, -(c[14] + 0.012f), c[12], c[13], -1f, 0f, 0f, 0f, 1f, 0f, 0.36f, 0.36f, 1f, T1_HOLE, T1_HOLE, face)
        if (face < 0.999f) GLES20.glDepthMask(true)
        t1Color(lines[0], fT, 0f, 0f, 0f, 2.5f, false, face)                       // vibrissae
        t1Color(lines[3], fT, 0f, 0f, 0f, 2f, false, face)                         // epidermis line
        t1Color(lines[4], fT, 0f, 0f, 0f, 3f, true, face)                          // melanocytes
        if (quality < 2) {
            t1Color(lines[1], fT, 0f, 0f, 0f, 2f, true, face * 0.9f, depthWrite = false)   // pores
            t1Color(lines[2], fT, 0f, 0f, 0f, 1f, false, face * 0.6f, depthWrite = false) // vellus hairs
        }
    }
    // Inside: the conchae (turbinates), scroll-like ridges on the lateral wall of the nasal cavity.
    if (rp > 0.4f && rp < 0.8f) {
        val ft = frameAt(0.70f)
        for (k in 0 until 3) {
            val th = (200f + 38f * k) * DEG
            val r = t1WallR(0.70f, th) - 0.18f
            val c = cos(th); val s = sin(th)
            t1Shape(sphere, ft, 0.2f * k, c * r, s * r, 1f, 0f, 0f, 0f, c, s, 0.42f - 0.06f * k, 0.3f, 1.3f - 0.2f * k, T1_MUCOSA, T1_MUCOSA_RIM, 1f, 0.1f)
        }
    }
}

// ================================================================ stop 1: THE AIRWAY (larynx and trachea, 12 mm rung)
// 1 unit = 8 mm. Trachea 2 cm across (radius 1.25 units), C-shaped hyaline rings 4 mm tall on a
// 7 mm pitch, open posteriorly (-up) over 90 degrees where the trachealis closes the gap; the
// ciliated mucosa shimmers and carries mucus and dust cranially at 1 cm/min; epiglottis on the
// anterior wall, vestibular and vocal folds forming the V-shaped glottis (apex anterior); the
// carina and the left main bronchus opening at the far end (the craft takes the right).

private const val T1_RING0 = 0.875f
private const val T1_RINGS = 9
private const val T1_RING_STEP = 0.05f
private const val T1_CARINA = 1.30f

private fun StereoBodyRenderer.t1BuildTrachea(): Array<T1Batch> {
    val rings = T1Builder(); val bands = T1Builder()
    for (k in 0 until T1_RINGS) {
        val pk = T1_RING0 + k * T1_RING_STEP
        rings.surface(44, 8) { u, v, out ->
            val th = (-45f + 270f * u) * DEG
            val b = v * TAU
            val pp = pk + 0.25f / 16f * sin(b)
            t1RailPoint(pp, th, t1WallR(pp, th) * 0.965f + 0.065f * cos(b), out)
        }
        bands.surface(4, 10) { u, v, out ->
            val th = (225f + 90f * v) * DEG
            val pp = pk - 0.025f + 0.05f * u
            t1RailPoint(pp, th, t1WallR(pp, th) * 0.975f, out)
        }
    }
    return arrayOf(rings.build(), bands.build())
}

private fun StereoBodyRenderer.t1BuildSparkle(seed: Int, count: Int, p0: Float, p1: Float, col: FloatArray, k: Float): PointMesh {
    val rnd = java.util.Random(seed.toLong())
    val out = FloatArray(3)
    val data = FloatArray(count * 7)
    for (j in 0 until count) {
        val p = p0 + rnd.nextFloat() * (p1 - p0); val th = rnd.nextFloat() * TAU
        t1RailPoint(p, th, t1WallR(p, th) * k, out)
        t1Put(data, j, out[0], out[1], out[2], col, col[3] * (0.5f + 0.5f * rnd.nextFloat()))
    }
    return PointMesh(data)
}

internal fun StereoBodyRenderer.drawAirway(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp < 0.45f || rp > 1.25f) return
    val vis = ((1.245f - rp) / 0.035f).coerceIn(0f, 1f)
    landmarkFade *= vis; colorShader.globalFade *= vis
    // Epiglottis: a leaf on the anterior wall, its free tip curling up toward the tongue.
    val fe = frameAt(0.81f)
    val epi = t1Mesh("epiglottis") { ParamMesh(12, 16) { u, v, out ->
        val vv = v * 2f - 1f
        val hw = (0.3f + 0.42f * sin(PI_F * (0.15f + 0.7f * u))) * sqrt((1f - u.pow(6)).coerceAtLeast(0f))
        out[0] = hw * vv
        out[1] = 2.15f - 1.2f * u - 0.2f * vv * vv + 0.25f * u * u * u
        out[2] = 1.3f * u
    } }
    t1Lit(epi, fe, 0f, 0f, 0f, T1_EPIGLOTTIS, T1_MUCOSA_RIM, 1f, 0.35f)
    // The glottis: vestibular (false) folds above the pearly vocal folds; a V with its apex anterior.
    val fv = frameAt(0.845f)
    val off = t1ShipOff(fv); val so = off[0]; val uo = off[1]
    for (fold in 0..1) {
        val aF = if (fold == 0) -0.3f else 0f
        val apexU = if (fold == 0) 1.55f else 1.45f
        val procS = if (fold == 0) 1.05f else 0.72f
        val procU = if (fold == 0) -1.1f else -1.15f
        val half = if (fold == 0) 0.6f else 0.55f
        for (sgn in SIGNS) {
            val dS = sgn * procS; val dU = procU - apexU
            val dl = sqrt(dS * dS + dU * dU)
            var lS = -dU / dl; var lU = dS / dl
            if (lS * sgn < 0f) { lS = -lS; lU = -lU }
            val cS = dS * 0.5f + lS * half; val cU = apexU + dU * 0.5f + lU * half
            t1Shape(sphere, fv, aF, so + cS, uo + cU, 0f, dS / dl, dU / dl, 1f, 0f, 0f,
                half, if (fold == 0) 0.26f else 0.2f, dl * 0.5f,
                if (fold == 0) T1_MUCOSA else T1_VOCAL, if (fold == 0) T1_MUCOSA_RIM else T1_WHITE, 1f, if (fold == 0) 0.4f else 0.5f)
        }
    }
    for (sgn in SIGNS) t1Shape(sphere, fv, 0.05f, so + sgn * 0.85f, uo - 1.35f, 1f, 0f, 0f, 0f, 0f, 1f, 0.32f, 0.4f, 0.3f, T1_MUCOSA, T1_MUCOSA_RIM, 1f, 0.4f)
    // Trachea: rings and trachealis, one batch each.
    val tr = t1Mesh("trachea") { t1BuildTrachea() }
    t1LitWorld(tr[0], T1_CARTILAGE, T1_MUCOSA_RIM, 1f, 0.05f)
    t1LitWorld(tr[1], T1_TRACHEALIS, T1_MUCOSA_RIM, 1f, 0.08f)
    // Ciliary shimmer: three sets of glints flickering out of step.
    if (quality < 2) {
        val pEnd = T1_RING0 + (T1_RINGS - 1) * T1_RING_STEP + 0.02f
        for (k in 0 until 3) {
            val sp = t1Mesh("trachea.sparkle$k") { t1BuildSparkle(40 + k, 420, T1_RING0 - 0.02f, pEnd, floatArrayOf(1f, 1f, 0.94f, 1f), 0.93f) }
            val a = 0.35f + 0.65f * (0.5f + 0.5f * sin(seconds * 5.5f + k * 2.1f))
            t1Color(sp, null, 0f, 0f, 0f, 2.8f, true, a, depthWrite = false)
        }
    }
    // Mucus blanket with trapped dust, carried toward the throat (-along) at 1 cm/min.
    val d = t1Dyn.data
    var v = 0
    val q = FloatArray(3)
    val span = T1_RINGS * T1_RING_STEP
    val dc = floatArrayOf(0.88f, 0.84f, 0.70f, 1f)
    for (j in 0 until 40) {
        val pp = T1_RING0 - 0.02f + ((t1Hash(j) * span - seconds * 0.0013f) % span + span) % span
        val th = j * 2.39996f
        t1RailPoint(pp, th, t1WallR(pp, th) * 0.945f, q)
        v = t1Put(d, v, q[0], q[1], q[2], dc, 0.95f)
    }
    t1DynDraw(v, GLES20.GL_POINTS, 3.2f)
    // Carina: the left main bronchus opens off the left wall; our lumen carries on as the right.
    val fc = frameAt(T1_CARINA)
    val wr = t1WallR(T1_CARINA, PI_F) * 0.965f
    t1Shape(t1Disc(), fc, 0f, -wr, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 0.55f, 0.75f, 1f, T1_HOLE, T1_HOLE, 1f)
    t1Shape(t1Ring(0.14f), fc, 0f, -wr + 0.02f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 0.58f, 0.8f, 0.6f, T1_CARTILAGE, T1_WHITE, 1f, 0.2f)
}

// ================================================================ stop 2: THE ALVEOLUS (120 um rung)
// 1 unit = 80 um. One alveolus ~270 um across and ~300 um deep: its mouth opens off the alveolar
// duct (the entrance ring, with neighbouring mouths on the duct wall), its walls are septa shared
// with the neighbours (hexagonal facets, the far wall a polyhedral cup), every septum carries a
// dense capillary sheet (10 um meshes) in which red cells run and load oxygen (dark -> bright),
// a surfactant film glistens on the air side, type II cells sit in the corners, one macrophage
// crawls the floor, and the whole sac breathes in phase with the ambience.

private const val T1_MOUTH_P = 1.855f
private const val T1_DOME_P = 2.10f
private const val T1_DOME_H = 1.3f
private const val T1_DOME_R = 1.95f
private const val T1_SIDE_P0 = 1.87f
private const val T1_SIDE_P1 = 2.03f

private fun t1HexDist(s: Float, u: Float): Float {
    var m = 0f
    for (k in 0 until 6) { val a = 60f * k * DEG; m = max(m, s * cos(a) + u * sin(a)) }
    return m / (T1_DOME_R * cos(30f * DEG))
}

/** Honeycomb edges within a region: returns (vertices xy, edges as index pairs). */
private fun t1Honeycomb(d: Float, xMin: Float, xMax: Float, yMin: Float, yMax: Float, jitter: Float, seed: Int, keep: (Float, Float) -> Boolean): Pair<FloatArray, IntArray> {
    val index = HashMap<Long, Int>()
    val vx = ArrayList<Float>(); val vy = ArrayList<Float>()
    val edges = HashSet<Long>()
    val rv = d / sqrt(3f)
    fun vid(x: Float, y: Float): Int {
        val key = (Math.round(x * 1000f).toLong() shl 32) xor (Math.round(y * 1000f).toLong() and 0xffffffffL)
        return index.getOrPut(key) {
            val h = t1Hash(key.toInt() xor seed)
            val h2 = t1Hash((key ushr 32).toInt() * 31 + seed)
            vx.add(x + (h - 0.5f) * 2f * jitter); vy.add(y + (h2 - 0.5f) * 2f * jitter); vx.size - 1
        }
    }
    val dy = d * sqrt(3f) / 2f
    var j = 0
    var y = yMin - d
    while (y <= yMax + d) {
        var x = xMin - d + (if (j % 2 == 0) 0f else d * 0.5f)
        while (x <= xMax + d) {
            val ids = IntArray(6)
            for (m in 0 until 6) { val a = (30f + 60f * m) * DEG; ids[m] = vid(x + rv * cos(a), y + rv * sin(a)) }
            for (m in 0 until 6) {
                val a = ids[m]; val b = ids[(m + 1) % 6]
                val lo = min(a, b).toLong(); val hi = max(a, b).toLong()
                edges.add((lo shl 32) or hi)
            }
            x += d
        }
        y += dy; j++
    }
    val xs = vx.toFloatArray(); val ys = vy.toFloatArray()
    val out = ArrayList<Int>()
    for (e in edges) {
        val a = (e ushr 32).toInt(); val b = (e and 0xffffffffL).toInt()
        if (keep(xs[a], ys[a]) && keep(xs[b], ys[b])) { out.add(a); out.add(b) }
    }
    val xy = FloatArray(xs.size * 2)
    for (k in xs.indices) { xy[2 * k] = xs[k]; xy[2 * k + 1] = ys[k] }
    return xy to out.toIntArray()
}

private class T1DomeNet(val mesh: TriMesh, val paths: List<FloatArray>)

/** The far wall's capillary sheet (ribbons on the cup, dome-local) and red-cell paths through it. */
private fun t1BuildDomeNet(): T1DomeNet {
    val lim = T1_DOME_R * 0.95f
    val (xy, e) = t1Honeycomb(0.15f, -lim, lim, -lim, lim, 0.034f, 7) { x, y -> t1HexDist(x, y) < 0.96f }
    val tri = ArrayList<Float>()
    val col = floatArrayOf(0.80f, 0.16f, 0.22f, 0.8f)
    fun z(x: Float, y: Float) = T1_DOME_H * t1HexDist(x, y) + 0.015f
    fun v(x: Float, y: Float) { tri.add(x); tri.add(y); tri.add(z(x, y)); tri.add(col[0]); tri.add(col[1]); tri.add(col[2]); tri.add(col[3]) }
    val w = 0.035f
    val adj = HashMap<Int, MutableList<Int>>()
    var k = 0
    while (k < e.size) {
        val a = e[k]; val b = e[k + 1]; k += 2
        adj.getOrPut(a) { ArrayList() }.add(b); adj.getOrPut(b) { ArrayList() }.add(a)
        val x0 = xy[2 * a]; val y0 = xy[2 * a + 1]; val x1 = xy[2 * b]; val y1 = xy[2 * b + 1]
        val l = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0)).coerceAtLeast(1e-5f)
        val nx = -(y1 - y0) / l * w; val ny = (x1 - x0) / l * w
        v(x0 + nx, y0 + ny); v(x1 + nx, y1 + ny); v(x1 - nx, y1 - ny)
        v(x0 + nx, y0 + ny); v(x1 - nx, y1 - ny); v(x0 - nx, y0 - ny)
    }
    // Red-cell paths: random walks through the mesh (no immediate backtracking).
    val rnd = java.util.Random(5)
    val keys = adj.keys.toList()
    val paths = ArrayList<FloatArray>()
    var tries = 0
    while (paths.size < 14 && tries < 400) {
        tries++
        var cur = keys[rnd.nextInt(keys.size)]
        val hd = t1HexDist(xy[2 * cur], xy[2 * cur + 1])
        if (hd < 0.45f || hd > 0.85f) continue
        var prev = -1
        val pts = ArrayList<Float>()
        for (step in 0 until 28) {
            val x = xy[2 * cur]; val y = xy[2 * cur + 1]
            pts.add(x); pts.add(y); pts.add(z(x, y) + 0.02f)
            val nb = adj[cur]?.filter { it != prev && t1HexDist(xy[2 * it], xy[2 * it + 1]) < 0.9f } ?: break
            if (nb.isEmpty()) break
            prev = cur; cur = nb[rnd.nextInt(nb.size)]
        }
        if (pts.size >= 30) paths.add(pts.toFloatArray())
    }
    return T1DomeNet(TriMesh(tri.toFloatArray()), paths)
}

/** Capillary ribbons on the sac's side walls (world space). */
private fun StereoBodyRenderer.t1BuildSideNet(): TriMesh {
    val circ = 10.6f; val len = (T1_SIDE_P1 - T1_SIDE_P0) * 16f
    val (xy, e) = t1Honeycomb(0.2f, 0f, circ, 0f, len, 0.03f, 11) { x, y -> x in 0f..circ && y in 0f..len }
    val tri = ArrayList<Float>()
    val col = floatArrayOf(0.80f, 0.13f, 0.17f, 0.9f)
    val q = FloatArray(3)
    fun v(x: Float, y: Float) {
        val th = x / circ * TAU; val p = T1_SIDE_P0 + y / 16f
        t1RailPoint(p, th, t1WallR(p, th) * 0.972f, q)
        tri.add(q[0]); tri.add(q[1]); tri.add(q[2]); tri.add(col[0]); tri.add(col[1]); tri.add(col[2]); tri.add(col[3])
    }
    val w = 0.034f
    var k = 0
    while (k < e.size) {
        val a = e[k]; val b = e[k + 1]; k += 2
        val x0 = xy[2 * a]; val y0 = xy[2 * a + 1]; val x1 = xy[2 * b]; val y1 = xy[2 * b + 1]
        val l = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0)).coerceAtLeast(1e-5f)
        val nx = -(y1 - y0) / l * w; val ny = (x1 - x0) / l * w
        v(x0 + nx, y0 + ny); v(x1 + nx, y1 + ny); v(x1 - nx, y1 - ny)
        v(x0 + nx, y0 + ny); v(x1 - nx, y1 - ny); v(x0 - nx, y0 - ny)
    }
    return TriMesh(tri.toFloatArray())
}

/** Septal junction lines on the side walls, plus the neighbouring alveolus seen through the far wall. */
private fun StereoBodyRenderer.t1BuildSepta(): Array<LineMesh> {
    val side = ArrayList<Float>()
    val col = floatArrayOf(1f, 0.88f, 0.88f, 0.95f)
    val q = FloatArray(3)
    fun add(list: ArrayList<Float>, x: Float, y: Float, z: Float, c: FloatArray) { list.add(x); list.add(y); list.add(z); list.add(c[0]); list.add(c[1]); list.add(c[2]); list.add(c[3]) }
    for (k in 0 until 6) {
        val th = (30f + 60f * k) * DEG
        val steps = 8
        for (m in 0 until steps) {
            for (h in 0..1) {
                val p = T1_SIDE_P0 + (T1_SIDE_P1 - T1_SIDE_P0) * (m + h) / steps
                t1RailPoint(p, th, t1WallR(p, th) * 0.965f, q); add(side, q[0], q[1], q[2], col)
            }
        }
    }
    // Neighbour (dome-local): a smaller hexagonal cell behind the far wall.
    val nb = ArrayList<Float>()
    val nc = floatArrayOf(0.97f, 0.74f, 0.78f, 0.55f)
    val rN = 1.35f
    for (k in 0 until 6) {
        val a0 = (30f + 60f * k) * DEG; val a1 = (90f + 60f * k) * DEG
        val x0 = rN * cos(a0); val y0 = rN * sin(a0); val x1 = rN * cos(a1); val y1 = rN * sin(a1)
        add(nb, x0, y0, -0.25f, nc); add(nb, x0, y0, -3.0f, nc)          // side edges (z = -along)
        add(nb, x0, y0, -3.0f, nc); add(nb, 0f, 0f, -4.2f, nc)           // its far cup
        add(nb, x0, y0, -0.25f, nc); add(nb, x1, y1, -0.25f, nc)         // near rim
        add(nb, x0, y0, -3.0f, nc); add(nb, x1, y1, -3.0f, nc)
    }
    return arrayOf(LineMesh(side.toFloatArray()), LineMesh(nb.toFloatArray()))
}

internal fun StereoBodyRenderer.drawAlveolus(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp < 1.2f || rp > 2.24f) return
    val vis = t1Smooth(1.2f, 1.26f, rp) * (1f - t1Smooth(2.17f, 2.23f, rp))
    landmarkFade *= vis; colorShader.globalFade *= vis
    val breath = t1Breath(seconds)
    val inflate = 1f + 0.05f * sin(breath * TAU)       // the sac deepens on the inhale, in phase with every other sac
    // Neighbouring alveolar mouths on the duct wall (dark openings with pale rims).
    val holes = floatArrayOf(1.60f, 40f, 1.70f, 135f, 1.64f, 215f, 1.76f, 320f)
    for (k in 0 until 4) {
        val p = holes[2 * k]; val th = holes[2 * k + 1] * DEG
        val fh = frameAt(p)
        val r = t1WallR(p, th) * 0.965f; val c = cos(th); val s = sin(th)
        t1Shape(t1Disc(), fh, 0f, c * r, s * r, 0f, -c, -s, 1f, 0f, 0f, 0.42f, 0.55f, 1f, T1_HOLE, T1_HOLE, 1f)
        t1Shape(t1Ring(0.16f), fh, 0f, c * (r - 0.02f), s * (r - 0.02f), 0f, -c, -s, 1f, 0f, 0f, 0.44f, 0.58f, 0.4f, T1_SEPTUM, T1_WHITE, 1f, 0.2f)
    }
    // The mouth: the entrance ring (elastic and smooth-muscle fibres) and the septum joining it to the wall.
    val fm = frameAt(T1_MOUTH_P)
    t1Lit(t1Ring(0.14f), fm, 0f, 0f, 0f, T1_SEPTUM, T1_WHITE, 1f, 0.18f, 1.3f, 1.3f, 1.3f)
    val annulus = t1Mesh("alv.annulus") { ParamMesh(3, 36) { u, v, out -> val r = 1.33f + 0.9f * u; val a = v * TAU; out[0] = cos(a) * r; out[1] = sin(a) * r; out[2] = 0f } }
    GLES20.glDepthMask(false)
    t1Lit(annulus, fm, 0f, 0f, 0f, T1_SEPTUM, T1_WHITE, 0.55f, 0.12f)
    GLES20.glDepthMask(true)
    // Side walls: capillary sheet and the septal junctions that make the sac polyhedral.
    val side = t1Mesh("alv.side") { t1BuildSideNet() }
    t1Color(side, null, 0f, 0f, 0f, 1f, false, 1f)
    val septa = t1Mesh("alv.septa") { t1BuildSepta() }
    t1Color(septa[0], null, 0f, 0f, 0f, 3f, false, 0.9f)
    // Type II pneumocytes in the corners where septa meet; a macrophage on the floor.
    val fd = frameAt(T1_DOME_P)
    for (k in 0 until 3) {
        val a = (30f + 120f * k) * DEG
        t1Lit(sphere, fd, -1.0f * inflate, 1.5f * cos(a), 1.5f * sin(a), T1_TYPE2, T1_WHITE, 1f, 0.2f, 0.12f, 0.1f, 0.09f)
    }
    val fmc = frameAt(1.97f); val tm = 250f * DEG
    val rmc = t1WallR(1.97f, tm) * 0.9f
    val cm = cos(tm); val sm = sin(tm)
    t1Shape(sphere, fmc, 0f, cm * rmc, sm * rmc, 1f, 0f, 0f, 0f, cm, sm, 0.22f, 0.13f, 0.2f, T1_MACRO, T1_WHITE, 1f, 0.18f)
    for (k in 0 until 3) {
        val wob = 0.04f * sin(seconds * 0.7f + k * 2f)
        val ta = (k - 1) * 0.55f
        t1Lit(blob, fmc, (0.2f + wob) * cos(ta), cm * (rmc + 0.02f) - sm * (0.2f + wob) * sin(ta), sm * (rmc + 0.02f) + cm * (0.2f + wob) * sin(ta),
            T1_MACRO, T1_WHITE, 1f, 0.18f, 0.07f, 0.07f, 0.07f)
    }
    // The far wall: the neighbour behind it, the translucent septum, its capillary sheet and red cells, surfactant.
    t1Color(septa[1], fd, 0f, 0f, 0f, 1.5f, false, 0.45f)
    val cup = t1Mesh("alv.cup") { ParamMesh(6, 36) { u, v, out ->
        val sector = (v * 6f).toInt().coerceAtMost(5); val t = v * 6f - sector
        val a0 = (30f + 60f * sector) * DEG; val a1 = a0 + 60f * DEG
        val rs = T1_DOME_R * u
        out[0] = rs * ((1f - t) * cos(a0) + t * cos(a1)); out[1] = rs * ((1f - t) * sin(a0) + t * sin(a1)); out[2] = T1_DOME_H * u
    } }
    GLES20.glDepthMask(false)
    t1Lit(cup, fd, 0f, 0f, 0f, T1_SEPTUM, T1_WHITE, 0.5f, 0.15f, 1f, 1f, inflate)
    GLES20.glDepthMask(true)
    val net = t1Mesh("alv.net") { t1BuildDomeNet() }
    t1Model(fd, 0f, 0f, 0f); Matrix.scaleM(model, 0, 1f, 1f, inflate)
    Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    colorShader.use(mvp, 1f)
    GLES20.glDisable(GLES20.GL_CULL_FACE)
    net.mesh.draw(colorShader.positionHandle, colorShader.colorHandle)
    GLES20.glEnable(GLES20.GL_CULL_FACE)
    // Red cells single file through the sheet, loading oxygen as they go (dark -> bright red).
    val d = t1Dyn.data
    var v = 0
    val cDeoxy = floatArrayOf(0.62f, 0.42f, 0.95f, 1f); val cOxy = floatArrayOf(1f, 0.72f, 0.55f, 1f)
    val col = FloatArray(4)
    for ((j, path) in net.paths.withIndex()) {
        val segs = path.size / 3 - 1
        var total = 0f
        for (m in 0 until segs) total += sqrt((path[3 * m + 3] - path[3 * m]).pow(2) + (path[3 * m + 4] - path[3 * m + 1]).pow(2) + (path[3 * m + 5] - path[3 * m + 2]).pow(2))
        for (c in 0 until 3) {
            var s = ((seconds * 1.6f + j * 1.7f + c * total / 3f) % total)
            val frac = s / total
            var m = 0
            while (m < segs) {
                val l = sqrt((path[3 * m + 3] - path[3 * m]).pow(2) + (path[3 * m + 4] - path[3 * m + 1]).pow(2) + (path[3 * m + 5] - path[3 * m + 2]).pow(2))
                if (s <= l || m == segs - 1) {
                    val t = (s / l).coerceIn(0f, 1f)
                    val x = path[3 * m] + (path[3 * m + 3] - path[3 * m]) * t
                    val y = path[3 * m + 1] + (path[3 * m + 4] - path[3 * m + 1]) * t
                    val z = (path[3 * m + 2] + (path[3 * m + 5] - path[3 * m + 2]) * t) * inflate
                    for (q in 0..2) col[q] = cDeoxy[q] + (cOxy[q] - cDeoxy[q]) * frac
                    v = t1Put(d, v, fd.cx + fd.sx * x + fd.ux * y - fd.dx * z, fd.cy + fd.sy * x + fd.uy * y - fd.dy * z,
                        fd.cz + fd.sz * x + fd.uz * y - fd.dz * z, col, 1f)
                    break
                }
                s -= l; m++
            }
        }
    }
    t1DynDraw(v, GLES20.GL_POINTS, 8f)
    GLES20.glDepthMask(false)
    t1Lit(cup, fd, -0.03f, 0f, 0f, T1_FILM, T1_WHITE, 0.16f + 0.05f * sin(breath * TAU), 0.3f, 0.985f, 0.985f, inflate)
    GLES20.glDepthMask(true)
}

// ================================================================ stop 3: THE BLOODSTREAM (pulmonary venule, 12 um rung)
// 1 unit = 8 um. A venule 26 um across lined by flat endothelial cells (zig-zag junctions, bulging
// nuclei); behind the craft the capillary it came out of opens through the wall, a 5 um bore from
// which red cells emerge folded into parachutes. The free red cells are the drift field (true-size
// biconcave discs, oxygenated, carried downstream faster than the craft).

private const val T1_OSTIUM_P = 2.83f
private const val T1_OSTIUM_TH = 170f

internal fun StereoBodyRenderer.drawBloodstream(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp < 2.2f || rp > 3.55f) return
    val vis = t1Smooth(2.2f, 2.3f, rp) * (1f - t1Smooth(3.45f, 3.55f, rp))
    landmarkFade *= vis; colorShader.globalFade *= vis
    t1DrawEndothelium("venule3", 2.52f, 3.42f, 6, 4.2f, 3)
    // The capillary's opening (5 um) and the red cells squeezing out of it, folded to fit.
    val fo = frameAt(T1_OSTIUM_P); val th = T1_OSTIUM_TH * DEG
    val r = t1WallR(T1_OSTIUM_P, th) * 0.972f; val c = cos(th); val s = sin(th)
    t1Shape(t1Disc(), fo, 0f, c * r, s * r, 0f, -c, -s, 1f, 0f, 0f, 0.31f, 0.31f, 1f, T1_HOLE, T1_HOLE, 1f)
    t1Shape(t1Ring(0.2f), fo, 0f, c * (r - 0.01f), s * (r - 0.01f), 0f, -c, -s, 1f, 0f, 0f, 0.33f, 0.33f, 0.33f, T1_ENDO_NUC, T1_MUCOSA_RIM, 1f, 0.15f)
    val para = t1Mesh("parachute") { ParamMesh(14, 24) { u, v, out ->
        val top = u < 0.5f
        val rr = (if (top) 1f - u * 2f else (u - 0.5f) * 2f).coerceIn(0f, 0.999f)
        val q = rr * rr
        val h = 0.5f * sqrt(1f - q) * (0.81f + 7.83f * q - 4.39f * q * q) / 3.91f
        val a = v * TAU
        out[0] = cos(a) * rr; out[2] = sin(a) * rr; out[1] = (if (top) h else -h) + 0.45f * q   // the rim folds back: a cup
    } }
    // capillary axis: out of the wall, angled 35 degrees downstream
    t1VenuleCells(i, seconds, 2.3f, 3.5f, COL_RBC_OXY, 40, 1.9f)
    val ca = sin(35f * DEG); val cr = cos(35f * DEG)
    for (k in 0..1) {
        val ph = ((seconds / 2.2f + k * 0.5f) % 1f)
        val x = -0.35f + 1.2f * ph
        val al = (1f - t1Smooth(0.55f, 1f, ph))
        t1Shape(para, fo, ca * x, c * (r - cr * x), s * (r - cr * x), 0f, -s, c, -ca, c * cr, s * cr,
            0.34f, 0.34f, 0.34f, COL_RBC_OXY, COL_RBC_RIM, al, 0.1f)
    }
}

/**
 * Red cells in the venule: true size (7.5 um = 0.94 units), oxygenated, carried downstream faster
 * than the craft on a parabolic profile, each tumbling freely in the shear so that its biconcave
 * face, rim and central pallor turn toward the viewer.
 */
private fun StereoBodyRenderer.t1VenuleCells(i: Int, seconds: Float, pMin: Float, pMax: Float, col: FloatArray, count: Int, vmax: Float) {
    val rp = routeProgress
    val n = when (quality) { 0 -> count; 1 -> count * 2 / 3; else -> count / 2 }
    val span = 1.35f
    for (k in 0 until n) {
        val rf = 0.8f * sqrt(t1Hash(k * 3 + 1)); val ang = t1Hash(k * 3 + 2) * TAU
        val v = vmax * (1f - rf * rf / 0.7f).coerceAtLeast(0.15f)          // units/s, fastest on the axis
        val base = i - 0.35f + t1Hash(k * 3) * span
        val pp = rp - 0.3f + (((base + seconds * v / 16f) - (rp - 0.3f)) % span + span) % span
        if (pp < pMin || pp > pMax) continue
        val f = frameAt(pp)
        val rr = (tunnelRadius(pp) - 0.5f) * rf
        val x = f.cx + (f.sx * cos(ang) + f.ux * sin(ang)) * rr
        val y = f.cy + (f.sy * cos(ang) + f.uy * sin(ang)) * rr
        val z = f.cz + (f.sz * cos(ang) + f.uz * sin(ang)) * rr
        if ((x - camNowX).pow(2) + (y - camNowY).pow(2) + (z - camNowZ).pow(2) < 2.3f * 2.3f) continue
        val t = seconds * (0.5f + 0.6f * t1Hash(k + 50)) + k
        val a1 = t1Hash(k + 70) * TAU
        // disc normal wandering over the sphere
        val nx = sin(t) * cos(a1); val ny = cos(t); val nz = sin(t) * sin(a1)
        val px = -sin(a1); val pz = cos(a1)
        drawBasis(x, y, z, px, 0f, pz, nx, ny, nz, 0.47f, 0.47f, 0.47f, rbc, col, COL_RBC_RIM, 1f, 0f, 0.05f)
    }
}

// ================================================================ stop 4: THE HEART (12 mm rung)
// 1 unit = 8 mm. Left atrium (smooth-walled; pulmonary vein ostia) -> mitral valve: a D-shaped
// saddle annulus ~3.2 x 2.6 cm, a large anterior leaflet on the aortic third and a crescent
// posterior leaflet with three scallops, closing on a curved coaptation line; chordae tendineae
// to two papillary muscles; a trabeculated left ventricle -> outflow tract -> aortic valve (three
// semilunar cusps, sinuses of Valsalva, two coronary ostia) -> ascending aorta. For the ride the
// chambers are laid in series along the rail (the flow diagram), not with the real U-turn at the
// apex. Timing follows the beat: mitral closed at S1 (heartPhase 0), aortic open 0.05-0.33 s
// (systole), aortic shut at S2, mitral open again after isovolumic relaxation (0.36-0.9 s).

private const val T1_MV_P = 3.97f
private const val T1_AV_P = 4.3125f

private fun t1MvAnnulus(th: Float, out: FloatArray) {      // (side, up, along)
    val sn = sin(th)
    out[0] = 2.0f * cos(th)
    out[1] = if (sn > 0f) min(1.6f * sn, 1.15f) else 1.6f * sn
    out[2] = -0.22f * sn * sn
}

/** A point on a mitral leaflet (frame-local side, up, along) for openness o. */
private fun t1MvLeaf(anterior: Boolean, u0: Float, v: Float, o: Float, h: FloatArray, k: FloatArray, out: FloatArray) {
    val th: Float; val kv: Float; var u = u0
    if (anterior) { th = (25f + 130f * v) * DEG; kv = v }
    else {
        th = (155f + 230f * v) * DEG; kv = 1f - v
        val n1 = (v - 0.333f) / 0.035f; val n2 = (v - 0.667f) / 0.035f
        u *= 1f - (0.06f + 0.16f * o) * (exp(-n1 * n1) + exp(-n2 * n2))    // clefts between P1, P2, P3
    }
    t1MvAnnulus(th, h)
    // coaptation line: a "smile" from commissure to commissure, bowed toward the posterior wall
    t1MvAnnulus(25f * DEG, k); val ax = k[0]; val ay = k[1]
    t1MvAnnulus(155f * DEG, k); val bx = k[0]; val by = k[1]
    val kx = ax + (bx - ax) * kv; val ky = ay + (by - ay) * kv - 1.1f * sin(PI_F * kv); val kz = 0.1f * sin(PI_F * kv)
    val belly = sin(PI_F * u) * sin(PI_F * v)
    // closed: hinge -> coaptation, bellied toward the atrium
    val uc = u * 1.07f                                                 // closed leaflets overlap along the coaptation zone
    val cx = h[0] + (kx - h[0]) * uc; val cy = h[1] + (ky - h[1]) * uc; val cz = h[2] + (kz - h[2]) * uc - 0.3f * belly
    // open: hanging into the ventricle
    val sc = if (anterior) 0.6f else 0.7f
    val depth = if (anterior) 2.6f else 1.6f
    val ox = h[0] + (h[0] * sc - h[0]) * u; val oy = h[1] + (h[1] * sc - h[1]) * u; val oz = h[2] + depth * u + 0.15f * belly
    out[0] = cx + (ox - cx) * o; out[1] = cy + (oy - cy) * o; out[2] = cz + (oz - cz) * o
}

/** A point on aortic cusp j (frame-local side, up, along relative to the annulus) for openness o. */
private fun t1AvCusp(j: Int, u: Float, v: Float, o: Float, out: FloatArray) {
    val t0 = (90f + 120f * j) * DEG
    val th = t0 + 120f * DEG * v
    val R = 1.48f
    val hx = R * cos(th); val hy = R * sin(th); val hz = 0.9f * (1f - sin(PI_F * v))
    // closed free edge: commissure -> centre -> commissure (the three meet in a Y)
    val t1 = t0 + 120f * DEG
    val fxv: Float; val fyv: Float; val fzv: Float
    if (v < 0.5f) { val t = v * 2f; fxv = R * cos(t0) * (1f - t); fyv = R * sin(t0) * (1f - t); fzv = 0.9f + (0.55f - 0.9f) * t }
    else { val t = v * 2f - 1f; fxv = R * cos(t1) * t; fyv = R * sin(t1) * t; fzv = 0.55f + (0.9f - 0.55f) * t }
    val belly = sin(PI_F * u) * sin(PI_F * v)
    val cx = hx + (fxv - hx) * u; val cy = hy + (fyv - hy) * u; val cz = hz + (fzv - hz) * u - 0.35f * belly
    val ox = hx + (1.3f * cos(th) - hx) * u + 0.1f * cos(th) * belly; val oy = hy + (1.3f * sin(th) - hy) * u + 0.1f * sin(th) * belly
    val oz = hz + (1.0f - hz) * u
    out[0] = cx + (ox - cx) * o; out[1] = cy + (oy - cy) * o; out[2] = cz + (oz - cz) * o
}

private class T1Heart {
    val ant = T1DynSurface(9, 26); val post = T1DynSurface(9, 36)
    val cusps = Array(3) { T1DynSurface(8, 16) }
    var mvOpen = -1f; var avOpen = -1f            // re-tessellate only when the valve has moved (not per eye)
}
private var t1Heart: T1Heart? = null

private fun StereoBodyRenderer.t1BuildTrabeculae(fm: T1F): T1Batch {
    val b = T1Builder()
    val rnd = java.util.Random(17)
    val q0 = FloatArray(3); val q1 = FloatArray(3)
    fun local(q: FloatArray, out: FloatArray) {       // world -> fm-local (x = side, y = up, z = -along)
        val dx = q[0] - fm.cx; val dy = q[1] - fm.cy; val dz = q[2] - fm.cz
        out[0] = dx * fm.sx + dy * fm.sy + dz * fm.sz; out[1] = dx * fm.ux + dy * fm.uy + dz * fm.uz; out[2] = -(dx * fm.dx + dy * fm.dy + dz * fm.dz)
    }
    val l0 = FloatArray(3); val l1 = FloatArray(3)
    for (k in 0 until 26) {
        val th = rnd.nextFloat() * TAU
        val a0 = 0.6f + rnd.nextFloat() * 2.6f
        val len = 0.9f + rnd.nextFloat() * 1.4f
        val dth = (rnd.nextFloat() - 0.5f) * 0.5f
        val p0 = T1_MV_P + a0 / 16f; val p1 = T1_MV_P + (a0 + len) / 16f
        t1RailPoint(p0, th, t1WallR(p0, th) * 0.975f, q0); t1RailPoint(p1, th + dth, t1WallR(p1, th + dth) * 0.975f, q1)
        local(q0, l0); local(q1, l1)
        b.rod(l0[0], l0[1], l0[2], l1[0], l1[1], l1[2], 0.11f + rnd.nextFloat() * 0.09f)
    }
    return b.build()
}

internal fun StereoBodyRenderer.drawHeart(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp < 3.45f || rp > 4.44f) return
    val vis = t1Smooth(3.45f, 3.56f, rp) * (1f - t1Smooth(4.37f, 4.43f, rp))
    landmarkFade *= vis; colorShader.globalFade *= vis
    val hs = t1Heart ?: T1Heart().also { t1Heart = it }
    val ph = heartPhase
    val oM = t1Smooth(0.36f, 0.46f, ph) * (1f - t1Smooth(0.86f, 0.92f, ph))
    val oA = t1Smooth(0.05f, 0.10f, ph) * (1f - t1Smooth(0.28f, 0.34f, ph))
    val sys = t1Smooth(0.02f, 0.12f, ph) * (1f - t1Smooth(0.30f, 0.42f, ph))
    // Left atrium: the four pulmonary vein ostia on its posterior wall.
    val fl = frameAt(T1_MV_P - 3.2f / 16f)
    for (k in 0 until 4) {
        val th = floatArrayOf(200f, 235f, 305f, 340f)[k] * DEG
        val a = if (k % 2 == 0) -0.45f else 0.45f
        val pp = T1_MV_P - 3.2f / 16f + a / 16f
        val r = t1WallR(pp, th) * 0.965f; val c = cos(th); val s = sin(th)
        t1Shape(t1Disc(), fl, a, c * r, s * r, 0f, -c, -s, 1f, 0f, 0f, 0.6f, 0.62f, 1f, T1_HOLE, T1_HOLE, 1f)
        t1Shape(t1Ring(0.15f), fl, a, c * (r - 0.02f), s * (r - 0.02f), 0f, -c, -s, 1f, 0f, 0f, 0.64f, 0.66f, 0.5f, T1_MYO, T1_MYO_RIM, 1f, 0.15f)
    }
    val fm = frameAt(T1_MV_P)
    // The atrioventricular junction around the annulus (the floor of the atrium).
    val floor = t1Mesh("mv.floor") { val h = FloatArray(3); ParamMesh(3, 64) { u, v, out ->
        t1MvAnnulus(v * TAU, h)
        val rr = sqrt(h[0] * h[0] + h[1] * h[1]); val k = 1f + (4.0f / rr - 1f) * u
        out[0] = h[0] * k; out[1] = h[1] * k; out[2] = -h[2] * (1f - u)
    } }
    t1Lit(floor, fm, 0f, 0f, 0f, T1_MYO, T1_MYO_RIM, 1f, 0.1f)
    val annulus = t1Mesh("mv.annulus") { val h = FloatArray(3); ParamMesh(48, 8) { u, v, out ->
        t1MvAnnulus(u * TAU, h)
        val b = v * TAU; val rr = sqrt(h[0] * h[0] + h[1] * h[1])
        out[0] = h[0] * (1f + 0.07f * cos(b) / rr); out[1] = h[1] * (1f + 0.07f * cos(b) / rr); out[2] = -(h[2] + 0.07f * sin(b))
    } }
    t1Lit(annulus, fm, 0f, 0f, 0f, T1_VALVE, T1_WHITE, 1f, 0.2f)
    // Leaflets: fade as the craft or a camera slips through the orifice.
    val aShip = t1Along(fm, shipX, shipY, shipZ); val aCam = t1Along(fm, camNowX, camNowY, camNowZ)
    val leafA = ((min(abs(aShip), abs(aCam)) - 0.3f) / 0.9f).coerceIn(0f, 1f)
    val h = FloatArray(3); val kk = FloatArray(3); val q = FloatArray(3)
    if (leafA > 0.02f) {
        if (abs(oM - hs.mvOpen) > 0.004f) {
            hs.mvOpen = oM
            hs.ant.update { u, v, out -> t1MvLeaf(true, u, v, oM, h, kk, q); out[0] = q[0]; out[1] = q[1]; out[2] = -q[2] }
            hs.post.update { u, v, out -> t1MvLeaf(false, u, v, oM, h, kk, q); out[0] = q[0]; out[1] = q[1]; out[2] = -q[2] }
        }
        if (leafA < 0.999f) GLES20.glDepthMask(false)
        t1Lit(hs.ant, fm, 0f, 0f, 0f, T1_VALVE, T1_VALVE_RIM, leafA, 0.18f)
        t1Lit(hs.post, fm, 0f, 0f, 0f, T1_VALVE_P, T1_VALVE_RIM, leafA, 0.18f)
        if (leafA < 0.999f) GLES20.glDepthMask(true)
    }
    // Papillary muscles (shortening in systole) and the chordae: taut when shut, slack when open.
    val tipA = floatArrayOf(1.3f, -0.45f, 3.1f - 0.25f * sys); val tipP = floatArrayOf(-1.35f, -0.75f, 3.1f - 0.25f * sys)
    t1Rod(fm, 4.4f, 2.7f, -0.75f, tipA[2], tipA[0], tipA[1], 0.52f, T1_MYO, T1_MYO_RIM, 1f, 0.12f, sphere)
    t1Rod(fm, 4.4f, -2.65f, -1.25f, tipP[2], tipP[0], tipP[1], 0.52f, T1_MYO, T1_MYO_RIM, 1f, 0.12f, sphere)
    val d = t1Dyn.data
    var v = 0
    val cc = floatArrayOf(0.98f, 0.96f, 0.90f, 1f)
    fun wp(s: Float, uu: Float, a: Float, alpha: Float) { v = t1Put(d, v, fx(fm, a, s, uu), fy(fm, a, s, uu), fz(fm, a, s, uu), cc, alpha) }
    for (leaf in 0..1) {
        val vs = if (leaf == 0) floatArrayOf(0.1f, 0.22f, 0.34f, 0.66f, 0.78f, 0.9f) else floatArrayOf(0.08f, 0.2f, 0.3f, 0.7f, 0.8f, 0.92f)
        for (vv in vs) {
            t1MvLeaf(leaf == 0, 1f, vv, oM, h, kk, q)
            val qs = q[0]; val qu = q[1]; val qa = q[2]
            t1MvLeaf(leaf == 0, 0.8f, vv + 0.04f, oM, h, kk, q)
            val rs = q[0]; val ru = q[1]; val ra = q[2]
            val tip = if (qs > 0f) tipA else tipP
            val slack = 0.35f * oM
            val bs = tip[0] + (qs - tip[0]) * 0.7f; val bu = tip[1] + (qu - tip[1]) * 0.7f - slack; val ba = tip[2] + (qa - tip[2]) * 0.7f
            wp(tip[0], tip[1], tip[2], 1f); wp(bs, bu, ba, 1f)
            wp(bs, bu, ba, 1f); wp(qs, qu, qa, 1f)
            wp(bs, bu, ba, 1f); wp(rs, ru, ra, 1f)
        }
    }
    t1DynDraw(v, GLES20.GL_LINES, 2.2f)
    if (leafA > 0.02f && oM < 0.6f) {
        v = 0
        val lc = floatArrayOf(0.55f, 0.20f, 0.22f, 1f)
        for (m in 0 until 24) for (hh in 0..1) {
            t1MvLeaf(true, 0.935f, (m + hh) / 24f, oM, h, kk, q)
            v = t1Put(d, v, fx(fm, q[2] - 0.1f, q[0], q[1]), fy(fm, q[2] - 0.1f, q[0], q[1]), fz(fm, q[2] - 0.1f, q[0], q[1]), lc, 1f)
        }
        t1DynDraw(v, GLES20.GL_LINES, 3f, leafA * (1f - oM / 0.6f))
    }
    // Trabeculae carneae on the ventricular walls, drawn in a little in systole.
    val trab = t1Mesh("lv.trab") { t1BuildTrabeculae(fm) }
    t1Lit(trab, fm, 0f, 0f, 0f, T1_TRAB, T1_MYO_RIM, 1f, 0.06f, 1f - 0.035f * sys, 1f - 0.035f * sys, 1f)
    // Outflow tract narrowing to the aortic annulus.
    val fa = frameAt(T1_AV_P)
    val funnel = t1Mesh("lvot") { ParamMesh(8, 40) { u, v, out ->
        val r = 1.5f + 2.1f * (1f - t1Smooth(0f, 1f, u)); val a = v * TAU
        out[0] = cos(a) * r; out[1] = sin(a) * r; out[2] = 1.3f - 1.3f * u
    } }
    t1Lit(funnel, fa, 0f, 0f, 0f, T1_MYO, T1_MYO_RIM, 1f, 0.1f)
    // Aortic root: three sinuses of Valsalva above the cusps, then the tube of the ascending aorta.
    val root = t1Mesh("aorta.root") { ParamMesh(16, 48) { u, v, out ->
        val a = v * TAU; val z = 3.4f * u
        val bulge = if (z < 1.6f) 0.2f * max(0f, cos(3f * (a - 150f * DEG))) * sin(PI_F * z / 1.6f) else 0f
        val r = 1.5f * (1f + bulge)
        out[0] = cos(a) * r; out[1] = sin(a) * r; out[2] = -z
    } }
    t1Lit(root, fa, 0f, 0f, 0f, T1_INTIMA, T1_WHITE, 1f, 0.12f)
    for (k in 0..1) {       // left and right coronary ostia, in their sinuses
        val th = (if (k == 0) 30f else 150f) * DEG
        val r = 1.5f * 1.17f
        t1Shape(t1Disc(), fa, 0.8f, cos(th) * (r - 0.03f), sin(th) * (r - 0.03f), 0f, -cos(th), -sin(th), 1f, 0f, 0f, 0.2f, 0.22f, 1f, T1_HOLE, T1_HOLE, 1f)
    }
    val aShipA = t1Along(fa, shipX, shipY, shipZ); val aCamA = t1Along(fa, camNowX, camNowY, camNowZ)
    val cuspA = ((min(abs(aShipA - 0.5f), abs(aCamA - 0.5f)) - 0.4f) / 0.9f).coerceIn(0f, 1f)
    if (cuspA > 0.02f) {
        if (cuspA < 0.999f) GLES20.glDepthMask(false)
        val redo = abs(oA - hs.avOpen) > 0.004f
        if (redo) hs.avOpen = oA
        for (j in 0 until 3) {
            if (redo) hs.cusps[j].update { u, vv, out -> t1AvCusp(j, u, vv, oA, q); out[0] = q[0]; out[1] = q[1]; out[2] = -q[2] }
            t1Lit(hs.cusps[j], fa, 0f, 0f, 0f, T1_VALVE, T1_VALVE_RIM, cuspA, 0.18f)
        }
        if (cuspA < 0.999f) GLES20.glDepthMask(true)
    }
}

// ================================================================ stop 5: THE SENTINEL (neck venule, 12 um rung)
// 1 unit = 8 um. A post-capillary venule 48 um across, where leukocytes leave the blood: a
// neutrophil (12 um; 3-4-lobed nucleus, lilac granules) rolls along the endothelium, stops, and
// crawls toward the craft behind a flat lamellipodium with filopodia, touches the stern, lets go
// and turns away. Further on a monocyte (18 um, kidney-shaped nucleus, ruffled) on the wall, and
// bacteria coated with antibodies (10 nm: glints on their surfaces, far below the resolution of a
// Y at this scale). Red cells: the drift field, deoxygenated venous blood.

private const val T1_NEUT_TH = -35f
private const val T1_NEUT_R = 0.75f

private fun t1BuildGranules(n: Int, r: Float, seed: Int, col: FloatArray): PointMesh {
    val rnd = java.util.Random(seed.toLong())
    val data = FloatArray(n * 7)
    var k = 0
    while (k < n) {
        val x = rnd.nextFloat() * 2f - 1f; val y = rnd.nextFloat() * 2f - 1f; val z = rnd.nextFloat() * 2f - 1f
        if (x * x + y * y + z * z > 1f) continue
        t1Put(data, k, x * r, y * r * 0.8f, z * r, col, 0.9f); k++
    }
    return PointMesh(data)
}

/** Antibody glints on the surface of a rod-shaped bacterium (capsule 2.2 x 1 um), local coords. */
private fun t1BuildGlints(): PointMesh {
    val rnd = java.util.Random(23)
    val n = 36
    val data = FloatArray(n * 7)
    val c = floatArrayOf(1f, 0.92f, 0.45f, 1f)
    for (k in 0 until n) {
        val a = rnd.nextFloat() * TAU; val z = (rnd.nextFloat() * 2f - 1f) * 0.12f
        val rr = 0.068f * (if (abs(z) > 0.075f) sqrt((1f - ((abs(z) - 0.075f) / 0.068f).pow(2)).coerceAtLeast(0f)) else 1f)
        t1Put(data, k, cos(a) * (rr + 0.01f), sin(a) * (rr + 0.01f), z, c, 1f)
    }
    return PointMesh(data)
}

private val t1Basis = FloatArray(16)

/** model = T(x,y,z) * [X Y Z] (scaled world axes) */
private fun StereoBodyRenderer.t1BasisModel(x: Float, y: Float, z: Float, X: FloatArray, Y: FloatArray, Z: FloatArray) {
    model[0] = X[0]; model[1] = X[1]; model[2] = X[2]; model[3] = 0f
    model[4] = Y[0]; model[5] = Y[1]; model[6] = Y[2]; model[7] = 0f
    model[8] = Z[0]; model[9] = Z[1]; model[10] = Z[2]; model[11] = 0f
    model[12] = x; model[13] = y; model[14] = z; model[15] = 1f
}

internal fun StereoBodyRenderer.drawSentinel(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp < 4.3f || rp > 5.8f) return
    val vis = t1Smooth(4.3f, 4.42f, rp) * (1f - t1Smooth(5.6f, 5.8f, rp))
    landmarkFade *= vis; colorShader.globalFade *= vis
    t1DrawEndothelium("venule5", 4.52f, 5.52f, 9, 4.0f, 5)
    t1VenuleCells(i, seconds, 4.45f, 5.6f, COL_RBC_DEOXY, 26, 1.2f)
    val f5 = frameAt(i.toFloat())
    val aS = (rp - i) * 16f                                  // craft, along from the node
    // ---- the neutrophil's itinerary
    val aRoll = 2.6f
    val th = T1_NEUT_TH * DEG
    val aN: Float; val roll: Float; var reach: Float; var lead = -1f   // lead: -1 = lamellipodium upstream (toward the craft)
    var toStern = false
    val aContact = (4.935f - i) * 16f - 0.6f
    when {
        rp < 4.80f -> { aN = aRoll + (rp - 4.80f) * 16f * 1.25f; roll = aN / T1_NEUT_R; reach = 0f }
        rp < 4.935f -> {
            val t = t1Smooth(4.80f, 4.935f, rp).pow(1.5f)
            aN = aRoll + (aContact - aRoll) * t; roll = aRoll / T1_NEUT_R; reach = 0.35f + 0.65f * t
        }
        rp < 4.968f -> { aN = aContact; roll = aRoll / T1_NEUT_R; reach = 1f; toStern = true }
        else -> {
            val t = t1Smooth(4.968f, 5.05f, rp)
            aN = aContact - 1.2f * t; roll = aRoll / T1_NEUT_R; reach = 0.5f; lead = 1f
        }
    }
    val pN = i + aN / 16f
    val wN = t1WallR(pN, th)
    val c = cos(th); val s = sin(th)
    val rc = wN - 0.6f                                        // flattened against the wall
    val cS = c * rc; val cU = s * rc
    // local axes in (along, side, up): d = downstream, r = inward radial, t = tangential
    val rS = -c; val rU = -s; val tS = -s; val tU = c
    val cr = cos(roll); val sr = sin(roll)
    // rolled vector (vd, vr, vt) -> (along, side, up)
    fun rolled(vd: Float, vr: Float, vt: Float, out: FloatArray) {
        val d2 = cr * vd + sr * vr; val r2 = -sr * vd + cr * vr
        out[0] = d2; out[1] = rS * r2 + tS * vt; out[2] = rU * r2 + tU * vt
    }
    val q = FloatArray(3)
    // nucleus: four lobes in a curved chain, joined by thin strands
    val lobes = floatArrayOf(0.30f, 0.05f, 0.16f, 0.06f, -0.14f, 0.26f, -0.20f, 0.02f, 0.12f, -0.26f, -0.02f, -0.16f)
    var px = 0f; var ps = 0f; var pu = 0f
    for (k in 0 until 4) {
        rolled(lobes[3 * k], lobes[3 * k + 1], lobes[3 * k + 2], q)
        val la = aN + q[0]; val ls = cS + q[1]; val lu = cU + q[2]
        t1Lit(sphere, f5, la, ls, lu, T1_NEUT_NUC, T1_WHITE, 1f, 0.25f, 0.17f, 0.15f, 0.17f)
        if (k > 0) t1Rod(f5, px, ps, pu, la, ls, lu, 0.035f, T1_NEUT_NUC, T1_WHITE, 1f, 0.25f)
        px = la; ps = ls; pu = lu
    }
    // granules, rotating with the cell
    val gran = t1Mesh("neut.gran") { t1BuildGranules(70, 0.62f, 9, floatArrayOf(0.82f, 0.70f, 0.98f, 1f)) }
    rolled(1f, 0f, 0f, q); val zx = f5.dx * q[0] + f5.sx * q[1] + f5.ux * q[2]; val zy = f5.dy * q[0] + f5.sy * q[1] + f5.uy * q[2]; val zz = f5.dz * q[0] + f5.sz * q[1] + f5.uz * q[2]
    rolled(0f, 1f, 0f, q); val yx = f5.dx * q[0] + f5.sx * q[1] + f5.ux * q[2]; val yy = f5.dy * q[0] + f5.sy * q[1] + f5.uy * q[2]; val yz = f5.dz * q[0] + f5.sz * q[1] + f5.uz * q[2]
    rolled(0f, 0f, 1f, q); val xx = f5.dx * q[0] + f5.sx * q[1] + f5.ux * q[2]; val xy = f5.dy * q[0] + f5.sy * q[1] + f5.uy * q[2]; val xz = f5.dz * q[0] + f5.sz * q[1] + f5.uz * q[2]
    t1BasisModel(fx(f5, aN, cS, cU), fy(f5, aN, cS, cU), fz(f5, aN, cS, cU), floatArrayOf(xx, xy, xz), floatArrayOf(yx, yy, yz), floatArrayOf(zx, zy, zz))
    Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    colorShader.use(mvp, 3.2f, points = true)
    gran.draw(colorShader.positionHandle, colorShader.colorHandle)
    // lamellipodium and filopodia at the leading edge
    var tA: Float; var tS2: Float; var tU2: Float
    if (toStern) {
        // reaching for the starboard edge of the stern
        val sw = shipX - dirX * 0.7f + sideX * 0.28f; val swy = shipY - dirY * 0.7f + sideY * 0.28f; val swz = shipZ - dirZ * 0.7f + sideZ * 0.28f
        tA = t1Along(f5, sw, swy, swz); tS2 = (sw - f5.cx) * f5.sx + (swy - f5.cy) * f5.sy + (swz - f5.cz) * f5.sz
        tU2 = (sw - f5.cx) * f5.ux + (swy - f5.cy) * f5.uy + (swz - f5.cz) * f5.uz
    } else if (lead > 0f) {
        // letting go: it turns off along the wall, away from the craft's path
        tA = aN - 0.8f; tS2 = cS + tS * 1.4f + rS * 0.3f; tU2 = cU + tU * 1.4f + rU * 0.3f
    } else {
        tA = aN - 1.6f; tS2 = cS + rS * 0.5f; tU2 = cU + rU * 0.5f
    }
    var ddA = tA - aN; var ddS = tS2 - cS; var ddU = tU2 - cU
    val dl = sqrt(ddA * ddA + ddS * ddS + ddU * ddU).coerceAtLeast(1e-3f)
    ddA /= dl; ddS /= dl; ddU /= dl
    val ext = if (toStern) (dl - T1_NEUT_R).coerceIn(0.2f, 1.6f) else 0.3f + 0.5f * reach * (0.8f + 0.2f * sin(seconds * 1.3f))
    if (reach > 0.01f) {
        val la = aN + ddA * (T1_NEUT_R * 0.8f + ext * 0.5f); val ls = cS + ddS * (T1_NEUT_R * 0.8f + ext * 0.5f); val lu = cU + ddU * (T1_NEUT_R * 0.8f + ext * 0.5f)
        t1Shape(sphere, f5, la, ls, lu, ddA, ddS, ddU, 0f, rS, rU, 0.45f, 0.07f, ext * 0.5f + 0.15f, T1_NEUT, T1_WHITE, 0.8f, 0.2f)
        val ea = aN + ddA * (T1_NEUT_R * 0.8f + ext); val es = cS + ddS * (T1_NEUT_R * 0.8f + ext); val eu = cU + ddU * (T1_NEUT_R * 0.8f + ext)
        for (k in -1..1) {
            val sp = k * 0.25f; val fl = 0.25f + 0.1f * sin(seconds * 2.1f + k)
            t1Rod(f5, ea + tS * 0f, es + tS * sp, eu + tU * sp, ea + ddA * fl, es + ddS * fl + tS * sp * 1.3f, eu + ddU * fl + tU * sp * 1.3f, 0.02f, T1_NEUT, T1_WHITE, 0.9f, 0.2f)
        }
    }
    // uropod at the trailing end
    t1Lit(blob, f5, aN - ddA * 0.72f, cS - ddS * 0.72f + rS * 0.1f, cU - ddU * 0.72f + rU * 0.1f, T1_NEUT, T1_WHITE, 0.85f, 0.15f, 0.16f, 0.16f, 0.16f)
    // the cell body: translucent, flattened against the wall
    GLES20.glDepthMask(false)
    t1Shape(sphere, f5, aN, cS, cU, 1f, 0f, 0f, 0f, rS, rU, T1_NEUT_R, 0.58f, T1_NEUT_R * 1.05f, T1_NEUT, T1_WHITE, 0.5f, 0.15f)
    GLES20.glDepthMask(true)

    // ---- the monocyte, further on at the port side, barely moving
    val aM = 4.8f + 0.15f * sin(seconds * 0.15f); val thM = 205f * DEG
    val pM = i + aM / 16f; val rM = t1WallR(pM, thM) - 0.85f
    val cM = cos(thM); val sM = sin(thM)
    t1Shape(t1Mesh("mono.nuc") { ParamMesh.torusArc(0.45f, 0.62f) }, f5, aM, cM * rM, sM * rM, 0f, -cM, -sM, 1f, 0f, 0f, 0.5f, 0.5f, 0.5f, T1_MONO_NUC, T1_WHITE, 1f, 0.22f)
    for (k in 0 until 4) {
        val a = k * 1.7f + 0.4f
        val bs = cM * rM + (-sM) * 0.95f * cos(a) + (-cM) * 0.2f; val bu = sM * rM + cM * 0.95f * cos(a) + (-sM) * 0.2f
        t1Lit(blob, f5, aM + 1.0f * sin(a), bs, bu, T1_MONO, T1_WHITE, 0.8f, 0.15f, 0.2f + 0.04f * sin(seconds + k), 0.12f, 0.2f)
    }
    GLES20.glDepthMask(false)
    t1Shape(sphere, f5, aM, cM * rM, sM * rM, 1f, 0f, 0f, 0f, -cM, -sM, 1.1f, 0.9f, 1.15f, T1_MONO, T1_WHITE, 0.55f, 0.15f)
    GLES20.glDepthMask(true)
    // ---- bacteria tagged with antibodies, drifting past the monocyte
    val glints = t1Mesh("glints") { t1BuildGlints() }
    for (k in 0 until 3) {
        val ab = 3.2f + k * 0.55f + ((seconds * 0.12f + k * 0.37f) % 1.4f)
        val ang = (160f + 35f * k) * DEG; val rb = 1.9f + 0.25f * k
        val bS = cos(ang) * rb; val bU = sin(ang) * rb
        val tumble = seconds * 0.3f + k
        val zA = cos(tumble); val zS = sin(tumble) * 0.6f; val zU = sin(tumble) * 0.8f
        t1Shape(capsule, f5, ab, bS, bU, zA, zS, zU, 0f, 0.8f, -0.6f, 0.14f, 0.14f, 0.14f, T1_BACT, T1_WHITE, 1f, 0.2f)
        val wzx = f5.dx * zA + f5.sx * zS + f5.ux * zU; val wzy = f5.dy * zA + f5.sy * zS + f5.uy * zU; val wzz = f5.dz * zA + f5.sz * zS + f5.uz * zU
        var wyx = f5.sx * 0.8f - f5.ux * 0.6f; var wyy = f5.sy * 0.8f - f5.uy * 0.6f; var wyz = f5.sz * 0.8f - f5.uz * 0.6f
        val dd = wyx * wzx + wyy * wzy + wyz * wzz; wyx -= dd * wzx; wyy -= dd * wzy; wyz -= dd * wzz
        val yl = sqrt(wyx * wyx + wyy * wyy + wyz * wyz).coerceAtLeast(1e-4f); wyx /= yl; wyy /= yl; wyz /= yl
        val wxx = wyy * wzz - wyz * wzy; val wxy = wyz * wzx - wyx * wzz; val wxz = wyx * wzy - wyy * wzx
        t1BasisModel(fx(f5, ab, bS, bU), fy(f5, ab, bS, bU), fz(f5, ab, bS, bU), floatArrayOf(wxx, wxy, wxz), floatArrayOf(wyx, wyy, wyz), floatArrayOf(wzx, wzy, wzz))
        Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 2.5f, points = true)
        glints.draw(colorShader.positionHandle, colorShader.colorHandle)
    }
    // free antibodies: a faint dust of sub-resolution glints around the monocyte
    val dust = t1Mesh("ab.dust") { val rnd = java.util.Random(4); val nd = 90; val dd = FloatArray(nd * 7)
        for (k in 0 until nd) { val a = rnd.nextFloat() * TAU; val r = 0.6f + rnd.nextFloat() * 1.8f
            t1Put(dd, k, cos(a) * r, sin(a) * r, -(rnd.nextFloat() * 4f), floatArrayOf(1f, 0.92f, 0.5f), 0.7f) }
        PointMesh(dd) }
    t1Color(dust, f5, 3.0f + (seconds * 0.1f) % 1.5f, 0f, 0f, 1.6f, true, 0.8f, depthWrite = false)
}

// ================================================================ stop 6: THE NEURON (12 um rung, then the synapse at 120 nm)
// 1 unit = 8 um. A brain venule (26 um) whose endothelial cells are sealed by tight junctions
// (bright zipper seams), wrapped by astrocyte end-feet; the craft slips out through its wall into
// a clearing in the cortex (neuropil omitted, as in a textbook figure) beside a layer-5 pyramidal
// neuron: a 25 um triangular soma with its nucleus, one thick apical dendrite rising toward the
// pia with oblique branches, a skirt of basal dendrites, spines everywhere, and one axon from the
// hillock: a 40 um bare initial segment, then myelin sleeves (internodes 36 um, fibre 3 um) with
// 1 um nodes of Ranvier. The action potential starts at the initial segment and jumps node to
// node (slowed ~10^5 times to be seen). After the drop to 120 nm (1 unit = 80 nm): a synapse
// seen edge-on - bouton full of 40 nm vesicles, a 28 nm cleft, the spine head with its
// postsynaptic density and receptors; vesicles fuse and release transmitter into the cleft.

private const val T1_V_P0 = 5.3f
private const val T1_V_P1 = 6.3f
private const val T1_V_R = 1.6f
private const val T1_AX_S = 1.3f
private const val T1_AX_U = -1.35f

/** The vessel's centre line (world): a brain microvessel running beside the craft's course, to port. */
private fun StereoBodyRenderer.t1VesselLine(t: Float, out: FloatArray, tan: FloatArray) {
    val f = frameAt(T1_V_P0 + (T1_V_P1 - T1_V_P0) * t)
    val so = -3.3f; val uo = 1.3f
    out[0] = f.cx + f.sx * so + f.ux * uo; out[1] = f.cy + f.sy * so + f.uy * uo; out[2] = f.cz + f.sz * so + f.uz * uo
    tan[0] = f.dx; tan[1] = f.dy; tan[2] = f.dz
}

/** Point at angle ang and radius r around the vessel centre line at t (world). */
private fun StereoBodyRenderer.t1VesselPoint(t: Float, ang: Float, r: Float, out: FloatArray) {
    val c = FloatArray(3); val tn = FloatArray(3)
    t1VesselLine(t, c, tn)
    // n1 = world-ish up made perpendicular to the tangent; n2 = t x n1
    val f = frameAt(T1_V_P1)
    var n1x = f.ux; var n1y = f.uy; var n1z = f.uz
    val d = n1x * tn[0] + n1y * tn[1] + n1z * tn[2]; n1x -= d * tn[0]; n1y -= d * tn[1]; n1z -= d * tn[2]
    val l = sqrt(n1x * n1x + n1y * n1y + n1z * n1z).coerceAtLeast(1e-5f); n1x /= l; n1y /= l; n1z /= l
    val n2x = tn[1] * n1z - tn[2] * n1y; val n2y = tn[2] * n1x - tn[0] * n1z; val n2z = tn[0] * n1y - tn[1] * n1x
    out[0] = c[0] + (n2x * cos(ang) + n1x * sin(ang)) * r
    out[1] = c[1] + (n2y * cos(ang) + n1y * sin(ang)) * r
    out[2] = c[2] + (n2z * cos(ang) + n1z * sin(ang)) * r
}

private class T1Vessel(val tube: T1Batch, val feet: T1Batch, val nuclei: T1Batch, val zips: LineMesh)

private fun StereoBodyRenderer.t1BuildVessel(): T1Vessel {
    val tube = T1Builder()
    tube.surface(40, 28) { u, v, out -> t1VesselPoint(u, v * TAU, T1_V_R, out) }
    val feet = T1Builder(); val nuc = T1Builder()
    val rnd = java.util.Random(12)
    val q = FloatArray(3); val q2 = FloatArray(3); val q3 = FloatArray(3)
    // astrocyte end-feet tiling the outside
    for (k in 0 until 44) {
        val t = 0.04f + 0.92f * (k / 44f) + 0.01f * rnd.nextFloat(); val a = k * 2.4f
        t1VesselPoint(t, a, T1_V_R + 0.1f, q); t1VesselPoint(t + 0.03f, a, T1_V_R + 0.1f, q2); t1VesselPoint(t, a + 0.4f, T1_V_R + 0.1f, q3)
        val lx = sqrt((q2[0] - q[0]).pow(2) + (q2[1] - q[1]).pow(2) + (q2[2] - q[2]).pow(2)).coerceAtLeast(1e-4f)
        val lz = sqrt((q3[0] - q[0]).pow(2) + (q3[1] - q[1]).pow(2) + (q3[2] - q[2]).pow(2)).coerceAtLeast(1e-4f)
        val ex = floatArrayOf((q2[0] - q[0]) / lx * 0.95f, (q2[1] - q[1]) / lx * 0.95f, (q2[2] - q[2]) / lx * 0.95f)
        val ez = floatArrayOf((q3[0] - q[0]) / lz * 0.9f, (q3[1] - q[1]) / lz * 0.9f, (q3[2] - q[2]) / lz * 0.9f)
        val nx = ex[1] * ez[2] - ex[2] * ez[1]; val ny = ex[2] * ez[0] - ex[0] * ez[2]; val nz = ex[0] * ez[1] - ex[1] * ez[0]
        val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
        feet.ellipsoid(q[0], q[1], q[2], ex, floatArrayOf(nx / nl * 0.08f, ny / nl * 0.08f, nz / nl * 0.08f), ez, 7, 12)
    }
    // endothelial nuclei bulging into the lumen
    for (k in 0 until 4) {
        val t = 0.12f + 0.2f * k; val a = 1.2f + 2.1f * k
        t1VesselPoint(t, a, T1_V_R - 0.1f, q); t1VesselPoint(t + 0.04f, a, T1_V_R - 0.1f, q2); t1VesselPoint(t, a + 0.25f, T1_V_R - 0.1f, q3)
        val ex = floatArrayOf((q2[0] - q[0]) * 0.9f, (q2[1] - q[1]) * 0.9f, (q2[2] - q[2]) * 0.9f)
        val ez = floatArrayOf((q3[0] - q[0]) * 1.4f, (q3[1] - q[1]) * 1.4f, (q3[2] - q[2]) * 1.4f)
        val nx = ex[1] * ez[2] - ex[2] * ez[1]; val ny = ex[2] * ez[0] - ex[0] * ez[2]; val nz = ex[0] * ez[1] - ex[1] * ez[0]
        val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
        nuc.ellipsoid(q[0], q[1], q[2], ex, floatArrayOf(nx / nl * 0.15f, ny / nl * 0.15f, nz / nl * 0.15f), ez, 6, 10)
    }
    // tight junctions: seams between the three cells round the lumen, stitched like a zipper
    val z = ArrayList<Float>()
    val zc = floatArrayOf(1f, 0.88f, 0.42f, 1f)
    fun add(p: FloatArray) { z.add(p[0]); z.add(p[1]); z.add(p[2]); z.add(zc[0]); z.add(zc[1]); z.add(zc[2]); z.add(zc[3]) }
    val seams = floatArrayOf(0.4f, 2.5f, 4.6f)
    val n = 120
    for (sa in seams) {
        for (k in 0 until n) {
            val t0 = k.toFloat() / n; val t1 = (k + 1f) / n
            val w0 = sa + 0.12f * sin(t0 * 9f); val w1 = sa + 0.12f * sin(t1 * 9f)
            t1VesselPoint(t0, w0, T1_V_R - 0.02f, q); add(q); t1VesselPoint(t1, w1, T1_V_R - 0.02f, q); add(q)
            if (k % 2 == 0) {               // the stitches across the seam
                t1VesselPoint(t0, w0 - 0.07f, T1_V_R - 0.02f, q); add(q); t1VesselPoint(t0, w0 + 0.07f, T1_V_R - 0.02f, q); add(q)
            }
        }
    }
    for (tb in floatArrayOf(0.3f, 0.62f)) {           // two end-to-end seams between cells along the vessel
        for (k in 0 until 60) {
            val a0 = TAU * k / 60f; val a1 = TAU * (k + 1) / 60f
            t1VesselPoint(tb + 0.01f * sin(a0 * 5f), a0, T1_V_R - 0.02f, q); add(q)
            t1VesselPoint(tb + 0.01f * sin(a1 * 5f), a1, T1_V_R - 0.02f, q); add(q)
            if (k % 2 == 0) { t1VesselPoint(tb - 0.012f, a0, T1_V_R - 0.02f, q); add(q); t1VesselPoint(tb + 0.012f, a0, T1_V_R - 0.02f, q); add(q) }
        }
    }
    // an astrocyte beside the vessel, its processes ending in the end-feet
    run {
        val c = FloatArray(3); val tn = FloatArray(3)
        t1VesselLine(0.62f, c, tn)
        val f = frameAt(T1_V_P0 + (T1_V_P1 - T1_V_P0) * 0.62f)
        val ax = c[0] - f.sx * 1.2f + f.ux * 2.6f; val ay = c[1] - f.sy * 1.2f + f.uy * 2.6f; val az = c[2] - f.sz * 1.2f + f.uz * 2.6f
        feet.ellipsoid(ax, ay, az, floatArrayOf(0.5f, 0f, 0f), floatArrayOf(0f, 0.45f, 0f), floatArrayOf(0f, 0f, 0.5f), 7, 10)
        for (k in 0 until 7) {
            val t = 0.45f + 0.05f * k; val a = 0.9f + 0.5f * (k % 3)
            t1VesselPoint(t, a, T1_V_R + 0.2f, q)
            feet.rod(ax, ay, az, q[0], q[1], q[2], 0.07f, 4, 6)
        }
        for (k in 0 until 4) {
            val th = k * 1.6f
            feet.rod(ax, ay, az, ax + cos(th) * 1.6f * f.sx + f.dx * sin(th) * 1.6f, ay + cos(th) * 1.6f * f.sy + f.dy * sin(th) * 1.6f + 0.8f, az + cos(th) * 1.6f * f.sz + f.dz * sin(th) * 1.6f, 0.06f, 4, 6)
        }
    }
    return T1Vessel(tube.build(), feet.build(), nuc.build(), LineMesh(z.toFloatArray()))
}

/** A world point on the axon's course: parallel to the rail at (side T1_AX_S, up T1_AX_U) from the node. */
private fun StereoBodyRenderer.t1AxonPoint(i: Int, a: Float, out: FloatArray) {
    val f = frameAt(i + a / 16f)
    out[0] = f.cx + f.sx * T1_AX_S + f.ux * T1_AX_U; out[1] = f.cy + f.sy * T1_AX_S + f.uy * T1_AX_U; out[2] = f.cz + f.sz * T1_AX_S + f.uz * T1_AX_U
}

private class T1Neuron(val soma: T1Batch, val body: T1Batch, val nucleus: T1Batch, val nucleolus: T1Batch, val axon: T1Batch, val myelin: T1Batch, val spines: PointMesh, val channels: PointMesh)

private const val T1_AIS0 = 3.8f
private val T1_NODES = floatArrayOf(7.8f, 12.4f, 17.0f)

private fun StereoBodyRenderer.t1BuildNeuron(i: Int): T1Neuron {
    val f6 = frameAt(i.toFloat())
    fun w(a: Float, s: Float, u: Float, out: FloatArray) { out[0] = fx(f6, a, s, u); out[1] = fy(f6, a, s, u); out[2] = fz(f6, a, s, u) }
    val sa = 2.6f; val ss = 2.3f; val su = 1.1f
    val somaB = T1Builder(); val body = T1Builder(); val nuc = T1Builder(); val nol = T1Builder()
    val q = FloatArray(3); val q2 = FloatArray(3)
    // soma: a pyramid-shaped teardrop, base down, apex up into the apical dendrite
    somaB.surface(24, 30) { t, v, out ->
        val phi = v * TAU
        var r = 1.15f * sin(PI_F * t.pow(0.7f)).coerceAtLeast(0f).pow(0.8f) * (1f - 0.72f * t) + 0.28f * t
        r *= 1f + 0.1f * cos(3f * phi)
        w(sa + r * cos(phi), ss + r * sin(phi), su - 1.55f + 3.1f * t, out)
    }
    w(sa, ss, su - 0.35f, q)
    nuc.ellipsoid(q[0], q[1], q[2], floatArrayOf(f6.dx * 0.62f, f6.dy * 0.62f, f6.dz * 0.62f), floatArrayOf(f6.ux * 0.66f, f6.uy * 0.66f, f6.uz * 0.66f),
        floatArrayOf(f6.sx * 0.62f, f6.sy * 0.62f, f6.sz * 0.62f), 10, 14)
    w(sa + 0.12f, ss - 0.1f, su - 0.2f, q)
    nol.ellipsoid(q[0], q[1], q[2], floatArrayOf(f6.dx * 0.16f, f6.dy * 0.16f, f6.dz * 0.16f), floatArrayOf(f6.ux * 0.16f, f6.uy * 0.16f, f6.uz * 0.16f),
        floatArrayOf(f6.sx * 0.16f, f6.sy * 0.16f, f6.sz * 0.16f), 6, 8)
    // dendrites (frame-local polylines), with spines along them
    val spines = ArrayList<Float>()
    val sc = floatArrayOf(0.86f, 0.76f, 1f, 1f)
    val rnd = java.util.Random(66)
    fun dend(a0: Float, s0: Float, u0: Float, a1: Float, s1: Float, u1: Float, r: Float) {
        w(a0, s0, u0, q); w(a1, s1, u1, q2)
        body.rod(q[0], q[1], q[2], q2[0], q2[1], q2[2], r, 6, 8)
        val len = sqrt((a1 - a0) * (a1 - a0) + (s1 - s0) * (s1 - s0) + (u1 - u0) * (u1 - u0))
        val nsp = (len / 0.22f).toInt()
        for (k in 0 until nsp) {
            val t = (k + 0.5f) / nsp
            val pa = a0 + (a1 - a0) * t + (rnd.nextFloat() - 0.5f) * 2f * (r + 0.1f)
            val ps = s0 + (s1 - s0) * t + (rnd.nextFloat() - 0.5f) * 2f * (r + 0.1f)
            val pu = u0 + (u1 - u0) * t + (rnd.nextFloat() - 0.5f) * 2f * (r + 0.1f)
            w(pa, ps, pu, q)
            spines.add(q[0]); spines.add(q[1]); spines.add(q[2]); spines.add(sc[0]); spines.add(sc[1]); spines.add(sc[2]); spines.add(sc[3])
        }
    }
    // apical dendrite and its oblique branches
    val ap = floatArrayOf(sa, ss, su + 1.5f, sa - 0.1f, ss + 0.1f, su + 3.3f, sa - 0.2f, ss + 0.2f, su + 5.2f, sa - 0.3f, ss + 0.35f, su + 7.6f)
    val apr = floatArrayOf(0.28f, 0.24f, 0.2f)
    for (k in 0 until 3) dend(ap[3 * k], ap[3 * k + 1], ap[3 * k + 2], ap[3 * k + 3], ap[3 * k + 4], ap[3 * k + 5], apr[k])
    dend(sa - 0.1f, ss + 0.1f, su + 2.6f, sa + 1.6f, ss + 0.9f, su + 3.6f, 0.1f)
    dend(sa - 0.15f, ss + 0.15f, su + 3.9f, sa - 1.9f, ss + 0.7f, su + 4.8f, 0.09f)
    dend(sa - 0.2f, ss + 0.25f, su + 5.0f, sa + 1.2f, ss + 1.6f, su + 6.0f, 0.08f)
    // basal dendrites: six from the base, each forking once
    for (k in 0 until 6) {
        val az = (60f * k + 15f) * DEG
        var da = cos(az); var ds = sin(az); val du = -0.55f
        val lean = if (ds < -0.3f) 0.3f else if (ds < 0.2f) 0.7f else 1f     // keep clear of the craft's lane
        val b0a = sa + 0.6f * da; val b0s = ss + 0.6f * ds; val b0u = su - 1.2f
        val len = 1.8f * lean
        val dl = sqrt(da * da + ds * ds + du * du); da /= dl; ds /= dl
        val ea = b0a + da * len; val es = b0s + ds * len; val eu = b0u + du / dl * len
        dend(b0a, b0s, b0u, ea, es, eu, 0.13f)
        for (sg in SIGNS) {
            val ra = cos(az + sg * 0.6f); val rs = sin(az + sg * 0.6f)
            val l2 = 1.4f * lean
            dend(ea, es, eu, ea + ra * l2, (es + rs * l2).coerceAtLeast(1.2f), eu - 0.4f * l2, 0.08f)
        }
    }
    // axon hillock (a cone out of the soma's base) curving into the initial segment
    val ax = T1Builder(); val my = T1Builder()
    w(sa, ss, su - 1.45f, q)
    val hill = FloatArray(3); t1AxonPoint(i, T1_AIS0, hill)
    ax.surface(10, 12) { t, v, out ->
        val c0 = FloatArray(3); w(sa + 0.9f * t, ss - 0.5f * t, su - 1.45f - 0.35f * t, c0)
        val px = c0[0] + (hill[0] - c0[0]) * t * t; val py = c0[1] + (hill[1] - c0[1]) * t * t; val pz = c0[2] + (hill[2] - c0[2]) * t * t
        val r = 0.36f + (0.125f - 0.36f) * t.pow(0.6f)
        val phi = v * TAU
        out[0] = px + (f6.sx * cos(phi) + f6.ux * sin(phi)) * r
        out[1] = py + (f6.sy * cos(phi) + f6.uy * sin(phi)) * r
        out[2] = pz + (f6.sz * cos(phi) + f6.uz * sin(phi)) * r
    }
    // the axon itself, a 2 um tube along the craft's course
    val aEnd = 18f
    ax.surface(60, 10) { t, v, out ->
        val a = T1_AIS0 + (aEnd - T1_AIS0) * t
        val f = frameAt(i + a / 16f); val phi = v * TAU
        val px = f.cx + f.sx * T1_AX_S + f.ux * T1_AX_U; val py = f.cy + f.sy * T1_AX_S + f.uy * T1_AX_U; val pz = f.cz + f.sz * T1_AX_S + f.uz * T1_AX_U
        out[0] = px + (f.sx * cos(phi) + f.ux * sin(phi)) * 0.125f
        out[1] = py + (f.sy * cos(phi) + f.uy * sin(phi)) * 0.125f
        out[2] = pz + (f.sz * cos(phi) + f.uz * sin(phi)) * 0.125f
    }
    // myelin sleeves between the nodes (rounded, paranodal ends)
    for (k in 0 until 2) {
        val m0 = T1_NODES[k] + 0.07f; val m1 = T1_NODES[k + 1] - 0.07f
        my.surface(40, 14) { t, v, out ->
            val a = m0 + (m1 - m0) * t
            val f = frameAt(i + a / 16f); val phi = v * TAU
            val e = abs(2f * t - 1f)
            val r = 0.125f + 0.07f * sqrt((1f - e.pow(10)).coerceAtLeast(0f))
            val px = f.cx + f.sx * T1_AX_S + f.ux * T1_AX_U; val py = f.cy + f.sy * T1_AX_S + f.uy * T1_AX_U; val pz = f.cz + f.sz * T1_AX_S + f.uz * T1_AX_U
            out[0] = px + (f.sx * cos(phi) + f.ux * sin(phi)) * r
            out[1] = py + (f.sy * cos(phi) + f.uy * sin(phi)) * r
            out[2] = pz + (f.sz * cos(phi) + f.uz * sin(phi)) * r
        }
    }
    // sodium channels packed at each node
    val ch = ArrayList<Float>()
    val cc = floatArrayOf(1f, 0.86f, 0.35f, 1f)
    for (nd in T1_NODES) {
        val f = frameAt(i + nd / 16f)
        for (k in 0 until 14) {
            val phi = TAU * k / 14f; val a = nd + (if (k % 2 == 0) -0.03f else 0.03f)
            val g = frameAt(i + a / 16f)
            ch.add(g.cx + g.sx * T1_AX_S + g.ux * T1_AX_U + (f.sx * cos(phi) + f.ux * sin(phi)) * 0.14f)
            ch.add(g.cy + g.sy * T1_AX_S + g.uy * T1_AX_U + (f.sy * cos(phi) + f.uy * sin(phi)) * 0.14f)
            ch.add(g.cz + g.sz * T1_AX_S + g.uz * T1_AX_U + (f.sz * cos(phi) + f.uz * sin(phi)) * 0.14f)
            ch.add(cc[0]); ch.add(cc[1]); ch.add(cc[2]); ch.add(cc[3])
        }
    }
    return T1Neuron(somaB.build(cullBack = true), body.build(), nuc.build(), nol.build(), ax.build(), my.build(), PointMesh(spines.toFloatArray()), PointMesh(ch.toFloatArray()))
}

private class T1Synapse(val vesicles: T1Batch, val receptors: T1Batch)

private fun t1BuildSynapse(): T1Synapse {
    val ves = T1Builder(); val rec = T1Builder()
    val rnd = java.util.Random(8)
    // local coords (x = side, y = up, z = -along); bouton centre (s 3.2, u 2.6), radii (3.0, 2.4, 3.2 along)
    var placed = 0; var tries = 0
    val pts = ArrayList<FloatArray>()
    while (placed < 24 && tries < 3000) {
        tries++
        val x = 3.2f + (rnd.nextFloat() * 2f - 1f) * 2.4f; val y = 0.52f + rnd.nextFloat() * 1.9f; val z = (rnd.nextFloat() * 2f - 1f) * 2.4f
        val e = ((x - 3.2f) / 2.7f).pow(2) + ((y - 2.6f) / 2.1f).pow(2) + (z / 2.9f).pow(2)
        if (e > 1f) continue
        if (pts.any { (it[0] - x).pow(2) + (it[1] - y).pow(2) + (it[2] - z).pow(2) < 0.36f }) continue
        pts.add(floatArrayOf(x, y, z)); placed++
    }
    // a row docked at the active zone
    for (k in 0 until 5) pts.add(floatArrayOf(2.2f + 0.5f * k, 0.5f, (k % 2) * 0.5f - 0.25f))
    for (p in pts) ves.ellipsoid(p[0], p[1], p[2], floatArrayOf(0.25f, 0f, 0f), floatArrayOf(0f, 0.25f, 0f), floatArrayOf(0f, 0f, 0.25f), 6, 8)
    for (k in 0 until 22) {
        val a = rnd.nextFloat() * TAU; val r = sqrt(rnd.nextFloat()) * 1.5f
        rec.ellipsoid(3.2f + cos(a) * r, -0.07f, sin(a) * r, floatArrayOf(0.06f, 0f, 0f), floatArrayOf(0f, 0.1f, 0f), floatArrayOf(0f, 0f, 0.06f), 4, 6)
    }
    return T1Synapse(ves.build(), rec.build())
}

internal fun StereoBodyRenderer.drawNeuron(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp < 5.25f || rp > 6.95f) return
    val keepL = landmarkFade; val keepC = colorShader.globalFade
    val ow = t1ShipOffWorld(); val ox = ow[0]; val oy = ow[1]; val oz = ow[2]
    // ---- the blood-brain barrier
    val vVis = t1Smooth(5.25f, 5.4f, rp) * (1f - t1Smooth(6.19f, 6.25f, rp))
    if (vVis > 0.01f) {
        landmarkFade = keepL * vVis; colorShader.globalFade = keepC * vVis
        val vs = t1Mesh("bbb") { t1BuildVessel() }
        t1LitWorld(vs.nuclei, T1_ENDO_NUC, T1_WHITE, 1f, 0.15f, ox, oy, oz)
        t1Color(vs.zips, null, 0f, 0f, 0f, 2.5f, false, 1f, ox = ox, oy = oy, oz = oz)
        // red cells in the lumen, off the axis
        val c = FloatArray(3); val tn = FloatArray(3)
        for (k in 0 until 6) {
            val t = ((seconds * 0.04f + k * 0.167f) % 1f)
            val ang = k * 2.3f + 0.6f
            t1VesselPoint(t, ang, 0.8f, c); t1VesselLine(t, FloatArray(3), tn)
            val tb = seconds * 0.4f + k
            drawBasis(c[0] + ox, c[1] + oy, c[2] + oz, tn[0], tn[1], tn[2], sin(tb), cos(tb), 0.3f, 0.47f, 0.47f, 0.47f, rbc, T1_TRNA_DEOXY, COL_RBC_RIM, 1f, 0f, 0f)
        }
        GLES20.glDepthMask(false)
        t1LitWorld(vs.feet, T1_ENDFOOT, T1_WHITE, 0.4f, 0.2f, ox, oy, oz)
        t1LitWorld(vs.tube, T1_VESSEL, T1_WHITE, 0.35f, 0.3f, ox, oy, oz)
        GLES20.glDepthMask(true)
    }
    // ---- the pyramidal neuron
    val nVis = t1Smooth(5.4f, 5.6f, rp) * (1f - t1Smooth(6.19f, 6.25f, rp))
    if (nVis > 0.01f) {
        landmarkFade = keepL * nVis; colorShader.globalFade = keepC * nVis
        val nr = t1Mesh("neuron") { t1BuildNeuron(i) }
        t1LitWorld(nr.nucleus, T1_SOMA_NUC, T1_WHITE, 1f, 0.2f, ox, oy, oz)
        t1LitWorld(nr.nucleolus, T1_NEUT, T1_WHITE, 1f, 0.4f, ox, oy, oz)
        t1LitWorld(nr.axon, T1_SOMA, T1_WHITE, 1f, 0.22f, ox, oy, oz)
        t1LitWorld(nr.myelin, T1_MYELIN, T1_WHITE, 1f, 0.22f, ox, oy, oz)
        t1Color(nr.spines, null, 0f, 0f, 0f, 3f, true, 1f, ox = ox, oy = oy, oz = oz)
        t1Color(nr.channels, null, 0f, 0f, 0f, 2.5f, true, 1f, ox = ox, oy = oy, oz = oz)
        // the action potential: along the initial segment, then node to node
        val per = 3.2f
        val t = seconds % per
        val q = FloatArray(3)
        if (t < 0.45f) {
            val a = T1_AIS0 + (T1_NODES[0] - T1_AIS0) * (t / 0.45f)
            t1AxonPoint(i, a, q)
            val q2 = FloatArray(3); t1AxonPoint(i, a - 0.9f, q2)
            drawStrut(q2[0] + ox, q2[1] + oy, q2[2] + oz, q[0] + ox, q[1] + oy, q[2] + oz, 0.16f, T1_AP, T1_AP, 1.4f)
            drawSphereAt(q[0] + ox, q[1] + oy, q[2] + oz, 0.24f, 0.24f, 0.24f, T1_AP, T1_AP, 0.9f, 0f, 0f, 1f, 0f, blob, 0f, 1.6f)
        } else {
            for (k in T1_NODES.indices) {
                val t0 = 0.45f + 0.27f * k
                val env = if (t < t0) 0f else exp(-(t - t0) / 0.1f)
                if (env > 0.03f) {
                    t1AxonPoint(i, T1_NODES[k], q)
                    drawSphereAt(q[0] + ox, q[1] + oy, q[2] + oz, 0.3f, 0.3f, 0.3f, T1_AP, T1_AP, env, 0f, 0f, 1f, 0f, blob, 0f, 1.8f)
                }
                // the passive current racing under the sleeve toward the next node
                if (k < 2 && t > t0 && t < t0 + 0.14f) {
                    val a0 = T1_NODES[k] + 0.2f; val a1 = T1_NODES[k + 1] - 0.2f
                    val q2 = FloatArray(3); t1AxonPoint(i, a0, q); t1AxonPoint(i, a1, q2)
                    GLES20.glDepthMask(false)
                    drawStrut(q[0] + ox, q[1] + oy, q[2] + oz, q2[0] + ox, q2[1] + oy, q2[2] + oz, 0.24f, T1_AP, T1_AP, 0.6f)
                    GLES20.glDepthMask(true)
                }
            }
        }
        GLES20.glDepthMask(false)
        t1LitWorld(nr.body, T1_SOMA, T1_WHITE, 1f, 0.22f, ox, oy, oz)
        GLES20.glDepthMask(false)
        t1LitWorld(nr.soma, T1_SOMA, T1_WHITE, 0.72f, 0.22f, ox, oy, oz)
        GLES20.glDepthMask(true)
        GLES20.glDepthMask(true)
    }
    // ---- the synapse, after the drop to 120 nm
    val sVis = t1Smooth(6.22f, 6.3f, rp) * (1f - t1Smooth(6.85f, 6.95f, rp))
    if (sVis > 0.01f) {
        landmarkFade = keepL * sVis; colorShader.globalFade = keepC * sVis
        val fsn = frameAt(6.56f)
        val off = t1ShipOff(fsn); val so = off[0]; val uo = off[1]
        val syn = t1Mesh("synapse") { t1BuildSynapse() }
        t1Lit(syn.vesicles, fsn, 0f, so, uo, T1_SYN_VES, T1_WHITE, 1f, 0.25f)
        t1Lit(syn.receptors, fsn, 0f, so, uo, T1_BACT, T1_WHITE, 1f, 0.25f)
        t1Lit(sphere, fsn, 0f, so + 3.2f, uo - 0.33f, T1_PSD, T1_WHITE, 1f, 0.25f, 1.6f, 0.12f, 1.6f)
        // a vesicle fusing at the active zone, and its transmitter spreading through the cleft
        val cyc = (seconds % 1.4f) / 1.4f
        val fuse = t1Smooth(0f, 0.3f, cyc)
        if (cyc < 0.35f) t1Lit(sphere, fsn, 0.4f, so + 3.0f, uo + 0.55f - 0.3f * fuse, T1_SYN_VES, T1_WHITE, 1f, 0.3f, 0.25f * (1f - 0.5f * fuse), 0.25f * (1f - 0.5f * fuse), 0.25f * (1f - 0.5f * fuse))
        if (cyc > 0.25f) {
            val spread = (cyc - 0.25f) / 0.75f
            val d = t1Dyn.data
            var v = 0
            for (k in 0 until 48) {
                val a = t1Hash(k) * TAU; val r = spread * (0.2f + 1.8f * t1Hash(k + 99))
                val ps = so + 3.0f + cos(a) * r; val pa = 0.4f + sin(a) * r; val pu = uo + 0.02f + 0.1f * (t1Hash(k + 7) - 0.5f)
                v = t1Put(d, v, fx(fsn, pa, ps, pu), fy(fsn, pa, ps, pu), fz(fsn, pa, ps, pu), T1_AP, 1f - spread * 0.7f)
            }
            t1DynDraw(v, GLES20.GL_POINTS, 3f)
        }
        GLES20.glDepthMask(false)
        t1Lit(sphere, fsn, 0f, so + 3.2f, uo + 2.6f, T1_BOUTON, T1_WHITE, 0.3f, 0.15f, 3.0f, 2.4f, 3.2f)
        t1Lit(sphere, fsn, 0f, so + 3.2f, uo - 2.3f, T1_SPINE, T1_WHITE, 0.3f, 0.15f, 2.6f, 2.15f, 2.8f)
        GLES20.glDepthMask(true)
    }
    landmarkFade = keepL; colorShader.globalFade = keepC
}

private val T1_TRNA_DEOXY = floatArrayOf(0.74f, 0.10f, 0.14f, 1f)

// ================================================================ stop 7: THE MEMBRANE (120 nm rung)
// 1 unit = 80 nm. A lipid bilayer at true thickness (7.5 nm = 0.094 units: the craft is ~15x
// thicker), heads (amber) outward, two tails each (teal) inward, cholesterol among the tails;
// proteins at true size: Na+/K+-ATPase towers with their big cytoplasmic domain, receptors with
// glycosylated extracellular domains, pentameric channels; a glycocalyx of short branched sugar
// chains on the outer face. The craft enters by endocytosis: the membrane dimples round it into a
// clathrin-coated pit (the geodesic cage on the cytoplasmic side), dynamin collars the neck, and a
// 170 nm vesicle pinches off with the craft inside; the coat falls away and the craft leaves the
// vesicle into the cytoplasm.

private const val T1_MEM_P = 7.02f
private const val T1_RV = 1.05f
private const val T1_FIL = 0.22f
private const val T1_PIT_R = 1.6f
private const val T1_LEAF = 0.047f

private class T1Membrane {
    val pit = T1DynSurface(30, 40)
    val heads = DynMesh(8200)
    var headVerts = 0
    val cage = DynMesh(4200)
    var cageVerts = 0
    var lastH = 99f; var lastPsi = -1f
    val pr = FloatArray(80); val pz = FloatArray(80); val nr = FloatArray(80); val nz = FloatArray(80)
    var np = 0
}
private var t1Mem: T1Membrane? = null

/** Unit-sphere clathrin lattice: the dual of a subdivided icosahedron (hexagons and 12 pentagons). */
private val t1Clathrin: FloatArray by lazy {
    val t = ((1.0 + sqrt(5.0)) / 2.0).toFloat()
    val vs = arrayListOf(
        floatArrayOf(-1f, t, 0f), floatArrayOf(1f, t, 0f), floatArrayOf(-1f, -t, 0f), floatArrayOf(1f, -t, 0f),
        floatArrayOf(0f, -1f, t), floatArrayOf(0f, 1f, t), floatArrayOf(0f, -1f, -t), floatArrayOf(0f, 1f, -t),
        floatArrayOf(t, 0f, -1f), floatArrayOf(t, 0f, 1f), floatArrayOf(-t, 0f, -1f), floatArrayOf(-t, 0f, 1f))
    for (v in vs) { val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); v[0] /= l; v[1] /= l; v[2] /= l }
    var faces = arrayListOf(
        intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
        intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
        intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
        intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1))
    repeat(2) {
        val mid = HashMap<Long, Int>()
        fun m(a: Int, b: Int): Int {
            val key = min(a, b).toLong() * 100000L + max(a, b)
            return mid.getOrPut(key) {
                val p = vs[a]; val q = vs[b]
                val x = p[0] + q[0]; val y = p[1] + q[1]; val z = p[2] + q[2]; val l = sqrt(x * x + y * y + z * z)
                vs.add(floatArrayOf(x / l, y / l, z / l)); vs.size - 1
            }
        }
        val nf = ArrayList<IntArray>()
        for (f in faces) {
            val a = m(f[0], f[1]); val b = m(f[1], f[2]); val c = m(f[2], f[0])
            nf.add(intArrayOf(f[0], a, c)); nf.add(intArrayOf(f[1], b, a)); nf.add(intArrayOf(f[2], c, b)); nf.add(intArrayOf(a, b, c))
        }
        faces = nf
    }
    // dual: centroid per face; edge between faces sharing an edge
    val cen = faces.map { f -> val x = vs[f[0]][0] + vs[f[1]][0] + vs[f[2]][0]; val y = vs[f[0]][1] + vs[f[1]][1] + vs[f[2]][1]; val z = vs[f[0]][2] + vs[f[1]][2] + vs[f[2]][2]
        val l = sqrt(x * x + y * y + z * z); floatArrayOf(x / l, y / l, z / l) }
    val edgeFace = HashMap<Long, Int>()
    val out = ArrayList<Float>()
    for ((fi, f) in faces.withIndex()) for (e in 0 until 3) {
        val a = f[e]; val b = f[(e + 1) % 3]
        val key = min(a, b).toLong() * 100000L + max(a, b)
        val other = edgeFace[key]
        if (other == null) edgeFace[key] = fi else { val p = cen[fi]; val q = cen[other]; out.add(p[0]); out.add(p[1]); out.add(p[2]); out.add(q[0]); out.add(q[1]); out.add(q[2]) }
    }
    out.toFloatArray()
}

/** Profile of the membrane's mid-surface around the pit (r, along, extracellular normal). */
private fun t1PitProfile(h: Float, m: T1Membrane, pinched: Boolean) {
    val flat = h <= -T1_RV + 0.01f || pinched
    val n = 70
    if (flat) {
        for (k in 0 until n) { m.pr[k] = T1_PIT_R * k / (n - 1); m.pz[k] = 0f; m.nr[k] = 0f; m.nz[k] = -1f }
        m.np = n; return
    }
    val f = T1_FIL
    val dz = h - f
    val rF = sqrt(((T1_RV + f) * (T1_RV + f) - dz * dz).coerceAtLeast(0f))
    val psiT = atan2(rF, f - h)
    val l1 = T1_RV * psiT; val l2 = f * psiT; val l3 = (T1_PIT_R - rF).coerceAtLeast(0f)
    val tot = l1 + l2 + l3
    for (k in 0 until n) {
        val s = tot * k / (n - 1)
        if (s <= l1) {
            val ps = s / T1_RV
            m.pr[k] = T1_RV * sin(ps); m.pz[k] = h + T1_RV * cos(ps); m.nr[k] = -sin(ps); m.nz[k] = -cos(ps)
        } else if (s <= l1 + l2) {
            val ph = psiT - (s - l1) / f
            m.pr[k] = rF - f * sin(ph); m.pz[k] = f - f * cos(ph); m.nr[k] = -sin(ph); m.nz[k] = -cos(ph)
        } else {
            m.pr[k] = rF + (s - l1 - l2); m.pz[k] = 0f; m.nr[k] = 0f; m.nz[k] = -1f
        }
    }
    m.np = n
}

private class T1MemStatic(val headsA: PointMesh, val headsB: PointMesh, val tails: LineMesh, val glyco: LineMesh, val proteins: T1Batch, val channels: T1Batch, val pumps: FloatArray,
    val transporters: T1Batch, val pumpBatch: T1Batch)

private fun t1BuildMembrane(): T1MemStatic {
    val rnd = java.util.Random(77)
    val hA = ArrayList<Float>(); val hB = ArrayList<Float>(); val tails = ArrayList<Float>(); val gly = ArrayList<Float>()
    val hc = floatArrayOf(1f, 0.78f, 0.45f, 0.95f); val tc = floatArrayOf(0.35f, 0.85f, 0.80f, 0.75f); val chol = floatArrayOf(0.96f, 0.96f, 0.55f, 0.95f)
    val gc = floatArrayOf(0.72f, 0.96f, 0.70f, 0.6f)
    fun add(l: ArrayList<Float>, x: Float, y: Float, z: Float, c: FloatArray) { l.add(x); l.add(y); l.add(z); l.add(c[0]); l.add(c[1]); l.add(c[2]); l.add(c[3]) }
    // protein positions first (so lipids can leave room)
    val prot = ArrayList<FloatArray>()      // x, y, kind (0 pump, 1 receptor, 2 channel)
    var tries = 0
    // a crowded mosaic: membranes are about half protein (true sizes; ~5 per 1000 nm^2 here)
    val want = intArrayOf(8, 90, 40, 60, 40)
    for (kind in 0..4) {
        var placed = 0
        while (placed < want[kind] && tries < 40000) {
            tries++
            val r = sqrt(1.95f * 1.95f + rnd.nextFloat() * (4.6f * 4.6f - 1.95f * 1.95f)); val a = rnd.nextFloat() * TAU
            val x = cos(a) * r; val y = sin(a) * r
            if (prot.any { (it[0] - x).pow(2) + (it[1] - y).pow(2) < 0.19f * 0.19f }) continue
            prot.add(floatArrayOf(x, y, kind.toFloat())); placed++
        }
    }
    var r = T1_PIT_R + 0.02f
    var ring = 0
    while (r < 4.8f) {
        val dr = 0.055f * (1f + r / 3f)
        val cnt = (TAU * r / dr).toInt()
        for (k in 0 until cnt) {
            val a = TAU * (k + 0.5f * (ring % 2)) / cnt + (rnd.nextFloat() - 0.5f) * 0.3f / cnt
            val rr = r + (rnd.nextFloat() - 0.5f) * dr * 0.3f
            val x = cos(a) * rr; val y = sin(a) * rr
            if (prot.any { (it[0] - x).pow(2) + (it[1] - y).pow(2) < 0.075f * 0.075f }) continue
            for (side in 0..1) {
                val sg = if (side == 0) 1f else -1f             // +z = extracellular (toward the craft)
                add(if ((k + side) % 2 == 0) hA else hB, x, y, sg * T1_LEAF, hc)
                val isChol = rnd.nextFloat() < 0.1f
                if (isChol) { add(tails, x, y, sg * 0.036f, chol); add(tails, x, y, sg * 0.016f, chol) }
                else for (tw in SIGNS) {
                    val ox = -sin(a) * 0.006f * tw; val oy = cos(a) * 0.006f * tw
                    add(tails, x + ox, y + oy, sg * 0.040f, tc); add(tails, x + ox * 1.3f, y + oy * 1.3f, sg * 0.006f, tc)
                }
                if (side == 0 && rnd.nextFloat() < 0.07f) {     // glycolipid sugar chains, branching
                    val z0 = T1_LEAF + 0.01f
                    val z1 = z0 + 0.05f + rnd.nextFloat() * 0.05f
                    val x1 = x + (rnd.nextFloat() - 0.5f) * 0.04f; val y1 = y + (rnd.nextFloat() - 0.5f) * 0.04f
                    add(gly, x, y, z0, gc); add(gly, x1, y1, z1, gc)
                    add(gly, x1, y1, z1, gc); add(gly, x1 + (rnd.nextFloat() - 0.5f) * 0.05f, y1 + (rnd.nextFloat() - 0.5f) * 0.05f, z1 + 0.04f, gc)
                    add(gly, x1, y1, z1, gc); add(gly, x1 + (rnd.nextFloat() - 0.5f) * 0.05f, y1 + (rnd.nextFloat() - 0.5f) * 0.05f, z1 + 0.035f, gc)
                }
            }
        }
        r += dr; ring++
    }
    // receptors (7-TM bundle + glycosylated extracellular domain) and channels (pentamers round a pore)
    val pb = T1Builder(); val cb = T1Builder(); val tb = T1Builder(); val qb = T1Builder()
    val pumps = ArrayList<Float>()
    for (p in prot) {
        val x = p[0]; val y = p[1]
        when (p[2].toInt()) {
            0 -> { pumps.add(x); pumps.add(y) }
            1 -> {
                pb.ellipsoid(x, y, 0f, floatArrayOf(0.045f, 0f, 0f), floatArrayOf(0f, 0.045f, 0f), floatArrayOf(0f, 0f, 0.06f), 5, 8)
                pb.ellipsoid(x, y, 0.1f, floatArrayOf(0.018f, 0f, 0f), floatArrayOf(0f, 0.018f, 0f), floatArrayOf(0f, 0f, 0.05f), 4, 6)      // stalk
                pb.ellipsoid(x + 0.01f, y, 0.17f, floatArrayOf(0.045f, 0f, 0f), floatArrayOf(0f, 0.04f, 0f), floatArrayOf(0f, 0f, 0.035f), 5, 8)   // ligand-binding head
                for (b in 0 until 3) {                       // branched glycans on top
                    val a = b * 2.1f + x
                    val z0 = 0.2f; val x1 = x + cos(a) * 0.03f; val y1 = y + sin(a) * 0.03f
                    add(gly, x, y, z0, gc); add(gly, x1, y1, z0 + 0.05f, gc)
                    add(gly, x1, y1, z0 + 0.05f, gc); add(gly, x1 + cos(a + 0.8f) * 0.025f, y1 + sin(a + 0.8f) * 0.025f, z0 + 0.08f, gc)
                    add(gly, x1, y1, z0 + 0.05f, gc); add(gly, x1 + cos(a - 0.8f) * 0.025f, y1 + sin(a - 0.8f) * 0.025f, z0 + 0.085f, gc)
                }
            }
            2 -> for (k in 0 until 5) {
                val a = TAU * k / 5f
                cb.ellipsoid(x + cos(a) * 0.035f, y + sin(a) * 0.035f, 0.005f, floatArrayOf(0.022f, 0f, 0f), floatArrayOf(0f, 0.022f, 0f), floatArrayOf(0f, 0f, 0.058f), 4, 6)
            }
            3 -> {                                          // carriers: two-lobed, spanning the bilayer
                val a = x * 3.1f + y
                for (sg in SIGNS) tb.ellipsoid(x + cos(a) * 0.028f * sg, y + sin(a) * 0.028f * sg, 0f, floatArrayOf(0.03f, 0f, 0f), floatArrayOf(0f, 0.03f, 0f), floatArrayOf(0f, 0f, 0.06f), 4, 6)
            }
            else -> {                                       // more Na+/K+ pumps: membrane body, big cytoplasmic head, beta subunit
                qb.ellipsoid(x, y, 0f, floatArrayOf(0.055f, 0f, 0f), floatArrayOf(0f, 0.055f, 0f), floatArrayOf(0f, 0f, 0.062f), 5, 8)
                qb.ellipsoid(x, y, -0.125f, floatArrayOf(0.08f, 0f, 0f), floatArrayOf(0f, 0.08f, 0f), floatArrayOf(0f, 0f, 0.075f), 5, 8)
                qb.ellipsoid(x + 0.015f, y, 0.07f, floatArrayOf(0.035f, 0f, 0f), floatArrayOf(0f, 0.035f, 0f), floatArrayOf(0f, 0f, 0.03f), 4, 6)
            }
        }
    }
    return T1MemStatic(PointMesh(hA.toFloatArray()), PointMesh(hB.toFloatArray()), LineMesh(tails.toFloatArray()), LineMesh(gly.toFloatArray()),
        pb.build(), cb.build(), pumps.toFloatArray(), tb.build(), qb.build())
}

internal fun StereoBodyRenderer.drawMembrane(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp < 6.35f || rp > 7.35f) return
    // The membrane stands 25 degrees off square to the course, so its two leaflets and the proteins'
    // profiles read in perspective; it is centred on the craft's lane.
    val f0 = frameAt(T1_MEM_P)
    val off = t1ShipOff(f0)
    val tc = cos(25f * DEG); val ts = sin(25f * DEG)
    val fm = StereoBodyRenderer.Frame(f0.cx + f0.sx * off[0] + f0.ux * off[1], f0.cy + f0.sy * off[0] + f0.uy * off[1], f0.cz + f0.sz * off[0] + f0.uz * off[1],
        f0.dx * tc + f0.sx * ts, f0.dy * tc + f0.sy * ts, f0.dz * tc + f0.sz * ts,
        f0.sx * tc - f0.dx * ts, f0.sy * tc - f0.dy * ts, f0.sz * tc - f0.dz * ts, f0.ux, f0.uy, f0.uz)
    val so = 0f; val uo = 0f
    val m = t1Mem ?: T1Membrane().also { t1Mem = it }
    val st = t1Mesh("membrane") { t1BuildMembrane() }
    val sShip = t1Along(fm, shipX, shipY, shipZ)
    val pinchH = T1_FIL + sqrt(T1_RV * T1_RV + 2f * T1_RV * T1_FIL)
    val h = max(-T1_RV, sShip)
    val pinched = h >= pinchH - 0.01f
    // ---- proteins (opaque)
    t1Lit(st.proteins, fm, 0f, so, uo, T1_RECEPTOR, T1_WHITE, 1f, 0.2f)
    t1Lit(st.channels, fm, 0f, so, uo, T1_ETC, T1_WHITE, 1f, 0.2f)
    t1Lit(st.transporters, fm, 0f, so, uo, T1_BACT, T1_WHITE, 1f, 0.2f)
    t1Lit(st.pumpBatch, fm, 0f, so, uo, T1_PUMP, T1_WHITE, 1f, 0.2f)
    val pumps = st.pumps
    for (k in 0 until pumps.size / 2) {
        val x = so + pumps[2 * k] + 0.03f * sin(seconds * 0.23f + k * 1.7f); val y = uo + pumps[2 * k + 1] + 0.03f * cos(seconds * 0.19f + k)
        t1Lit(sphere, fm, 0f, x, y, T1_PUMP, T1_WHITE, 1f, 0.22f, 0.055f, 0.055f, 0.062f)          // transmembrane body
        t1Lit(sphere, fm, 0.125f, x, y, T1_PUMP, T1_WHITE, 1f, 0.22f, 0.08f, 0.08f, 0.075f)       // cytoplasmic (ATP-binding) domain
        t1Lit(sphere, fm, -0.07f, x + 0.015f, y, T1_RECEPTOR, T1_WHITE, 1f, 0.22f, 0.035f, 0.035f, 0.03f) // beta subunit
    }
    // ---- flat bilayer: heads (jiggling), tails
    val jA = 0.007f * sin(seconds * 8.3f); val jB = 0.007f * cos(seconds * 7.1f)
    t1Color(st.headsA, fm, 0f, so + jA, uo - jB, 2.6f, true)
    t1Color(st.headsB, fm, 0f, so - jB, uo + jA, 2.6f, true)
    if (quality < 2) t1Color(st.tails, fm, 0f, so, uo, 1f, false, 0.9f)
    // ---- the pit (or, once pinched, the flat membrane with the vesicle below)
    if (abs(h - m.lastH) > 0.002f || m.headVerts == 0) {
        m.lastH = h
        t1PitProfile(h, m, pinched)
        // heads along the profile, both leaflets
        val d = m.heads.data
        var v = 0
        var acc = 0f
        val hc = floatArrayOf(1f, 0.78f, 0.45f)
        for (k in 0 until m.np) {
            if (k > 0) acc += sqrt((m.pr[k] - m.pr[k - 1]).pow(2) + (m.pz[k] - m.pz[k - 1]).pow(2))
            if (k > 0 && acc < 0.08f) continue
            acc = 0f
            val r = m.pr[k]
            val cnt = max(1, (TAU * r / 0.085f).toInt())
            for (j in 0 until cnt) {
                if (v + 2 >= 8200) break
                val a = TAU * (j + 0.5f * (k % 2)) / cnt
                for (sg in SIGNS) {
                    val rr = r + m.nr[k] * T1_LEAF * sg; val zz = m.pz[k] + m.nz[k] * T1_LEAF * sg
                    v = t1Put(d, v, cos(a) * rr, sin(a) * rr, -zz, hc, 0.95f)
                }
            }
        }
        m.headVerts = v
        m.pit.update { u, vv, out ->
            val fk = u * (m.np - 1); val k0 = fk.toInt().coerceAtMost(m.np - 2); val t = fk - k0
            val r = m.pr[k0] + (m.pr[k0 + 1] - m.pr[k0]) * t; val z = m.pz[k0] + (m.pz[k0 + 1] - m.pz[k0]) * t
            val a = vv * TAU
            out[0] = cos(a) * r; out[1] = sin(a) * r; out[2] = -z
        }
    }
    // heads of the pit, in the membrane's frame
    t1Model(fm, 0f, so, uo)
    Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    colorShader.use(mvp, 2.6f, points = true)
    m.heads.draw(colorShader.positionHandle, colorShader.colorHandle, GLES20.GL_POINTS, m.headVerts)
    // clathrin coat on the cytoplasmic face of the pit
    val psi = if (pinched) PI_F else if (h <= -T1_RV + 0.01f) 0f else {
        val dz = h - T1_FIL; val rF = sqrt(((T1_RV + T1_FIL).pow(2) - dz * dz).coerceAtLeast(0f)); atan2(rF, T1_FIL - h)
    }
    val coat = if (pinched) 1f - t1Smooth(7.14f, 7.2f, rp) else t1Smooth(0.2f, 0.6f, psi)
    val cz = if (pinched) sShip else h
    if (coat > 0.02f && psi > 0.1f) {
        if (abs(psi - m.lastPsi) > 0.01f) {
            m.lastPsi = psi
            val e = t1Clathrin; val d = m.cage.data
            var v = 0
            val cr = T1_RV + 0.2f
            val cc = floatArrayOf(0.88f, 0.92f, 1f)
            var k = 0
            val cosLim = cos(psi)
            while (k < e.size && v + 2 < 4200) {
                // pole = +along: keep the lattice within psi of it
                if (e[k + 2] >= cosLim && e[k + 5] >= cosLim) {
                    v = t1Put(d, v, e[k] * cr, e[k + 1] * cr, -(e[k + 2] * cr), cc, 0.9f)
                    v = t1Put(d, v, e[k + 3] * cr, e[k + 4] * cr, -(e[k + 5] * cr), cc, 0.9f)
                }
                k += 6
            }
            m.cageVerts = v
        }
        t1Model(fm, cz, so, uo)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        val keep = colorShader.globalFade; colorShader.globalFade = keep * coat
        colorShader.use(mvp, 1f); lineWidth(2f)
        m.cage.draw(colorShader.positionHandle, colorShader.colorHandle, GLES20.GL_LINES, m.cageVerts)
        lineWidth(1f); colorShader.globalFade = keep
    }
    // dynamin collaring the neck just before scission
    if (!pinched && h > T1_FIL) {
        val dz = h - T1_FIL; val rF = sqrt(((T1_RV + T1_FIL).pow(2) - dz * dz).coerceAtLeast(0f))
        val neck = rF - T1_FIL
        if (neck < 0.45f) t1Lit(t1Ring(0.3f), fm, T1_FIL, so, uo, T1_DYNAMIN, T1_WHITE, t1Smooth(0.45f, 0.3f, neck), 0.3f, neck + 0.08f, neck + 0.08f, 0.25f)
    }
    // translucent bilayer cores: the flat sheet, the pit, and after scission the vesicle round the craft
    val core = t1Mesh("mem.core") { ParamMesh(4, 48) { u, v, out -> val r = T1_PIT_R + (4.8f - T1_PIT_R) * u; val a = v * TAU; out[0] = cos(a) * r; out[1] = sin(a) * r; out[2] = 0f } }
    GLES20.glDepthMask(false)
    t1Lit(core, fm, 0f, so, uo, T1_BILAYER, T1_WHITE, 0.3f, 0.12f)
    t1Lit(m.pit, fm, 0f, so, uo, T1_BILAYER, T1_WHITE, 0.3f, 0.12f)
    if (pinched) {
        val va = 1f - t1Smooth(7.18f, 7.26f, rp)
        if (va > 0.01f) {
            t1Lit(sphere, fm, sShip, so, uo, T1_BILAYER, T1_WHITE, 0.3f * va, 0.15f, T1_RV, T1_RV, T1_RV)
            t1Lit(sphere, fm, sShip, so, uo, floatArrayOf(1f, 0.78f, 0.45f, 1f), T1_WHITE, 0.16f * va, 0.2f, T1_RV + T1_LEAF, T1_RV + T1_LEAF, T1_RV + T1_LEAF)
        }
    }
    if (quality < 2) t1Color(st.glyco, fm, 0f, so, uo, 1.5f, false, 0.8f, depthWrite = false)
    GLES20.glDepthMask(true)
}

// ================================================================ stop 8: THE MITOCHONDRION (120 nm rung)
// 1 unit = 80 nm. A mitochondrion 0.5 um across (and microns long) seen from its tip: a
// translucent outer membrane dotted with porins, a 20 nm intermembrane space, the inner boundary
// membrane, and lamellar cristae across the long axis (like bellows): each a flattened sac (two
// membranes round a ~25 nm intracristal space) hanging from the inner membrane by narrow crista
// junctions, alternating sides, leaving the matrix open down the middle. ATP synthase dimers
// line every crista rim (F1 heads 10 nm, into the matrix); on the nearest crista each synthase
// is drawn whole: F0 c-ring in the membrane with the central stalk (the rotor, turning ~1/s here,
// ~100/s in life), the fixed alpha3-beta3 head, the peripheral stalk (stator), protons flowing
// in from the intracristal side. Respiratory-chain complexes sit in the crista membranes; the
// mtDNA nucleoid (a small tangled loop) and matrix granules float between the cristae.

private const val T1_MITO_TIP = 7.93f
private const val T1_MITO_END = 8.8f
private const val T1_MITO_R = 3.1f
private const val T1_IBM_R = 2.85f
private const val T1_CR_IN = 1.0f
private const val T1_CR_OUT = 2.55f
private const val T1_CR_T = 0.16f
private val T1_CRISTAE = FloatArray(9) { 7.99f + it * 0.085f }

private class T1Mito(val outer: T1Batch, val inner: T1Batch, val cristae: T1Batch, val etc: T1Batch, val porins: PointMesh, val heads: PointMesh, val stalks: LineMesh, val dna: LineMesh)

private fun StereoBodyRenderer.t1MitoShell(b: T1Builder, radius: Float, lead: Float) {
    val cap = radius * PI_F / 2f
    val len = (T1_MITO_END - T1_MITO_TIP) * 16f - lead
    b.surface(46, 40) { u, v, out ->
        val s = u * (cap + len - radius)
        val r: Float; val a: Float
        if (s < cap) { val g = s / radius; r = radius * sin(g); a = lead + radius * (1f - cos(g)) }
        else { r = radius; a = lead + radius + (s - cap) }
        t1RailPoint(T1_MITO_TIP + a / 16f, v * TAU, r, out)
    }
}

private fun t1CristaTheta(j: Int) = (if (j % 2 == 0) 90f else 270f) * DEG

private fun StereoBodyRenderer.t1BuildMito(): T1Mito {
    val outer = T1Builder(); val inner = T1Builder(); val cr = T1Builder(); val etc = T1Builder()
    t1MitoShell(outer, T1_MITO_R, 0f)
    t1MitoShell(inner, T1_IBM_R, 0.25f)
    val rc = (T1_CR_IN + T1_CR_OUT) * 0.5f; val hw = (T1_CR_OUT - T1_CR_IN) * 0.5f
    val q = FloatArray(3); val q2 = FloatArray(3)
    val heads = ArrayList<Float>(); val stalks = ArrayList<Float>()
    val hc = floatArrayOf(0.55f, 0.92f, 0.86f, 1f); val sc = floatArrayOf(0.95f, 0.92f, 0.62f, 1f)
    fun add(l: ArrayList<Float>, p: FloatArray, c: FloatArray) { l.add(p[0]); l.add(p[1]); l.add(p[2]); l.add(c[0]); l.add(c[1]); l.add(c[2]); l.add(c[3]) }
    val rnd = java.util.Random(88)
    for ((j, pj) in T1_CRISTAE.withIndex()) {
        val thc = t1CristaTheta(j); val span = 125f * DEG
        val f = frameAt(pj)
        // the sac: a flattened tube (superellipse section) swept round 250 degrees, closed at its ends
        cr.surface(16, 40) { u, v, out ->
            val b = u * TAU; val th = thc - span + 2f * span * v
            val k = sin(PI_F * v).coerceAtLeast(0f).pow(0.3f)
            val cb = cos(b); val sb = sin(b)
            val r = rc + hw * k * sign(cb) * abs(cb).pow(0.3f) + (1f - k) * 0.4f
            val z = T1_CR_T * k * sign(sb) * abs(sb).pow(0.8f)
            val c = cos(th); val s = sin(th)
            out[0] = f.cx + f.dx * z + (f.sx * c + f.ux * s) * r
            out[1] = f.cy + f.dy * z + (f.sy * c + f.uy * s) * r
            out[2] = f.cz + f.dz * z + (f.sz * c + f.uz * s) * r
        }
        // crista junctions: narrow necks to the inner boundary membrane
        for (m in 0 until 4) {
            val th = thc - span * 0.8f + span * 1.6f * m / 3f
            t1RailPoint(pj, th, T1_CR_OUT - 0.1f, q); t1RailPoint(pj, th, T1_IBM_R + 0.03f, q2)
            cr.rod(q[0], q[1], q[2], q2[0], q2[1], q2[2], 0.1f, 5, 8)
        }
        // ATP synthase dimer rows along the rim: heads out into the matrix, stalks back to the membrane
        var th = thc - span + 0.12f
        while (th < thc + span - 0.12f) {
            for (sg in SIGNS) {
                val c = cos(th); val s = sin(th)
                val rx = f.sx * c + f.ux * s; val ry = f.sy * c + f.uy * s; val rz = f.sz * c + f.uz * s
                val base = floatArrayOf(f.cx + rx * T1_CR_IN + f.dx * sg * 0.07f, f.cy + ry * T1_CR_IN + f.dy * sg * 0.07f, f.cz + rz * T1_CR_IN + f.dz * sg * 0.07f)
                val head = floatArrayOf(base[0] - rx * 0.12f + f.dx * sg * 0.09f, base[1] - ry * 0.12f + f.dy * sg * 0.09f, base[2] - rz * 0.12f + f.dz * sg * 0.09f)
                add(stalks, base, sc); add(stalks, head, sc); add(heads, head, hc)
            }
            th += 0.2f / T1_CR_IN
        }
        // respiratory-chain complexes in the crista faces (I: L-shaped; III, IV: blobs)
        for (m in 0 until 4) {
            val th2 = thc - span * 0.7f + span * 1.4f * rnd.nextFloat()
            val r = 1.35f + rnd.nextFloat() * 1.0f
            val sg = if (m % 2 == 0) 1f else -1f
            val c = cos(th2); val s = sin(th2)
            val rx = f.sx * c + f.ux * s; val ry = f.sy * c + f.uy * s; val rz = f.sz * c + f.uz * s
            val tx = -f.sx * s + f.ux * c; val ty = -f.sy * s + f.uy * c; val tz = -f.sz * s + f.uz * c
            val px = f.cx + rx * r + f.dx * sg * (T1_CR_T + 0.02f); val py = f.cy + ry * r + f.dy * sg * (T1_CR_T + 0.02f); val pz = f.cz + rz * r + f.dz * sg * (T1_CR_T + 0.02f)
            if (m < 2) {
                etc.ellipsoid(px, py, pz, floatArrayOf(tx * 0.22f, ty * 0.22f, tz * 0.22f), floatArrayOf(f.dx * 0.07f, f.dy * 0.07f, f.dz * 0.07f), floatArrayOf(rx * 0.08f, ry * 0.08f, rz * 0.08f), 5, 8)
                etc.ellipsoid(px + tx * 0.2f + f.dx * sg * 0.12f, py + ty * 0.2f + f.dy * sg * 0.12f, pz + tz * 0.2f + f.dz * sg * 0.12f,
                    floatArrayOf(tx * 0.08f, ty * 0.08f, tz * 0.08f), floatArrayOf(f.dx * 0.17f, f.dy * 0.17f, f.dz * 0.17f), floatArrayOf(rx * 0.08f, ry * 0.08f, rz * 0.08f), 5, 8)
            } else {
                etc.ellipsoid(px, py, pz, floatArrayOf(tx * 0.11f, ty * 0.11f, tz * 0.11f), floatArrayOf(f.dx * 0.1f, f.dy * 0.1f, f.dz * 0.1f), floatArrayOf(rx * 0.1f, ry * 0.1f, rz * 0.1f), 5, 8)
            }
        }
    }
    // porins on the outer membrane
    val por = ArrayList<Float>()
    val pc = floatArrayOf(1f, 0.86f, 0.62f, 0.95f)
    for (k in 0 until 1100) {
        val a = rnd.nextFloat() * ((T1_MITO_END - T1_MITO_TIP) * 16f); val th = rnd.nextFloat() * TAU
        val r = if (a < T1_MITO_R) T1_MITO_R * sin(acos((1f - a / T1_MITO_R).coerceIn(-1f, 1f))) else T1_MITO_R
        t1RailPoint(T1_MITO_TIP + a / 16f, th, r + 0.01f, q); add(por, q, pc)
    }
    // the nucleoid: a small tangled loop of mtDNA between cristae 3 and 4
    val dna = ArrayList<Float>()
    val dc = floatArrayOf(0.88f, 0.78f, 1f, 1f)
    val fN = frameAt((T1_CRISTAE[3] + T1_CRISTAE[4]) * 0.5f)
    val th0 = 200f * DEG
    val ncx = fN.cx + (fN.sx * cos(th0) + fN.ux * sin(th0)) * 1.85f; val ncy = fN.cy + (fN.sy * cos(th0) + fN.uy * sin(th0)) * 1.85f; val ncz = fN.cz + (fN.sz * cos(th0) + fN.uz * sin(th0)) * 1.85f
    for (k in 0 until 220) {
        for (h in 0..1) {
            val t = TAU * (k + h) / 220f
            val a = 0.28f * sin(3f * t) + 0.08f * sin(11f * t); val s = 0.4f * cos(2f * t) + 0.1f * cos(7f * t); val u = 0.4f * sin(t) + 0.12f * sin(5f * t + 1f)
            q[0] = ncx + fN.dx * a + fN.sx * s + fN.ux * u; q[1] = ncy + fN.dy * a + fN.sy * s + fN.uy * u; q[2] = ncz + fN.dz * a + fN.sz * s + fN.uz * u
            add(dna, q, dc)
        }
    }
    return T1Mito(outer.build(), inner.build(), cr.build(), etc.build(), PointMesh(por.toFloatArray()), PointMesh(heads.toFloatArray()),
        LineMesh(stalks.toFloatArray()), LineMesh(dna.toFloatArray()))
}

/** Unit meshes for a whole ATP synthase: the c-ring (8 subunits) and the alpha3 / beta3 halves of F1. */
private fun t1BuildLobes(n: Int, r: Float, lobe: FloatArray, phase: Float): T1Batch {
    val b = T1Builder()
    for (k in 0 until n) {
        val a = TAU * k / n + phase
        b.ellipsoid(cos(a) * r, sin(a) * r, 0f, floatArrayOf(lobe[0], 0f, 0f), floatArrayOf(0f, lobe[1], 0f), floatArrayOf(0f, 0f, lobe[2]), 6, 8)
    }
    return b.build()
}

internal fun StereoBodyRenderer.drawMitochondrion(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp < 7.2f || rp > 8.3f) return
    val vis = t1Smooth(7.2f, 7.32f, rp) * (1f - t1Smooth(8.18f, 8.26f, rp))
    landmarkFade *= vis; colorShader.globalFade *= vis
    val ow = t1ShipOffWorld(); val ox = ow[0]; val oy = ow[1]; val oz = ow[2]
    val mt = t1Mesh("mito") { t1BuildMito() }
    t1LitWorld(mt.cristae, T1_CRISTA, T1_WHITE, 1f, 0.16f, ox, oy, oz)
    t1LitWorld(mt.etc, T1_ETC, T1_WHITE, 1f, 0.2f, ox, oy, oz)
    t1Color(mt.stalks, null, 0f, 0f, 0f, 1.5f, false, 1f, ox = ox, oy = oy, oz = oz)
    t1Color(mt.heads, null, 0f, 0f, 0f, 7f, true, 1f, ox = ox, oy = oy, oz = oz)
    t1Color(mt.dna, null, 0f, 0f, 0f, 2f, false, 1f, ox = ox, oy = oy, oz = oz)
    // matrix granules
    for (k in 0 until 3) {
        val pg = (T1_CRISTAE[1 + 2 * k] + T1_CRISTAE[2 + 2 * k]) * 0.5f; val th = (40f + 110f * k) * DEG
        val f = frameAt(pg)
        drawSphereAt(f.cx + (f.sx * cos(th) + f.ux * sin(th)) * 1.6f + ox, f.cy + (f.sy * cos(th) + f.uy * sin(th)) * 1.6f + oy,
            f.cz + (f.sz * cos(th) + f.uz * sin(th)) * 1.6f + oz, 0.2f, 0.2f, 0.2f, T1_ETC, T1_WHITE, 1f, 0f, 0f, 1f, 0f, blob, 0f, 0.15f)
    }
    // whole synthases on the next crista ahead of the craft
    var j = T1_CRISTAE.indexOfFirst { it > rp + 0.012f }
    if (j < 0) j = T1_CRISTAE.lastIndex
    val cring = t1Mesh("atp.c") { t1BuildLobes(8, 1f, floatArrayOf(0.32f, 0.32f, 1f), 0f) }
    val f1a = t1Mesh("atp.a") { t1BuildLobes(3, 0.5f, floatArrayOf(0.5f, 0.5f, 0.85f), 0f) }
    val f1b = t1Mesh("atp.b") { t1BuildLobes(3, 0.5f, floatArrayOf(0.5f, 0.5f, 0.85f), PI_F / 3f) }
    val f = frameAt(T1_CRISTAE[j]); val thc = t1CristaTheta(j)
    val spin = seconds * TAU                       // 1 rev/s here; ~100 rev/s in life
    val d = t1Dyn.data
    var v = 0
    val pcol = floatArrayOf(1f, 0.95f, 0.55f, 1f)
    for (k in 0 until 6) {
        val th = thc + floatArrayOf(-100f, -60f, -20f, 20f, 60f, 100f)[k] * DEG
        val sg = if (k % 2 == 0) 1f else -1f
        val c = cos(th); val s = sin(th)
        val rx = f.sx * c + f.ux * s; val ry = f.sy * c + f.uy * s; val rz = f.sz * c + f.uz * s
        // head axis: in from the rim and tilted out of the crista plane (the dimer's two heads splay apart)
        var nx = -rx * 0.77f + f.dx * sg * 0.64f; var ny = -ry * 0.77f + f.dy * sg * 0.64f; var nz = -rz * 0.77f + f.dz * sg * 0.64f
        val nl = sqrt(nx * nx + ny * ny + nz * nz); nx /= nl; ny /= nl; nz /= nl
        val tx = -f.sx * s + f.ux * c; val ty = -f.sy * s + f.uy * c; val tz = -f.sz * s + f.uz * c
        val bx = f.cx + rx * T1_CR_IN + f.dx * sg * 0.08f + ox; val by = f.cy + ry * T1_CR_IN + f.dy * sg * 0.08f + oy; val bz = f.cz + rz * T1_CR_IN + f.dz * sg * 0.08f + oz
        // perpendiculars to the axis
        val p2x = ny * tz - nz * ty; val p2y = nz * tx - nx * tz; val p2z = nx * ty - ny * tx
        val cw = cos(spin); val sw = sin(spin)
        val yx = tx * cw + p2x * sw; val yy = ty * cw + p2y * sw; val yz = tz * cw + p2z * sw
        drawBasis(bx, by, bz, nx, ny, nz, yx, yy, yz, 0.04f, 0.04f, 0.035f, cring, T1_FO, T1_WHITE, 1f, 0f, 0.25f)
        // the central stalk, slightly eccentric so its turning shows
        val ex = yx * 0.012f; val ey = yy * 0.012f; val ez = yz * 0.012f
        drawStrut(bx + ex, by + ey, bz + ez, bx + nx * 0.12f + ex, by + ny * 0.12f + ey, bz + nz * 0.12f + ez, 0.014f, T1_FO, T1_WHITE, 0.3f)
        // the F1 head, held still by the stator
        val hx = bx + nx * 0.15f; val hy = by + ny * 0.15f; val hz = bz + nz * 0.15f
        drawBasis(hx, hy, hz, nx, ny, nz, tx, ty, tz, 0.06f, 0.06f, 0.06f, f1a, T1_F1_A, T1_WHITE, 1f, 0f, 0.25f)
        drawBasis(hx, hy, hz, nx, ny, nz, tx, ty, tz, 0.06f, 0.06f, 0.06f, f1b, T1_F1_B, T1_WHITE, 1f, 0f, 0.25f)
        drawStrut(bx + p2x * 0.065f, by + p2y * 0.065f, bz + p2z * 0.065f, hx + nx * 0.06f + p2x * 0.03f, hy + ny * 0.06f + p2y * 0.03f, hz + nz * 0.06f + p2z * 0.03f,
            0.011f, T1_STATOR, T1_WHITE, 0.3f)
        // protons coming in from the intracristal space through the rotor
        for (m in 0..1) {
            val t = ((seconds * 1.4f + m * 0.5f + k * 0.17f) % 1f)
            val sx = bx + rx * 0.3f; val sy = by + ry * 0.3f; val sz = bz + rz * 0.3f
            v = t1Put(d, v, sx + (bx - sx) * t, sy + (by - sy) * t, sz + (bz - sz) * t, pcol, 1f - t * 0.5f)
        }
    }
    t1DynDraw(v, GLES20.GL_POINTS, 3.5f)
    // membranes last (translucent): the inner boundary membrane, then the outer membrane with its porins
    GLES20.glDepthMask(false)
    t1LitWorld(mt.inner, T1_MITO_IN, T1_WHITE, 0.28f, 0.12f, ox, oy, oz)
    t1LitWorld(mt.outer, T1_MITO_OUT, T1_WHITE, 0.34f, 0.15f, ox, oy, oz)
    GLES20.glDepthMask(true)
    if (quality < 2) t1Color(mt.porins, null, 0f, 0f, 0f, 2.2f, true, 0.9f, depthWrite = false, ox = ox, oy = oy, oz = oz)
}

// ================================================================ stop 9: THE NUCLEUS (12 nm rung)
// 1 unit = 8 nm. The nuclear envelope (outer and inner membranes 8 nm thick, a 27 nm perinuclear
// space, fused round the pore) with a nuclear pore complex in it: eightfold cytoplasmic ring,
// spokes lining a 48 nm channel, nucleoplasmic ring, luminal ring, cytoplasmic filaments, the
// nuclear basket, and FG-repeat strands filling the channel (they part round the craft). Inside,
// chromatin as 10 nm fibres - nucleosomes (histone octamers 11 x 5.7 nm) each wrapped by 1.65
// left-handed turns of DNA, joined by linker DNA - and a stretch of bare B-DNA (2 nm wide, 3.4 nm
// per turn, right-handed, major and minor grooves) being read by RNA polymerase II at ~20 nt/s,
// its messenger RNA trailing out behind.

private const val T1_NPC_P = 8.815f

private class T1Nucleus(val envelope: ParamMesh, val heads: PointMesh, val npc: T1Batch, val fil: LineMesh, val histones: T1Batch, val dna: LineMesh, val helix: LineMesh)

private fun t1EnvProfile(s: Float, out: FloatArray) {        // (r, along) of the envelope's mid-surface; s in [0, 1]
    val l1 = 9.8f - 8.4f; val l2 = PI_F * 2.2f; val tot = 2f * l1 + l2
    val d = s * tot
    when {
        d < l1 -> { out[0] = 9.8f - d; out[1] = -2.2f }
        d < l1 + l2 -> { val g = (d - l1) / 2.2f; out[0] = 8.4f - 2.2f * sin(g); out[1] = -2.2f * cos(g) }
        else -> { out[0] = 8.4f + (d - l1 - l2); out[1] = 2.2f }
    }
}

private fun t1BuildNucleus(): T1Nucleus {
    val tmp = FloatArray(2)
    val env = ParamMesh(40, 72) { u, v, out -> t1EnvProfile(u, tmp); val a = v * TAU; out[0] = cos(a) * tmp[0]; out[1] = sin(a) * tmp[0]; out[2] = -tmp[1] }
    val heads = ArrayList<Float>()
    val hc = floatArrayOf(1f, 0.80f, 0.55f, 0.9f)
    fun add(l: ArrayList<Float>, x: Float, y: Float, z: Float, c: FloatArray) { l.add(x); l.add(y); l.add(z); l.add(c[0]); l.add(c[1]); l.add(c[2]); l.add(c[3]) }
    val t2 = FloatArray(2); val t3 = FloatArray(2)
    val np = 60
    for (k in 0 until np) {
        t1EnvProfile(k / (np - 1f), t2); t1EnvProfile(((k + 0.5f) / (np - 1f)).coerceAtMost(1f), t3)
        val tr = t3[0] - t2[0]; val ta = t3[1] - t2[1]; val tl = sqrt(tr * tr + ta * ta).coerceAtLeast(1e-5f)
        val nr = -ta / tl; val na = tr / tl
        val cnt = (TAU * t2[0] / 0.3f).toInt()
        for (m in 0 until cnt) {
            val a = TAU * (m + 0.5f * (k % 2)) / cnt
            for (sg in SIGNS) {
                val r = t2[0] + nr * 0.45f * sg; val al = t2[1] + na * 0.45f * sg
                add(heads, cos(a) * r, sin(a) * r, -al, hc)
            }
        }
    }
    // the pore complex, eightfold (local: x side, y up, z = -along)
    val b = T1Builder()
    for (k in 0 until 8) {
        val a = TAU * k / 8f + 0.2f; val c = cos(a); val s = sin(a)
        for (al in floatArrayOf(-2.8f, 2.8f)) b.ellipsoid(c * 5.0f, s * 5.0f, -al, floatArrayOf(c * 1.1f, s * 1.1f, 0f), floatArrayOf(-s * 1.3f, c * 1.3f, 0f), floatArrayOf(0f, 0f, 1.0f), 7, 10)
        b.ellipsoid(c * 4.3f, s * 4.3f, 0f, floatArrayOf(c * 1.3f, s * 1.3f, 0f), floatArrayOf(-s * 0.9f, c * 0.9f, 0f), floatArrayOf(0f, 0f, 1.9f), 7, 10)   // spokes
        b.ellipsoid(c * 7.2f, s * 7.2f, 0f, floatArrayOf(c * 0.6f, s * 0.6f, 0f), floatArrayOf(-s * 0.9f, c * 0.9f, 0f), floatArrayOf(0f, 0f, 0.8f), 5, 8)    // luminal ring
    }
    val fil = ArrayList<Float>()
    val fc = floatArrayOf(0.80f, 0.76f, 1f, 1f)
    for (k in 0 until 8) {
        val a = TAU * k / 8f + 0.2f
        // cytoplasmic filaments, curling out toward the cytoplasm
        for (m in 0 until 14) for (h in 0..1) {
            val t = (m + h) / 14f
            val r = 5.0f + 0.9f * sin(t * 5f + k); val aa = a + 0.35f * sin(t * 4f)
            add(fil, cos(aa) * r, sin(aa) * r, 3.6f + 5.5f * t, fc)
        }
        // basket filaments converging on the distal ring
        for (m in 0 until 10) for (h in 0..1) {
            val t = (m + h) / 10f
            val r = 5.0f + (2.4f - 5.0f) * t
            add(fil, cos(a) * r, sin(a) * r, -(3.4f + 5.6f * t), fc)
        }
    }
    for (m in 0 until 32) for (h in 0..1) { val a = TAU * (m + h) / 32f; add(fil, cos(a) * 2.4f, sin(a) * 2.4f, -9.0f, fc) }
    // chromatin fibres (node-local, relative to the node 9 frame: x side, y up, z = -along)
    val hb = T1Builder(); val dna = ArrayList<Float>()
    val dcA = floatArrayOf(1f, 0.86f, 0.52f, 1f)
    val rnd = java.util.Random(19)
    for (fib in 0 until 6) {
        val ang = fib * 1.05f + 0.4f
        val rad = 3.4f + (fib % 3) * 1.0f
        var px = cos(ang) * rad; var py = sin(ang) * rad; var pz = -(1.0f + rnd.nextFloat() * 2f)
        var dx = (rnd.nextFloat() - 0.5f) * 0.6f; var dy = (rnd.nextFloat() - 0.5f) * 0.6f; var dz = -1f
        var lastX = px; var lastY = py; var lastZ = pz
        for (nuc in 0 until 11) {
            // wander, staying off the craft's lane
            dx += (rnd.nextFloat() - 0.5f) * 0.9f; dy += (rnd.nextFloat() - 0.5f) * 0.9f; dz += (rnd.nextFloat() - 0.5f) * 0.5f - 0.1f
            val dl = sqrt(dx * dx + dy * dy + dz * dz); dx /= dl; dy /= dl; dz /= dl
            val step = 2.2f + rnd.nextFloat() * 1.2f
            px += dx * step; py += dy * step; pz += dz * step
            val rr = sqrt(px * px + py * py)
            if (rr < 2.4f) { px *= 2.4f / rr; py *= 2.4f / rr }
            if (rr > 6.6f) { px *= 6.6f / rr; py *= 6.6f / rr }
            if (pz < -14f) pz = -14f
            // octamer disc axis: roughly perpendicular to the fibre
            var axx = dy; var axy = -dx; var axz = 0.3f
            val al = sqrt(axx * axx + axy * axy + axz * axz); axx /= al; axy /= al; axz /= al
            var e1x = dy * axz - dz * axy; var e1y = dz * axx - dx * axz; var e1z = dx * axy - dy * axx
            val el = sqrt(e1x * e1x + e1y * e1y + e1z * e1z).coerceAtLeast(1e-5f); e1x /= el; e1y /= el; e1z /= el
            val e2x = axy * e1z - axz * e1y; val e2y = axz * e1x - axx * e1z; val e2z = axx * e1y - axy * e1x
            hb.ellipsoid(px, py, pz, floatArrayOf(e1x * 0.62f, e1y * 0.62f, e1z * 0.62f), floatArrayOf(axx * 0.33f, axy * 0.33f, axz * 0.33f),
                floatArrayOf(e2x * 0.62f, e2y * 0.62f, e2z * 0.62f), 6, 10)
            // 1.65 left-handed turns of DNA round the octamer (axis radius 4.2 nm, pitch 2.6 nm)
            val turns = 1.65f; val steps = 40
            var prevX = 0f; var prevY = 0f; var prevZ = 0f
            for (m in 0..steps) {
                val t = -turns * TAU * m / steps
                val h = (0.33f * m / steps - 0.165f) * 1.0f
                val wx = px + (e1x * cos(t) + e2x * sin(t)) * 0.56f + axx * h
                val wy = py + (e1y * cos(t) + e2y * sin(t)) * 0.56f + axy * h
                val wz = pz + (e1z * cos(t) + e2z * sin(t)) * 0.56f + axz * h
                if (m == 0) { add(dna, lastX, lastY, lastZ, dcA); add(dna, wx, wy, wz, dcA) }       // linker in
                else { add(dna, prevX, prevY, prevZ, dcA); add(dna, wx, wy, wz, dcA) }
                prevX = wx; prevY = wy; prevZ = wz
            }
            lastX = prevX; lastY = prevY; lastZ = prevZ
        }
    }
    // a stretch of bare B-DNA beside the lane, being transcribed (local to the node frame)
    val hx = ArrayList<Float>()
    val bpCol = arrayOf(floatArrayOf(0.4f, 0.95f, 0.5f, 1f), floatArrayOf(1f, 0.45f, 0.45f, 1f), floatArrayOf(0.45f, 0.7f, 1f, 1f), floatArrayOf(1f, 0.9f, 0.35f, 1f))
    val bA = floatArrayOf(1f, 0.74f, 0.45f, 1f); val bB = floatArrayOf(0.55f, 0.82f, 1f, 1f)
    val nbp = 330
    var pa = 0f; var pb = 0f
    for (k in 0 until nbp) {
        val along = -2.5f + k * 0.0405f
        val w = -TAU * k / 10.5f                        // right-handed in this frame (see header)
        val ax = cos(w) * 0.125f; val ay = sin(w) * 0.125f
        val bx = cos(w + 2.5f) * 0.125f; val by = sin(w + 2.5f) * 0.125f   // strands 143 degrees apart: minor and major grooves
        val cx = -2.0f; val cy = 0.9f; val z = -along
        if (k > 0) {
            add(hx, cx + cos(pa) * 0.125f, cy + sin(pa) * 0.125f, z + 0.0405f, bA); add(hx, cx + ax, cy + ay, z, bA)
            add(hx, cx + cos(pb) * 0.125f, cy + sin(pb) * 0.125f, z + 0.0405f, bB); add(hx, cx + bx, cy + by, z, bB)
        }
        val base = rnd.nextInt(4)
        val mx = (ax + bx) * 0.5f; val my = (ay + by) * 0.5f
        add(hx, cx + ax, cy + ay, z, bpCol[base]); add(hx, cx + mx, cy + my, z, bpCol[base])
        add(hx, cx + mx, cy + my, z, bpCol[base xor 1]); add(hx, cx + bx, cy + by, z, bpCol[base xor 1])
        pa = w; pb = w + 2.5f
    }
    return T1Nucleus(env, PointMesh(heads.toFloatArray()), b.build(), LineMesh(fil.toFloatArray()), hb.build(), LineMesh(dna.toFloatArray()), LineMesh(hx.toFloatArray()))
}

internal fun StereoBodyRenderer.drawNucleus(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp < 8.2f || rp > 9.75f) return
    val vis = t1Smooth(8.2f, 8.3f, rp) * (1f - t1Smooth(9.55f, 9.75f, rp))
    landmarkFade *= vis; colorShader.globalFade *= vis
    val nu = t1Mesh("nucleus") { t1BuildNucleus() }
    val fp = frameAt(T1_NPC_P)
    val off = t1ShipOff(fp); val so = off[0]; val uo = off[1]
    t1Lit(nu.npc, fp, 0f, so, uo, T1_NPC, T1_WHITE, 1f, 0.2f)
    t1Color(nu.fil, fp, 0f, so, uo, 2f, false)
    t1Color(nu.heads, fp, 0f, so, uo, 2.2f, true, 0.9f)
    // FG-repeat strands filling the channel, pushed aside where the craft is
    val sA = t1Along(fp, shipX, shipY, shipZ)
    val d = t1Dyn.data
    var v = 0
    val fg = floatArrayOf(0.88f, 0.88f, 1f, 0.85f)
    for (k in 0 until 30) {
        val a0 = TAU * k / 30f; val al0 = -2.4f + 4.8f * t1Hash(k)
        var px = 0f; var py = 0f; var pz = 0f
        for (m in 0..5) {
            val t = m / 5f
            val r = 3.0f - 2.4f * t + 0.3f * sin(seconds * 1.3f + k + m)
            val a = a0 + 0.9f * t * (if (k % 2 == 0) 1f else -1f)
            var al = al0 + 0.8f * sin(t * 3f + k)
            var rr = r
            val dsh = al - sA
            if (abs(dsh) < 1.4f) rr = max(rr, 1.3f * (1f - abs(dsh) / 1.4f) + rr * abs(dsh) / 1.4f)
            if (abs(dsh) < 1.4f && rr < 1.25f) rr = 1.25f
            val x = so + cos(a) * rr; val y = uo + sin(a) * rr
            val wx = fx(fp, al, x, y); val wy = fy(fp, al, x, y); val wz = fz(fp, al, x, y)
            if (m > 0) { v = t1Put(d, v, px, py, pz, fg, fg[3]); v = t1Put(d, v, wx, wy, wz, fg, fg[3]) }
            px = wx; py = wy; pz = wz
            al += 0f
        }
    }
    t1DynDraw(v, GLES20.GL_LINES, 2.5f, 1f, depthWrite = false)
    // chromatin, the gene and its polymerase (node frame)
    val f9 = frameAt(i.toFloat())
    val o9 = t1ShipOff(f9); val s9 = o9[0]; val u9 = o9[1]
    t1Lit(nu.histones, f9, 0f, s9, u9, T1_HISTONE, T1_WHITE, 1f, 0.18f)
    t1Color(nu.dna, f9, 0f, s9, u9, 2f, false)
    t1Color(nu.helix, f9, 0f, s9, u9, 2f, false)
    val slide = (seconds * 0.8f) % 12.5f             // 0.8 units/s = 6.4 nm/s ~ 20 nt/s
    val aP = -2.2f + slide
    t1Lit(sphere, f9, aP, s9 - 2.0f + 0.35f, u9 + 0.9f + 0.15f, T1_POL, T1_WHITE, 1f, 0.22f, 0.85f, 0.75f, 1.0f)
    t1Lit(sphere, f9, aP + 0.1f, s9 - 2.0f - 0.45f, u9 + 0.9f - 0.1f, T1_POL, T1_WHITE, 1f, 0.22f, 0.65f, 0.7f, 0.85f)
    // the transcript: out of the exit channel, trailing behind the enzyme
    var w = 0
    val rc = floatArrayOf(1f, 0.55f, 0.48f, 1f)
    val len = min(slide, 7f)
    val segs = 30
    var qx = 0f; var qy = 0f; var qz = 0f
    for (m in 0..segs) {
        val t = len * m / segs
        val a = aP - 0.3f - t * 0.8f; val sx = s9 - 2.0f + 0.2f + 0.25f * sin(t * 2.3f); val uy = u9 + 0.9f + 0.9f + t * 0.25f + 0.2f * sin(t * 1.7f)
        val wx = fx(f9, a, sx, uy); val wy = fy(f9, a, sx, uy); val wz = fz(f9, a, sx, uy)
        if (m > 0) { w = t1Put(d, w, qx, qy, qz, rc, 1f); w = t1Put(d, w, wx, wy, wz, rc, 1f) }
        qx = wx; qy = wy; qz = wz
    }
    t1DynDraw(w, GLES20.GL_LINES, 3f)
    // the envelope (translucent) last
    GLES20.glDepthMask(false)
    t1Lit(nu.envelope, fp, 0f, so, uo, T1_ENVELOPE, T1_WHITE, 0.4f, 0.12f)
    GLES20.glDepthMask(true)
}

// ================================================================ stop 10: THE RIBOSOME (12 nm rung)
// 1 unit = 8 nm. An 80S ribosome (~28 nm): the large 60S subunit (blue rRNA, grey proteins) with
// its central protuberance, L1 stalk and P stalk, over the small 40S subunit (gold) with head,
// beak, body and platform; the messenger RNA threads the 40S neck and carries on to more
// ribosomes (a polysome) and back toward the pore. L-shaped tRNAs sit in the A, P and E sites;
// each cycle (5 per second, as narrated) a new aminoacyl-tRNA arrives with EF-Tu, the peptide
// moves onto it, the ribosome steps one codon (3 nt, 1 nm) and the spent tRNA leaves from E.
// The chain runs up the exit tunnel and out of the top of the 60S, folding as it emerges.

private const val T1_RIBO_A = 1.5f
private const val T1_RIBO_S = -1.9f
private const val T1_RIBO_U = 0.2f

private class T1Ribo(val large: T1Batch, val small: T1Batch, val prot: T1Batch, val mrna: LineMesh)

private fun t1BuildRibo(): T1Ribo {
    val L = T1Builder(); val S = T1Builder(); val P = T1Builder()
    fun e(b: T1Builder, s: Float, u: Float, a: Float, rs: Float, ru: Float, ra: Float) =
        b.ellipsoid(s, u, -a, floatArrayOf(rs, 0f, 0f), floatArrayOf(0f, ru, 0f), floatArrayOf(0f, 0f, ra), 10, 14)
    e(L, 0f, 0.62f, 0f, 1.3f, 0.95f, 1.4f)
    e(L, -0.2f, 1.45f, -0.3f, 0.45f, 0.45f, 0.45f)                     // central protuberance
    L.rod(0.6f, 0.95f, -1.1f, 1.0f, 1.35f, -1.9f, 0.14f)                 // P stalk (A-site side)
    L.rod(-0.5f, 0.95f, 1.1f, -0.95f, 1.35f, 1.9f, 0.16f)                // L1 stalk (E-site side)
    e(S, 0.3f, -0.72f, 0f, 0.95f, 0.5f, 1.25f)                           // body
    e(S, -0.78f, -0.78f, -0.15f, 0.52f, 0.5f, 0.55f)                      // head
    e(S, -1.2f, -0.9f, -0.45f, 0.2f, 0.18f, 0.22f)                        // beak
    e(S, 0.95f, -0.55f, 0.6f, 0.35f, 0.3f, 0.35f)                         // platform
    val rnd = java.util.Random(3)
    for (k in 0 until 30) {
        val lg = k < 18
        val th = rnd.nextFloat() * TAU; val ph = (0.15f + rnd.nextFloat() * 0.7f) * PI_F
        val (cs, cu, ca, rs, ru, ra) = if (lg) arrayOf(0f, 0.62f, 0f, 1.3f, 0.95f, 1.4f) else arrayOf(0.3f, -0.72f, 0f, 0.95f, 0.5f, 1.25f)
        var ny = cos(ph); if (lg) ny = abs(ny) else ny = -abs(ny)
        val x = cs + rs * sin(ph) * cos(th); val y = cu + ru * ny; val z = -(ca + ra * sin(ph) * sin(th))
        val r = 0.13f + rnd.nextFloat() * 0.1f
        P.ellipsoid(x, y, z, floatArrayOf(r, 0f, 0f), floatArrayOf(0f, r, 0f), floatArrayOf(0f, 0f, r), 5, 8)
    }
    // the messenger (ribosome-local), with the codon ticks drawn per frame
    val m = ArrayList<Float>()
    val mc = floatArrayOf(1f, 0.58f, 0.50f, 1f)
    val segs = 200
    for (k in 0 until segs) for (h in 0..1) {
        val a = -17f + 29f * (k + h) / segs
        val wig = if (abs(a) < 1.6f || abs(a - 4.4f) < 1.6f || abs(a - 8.8f) < 1.6f) 0f else 1f
        val s = -0.2f + 0.25f * wig * sin(a * 0.9f); val u = -0.42f + 0.3f * wig * sin(a * 0.6f + 1f)
        m.add(s); m.add(u); m.add(-a); m.add(mc[0]); m.add(mc[1]); m.add(mc[2]); m.add(mc[3])
    }
    return T1Ribo(L.build(), S.build(), P.build(), LineMesh(m.toFloatArray()))
}

private operator fun <T> Array<T>.component6(): T = this[5]

/** tRNA geometry for a site coordinate x (A = +1, P = 0, E = -1): anticodon, elbow, acceptor (s, u, a). */
private fun t1TrnaAt(x: Float, out: FloatArray) {
    out[0] = -0.2f; out[1] = -0.36f; out[2] = 0.12f * x          // anticodon on its codon
    out[3] = -0.5f; out[4] = 0.22f; out[5] = 0.38f * x           // elbow
    out[6] = 0.22f; out[7] = 0.38f; out[8] = 0.14f * x           // acceptor end at the peptidyl-transferase centre
}

internal fun StereoBodyRenderer.drawRibosome(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp < 9.3f || rp > 10.26f) return
    val vis = t1Smooth(9.3f, 9.45f, rp) * (1f - t1Smooth(10.19f, 10.25f, rp))
    landmarkFade *= vis; colorShader.globalFade *= vis
    val rb = t1Mesh("ribo") { t1BuildRibo() }
    val f = frameAt(i + T1_RIBO_A / 16f)
    val off = t1ShipOff(f); val so = off[0] + T1_RIBO_S; val uo = off[1] + T1_RIBO_U
    val cyc = seconds * 5f                                   // five elongation cycles a second
    val ph = cyc - floor(cyc)
    // mRNA and its codon ticks, sliding one codon per cycle through the ribosome
    t1Color(rb.mrna, f, 0f, so, uo, 3f, false)
    val d = t1Dyn.data
    var v = 0
    val shift = (t1Smooth(0.45f, 0.75f, ph) + floor(cyc)) * 0.12f
    val tc = floatArrayOf(1f, 0.9f, 0.8f, 1f)
    for (k in -40..40) {
        val a = k * 0.12f - (shift % 0.12f)
        if (abs(a) > 4.8f) continue
        val x0 = fx(f, a, so - 0.2f, uo - 0.42f); val y0 = fy(f, a, so - 0.2f, uo - 0.42f); val z0 = fz(f, a, so - 0.2f, uo - 0.42f)
        v = t1Put(d, v, x0, y0, z0, tc, 1f)
        v = t1Put(d, v, fx(f, a, so - 0.2f, uo - 0.55f), fy(f, a, so - 0.2f, uo - 0.55f), fz(f, a, so - 0.2f, uo - 0.55f), tc, 1f)
    }
    t1DynDraw(v, GLES20.GL_LINES, 1.5f)
    // tRNAs: incoming (with EF-Tu) -> A -> P, and P -> E -> away
    val q = FloatArray(9)
    fun trna(x: Float, da: Float, ds: Float, du: Float, alpha: Float) {
        t1TrnaAt(x, q)
        t1Rod(f, q[2] + da, so + q[0] + ds, uo + q[1] + du, q[5] + da, so + q[3] + ds, uo + q[4] + du, 0.1f, T1_TRNA, T1_WHITE, alpha, 0.2f)
        t1Rod(f, q[5] + da, so + q[3] + ds, uo + q[4] + du, q[8] + da, so + q[6] + ds, uo + q[7] + du, 0.1f, T1_TRNA, T1_WHITE, alpha, 0.2f)
    }
    val tIn = t1Smooth(0f, 0.3f, ph)
    val move = t1Smooth(0.45f, 0.75f, ph)
    val away = t1Smooth(0.75f, 1f, ph)
    if (ph < 0.3f) {
        val k = 1f - tIn
        trna(1f, 2.4f * k, -1.8f * k, -1.4f * k, 1f)
        t1Lit(sphere, f, 0.14f + 2.4f * k + 0.1f, so + 0.55f - 1.8f * k, uo + 0.5f - 1.4f * k, T1_EFTU, T1_WHITE, 1f, 0.2f, 0.36f, 0.32f, 0.36f)
    } else trna(1f - move, 0f, 0f, 0f, 1f)
    if (ph < 0.75f) trna(-move, 0f, 0f, 0f, 1f) else trna(-1f, -1.6f * away, -1.4f * away, 0.6f * away, 1f - away)
    // nascent chain: from the P-site acceptor (the A site's after transfer) up the exit tunnel and out the top
    val holder = if (ph > 0.4f) 1f - move else 0f
    t1TrnaAt(holder, q)
    val pa0 = q[8]; val ps0 = q[6]; val pu0 = q[7]
    var w = 0
    val colA = floatArrayOf(1f, 0.78f, 0.42f, 1f); val colB = floatArrayOf(0.5f, 0.92f, 0.82f, 1f)
    val colC = floatArrayOf(0.95f, 0.55f, 0.75f, 1f); val colD = floatArrayOf(0.7f, 0.8f, 1f, 1f)
    val cols = arrayOf(colA, colB, colC, colD)
    val step = 0.045f
    val grow = ph * step
    for (k in 0 until 64) {
        val t = k * step + grow
        val sP: Float; val uP: Float; val aP: Float
        if (t < 1.3f) { val g = t / 1.3f; sP = ps0 + (0.35f - ps0) * g; uP = pu0 + (1.6f - pu0) * g; aP = pa0 + (0.3f - pa0) * g }
        else {
            val e = t - 1.3f                                  // outside: an alpha-helix-like coil drifting up and away
            sP = 0.35f + 0.07f * cos(e * 38f) + e * 0.25f; uP = 1.6f + e * 0.55f; aP = 0.3f + 0.07f * sin(e * 38f) + e * 0.12f
        }
        w = t1Put(d, w, fx(f, aP, so + sP, uo + uP), fy(f, aP, so + sP, uo + uP), fz(f, aP, so + sP, uo + uP), cols[(k + floor(cyc).toInt()) and 3], 1f)
    }
    t1DynDraw(w, GLES20.GL_POINTS, 4f)
    // the folded domain the chain has already become
    t1Lit(blob, f, 0.3f + 0.4f, so + 0.35f + 0.75f, uo + 1.6f + 1.55f, T1_FOLD, T1_WHITE, 1f, 0.2f, 0.42f, 0.36f, 0.4f)
    // subunits: proteins opaque, rRNA translucent so the tRNAs and the tunnel show through
    t1Lit(rb.prot, f, 0f, so, uo, T1_RPROT, T1_WHITE, 1f, 0.15f)
    for (k in 0..1) {       // two more ribosomes along the same messenger: a polysome
        val da = if (k == 0) 4.4f else 8.8f
        t1Lit(rb.prot, f, da, so, uo, T1_RPROT, T1_WHITE, 1f, 0.15f)
        t1Lit(rb.large, f, da, so, uo, T1_RRNA_L, T1_WHITE, 1f, 0.2f)
        t1Lit(rb.small, f, da, so, uo, T1_RRNA_S, T1_WHITE, 1f, 0.2f)
    }
    GLES20.glDepthMask(false)
    t1Lit(rb.large, f, 0f, so, uo, T1_RRNA_L, T1_WHITE, 0.72f, 0.2f)
    t1Lit(rb.small, f, 0f, so, uo, T1_RRNA_S, T1_WHITE, 0.72f, 0.2f)
    GLES20.glDepthMask(true)
}

// ================================================================ stop 11: THE ATOM (12 pm rung)
// 1 unit = 8 pm. A carbon atom in an aromatic ring of a base: no orbits, a probability cloud.
// Points are sampled from Slater-type orbital densities (Clementi-Raimondi exponents): the 1s core
// (2 electrons, peak at 9 pm), and the valence shell as three sp2 hybrid lobes in the ring plane
// plus the p orbital across it (peaks near 65 pm, fading out beyond 100 pm), reaching toward its
// neighbours: C at 140 pm, N at 135 pm, H at 108 pm, whose own clouds meet it along the bonds.
// Nuclei are fixed-size points (a carbon nucleus is ~3 fm: 25,000 times smaller than the atom).
// The cloud does not orbit or rotate; four interleaved samplings flicker to show probability.

private const val BOHR_PM = 52.92f

/**
 * The ring plane is tipped 40 degrees off face-on so the three sp2 lobes fan out across the view
 * (local x = side, y = up, z = -along): e1 = side, e2 = up*cos50 + along*sin50; the p (pi) orbital
 * lies along the plane's normal.
 */
private val T1_E2 = floatArrayOf(0f, cos(50f * DEG), -sin(50f * DEG))
private val T1_PI_N = floatArrayOf(0f, sin(50f * DEG), cos(50f * DEG))
private fun t1AtomBonds(): Array<FloatArray> = Array(3) { k ->
    val th = (90f + 120f * k) * DEG
    floatArrayOf(cos(th), sin(th) * T1_E2[1], sin(th) * T1_E2[2])
}

private fun t1Gamma(k: Int, rate: Float, rnd: java.util.Random): Float {
    var s = 0f
    repeat(k) { s -= ln(rnd.nextFloat().coerceAtLeast(1e-6f)) }
    return s / rate
}

private fun t1BuildAtom(set: Int): PointMesh {
    val rnd = java.util.Random(100L + set)
    val pts = ArrayList<Float>()
    fun add(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) { pts.add(x); pts.add(y); pts.add(z); pts.add(r); pts.add(g); pts.add(b); pts.add(a) }
    // unit direction helpers
    fun dir(out: FloatArray) {
        while (true) {
            val x = rnd.nextFloat() * 2f - 1f; val y = rnd.nextFloat() * 2f - 1f; val z = rnd.nextFloat() * 2f - 1f
            val l = x * x + y * y + z * z
            if (l in 0.01f..1f) { val s = sqrt(l); out[0] = x / s; out[1] = y / s; out[2] = z / s; return }
        }
    }
    val u = FloatArray(3)
    // local frame: x = side, y = up, z = -along. Bond directions in the (along, side) plane.
    val bonds = t1AtomBonds()
    fun cloud(cx: Float, cy: Float, cz: Float, zeta1: Float, n1: Int, zeta2: Float, nHyb: Int, hyb: Boolean, dim: Float, lobes: Array<FloatArray>?) {
        // 1s core: r^2 e^{-2 zeta r} -> Gamma(3, 2 zeta)
        for (k in 0 until n1) {
            val r = t1Gamma(3, 2f * zeta1, rnd) * BOHR_PM / 8f
            dir(u); add(cx + u[0] * r, cy + u[1] * r, cz + u[2] * r, 0.92f, 0.96f, 1f, 0.8f * dim)
        }
        if (zeta2 <= 0f) return
        // valence: r^4 e^{-2 zeta r} -> Gamma(5, 2 zeta), angular part by rejection
        var placed = 0
        while (placed < nHyb) {
            val r = t1Gamma(5, 2f * zeta2, rnd) * BOHR_PM / 8f
            dir(u)
            val accept: Float; val pi: Boolean
            if (hyb && lobes != null) {
                // three sp2 hybrids (1/sqrt3 s + sqrt(2/3) p) and one p (pi) orbital across the plane
                val which = rnd.nextInt(4)
                if (which < 3) { val c = u[0] * lobes[which][0] + u[1] * lobes[which][1] + u[2] * lobes[which][2]; val a = 0.57735f + 1.41421f * c; accept = a * a / 3.96f; pi = false }
                else { val c = u[1] * T1_PI_N[1] + u[2] * T1_PI_N[2]; accept = c * c; pi = true }
            } else { accept = 1f; pi = false }
            if (rnd.nextFloat() > accept) continue
            if (pi) add(cx + u[0] * r, cy + u[1] * r, cz + u[2] * r, 0.80f, 0.55f, 1f, 0.6f * dim)
            else add(cx + u[0] * r, cy + u[1] * r, cz + u[2] * r, 0.45f, 0.95f, 1f, 0.6f * dim)
            placed++
        }
    }
    // the carbon (Clementi-Raimondi: 1s 5.673, 2p 1.568 per bohr)
    cloud(0f, 0f, 0f, 5.673f, 320, 1.568f, 1000, true, 1f, bonds)
    // sigma-bond density: electrons shared along each bond, peaking between the nuclei
    val bl0 = floatArrayOf(140f, 135f, 108f)
    for (k in 0..2) {
        val d = bl0[k] / 8f
        var placed = 0
        while (placed < 260) {
            val t = rnd.nextFloat()
            if (rnd.nextFloat() > 0.35f + 0.65f * sin(PI_F * t)) continue
            dir(u)
            val g = t1Gamma(2, 1.5f, rnd)                      // spread across the bond, ~0.1 A
            val c = u[0] * bonds[k][0] + u[1] * bonds[k][1] + u[2] * bonds[k][2]
            val px = u[0] - c * bonds[k][0]; val py = u[1] - c * bonds[k][1]; val pz = u[2] - c * bonds[k][2]
            add(bonds[k][0] * d * t + px * g, bonds[k][1] * d * t + py * g, bonds[k][2] * d * t + pz * g, 1f, 0.84f, 0.45f, 0.75f)
            placed++
        }
    }
    // its neighbours along the bonds, fainter: C (140 pm), N (135 pm), H (108 pm)
    val bl = floatArrayOf(140f, 135f, 108f)
    for (k in 0..2) {
        val d = bl[k] / 8f
        val x = bonds[k][0] * d; val y = bonds[k][1] * d; val z = bonds[k][2] * d
        when (k) {
            0 -> cloud(x, y, z, 5.673f, 80, 1.568f, 320, false, 0.6f, null)
            1 -> cloud(x, y, z, 6.665f, 80, 1.917f, 340, false, 0.6f, null)
            else -> cloud(x, y, z, 1.24f, 120, 0f, 0, false, 0.6f, null)     // hydrogen 1s (in the molecule, zeta ~1.24)
        }
    }
    return PointMesh(pts.toFloatArray())
}

internal fun StereoBodyRenderer.drawAtom(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp < 10.2f || rp > 11.7f) return
    val vis = t1Smooth(10.2f, 10.32f, rp)
    landmarkFade *= vis; colorShader.globalFade *= vis
    // as the craft grows again at the look-back, the atom shrinks away with the true scale
    val k = (1.2e-11 / shipLengthM(rp)).toFloat().coerceIn(0f, 1f)
    if (k < 0.004f) return
    val f = frameAt(i.toFloat())
    val off = t1ShipOff(f)
    val a0 = 1.0f; val s0 = off[0] + 1.2f; val u0 = off[1] + 0.5f
    GLES20.glDepthMask(false)
    for (m in 0 until 4) {
        val cl = t1Mesh("atom$m") { t1BuildAtom(m) }
        val al = 0.55f + 0.45f * sin(TAU * (seconds * 0.9f + m / 4f))
        t1Model(f, a0, s0, u0, k)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        val keep = colorShader.globalFade; colorShader.globalFade = keep * al
        colorShader.use(mvp, 3.2f, points = true)
        cl.draw(colorShader.positionHandle, colorShader.colorHandle)
        colorShader.globalFade = keep
    }
    // nuclei: fixed-size points of light (a soft halo and a bright core), never resolved
    val nuc = t1Mesh("atom.nuclei") {
        val dd = FloatArray(4 * 7)
        t1Put(dd, 0, 0f, 0f, 0f, floatArrayOf(1f, 0.96f, 0.88f), 1f)
        val bl = floatArrayOf(140f, 135f, 108f); val bd = t1AtomBonds()
        for (q in 0..2) { val d = bl[q] / 8f; t1Put(dd, q + 1, bd[q][0] * d, bd[q][1] * d, bd[q][2] * d, floatArrayOf(1f, 0.9f, 0.8f), 0.7f) }
        PointMesh(dd)
    }
    t1Model(f, a0, s0, u0, k)
    Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    val keep = colorShader.globalFade
    colorShader.globalFade = keep * 0.3f
    colorShader.use(mvp, 12f, points = true); nuc.draw(colorShader.positionHandle, colorShader.colorHandle)
    colorShader.globalFade = keep
    colorShader.use(mvp, 4f, points = true); nuc.draw(colorShader.positionHandle, colorShader.colorHandle)
    GLES20.glDepthMask(true)
}


/**
 * The look-back (the last stop of every tour): the craft grows out through the body and the whole
 * person comes into view — drawn at TRUE scale for the craft's current length (a 1.7 m person is
 * 1.7 m / Mote-length × 1.5 units tall), so she fills the view as the climb passes ~0.25 m and
 * recedes to a small figure beside the 12 m ship when the crew call "twelve metres". Skin is a
 * translucent shell over the major organs; every stop of this tour is marked where it happened
 * and joined in order, so the ride can be read back on the body. While the craft is still tiny
 * the body is shown as what it is at that scale: a cosmos of cells.
 */
internal fun StereoBodyRenderer.drawLookBack(n: TourNode, i: Int, seconds: Float) {
    val h = personHeightUnits()
    // A cosmos of cells while the person is still far too big to see whole.
    val cosmos = ((h - 30f) / 30f).coerceIn(0f, 1f)
    if (cosmos > 0.01f) {
        val keep = colorShader.globalFade
        colorShader.globalFade = keep * cosmos
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
        colorShader.globalFade = keep
    }
    if (h < 40f) drawPerson(n, i, h, ((40f - h) / 8f).coerceIn(0f, 1f), seconds)
    // Chapter III closes on the man himself: the scroll portrait, out among the cells.
    if (map.id == 3) drawPlate("portrait", frameAt(i + 0.12f), tunnelRadius(i + 0.12f) * 0.34f, tunnelRadius(i + 0.12f) * 0.10f, 3.6f, seconds)
}

/**
 * An upright person facing the craft, H units tall, feet at the bottom. Figure-space x is the
 * person's LEFT (+x) / RIGHT (-x) as seen from the front, y is up from the soles, z toward the
 * viewer. Proportions follow the classical eight-head canon.
 */
internal fun StereoBodyRenderer.drawPerson(n: TourNode, i: Int, H: Float, alpha: Float, seconds: Float) {
    val f = frameAt(routeProgress)
    // The craft holds station a fixed PHYSICAL distance from her while it grows: its bow 2.5 m from
    // her. In scene units that distance shrinks as the craft grows (1.5 units = the Mote), so she
    // keeps her angular size and only shrinks relative to the hull — which is what a growing ship
    // holding station in front of a person would actually see. She stands a little to one side of
    // the axis so the hull never blocks her from the chase camera.
    val lengthM = shipLengthM(routeProgress).toFloat()
    val ahead = 0.75f + 2.5f / lengthM * 1.5f
    val side = 0.9f + 0.28f * H
    val bx = shipX + f.dx * ahead + f.sx * side; val bz = shipZ + f.dz * ahead + f.sz * side
    val by = shipY - H * 0.5f
    // Figure axes in world space: across (x) = -side so her left is on the viewer's right; up = world up; toward viewer = -dir.
    val ax = -f.sx; val az = -f.sz
    val tx = -f.dx; val tz = -f.dz
    fun wx(x: Float, z: Float) = bx + ax * x * H + tx * z * H
    fun wz(x: Float, z: Float) = bz + az * x * H + tz * z * H
    fun wy(y: Float) = by + y * H
    val yaw = atan2(tx, tz) * 180f / PI.toFloat()
    val sway = 0.004f * sin(seconds * 0.6f)
    fun part(x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float, col: FloatArray, acc: FloatArray, a: Float, pat: Float = 0f, glow: Float = 0f) =
        drawSphereAt(wx(x + sway, z), wy(y), wz(x + sway, z), rx * H, ry * H, rz * H, col, acc, a * alpha, yaw, 0f, 1f, 0f, sphere, pat, glow)
    fun limb(x0: Float, y0: Float, x1: Float, y1: Float, r: Float, col: FloatArray, a: Float) {
        drawStrut(wx(x0 + sway, 0f), wy(y0), wz(x0 + sway, 0f), wx(x1 + sway, 0f), wy(y1), wz(x1 + sway, 0f), r * H, col, COL_SKIN_RIM, 0f)
    }

    // ---- organs first (opaque), so they read through the skin
    part(0f, 0.925f, 0.005f, 0.045f, 0.036f, 0.05f, COL_ORG_BRAIN, COL_LAMP, 1f, 0.8f)                 // brain
    for (sgn in SIGNS) part(sgn * 0.058f, 0.715f, 0f, 0.048f, 0.085f, 0.045f, COL_ORG_LUNG, COL_LAMP, 0.95f, 0.5f) // lungs
    part(0.02f, 0.695f, 0.03f, 0.032f, 0.036f, 0.028f, COL_ORG_HEART, COL_LAMP, 1f, 0f, 0.2f)          // heart, left of midline
    part(-0.045f, 0.615f, 0.01f, 0.075f, 0.035f, 0.05f, COL_ORG_LIVER, COL_LAMP, 1f)                    // liver, her right
    part(0.045f, 0.61f, 0.015f, 0.04f, 0.03f, 0.03f, COL_ORG_STOMACH, COL_LAMP, 1f)                     // stomach, her left
    for (sgn in SIGNS) part(sgn * 0.045f, 0.565f, -0.03f, 0.018f, 0.032f, 0.016f, COL_ORG_KIDNEY, COL_LAMP, 1f) // kidneys, posterior
    part(0f, 0.525f, 0.02f, 0.072f, 0.05f, 0.045f, COL_ORG_GUT, COL_LAMP, 1f, 1f)                       // small intestine
    part(0f, 0.465f, 0.025f, 0.022f, 0.02f, 0.02f, COL_ORG_BLADDER, COL_LAMP, 1f)                       // bladder
    for (k in 0 until 14) part(0f, 0.47f + k * 0.03f, -0.045f, 0.012f, 0.011f, 0.012f, COL_BONE, COL_LAMP, 1f) // spine
    for (sgn in SIGNS) limb(sgn * 0.07f, 0.46f, sgn * 0.075f, 0.28f, 0.012f, COL_BONE, 1f)              // femurs

    // ---- the tour's stops, where they happened, joined in order
    GLES20.glDepthMask(false)
    val arr = dynLines.data
    var v = 0
    for (k in 0 until nodes.size - 1) {
        val a0 = nodes[k]
        val mx = (a0.mapX - 50f) / 100f * 0.667f; val my = 1f - a0.mapY / 150f
        val hot = 0.9f
        part(-mx, my, 0.075f, 0.011f, 0.011f, 0.011f, COL_LAMP, COL_LAMP, 1f, 0f, hot)
        if (k + 1 < nodes.size - 1 && v + 14 <= arr.size) {
            val b0 = nodes[k + 1]
            val nx = (b0.mapX - 50f) / 100f * 0.667f; val ny = 1f - b0.mapY / 150f
            for ((qx, qy) in listOf(-mx to my, -nx to ny)) {
                arr[v++] = wx(qx + sway, 0.075f); arr[v++] = wy(qy); arr[v++] = wz(qx + sway, 0.075f)
                arr[v++] = 1f; arr[v++] = 0.77f; arr[v++] = 0.42f; arr[v++] = 0.8f * alpha
            }
        }
    }
    if (v > 0) {
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        colorShader.use(mvp, 1f)
        lineWidth(2f)
        dynLines.draw(colorShader.positionHandle, colorShader.colorHandle, GLES20.GL_LINES, v / 7)
        lineWidth(1f)
    }

    // ---- the translucent skin: head, neck, trunk, arms, legs (eight-head canon)
    part(0f, 0.93f, 0f, 0.058f, 0.068f, 0.064f, COL_SKIN_SHELL, COL_SKIN_RIM, 0.3f)                    // head
    limb(0f, 0.845f, 0f, 0.875f, 0.026f, COL_SKIN_SHELL, 0.3f)                                         // neck
    part(0f, 0.72f, 0f, 0.125f, 0.12f, 0.07f, COL_SKIN_SHELL, COL_SKIN_RIM, 0.28f)                     // chest
    part(0f, 0.575f, 0f, 0.105f, 0.085f, 0.062f, COL_SKIN_SHELL, COL_SKIN_RIM, 0.28f)                  // abdomen
    part(0f, 0.49f, 0f, 0.12f, 0.06f, 0.068f, COL_SKIN_SHELL, COL_SKIN_RIM, 0.28f)                     // pelvis
    for (sgn in SIGNS) {
        limb(sgn * 0.165f, 0.80f, sgn * 0.19f, 0.63f, 0.04f, COL_SKIN_SHELL, 0.3f)                      // upper arm
        limb(sgn * 0.19f, 0.63f, sgn * 0.205f, 0.47f, 0.032f, COL_SKIN_SHELL, 0.3f)                     // forearm
        part(sgn * 0.21f, 0.43f, 0f, 0.022f, 0.04f, 0.012f, COL_SKIN_SHELL, COL_SKIN_RIM, 0.3f)          // hand
        limb(sgn * 0.072f, 0.47f, sgn * 0.078f, 0.27f, 0.062f, COL_SKIN_SHELL, 0.28f)                   // thigh
        limb(sgn * 0.078f, 0.27f, sgn * 0.08f, 0.045f, 0.045f, COL_SKIN_SHELL, 0.28f)                   // shin
        part(sgn * 0.082f, 0.018f, 0.03f, 0.03f, 0.018f, 0.06f, COL_SKIN_SHELL, COL_SKIN_RIM, 0.3f)      // foot
    }
    GLES20.glDepthMask(true)
}
