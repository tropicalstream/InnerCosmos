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
internal val T1_EPIDERMIS = floatArrayOf(0.90f, 0.70f, 0.56f, 1f)
internal val T1_CORNEUM = floatArrayOf(0.98f, 0.92f, 0.82f, 1f)
internal val T1_MELANOCYTE = floatArrayOf(0.42f, 0.24f, 0.12f, 1f)
internal val T1_SEPTUM_N = floatArrayOf(0.92f, 0.58f, 0.58f, 1f)
internal val T1_MEATUS = floatArrayOf(0.30f, 0.08f, 0.10f, 1f)
internal val T1_TR_GAP = floatArrayOf(0.86f, 0.48f, 0.50f, 1f)
internal val T1_MUCUS = floatArrayOf(0.96f, 0.94f, 0.82f, 1f)
internal val T1_ALV_SM = floatArrayOf(0.85f, 0.45f, 0.45f, 1f)
internal val T1_ENDO_H = floatArrayOf(0.84f, 0.50f, 0.48f, 1f)
internal val T1_ROUGH = floatArrayOf(0.80f, 0.60f, 0.55f, 1f)
internal val T1_PERICYTE = floatArrayOf(0.80f, 0.60f, 0.85f, 1f)
internal val T1_ICS = floatArrayOf(0.70f, 0.36f, 0.22f, 1f)
internal val T1_GRANULE = floatArrayOf(0.62f, 0.56f, 0.70f, 1f)
internal val T1_MITO_OUT2 = floatArrayOf(0.98f, 0.80f, 0.55f, 1f)
internal val T1_H1 = floatArrayOf(0.95f, 0.85f, 1f, 1f)
internal val T1_DNA = floatArrayOf(1f, 0.86f, 0.52f, 1f)
internal val T1_TRNA2 = floatArrayOf(1f, 0.45f, 0.55f, 1f)
internal val T1_LIP = floatArrayOf(0.86f, 0.44f, 0.46f, 1f)
internal val T1_PUMP_DIM = floatArrayOf(0.85f, 0.55f, 0.38f, 1f)
internal val T1_RING_UNDER = floatArrayOf(0.95f, 0.86f, 0.84f, 1f)
internal val T1_DUSTG = floatArrayOf(0.30f, 0.20f, 0.10f, 1f)
internal val T1_RBC_OXY = floatArrayOf(0.90f, 0.10f, 0.12f, 1f)
internal val T1_RBC_DEOXY = floatArrayOf(0.50f, 0.05f, 0.10f, 1f)
internal val T1_CAP_DEEP = floatArrayOf(0.30f, 0.08f, 0.10f, 1f)
internal val T1_NEUT_NUC2 = floatArrayOf(0.45f, 0.25f, 0.75f, 1f)
internal val T1_MYELIN2 = floatArrayOf(1f, 1f, 0.95f, 1f)
internal val T1_CH_DIM = floatArrayOf(0.58f, 0.68f, 0.86f, 1f)
internal val T1_TR_DIM = floatArrayOf(0.60f, 0.76f, 0.54f, 1f)
internal val T1_ICS2 = floatArrayOf(0.45f, 0.20f, 0.10f, 1f)
internal val T1_MATRIX = floatArrayOf(0.12f, 0.16f, 0.18f, 1f)
internal val T1_HETERO = floatArrayOf(0.75f, 0.62f, 0.40f, 1f)
internal val T1_TRNA_A = floatArrayOf(1f, 0.55f, 0.65f, 1f)
internal val T1_TRNA_P = floatArrayOf(0.96f, 0.42f, 0.52f, 1f)
internal val T1_TRNA_E = floatArrayOf(0.80f, 0.35f, 0.45f, 1f)
internal val T1_PTC = floatArrayOf(1f, 0.95f, 0.70f, 1f)
internal val T1_DERMIS = floatArrayOf(0.93f, 0.60f, 0.60f, 1f)
internal val T1_FAT = floatArrayOf(0.98f, 0.88f, 0.52f, 1f)
internal val T1_MUCOSA = floatArrayOf(0.95f, 0.60f, 0.62f, 1f)
internal val T1_MUCOSA_RIM = floatArrayOf(1f, 0.82f, 0.80f, 1f)
internal val T1_CARTILAGE = floatArrayOf(0.97f, 0.84f, 0.82f, 1f)
internal val T1_CART_RIM = floatArrayOf(0.85f, 0.92f, 1f, 1f)
internal val T1_TRACHEALIS = floatArrayOf(0.80f, 0.40f, 0.42f, 1f)
internal val T1_VOCAL = floatArrayOf(0.97f, 0.95f, 0.91f, 1f)
internal val T1_EPIGLOTTIS = floatArrayOf(0.96f, 0.80f, 0.76f, 1f)
internal val T1_SEPTUM = floatArrayOf(0.97f, 0.82f, 0.82f, 1f)
internal val T1_FILM = floatArrayOf(0.92f, 0.96f, 1f, 1f)
internal val T1_TYPE2 = floatArrayOf(0.98f, 0.74f, 0.60f, 1f)
internal val T1_MACRO = floatArrayOf(0.88f, 0.80f, 0.70f, 1f)
internal val T1_ENDO_BULGE = floatArrayOf(0.88f, 0.58f, 0.60f, 1f)
internal val T1_ENDO_NUC = floatArrayOf(0.64f, 0.27f, 0.33f, 1f)
internal val T1_MYO = floatArrayOf(0.68f, 0.16f, 0.20f, 1f)
internal val T1_TRAB = floatArrayOf(0.56f, 0.11f, 0.15f, 1f)
internal val T1_MYO_RIM = floatArrayOf(0.98f, 0.50f, 0.50f, 1f)
internal val T1_VALVE = floatArrayOf(0.96f, 0.89f, 0.80f, 1f)
internal val T1_VALVE_P = floatArrayOf(0.88f, 0.70f, 0.62f, 1f)
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
internal val T1_MITO_IN = floatArrayOf(0.95f, 0.52f, 0.28f, 1f)
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
private val t1Dyn = DynMesh(2400)
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
private fun StereoBodyRenderer.t1BuildEndothelium(p0: Float, p1: Float, around: Int, cellLen: Float, seed: Int): Triple<LineMesh, T1Batch, T1Batch> {
    val rnd = java.util.Random(seed.toLong())
    val lines = ArrayList<Float>()
    val col = floatArrayOf(1f, 0.86f, 0.82f, 0.6f)
    val a = FloatArray(3); val b = FloatArray(3)
    val stepP = cellLen / 16f
    val rows = ((p1 - p0) / stepP).toInt()
    val dth = TAU / around
    val nuc = T1Builder(); val dark = T1Builder()
    fun wall(p: Float, th: Float, out: FloatArray, k: Float = 0.97f) = t1RailPoint(p, th, t1WallR(p, th) * k, out)
    fun line(p: Float, q: Float, th: Float, th2: Float) {
        wall(p, th, a); wall(q, th2, b)
        lines.add(a[0]); lines.add(a[1]); lines.add(a[2]); lines.add(col[0]); lines.add(col[1]); lines.add(col[2]); lines.add(col[3])
        lines.add(b[0]); lines.add(b[1]); lines.add(b[2]); lines.add(col[0]); lines.add(col[1]); lines.add(col[2]); lines.add(col[3])
    }
    for (j in 0..rows) {
        val pj = p0 + j * stepP
        // circumferential border: a gently wavy seam (two waves per cell width)
        val segs = around * 12
        for (k in 0 until segs) {
            val t0 = TAU * k / segs; val t1 = TAU * (k + 1) / segs
            line(pj + 0.015f / 16f * sin(t0 * around * 2f), pj + 0.015f / 16f * sin(t1 * around * 2f), t0, t1)
        }
        if (j == rows) break
        val off = if (j % 2 == 0) 0f else dth * 0.5f
        for (m in 0 until around) {
            val th = off + m * dth + (rnd.nextFloat() - 0.5f) * dth * 0.25f
            // longitudinal border
            val n = 16
            val wr = 0.015f / t1WallR(pj, th)
            for (k in 0 until n) {
                val q0 = pj + stepP * k / n; val q1 = pj + stepP * (k + 1) / n
                line(q0, q1, th + wr * sin(TAU * 4f * k / n), th + wr * sin(TAU * 4f * (k + 1) / n))
            }
            // nucleus at the cell centre
            val pc = pj + stepP * (0.4f + 0.2f * rnd.nextFloat()); val tc = th + dth * 0.5f
            val f = frameAt(pc)
            val r = t1WallR(pc, tc) * 0.99f
            val c = cos(tc); val s = sin(tc)
            val rx = f.sx * c + f.ux * s; val ry = f.sy * c + f.uy * s; val rz = f.sz * c + f.uz * s
            val tx = -f.sx * s + f.ux * c; val ty = -f.sy * s + f.uy * c; val tz = -f.sz * s + f.uz * c
            nuc.ellipsoid(f.cx + rx * r, f.cy + ry * r, f.cz + rz * r,
                floatArrayOf(tx * 0.32f, ty * 0.32f, tz * 0.32f), floatArrayOf(rx * 0.14f, ry * 0.14f, rz * 0.14f),
                floatArrayOf(f.dx * 0.75f, f.dy * 0.75f, f.dz * 0.75f), 6, 10)
            val r2 = r - 0.02f
            dark.ellipsoid(f.cx + rx * r2, f.cy + ry * r2, f.cz + rz * r2,
                floatArrayOf(tx * 0.22f, ty * 0.22f, tz * 0.22f), floatArrayOf(rx * 0.09f, ry * 0.09f, rz * 0.09f),
                floatArrayOf(f.dx * 0.55f, f.dy * 0.55f, f.dz * 0.55f), 5, 8)
        }
    }
    return Triple(LineMesh(lines.toFloatArray()), nuc.build(), dark.build())
}

private fun StereoBodyRenderer.t1DrawEndothelium(key: String, p0: Float, p1: Float, around: Int, cellLen: Float, seed: Int) {
    val m = t1Mesh(key) { t1BuildEndothelium(p0, p1, around, cellLen, seed) }
    t1LitWorld(m.third, T1_ENDO_NUC, T1_WHITE, 1f, 0.1f)
    GLES20.glDepthMask(false)
    t1LitWorld(m.second, T1_ENDO_BULGE, T1_MUCOSA_RIM, 0.55f, 0.1f)
    GLES20.glDepthMask(true)
    t1Color(m.first, null, 0f, 0f, 0f, 2f, false, 1f)
}


/**
 * Many red cells in one draw: a low-poly biconcave (or parachute-folded) template transformed on
 * the CPU once per frame into a VBO, then drawn for both eyes. Each drawBasis of the stock rbc mesh
 * costs 14 strip draws; a crowd of 90 cells here costs one.
 */
private class T1CellBatch(private val capacity: Int, cup: Float = 0f, ball: Boolean = false) : LitMesh() {
    private val tpl: FloatArray
    private val tplVerts: Int
    private val data: FloatArray
    private val buf: FloatBuffer
    private var vbo = 0
    private var count = 0
    var stamp = -1f

    init {
        val U = 8; val V = 10
        val pos = FloatArray((U + 1) * (V + 1) * 3); val nor = FloatArray((U + 1) * (V + 1) * 3)
        fun p(u: Float, v: Float, out: FloatArray, o: Int) {
            if (ball) { val ph = u * PI_F; val a = v * TAU; out[o] = sin(ph) * cos(a); out[o + 1] = cos(ph); out[o + 2] = sin(ph) * sin(a); return }
            val top = u < 0.5f
            val r = (if (top) 1f - u * 2f else (u - 0.5f) * 2f).coerceIn(0f, 0.999f)
            val q = r * r
            val h = 0.5f * sqrt(1f - q) * (0.81f + 7.83f * q - 4.39f * q * q) / 3.91f
            val a = v * TAU
            out[o] = cos(a) * r; out[o + 2] = sin(a) * r; out[o + 1] = (if (top) h else -h) + cup * q
        }
        val t = FloatArray(12)
        for (i in 0..U) for (j in 0..V) {
            val u = i.toFloat() / U; val v = j.toFloat() / V; val k = (i * (V + 1) + j) * 3
            p(u, v, pos, k)
            val e = 1e-3f
            p((u + e).coerceAtMost(1f), v, t, 0); p((u - e).coerceAtLeast(0f), v, t, 3)
            p(u, v + e, t, 6); p(u, v - e, t, 9)
            val ux = t[0] - t[3]; val uy = t[1] - t[4]; val uz = t[2] - t[5]
            val vx = t[6] - t[9]; val vy = t[7] - t[10]; val vz = t[8] - t[11]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 1e-12f) { nx /= l; ny /= l; nz /= l } else { nx = 0f; ny = if (u < 0.5f) 1f else -1f; nz = 0f }
            nor[k] = nx; nor[k + 1] = ny; nor[k + 2] = nz
        }
        val out = ArrayList<Float>()
        fun put(k: Int) { for (q in 0..2) out.add(pos[k + q]); for (q in 0..2) out.add(nor[k + q]) }
        for (i in 0 until U) for (j in 0 until V) {
            val a = (i * (V + 1) + j) * 3; val b = ((i + 1) * (V + 1) + j) * 3; val c = ((i + 1) * (V + 1) + j + 1) * 3; val d = (i * (V + 1) + j + 1) * 3
            put(a); put(b); put(c); put(a); put(c); put(d)
        }
        tpl = out.toFloatArray(); tplVerts = tpl.size / 6
        data = FloatArray(capacity * tpl.size)
        buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    fun reset() { count = 0 }

    /** Add a cell of radius r at (x,y,z): its disc normal (local y) along (yx,yy,yz), local z along (zx,zy,zz). */
    fun add(x: Float, y: Float, z: Float, zx0: Float, zy0: Float, zz0: Float, yx0: Float, yy0: Float, yz0: Float, r: Float) {
        if (count >= capacity) return
        var yl = sqrt(yx0 * yx0 + yy0 * yy0 + yz0 * yz0).coerceAtLeast(1e-6f)
        val Yx = yx0 / yl; val Yy = yy0 / yl; val Yz = yz0 / yl
        val d = zx0 * Yx + zy0 * Yy + zz0 * Yz
        var Zx = zx0 - d * Yx; var Zy = zy0 - d * Yy; var Zz = zz0 - d * Yz
        yl = sqrt(Zx * Zx + Zy * Zy + Zz * Zz)
        if (yl < 1e-5f) { Zx = if (abs(Yx) < 0.9f) 1f else 0f; Zy = if (abs(Yx) < 0.9f) 0f else 1f; Zz = 0f
            val d2 = Zx * Yx + Zy * Yy + Zz * Yz; Zx -= d2 * Yx; Zy -= d2 * Yy; Zz -= d2 * Yz; yl = sqrt(Zx * Zx + Zy * Zy + Zz * Zz) }
        Zx /= yl; Zy /= yl; Zz /= yl
        val Xx = Yy * Zz - Yz * Zy; val Xy = Yz * Zx - Yx * Zz; val Xz = Yx * Zy - Yy * Zx
        var w = count * tpl.size
        var k = 0
        while (k < tpl.size) {
            val px = tpl[k]; val py = tpl[k + 1]; val pz = tpl[k + 2]; val nx = tpl[k + 3]; val ny = tpl[k + 4]; val nz = tpl[k + 5]
            data[w] = x + (Xx * px + Yx * py + Zx * pz) * r
            data[w + 1] = y + (Xy * px + Yy * py + Zy * pz) * r
            data[w + 2] = z + (Xz * px + Yz * py + Zz * pz) * r
            data[w + 3] = Xx * nx + Yx * ny + Zx * nz
            data[w + 4] = Xy * nx + Yy * ny + Zy * nz
            data[w + 5] = Xz * nx + Yz * ny + Zz * nz
            w += 6; k += 6
        }
        count++
    }

    fun upload() {
        if (vbo == 0) { val ids = IntArray(1); GLES20.glGenBuffers(1, ids, 0); vbo = ids[0]
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, null, GLES20.GL_DYNAMIC_DRAW) }
        else GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val n = count * tpl.size
        if (n > 0) { buf.position(0); buf.put(data, 0, n); buf.position(0); GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, n * 4, buf) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun draw(positionHandle: Int, normalHandle: Int) {
        if (count == 0 || vbo == 0) return
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count * tplVerts)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }
}

/** Fill a cell batch once per frame (both eyes share it), then draw it lit in world space. */
private fun StereoBodyRenderer.t1Cells(key: String, capacity: Int, cup: Float, col: FloatArray, glow: Float, ball: Boolean = false, fill: (T1CellBatch) -> Unit) {
    val b = t1Mesh(key) { T1CellBatch(capacity, cup, ball) }
    if (b.stamp != nowSeconds) { b.reset(); fill(b); b.upload(); b.stamp = nowSeconds }
    t1LitWorld(b, col, COL_RBC_RIM, 1f, glow)
}

/**
 * Red cells flowing down a vessel: parabolic profile, a cell-free plasma layer ~3 um wide at the
 * wall, cells near the axis tumbling freely, cells near the wall aligned with the flow (disc in the
 * plane of the wall). [radius] = vessel radius in units; [n] cells over the span.
 */
private fun StereoBodyRenderer.t1FlowCells(b: T1CellBatch, pMin: Float, pMax: Float, radius: Float, n: Int, vmax: Float, seconds: Float, seed: Int, skipNear: Float = 1.6f, span: Float = 1.0f) {
    val rp = routeProgress
    val rMax = radius - 0.375f - 0.47f
    for (k in 0 until n) {
        val h = seed * 131 + k * 7
        val rf = sqrt(t1Hash(h + 1)); val ang = t1Hash(h + 2) * TAU
        val v = vmax * (1f - rf * rf * 0.85f)
        val base = rp - 0.25f + t1Hash(h) * span
        val pp = rp - 0.25f + (((base + seconds * v / 16f) - (rp - 0.25f)) % span + span) % span
        if (pp < pMin || pp > pMax) continue
        val f = frameAt(pp)
        val rr = rMax * rf
        val ca = cos(ang); val sa = sin(ang)
        val rx = f.sx * ca + f.ux * sa; val ry = f.sy * ca + f.uy * sa; val rz = f.sz * ca + f.uz * sa
        val x = f.cx + rx * rr; val y = f.cy + ry * rr; val z = f.cz + rz * rr
        if ((x - camNowX).pow(2) + (y - camNowY).pow(2) + (z - camNowZ).pow(2) < skipNear * skipNear) continue
        val t = seconds * (0.4f + 0.5f * t1Hash(h + 5)) + k
        val a1 = t1Hash(h + 7) * TAU
        var nx = sin(t) * cos(a1); var ny = cos(t); var nz = sin(t) * sin(a1)
        // near the wall: the disc lies in the wall's plane (normal radial), carried along the flow
        val al = t1Smooth(0.55f, 0.85f, rf)
        nx = nx * (1f - al) + rx * al; ny = ny * (1f - al) + ry * al; nz = nz * (1f - al) + rz * al
        b.add(x, y, z, f.dx, f.dy, f.dz, nx, ny, nz, 0.47f)
    }
}

// ================================================================ stop 0: THE THRESHOLD (nose, 12 mm rung)
// The nasal base seen from below, 1 unit = 8 mm: two teardrop nostrils (1.4 x 0.8 cm) whose tips
// converge toward the nose tip, a columella widening into its footplates, alar rims and alar-facial
// grooves, the tip lobule, and the upper lip with its philtrum; pores and fine vellus hairs on the
// skin; vibrissae sloping out of the vestibule and swaying with the breath. A 6 mm punch biopsy is
// lifted out of the lip, and beside it a x100 inset (joined by leader lines, with a 0.1 mm scale bar)
// shows its cut face: stratum corneum, the living epidermis - about as thick as a sheet of paper -
// with melanocytes along its base handing melanin up to the keratinocytes above, and the dermis
// with its papillae and capillary loops. The craft's nostril is centred on its lane.
// Face coordinates: X runs from the lip (-) to the nose tip (+), Y across (the other nostril at +Y).

private const val T1_FACE_P = 0.62f
private const val T1_TILT = -26f * DEG
private val T1_TILT_K = tan(26f * DEG)
private const val T1_NOS_ROT = 30f * DEG
private const val T1_MID = 0.95f

/** The craft's nostril outline: a teardrop, its apex turned 17 degrees toward the midline. */
private fun t1NostrilRim(th: Float, out: FloatArray) {
    val w = 0.5f * (1f - 0.28f * cos(th))
    val x = 0.85f * cos(th); val y = w * sin(th)
    out[0] = x * cos(T1_NOS_ROT) - y * sin(T1_NOS_ROT); out[1] = x * sin(T1_NOS_ROT) + y * cos(T1_NOS_ROT)
}

private fun t1Q4(x: Float) = x * x * x * x

/** Elliptical distance from a nostril centre in that nostril's own (rotated) axes. */
private fun t1NosDist(X: Float, Y: Float, cy: Float, rot: Float, ax: Float, ay: Float): Float {
    val dx = X; val dy = Y - cy
    val lx = dx * cos(rot) + dy * sin(rot); val ly = -dx * sin(rot) + dy * cos(rot)
    return sqrt((lx / ax) * (lx / ax) + (ly / ay) * (ly / ay))
}

private fun t1OtherNostril(X: Float, Y: Float): Float = t1NosDist(X, Y, 1.9f, -T1_NOS_ROT, 0.85f, 0.52f)
private const val T1_PIT_X = -2.0f
private const val T1_PIT_Y = -0.55f

/** Skin depth along the rail (negative = toward the viewer) at face coordinates (X, Y). */
private fun t1FaceDepth(X: Float, Y: Float): Float {
    val ym = Y - T1_MID
    var h = 0.02f * (X * X + ym * ym)                                  // the face curves gently away
    val xp = X - 0.4f
    val wx = (2.1f - 0.35f * xp).coerceAtLeast(0.5f)
    h -= 1.7f * exp(-(t1Q4(xp / 2.3f) + t1Q4(ym / wx)))                // the nasal base projects ~1.5 cm
    h -= 0.8f * exp(-((X - 2.0f) * (X - 2.0f) / 0.5f + ym * ym / 0.5f)) // lobule (tip)
    // the nasal dorsum rising from the tip toward the root of the nose, and the nose's side walls
    val dor = t1Smooth(1.6f, 2.6f, X) * (1f - t1Smooth(5.6f, 6.6f, X))
    h -= dor * (0.9f * exp(-ym * ym / 0.32f) + 0.9f * exp(-ym * ym / 2.2f) * (1f - t1Smooth(2.5f, 5.5f, X) * 0.5f))
    h += 1.5f * (ym / 5.45f).pow(2)                                    // the cheeks curve back
    for (k in 0..1) {
        val cy = if (k == 0) 0f else 1.9f; val rot = if (k == 0) T1_NOS_ROT else -T1_NOS_ROT
        val e = t1NosDist(X, Y, cy, rot, 0.85f, 0.55f)
        val q = (e - 1.25f) / 0.22f
        h -= 0.14f * exp(-q * q)                                         // alar rim
        val lateral = if (k == 0) t1Smooth(0.2f, -0.4f, Y) else t1Smooth(1.7f, 2.3f, Y)
        val g = (t1NosDist(X, Y, cy, rot, 1.1f, 0.8f) - 1.55f) / 0.16f
        h += 0.35f * exp(-g * g) * lateral                               // alar-facial groove
    }
    // columella footplates: the columella widens as it meets the lip
    h -= 0.12f * exp(-((X + 0.95f) * (X + 0.95f) / 0.08f + ym * ym / 0.12f))
    // the upper lip and its philtrum (two ridges either side of a groove), the vermilion's white roll
    h -= 0.5f * exp(-((X + 2.6f) * (X + 2.6f) / 0.6f + ym * ym / 4f))
    h += 0.12f * exp(-((X + 2.0f) * (X + 2.0f) / 0.8f + ym * ym / 0.05f))
    for (sg in SIGNS) h -= 0.07f * exp(-((X + 2.0f) * (X + 2.0f) / 0.8f + (ym - sg * 0.35f) * (ym - sg * 0.35f) / 0.02f))
    h -= 0.06f * exp(-((X + 3.15f) * (X + 3.15f) / 0.015f)) * exp(-ym * ym / 2.5f)
    // the punch-biopsy pit: a 6 mm cylindrical defect, 2 mm deep, cut into the lip
    val pr = sqrt((X - T1_PIT_X).pow(2) + (Y - T1_PIT_Y).pow(2))
    h += 0.25f * t1Smooth(0.42f, 0.34f, pr)
    return h
}

/** The patch's outer boundary: an ellipse about the columella base, cut at the lip and the cheeks. */
private fun t1PatchEdge(dx: Float, dy: Float): Float {
    // distance along the ray (dx, dy) from the craft's nostril centre to the ellipse
    // ((X - x0)/ax)^2 + ((Y - y0)/ay)^2 = 1 with x0 = -0.6, y0 = mid, ax = 3.0, ay = 3.2
    val x0 = 1.25f; val y0 = T1_MID; val ax = 5.25f; val ay = 5.45f
    val a = (dx / ax).pow(2) + (dy / ay).pow(2)
    val b = 2f * ((-x0) * dx / (ax * ax) + (-y0) * dy / (ay * ay))
    val c = (x0 / ax).pow(2) + (y0 / ay).pow(2) - 1f
    return (-b + sqrt((b * b - 4f * a * c).coerceAtLeast(0f))) / (2f * a)
}

private fun t1FacePoint(u: Float, v: Float, tmp: FloatArray, out: FloatArray) {
    t1NostrilRim(v * TAU, tmp)
    val r0 = sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1])
    val dx = tmp[0] / r0; val dy = tmp[1] / r0
    val R = t1PatchEdge(dx, dy)
    val r = r0 + (R - r0) * u.pow(1.5f)
    val X = dx * r; val Y = dy * r
    var h = t1FaceDepth(X, Y)
    val od = t1OtherNostril(X, Y)
    h += 0.4f * t1Smooth(1.08f, 0.96f, od) + 3.2f * t1Smooth(0.96f, 0.85f, od)   // the other nostril: its rim rolls into its vestibule
    if (u < 0.07f) { val k = 1f - u / 0.07f; h += 0.4f * k * k }       // our rim rolls into the vestibule
    out[0] = X; out[1] = Y; out[2] = -h
}

private class T1Hairs(val root: FloatArray, val mid: FloatArray, val tip: FloatArray, val side: FloatArray)

private fun t1BuildHairs(): T1Hairs {
    val tmp = FloatArray(2)
    val rnd = java.util.Random(31)
    val n = 64
    val root = FloatArray(n * 3); val mid = FloatArray(n * 3); val tip = FloatArray(n * 3); val side = FloatArray(n * 3)
    for (k in 0 until n) {
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
        root[3 * k] = rx + bx; root[3 * k + 1] = ry; root[3 * k + 2] = z0
        mid[3 * k] = mx + bx; mid[3 * k + 1] = my; mid[3 * k + 2] = mz
        tip[3 * k] = ex + bx; tip[3 * k + 1] = ey; tip[3 * k + 2] = ez
        side[3 * k] = -iy; side[3 * k + 1] = ix; side[3 * k + 2] = 0f
    }
    return T1Hairs(root, mid, tip, side)
}

private fun StereoBodyRenderer.t1FaceMeshes(): Array<ColorVboMesh> {
    val tmp = FloatArray(2)
    val rnd = java.util.Random(31)
    fun add(list: ArrayList<Float>, x: Float, y: Float, z: Float, c: FloatArray) { list.add(x); list.add(y); list.add(z); list.add(c[0]); list.add(c[1]); list.add(c[2]); list.add(c[3]) }
    // Pores (0.1-0.3 mm: sub-pixel dots, denser on the nose) and vellus hairs (1-2 mm) on the skin.
    val pores = ArrayList<Float>(); val vellus = ArrayList<Float>()
    val pc = floatArrayOf(0.46f, 0.29f, 0.24f, 0.95f); val vc = floatArrayOf(0.70f, 0.52f, 0.40f, 0.9f)
    var placed = 0; var tries = 0
    while (placed < 1100 && tries < 40000) {
        tries++
        val X = -4f + rnd.nextFloat() * 10.5f; val Y = T1_MID + (rnd.nextFloat() * 2f - 1f) * 5.45f
        val rr = sqrt(X * X + Y * Y)
        if (rr < 1e-3f) continue
        if (rr > t1PatchEdge(X / rr, Y / rr) * 0.97f) continue
        t1NostrilRim(atan2(Y, X), tmp)
        if (rr < sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1]) * 1.3f) continue
        if (t1OtherNostril(X, Y) < 1.3f) continue
        if ((X - T1_PIT_X).pow(2) + (Y - T1_PIT_Y).pow(2) < 0.25f) continue
        val xp = X - 0.4f; val yp = Y - T1_MID
        val m = exp(-(t1Q4(xp / 2.3f) + t1Q4(yp / (2.1f - 0.35f * xp).coerceAtLeast(0.5f))))
        if (rnd.nextFloat() > 0.3f + 0.7f * m) continue
        val h = t1FaceDepth(X, Y)
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
    return arrayOf(PointMesh(pores.toFloatArray()), LineMesh(vellus.toFloatArray()))
}

/** The biopsy core in face coordinates: e1(0..2), e2(3..5), top centre(6..8), axis k(9..11), surface point(12..14). */
private fun t1CoreFrame(): FloatArray {
    val Xc = T1_PIT_X; val Yc = T1_PIT_Y
    val sz = -t1FaceDepth(Xc, Yc)
    var kx = -0.3f; var ky = -0.25f; var kz = 1f
    val kl = sqrt(kx * kx + ky * ky + kz * kz); kx /= kl; ky /= kl; kz /= kl
    var e1x = kz; var e1y = 0f; var e1z = -kx
    val l1 = sqrt(e1x * e1x + e1y * e1y + e1z * e1z); e1x /= l1; e1y /= l1; e1z /= l1
    val e2x = ky * e1z - kz * e1y; val e2y = kz * e1x - kx * e1z; val e2z = kx * e1y - ky * e1x
    val lift = 1.0f
    return floatArrayOf(e1x, e1y, e1z, e2x, e2y, e2z, Xc + kx * lift, Yc + ky * lift, sz + kz * lift, kx, ky, kz, Xc, Yc, sz)
}

// The x100 inset, in its own coordinates: x across (Y of the face), y = depth into the skin (down the
// screen = -X of the face), z toward the viewer. 1 unit of inset = 20 um of skin (0.1 mm = 5 units x
// 0.125). The slab is 1.5 wide (0.24 mm), 1.2 deep (0.19 mm), 0.35 thick.
private const val T1_INSET_X = 1.2f        // face X of the slab's skin surface
private const val T1_INSET_Y = -4.3f       // face Y of the slab's centre
private const val T1_INSET_Z = 3.6f        // toward the viewer
private const val T1_INS_K = 2f           // the slab drawn twice as large (x100 overall)
private const val T1_INSET_W = 1.5f
private const val T1_EPI = 0.62f           // epidermis 0.1 mm at x50 (with the stratum corneum)
private const val T1_SC = 0.08f

private fun t1Junction(x: Float) = T1_EPI + 0.07f * sin(x * 8.5f + 0.6f)     // rete ridges / dermal papillae

private class T1Inset(val corneum: T1Batch, val epi: T1Batch, val dermis: T1Batch, val top: T1Batch, val melano: T1Batch, val lines: LineMesh, val dots: PointMesh, val red: LineMesh)

private fun t1BuildInset(): T1Inset {
    val W = T1_INSET_W; val hw = W / 2f; val D = 1.2f; val T = 0.35f
    // face coords: X = T1_INSET_X - y, Y = T1_INSET_Y + x, zloc = T1_INSET_Z + z
    fun fc(x: Float, y: Float, z: Float, out: FloatArray) { out[0] = T1_INSET_X - y * T1_INS_K; out[1] = T1_INSET_Y + x * T1_INS_K; out[2] = T1_INSET_Z + z * T1_INS_K }
    val cor = T1Builder(); val epi = T1Builder(); val der = T1Builder(); val top = T1Builder(); val mel = T1Builder()
    val q = FloatArray(3)
    // cut face (z = +T/2) and a sliver of the side face
    cor.surface(1, 20) { u, v, out -> val x = -hw + W * v; fc(x, T1_SC * u, T / 2f, out) }
    epi.surface(1, 60) { u, v, out -> val x = -hw + W * v; fc(x, T1_SC + (t1Junction(x) - T1_SC) * u, T / 2f, out) }
    der.surface(1, 60) { u, v, out -> val x = -hw + W * v; fc(x, t1Junction(x) + (D - t1Junction(x)) * u, T / 2f, out) }
    der.surface(1, 10) { u, v, out -> fc(hw, D * v, T / 2f - T * u, out) }
    top.surface(4, 12) { u, v, out -> val x = -hw + W * v; fc(x, 0f, T / 2f - T * u, out) }
    val lines = ArrayList<Float>(); val dots = ArrayList<Float>(); val red = ArrayList<Float>()
    fun add(l: ArrayList<Float>, x: Float, y: Float, c: FloatArray) { fc(x, y, T / 2f + 0.004f, q); l.add(q[0]); l.add(q[1]); l.add(q[2]); l.add(c[0]); l.add(c[1]); l.add(c[2]); l.add(c[3]) }
    val cellC = floatArrayOf(0.62f, 0.38f, 0.30f, 0.9f); val flake = floatArrayOf(0.98f, 0.93f, 0.86f, 1f)
    val nucC = floatArrayOf(0.48f, 0.30f, 0.52f, 1f); val melC = floatArrayOf(0.30f, 0.16f, 0.07f, 1f)
    val rnd = java.util.Random(9)
    // stratum corneum: flattened dead squames, stacked like roof tiles
    for (row in 0 until 3) { val y = 0.015f + row * 0.025f
        var x = -hw + rnd.nextFloat() * 0.1f
        while (x < hw) { val l = 0.12f + rnd.nextFloat() * 0.08f; add(lines, x, y, flake); add(lines, (x + l).coerceAtMost(hw), y + 0.004f, flake); x += l + 0.02f } }
    // keratinocytes: rows of polygonal cells, flattening toward the surface; nuclei, and melanin caps
    // over the nuclei in the lower layers (melanin handed up from the melanocytes)
    val rows = 7
    for (row in 0 until rows) {
        val y0 = T1_SC + (T1_EPI - T1_SC) * row / rows
        val y1 = T1_SC + (T1_EPI - T1_SC) * (row + 1) / rows
        val cw = if (row < 2) 0.16f else 0.11f
        var x = -hw + (if (row % 2 == 0) 0f else cw * 0.5f)
        while (x < hw - 0.01f) {
            val x1 = (x + cw).coerceAtMost(hw)
            val yb = if (row == rows - 1) min(y1, t1Junction((x + x1) / 2f) - 0.01f) else y1
            add(lines, x, y0, cellC); add(lines, x1, y0, cellC)
            add(lines, x, y0, cellC); add(lines, x, yb, cellC)
            val cx = (x + x1) / 2f; val cy = (y0 + yb) / 2f
            if (row >= 2) {
                fc(cx, cy, T / 2f + 0.005f, q); dots.add(q[0]); dots.add(q[1]); dots.add(q[2]); dots.addAll(nucC.toList())
                if (row >= 4) for (g in -1..1) {               // supranuclear melanin cap
                    fc(cx + g * 0.012f, cy - 0.025f, T / 2f + 0.006f, q); dots.add(q[0]); dots.add(q[1]); dots.add(q[2]); dots.addAll(melC.toList())
                }
            }
            x = x1
        }
    }
    // the dermo-epidermal junction (basement membrane) as a line
    val bm = floatArrayOf(0.95f, 0.85f, 0.80f, 1f)
    for (k in 0 until 60) { val xa = -hw + W * k / 60f; val xb = -hw + W * (k + 1) / 60f; add(lines, xa, t1Junction(xa), bm); add(lines, xb, t1Junction(xb), bm) }
    // melanocytes on the basement membrane, one to every ~8 basal cells, dendrites reaching up
    val melDen = floatArrayOf(0.36f, 0.20f, 0.10f, 1f)
    var mx = -hw + 0.12f
    while (mx < hw - 0.08f) {
        val my = t1Junction(mx) - 0.035f
        fc(mx, my, T / 2f + 0.01f, q)
        mel.ellipsoid(q[0], q[1], q[2], floatArrayOf(0f, 0.06f * T1_INS_K, 0f), floatArrayOf(-0.035f * T1_INS_K, 0f, 0f), floatArrayOf(0f, 0f, 0.03f * T1_INS_K), 5, 8)
        for (d in -1..1) {
            val ex = mx + d * 0.07f; val ey = my - 0.13f - 0.03f * (1 - abs(d))
            add(lines, mx, my, melDen); add(lines, ex, ey, melDen)
            for (g in 0..1) { fc(ex + (g - 0.5f) * 0.015f, ey - 0.01f * g, T / 2f + 0.006f, q); dots.add(q[0]); dots.add(q[1]); dots.add(q[2]); dots.addAll(melC.toList()) }
        }
        mx += 0.36f
    }
    // dermis: collagen bundles, and a capillary loop up into each dermal papilla
    val coll = floatArrayOf(0.98f, 0.78f, 0.78f, 0.8f); val cap = floatArrayOf(0.85f, 0.10f, 0.14f, 1f)
    for (k in 0 until 14) { val y = T1_EPI + 0.15f + rnd.nextFloat() * (D - T1_EPI - 0.2f); val x = -hw + rnd.nextFloat() * (W - 0.3f)
        add(lines, x, y, coll); add(lines, x + 0.25f, y + 0.03f, coll) }
    val step = TAU / 8.5f
    for (k in -4..4) {
        // papillae are where the junction rises highest: sin(8.5x + 0.6) = -1
        val xc = (1.5f * PI_F - 0.6f) / 8.5f + k * step
        if (xc > -hw + 0.05f && xc < hw - 0.05f) {
            val yt = t1Junction(xc) + 0.03f
            add(red, xc - 0.03f, D * 0.8f, cap); add(red, xc - 0.03f, yt + 0.02f, cap)
            add(red, xc - 0.03f, yt + 0.02f, cap); add(red, xc + 0.03f, yt + 0.02f, cap)
            add(red, xc + 0.03f, yt + 0.02f, cap); add(red, xc + 0.03f, D * 0.8f, cap)
        }
    }
    // the scale bar: 0.1 mm (0.625 units at x50) along the slab's lower edge, with end ticks
    val sb = floatArrayOf(1f, 1f, 1f, 1f)
    add(lines, -hw, D + 0.08f, sb); add(lines, -hw + 0.625f, D + 0.08f, sb)
    add(lines, -hw, D + 0.04f, sb); add(lines, -hw, D + 0.12f, sb)
    add(lines, -hw + 0.625f, D + 0.04f, sb); add(lines, -hw + 0.625f, D + 0.12f, sb)
    return T1Inset(cor.build(), epi.build(), der.build(), top.build(), mel.build(), LineMesh(lines.toFloatArray()), PointMesh(dots.toFloatArray()), LineMesh(red.toFloatArray()))
}

/** Leader lines from a square on the core's epidermis to the inset's corners (face coordinates). */
private fun t1BuildLeaders(): LineMesh {
    val c = t1CoreFrame()
    val out = ArrayList<Float>()
    val col = floatArrayOf(0.85f, 0.85f, 0.92f, 0.7f)
    val hw = T1_INSET_W / 2f
    val corners = arrayOf(floatArrayOf(-hw, 0f, 0.175f), floatArrayOf(hw, 0f, 0.175f), floatArrayOf(-hw, 1.2f, 0.175f), floatArrayOf(hw, 1.2f, 0.175f))
    val sq = 0.06f
    val src = arrayOf(floatArrayOf(-sq, -sq), floatArrayOf(sq, -sq), floatArrayOf(-sq, sq), floatArrayOf(sq, sq))
    fun add(x: Float, y: Float, z: Float) { out.add(x); out.add(y); out.add(z); out.add(col[0]); out.add(col[1]); out.add(col[2]); out.add(col[3]) }
    for (k in 0..3) {
        // a point on the core's top (epidermal) face, 0.2 from its centre toward the inset
        val a = src[k][0]; val b = src[k][1]
        val sx = c[6] + c[0] * (a + 0.0f) + c[3] * (b - 0.2f) + c[9] * 0.004f
        val sy = c[7] + c[1] * (a + 0.0f) + c[4] * (b - 0.2f) + c[10] * 0.004f
        val sz = c[8] + c[2] * (a + 0.0f) + c[5] * (b - 0.2f) + c[11] * 0.004f
        val t = corners[k]
        add(sx, sy, sz); add(T1_INSET_X - t[1] * T1_INS_K, T1_INSET_Y + t[0] * T1_INS_K, T1_INSET_Z + t[2] * T1_INS_K)
    }
    // the little square itself
    val sqp = Array(4) { k -> val a = src[k][0]; val b = src[k][1]
        floatArrayOf(c[6] + c[0] * a + c[3] * (b - 0.2f) + c[9] * 0.004f, c[7] + c[1] * a + c[4] * (b - 0.2f) + c[10] * 0.004f, c[8] + c[2] * a + c[5] * (b - 0.2f) + c[11] * 0.004f) }
    for ((i0, i1) in listOf(0 to 1, 1 to 3, 3 to 2, 2 to 0)) { add(sqp[i0][0], sqp[i0][1], sqp[i0][2]); add(sqp[i1][0], sqp[i1][1], sqp[i1][2]) }
    return LineMesh(out.toFloatArray())
}

/** Node 0: the nose. */
internal fun StereoBodyRenderer.drawThreshold(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp > 1.0f) return
    val f = frameAt(T1_FACE_P)
    // centred in the passage while far off; lined up on the craft as it closes on the nostril
    val off = t1ShipOff(f); val fol = t1Smooth(0.25f, 0.5f, rp); val so = off[0] * fol; val uo = off[1] * fol
    val aCam = t1Along(f, camNowX, camNowY, camNowZ)
    // The nasal base as in a basal view - tip up, nostrils side by side - tilted 26 degrees so the tip
    // comes toward us. Face X runs up the screen, face Y toward +side (the other nostril, the septum's side).
    val ct = cos(T1_TILT); val st = -sin(T1_TILT)
    val fT = StereoBodyRenderer.Frame(f.cx + f.sx * so + f.ux * uo, f.cy + f.sy * so + f.uy * uo, f.cz + f.sz * so + f.uz * uo,
        f.dx * ct + f.ux * st, f.dy * ct + f.uy * st, f.dz * ct + f.uz * st,
        f.ux * ct - f.dx * st, f.uy * ct - f.dy * st, f.uz * ct - f.dz * st,
        f.sx, f.sy, f.sz)
    val face = ((-aCam - 0.3f) / 1.6f).coerceIn(0f, 1f)
    if (face > 0.01f) {
        val tmp = FloatArray(2)
        // the skin, in three bands so its edge fades out instead of ending in a rim
        val bands = floatArrayOf(0f, 0.82f, 0.91f, 1f)
        val bandA = floatArrayOf(1f, 0.55f, 0.2f)
        for (b in 0..2) {
            val skin = t1Mesh("face$b") { ParamMesh(if (b == 0) 72 else 5, 144) { u, v, out -> t1FacePoint(bands[b] + (bands[b + 1] - bands[b]) * u, v, tmp, out) } }
            if (b > 0 || face < 0.999f) GLES20.glDepthMask(false)
            t1Lit(skin, fT, 0f, 0f, 0f, T1_SKIN, T1_SKIN_RIM, face * bandA[b], 0.12f)
            GLES20.glDepthMask(true)
        }
        // the red of the upper lip, below the white roll
        val lip = t1Mesh("face.lip") { ParamMesh(4, 30) { u, v, out ->
            val Y = T1_MID + (v - 0.5f) * 3.2f * (1f - 0.35f * (2f * v - 1f).pow(2))
            val X = -3.2f - 0.45f * u * (1f - (2f * v - 1f).pow(4))
            out[0] = X; out[1] = Y; out[2] = -(t1FaceDepth(X, Y) - 0.015f) } }
        t1Lit(lip, fT, 0f, 0f, 0f, T1_LIP, T1_SKIN_RIM, face, 0.12f)
        val vest = t1Mesh("vestibule") { ParamMesh(10, 48) { u, v, out ->
            t1NostrilRim(v * TAU, tmp)
            val sh = 1f - 0.15f * u
            out[0] = tmp[0] * sh - T1_TILT_K * 1.7f * u; out[1] = tmp[1] * sh; out[2] = -(t1FaceDepth(tmp[0], tmp[1]) + 0.4f + 1.7f * u)
        } }
        val lines = t1Mesh("face.lines") { t1FaceMeshes() }
        if (face < 0.999f) GLES20.glDepthMask(false)
        t1Lit(vest, fT, 0f, 0f, 0f, T1_VESTIBULE, T1_MUCOSA_RIM, face, 0.12f)
        // the other nostril: the same vestibule, mirrored across the columella, its lumen dark red deep inside
        val vest2 = t1Mesh("vestibule2") { ParamMesh(10, 48) { u, v, out ->
            t1NostrilRim(v * TAU, tmp)
            val sh = 1f - 0.15f * u
            val X = tmp[0]; val Y = 2f * T1_MID - tmp[1]
            out[0] = X * sh - T1_TILT_K * 1.7f * u; out[1] = 2f * T1_MID - tmp[1] * sh; out[2] = -(t1FaceDepth(X, Y) + 0.4f + 1.7f * u)
        } }
        t1Lit(vest2, fT, 0f, 0f, 0f, T1_VESTIBULE, T1_MUCOSA_RIM, face, 0.12f)
        t1Shape(t1Disc(), fT, t1FaceDepth(0f, 1.9f) + 2.08f, -T1_TILT_K * 1.7f, 1.9f, 1f, 0f, 0f, 0f, cos(-T1_NOS_ROT), sin(-T1_NOS_ROT),
            0.44f, 0.74f, 1f, T1_MEATUS, T1_MEATUS, face)
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
        at(0.0035f, q); t1Shape(cyl, fT, q[0], q[1], q[2], kA, kS, kU, e1A, e1S, e1U, 0.365f, 0.365f, 0.0065f, T1_EPIDERMIS, T1_SKIN_RIM, face, 0.2f)
        at(0f, q); t1Shape(disc, fT, q[0], q[1], q[2], kA, kS, kU, e1A, e1S, e1U, 0.36f, 0.36f, 1f, T1_SKIN, T1_SKIN_RIM, face, 0.12f)
        // the pit it came out of: its wall shows the same layers, a thin epidermis over dermis over fat
        val sf = -c[14] - 0.25f                     // the skin surface round the pit (c[14] is its floor)
        t1Shape(cyl, fT, sf + 0.012f, c[12], c[13], 1f, 0f, 0f, 0f, 1f, 0f, 0.345f, 0.345f, 0.012f, T1_EPIDERMIS, T1_SKIN_RIM, face, 0.2f)
        t1Shape(cyl, fT, sf + 0.084f, c[12], c[13], 1f, 0f, 0f, 0f, 1f, 0f, 0.345f, 0.345f, 0.06f, T1_DERMIS, T1_DERMIS, face, 0.15f)
        t1Shape(cyl, fT, sf + 0.194f, c[12], c[13], 1f, 0f, 0f, 0f, 1f, 0f, 0.345f, 0.345f, 0.05f, T1_FAT, T1_FAT, face, 0.15f)
        t1Shape(disc, fT, sf + 0.243f, c[12], c[13], 1f, 0f, 0f, 0f, 1f, 0f, 0.345f, 0.345f, 1f, T1_FAT, T1_FAT, face, 0.1f)
        if (face < 0.999f) GLES20.glDepthMask(true)
        // the x100 inset of the biopsy's cut face
        val ins = t1Mesh("face.inset") { t1BuildInset() }
        val inA = face * t1Smooth(0.05f, 0.25f, 1f - rp.coerceAtMost(0.5f) * 2f)
        if (inA > 0.01f) {
            t1Lit(ins.corneum, fT, 0f, 0f, 0f, T1_CORNEUM, T1_WHITE, inA, 0.3f)
            t1Lit(ins.epi, fT, 0f, 0f, 0f, T1_EPIDERMIS, T1_WHITE, inA, 0.3f)
            t1Lit(ins.dermis, fT, 0f, 0f, 0f, T1_DERMIS, T1_WHITE, inA, 0.3f)
            t1Lit(ins.top, fT, 0f, 0f, 0f, T1_SKIN, T1_SKIN_RIM, inA, 0.25f)
            t1Lit(ins.melano, fT, 0f, 0f, 0f, T1_MELANOCYTE, T1_WHITE, inA, 0.2f)
            t1Color(ins.lines, fT, 0f, 0f, 0f, 1.5f, false, inA)
            t1Color(ins.red, fT, 0f, 0f, 0f, 3.5f, false, inA)
            t1Color(ins.dots, fT, 0f, 0f, 0f, 2.5f, true, inA)
            t1Color(t1Mesh("face.leaders") { t1BuildLeaders() }, fT, 0f, 0f, 0f, 1f, false, inA * 0.8f)
        }
        // vibrissae, swaying with the breath (more on the inhale)
        val hs = t1Mesh("face.hairs") { t1BuildHairs() }
        val br = t1Breath(seconds)
        val amp = 0.05f + 0.05f * max(0f, sin(br * TAU))
        val d = t1Dyn.data
        var v = 0
        val hc = floatArrayOf(0.32f, 0.22f, 0.16f, 1f)
        fun wv(x: Float, y: Float, zl: Float) { v = t1Put(d, v, fx(fT, -zl, x, y), fy(fT, -zl, x, y), fz(fT, -zl, x, y), hc, 1f) }
        for (side in 0..1) {
            v = 0
            fun my(y: Float) = if (side == 0) y else 2f * T1_MID - y
            for (k in 0 until hs.root.size / 3) {
                val sw = amp * sin(seconds * 1.1f + k * 0.9f + side)
                val o = 3 * k
                val mx = hs.mid[o] + hs.side[o] * sw * 0.4f; val myy = hs.mid[o + 1] + hs.side[o + 1] * sw * 0.4f
                val ex = hs.tip[o] + hs.side[o] * sw; val ey = hs.tip[o + 1] + hs.side[o + 1] * sw
                wv(hs.root[o], my(hs.root[o + 1]), hs.root[o + 2]); wv(mx, my(myy), hs.mid[o + 2])
                wv(mx, my(myy), hs.mid[o + 2]); wv(ex, my(ey), hs.tip[o + 2])
            }
            t1DynDraw(v, GLES20.GL_LINES, 2.5f, face)
        }
        // the inspired air: streaks converging on the craft's nostril on the inhale
        val insp = max(0f, sin(br * TAU))
        if (insp > 0.05f) {
            v = 0
            val ac = floatArrayOf(0.9f, 0.9f, 1f, 1f)
            for (k in 0 until 24) {
                val ang = TAU * k / 24f + 0.3f * t1Hash(k)
                val ph = (seconds * 0.8f + t1Hash(k + 40)) % 1f
                val r = 3.0f - 2.4f * ph
                val x = cos(ang) * r * 0.8f; val y = sin(ang) * r * 0.6f
                val x2 = cos(ang) * (r - 0.3f) * 0.8f; val y2 = sin(ang) * (r - 0.3f) * 0.6f
                val z = -(t1FaceDepth(x, y) - 0.3f - 0.4f * (1f - ph)); val z2 = -(t1FaceDepth(x2, y2) - 0.3f - 0.4f * (1f - ph) + 0.1f)
                val a = 0.4f * insp * sin(PI_F * ph)
                v = t1Put(d, v, fx(fT, -z, x, y), fy(fT, -z, x, y), fz(fT, -z, x, y), ac, 0f)
                v = t1Put(d, v, fx(fT, -z2, x2, y2), fy(fT, -z2, x2, y2), fz(fT, -z2, x2, y2), ac, a)
            }
            t1DynDraw(v, GLES20.GL_LINES, 2f, face, depthWrite = false)
        }
        if (quality < 2) {
            t1Color(lines[0], fT, 0f, 0f, 0f, 2f, true, face * 0.9f, depthWrite = false)   // pores
            t1Color(lines[1], fT, 0f, 0f, 0f, 1f, false, face * 0.7f, depthWrite = false) // vellus hairs
        }
    }
    // Inside: the nasal cavity - the flat septum on the medial side, and on the lateral wall the three
    // scroll-like conchae (turbinates), each over its dark meatus. The craft flies the common meatus.
    if (rp > 0.4f && rp < 0.85f) {
        val ft = frameAt(0.74f)
        val o2 = t1ShipOff(ft); val so2 = o2[0]; val uo2 = o2[1]
        val cav = t1Smooth(0.4f, 0.5f, rp) * (1f - t1Smooth(0.78f, 0.85f, rp))
        landmarkFade *= cav; colorShader.globalFade *= cav
        val sep = t1Mesh("nose.septum") { ParamMesh(2, 2) { u, v, out -> out[0] = 0f; out[1] = -2.2f + 4.4f * u; out[2] = -(-3f + 7f * v) } }
        t1Lit(sep, ft, 0f, so2 + 1.55f, uo2, T1_SEPTUM_N, T1_MUCOSA_RIM, 1f, 0.15f)
        t1Lit(sep, ft, 0f, so2 - 2.1f, uo2, T1_MUCOSA, T1_MUCOSA_RIM, 1f, 0.1f)
        val scroll = t1Mesh("nose.concha") { ParamMesh(12, 10) { u, v, out ->
            // a curled shelf: out from the lateral wall, then rolling down (x = toward the septum)
            val a = u * 1.25f * PI_F
            out[0] = 0.55f * sin(a).coerceAtLeast(-0.2f) + 0.15f * u; out[1] = 0.35f * cos(a); out[2] = -(-1f + 2f * v)
        } }
        val cu = floatArrayOf(1.2f, 0.25f, -0.85f); val cl = floatArrayOf(2.2f, 3.0f, 3.5f); val cs = floatArrayOf(0.7f, 1.0f, 1.2f)
        for (k in 0..2) {
            t1Model(ft, 0.6f + 0.5f * k, so2 - 2.05f, uo2 + cu[k]); Matrix.scaleM(model, 0, cs[k], cs[k], cl[k] * 0.5f)
            drawLitModel(scroll, T1_MUCOSA, T1_MUCOSA_RIM, landmarkFade, 0f, 0.15f)
            t1Shape(sphere, ft, 0.6f + 0.5f * k, so2 - 2.05f, uo2 + cu[k] - 0.45f * cs[k], 1f, 0f, 0f, 0f, 1f, 0f, 0.05f, 0.12f * cs[k], cl[k] * 0.45f, T1_MEATUS, T1_MEATUS, 1f)
        }
    }
}

// ================================================================ stop 1: THE AIRWAY (larynx and trachea, 12 mm rung)
// 1 unit = 8 mm. The larynx: epiglottis on the anterior wall, vestibular folds above the pearly
// vocal folds in a V with its apex anterior, the arytenoid mounds behind. Then the trachea, 2 cm
// across (radius 1.25) and 11 cm long to the carina: sixteen C-shaped hyaline rings 4 mm tall on a
// 7 mm pitch, low ridges under a translucent pink mucosa, open posteriorly (-up) over 90 degrees
// where the trachealis (membranous wall, with its longitudinal elastic folds) closes the gap. The
// ciliated lining shimmers in metachronal waves travelling up toward the larynx, and a thin
// mucus blanket carries dust cranially at 1 cm/min. At the carina the trachea divides: a keel
// between the wider, straighter right main bronchus (25 degrees off the tracheal axis) which the
// craft takes, and the narrower left (45 degrees); inside the right, the next fork already shows.

private const val T1_TR0 = 0.865f          // trachea begins just below the vocal folds
private const val T1_RING0 = 0.875f
private const val T1_RINGS = 16
private const val T1_RING_STEP = 0.055f    // 7 mm
private const val T1_CARINA = 1.78f
private const val T1_TR_R = 1.25f

private class T1Trachea(val tube: T1Batch, val membr: T1Batch, val rings: T1Batch, val shell: T1Batch, val mucus: T1Batch, val folds: LineMesh,
                        val cap: T1Batch, val bronchi: T1Batch, val keel: T1Batch, val holes: T1Batch, val sparkle: FloatArray)

/** Union of the two bronchial mouths (side, up), seen from (-0.5, 0): exit distance along direction a. */
private fun t1MouthExit(a: Float, circles: FloatArray): Float {
    val wx = cos(a); val wy = sin(a)
    var best = 0f
    var k = 0
    while (k < circles.size) {
        val cx = circles[k] + 0.5f; val cy = circles[k + 1]; val r = circles[k + 2]
        val b = cx * wx + cy * wy
        val disc = b * b - (cx * cx + cy * cy) + r * r
        if (disc > 0f) best = max(best, b + sqrt(disc))
        k += 3
    }
    return best
}

/** Tracheal radius at rail progress p and wall angle th: D-shaped (flat posterior membranous wall), flaring a little at the carina. */
private fun t1TrR(p: Float, th: Float, inset: Float = 0f): Float {
    val R = T1_TR_R + 0.2f * t1Smooth(T1_CARINA - 0.12f, T1_CARINA, p) - inset
    var a = (th / DEG) % 360f; if (a < 0f) a += 360f
    return if (a > 225f && a < 315f) R * cos(45f * DEG) / cos((a - 270f) * DEG) else R
}

private fun StereoBodyRenderer.t1BuildTracheaMesh(): T1Trachea {
    val tube = T1Builder(); val membr = T1Builder(); val rings = T1Builder(); val shell = T1Builder(); val mucus = T1Builder()
    val pEnd = T1_CARINA - 0.02f
    // the wall: anterior and lateral mucosa over the rings, and the flat posterior membranous strip
    tube.surface(70, 24) { u, v, out -> val p = T1_TR0 + (pEnd - T1_TR0) * u; val th = (-45f + 270f * v) * DEG; t1RailPoint(p, th, t1TrR(p, th) + 0.02f, out) }
    membr.surface(70, 8) { u, v, out -> val p = T1_TR0 + (pEnd - T1_TR0) * u; val th = (225f + 90f * v) * DEG; t1RailPoint(p, th, t1TrR(p, th) + 0.02f, out) }
    // C-rings, each a little different in height; one in eight forks into two over part of its arc
    val rnd0 = java.util.Random(21)
    for (k in 0 until T1_RINGS) {
        val pk = T1_RING0 + k * T1_RING_STEP
        val hgt = (0.40f + 0.15f * rnd0.nextFloat()) / 16f
        rings.surface(40, 6) { u, v, out ->
            val th = (-45f + 270f * u) * DEG
            val pp = pk + (v - 0.5f) * hgt
            val taper = sin(PI_F * u).pow(0.15f)                        // the free ends of the C thin out
            t1RailPoint(pp, th, t1TrR(pp, th) + 0.01f - 0.035f * sin(PI_F * v) * taper, out)
        }
        if (k % 8 == 3) rings.surface(16, 6) { u, v, out ->              // the fork: a second limb diverging and rejoining
            val th = (40f + 110f * u) * DEG
            val split = sin(PI_F * u).coerceAtLeast(0f)
            val pp = pk + 0.3f / 16f * split + (v - 0.5f) * hgt * 0.6f
            t1RailPoint(pp, th, t1TrR(pp, th) + 0.01f - 0.03f * sin(PI_F * v), out)
        }
    }
    shell.surface(70, 32) { u, v, out -> val p = T1_TR0 + (pEnd - T1_TR0) * u; val th = v * TAU; t1RailPoint(p, th, t1TrR(p, th, 0.045f), out) }
    mucus.surface(70, 32) { u, v, out -> val p = T1_TR0 + (pEnd - T1_TR0) * u; val th = v * TAU; t1RailPoint(p, th, t1TrR(p, th, 0.065f), out) }
    // longitudinal elastic folds of the membranous wall
    val folds = ArrayList<Float>()
    val fc = floatArrayOf(0.95f, 0.62f, 0.62f, 0.9f)
    val q = FloatArray(3)
    for (k in 0 until 4) {
        val th = (238f + 21f * k) * DEG
        for (m in 0 until 60) for (h in 0..1) {
            val p = T1_TR0 + (T1_CARINA - 0.03f - T1_TR0) * (m + h) / 60f
            t1RailPoint(p, th, t1TrR(p, th, 0.05f), q); folds.add(q[0]); folds.add(q[1]); folds.add(q[2]); folds.add(fc[0]); folds.add(fc[1]); folds.add(fc[2]); folds.add(fc[3])
        }
    }
    // the carina: a funnel from the tracheal wall down into the two mouths - the right main bronchus
    // wider (14 mm) and nearer the axis, the left narrower (11 mm) - with the keel between them
    val fcar = frameAt(T1_CARINA)
    val circles = floatArrayOf(0.58f, 0f, 0.9f, -0.99f, 0f, 0.66f)
    fun capPoint(u: Float, v: Float, out: FloatArray) {
        val a = v * TAU
        val d0 = t1MouthExit(a, circles)
        val wx = cos(a); val wy = sin(a)
        // exit from the D-shaped tracheal outline, seen from (-0.5, 0)
        var lo = 0f; var hi = 3f
        repeat(24) { val m = (lo + hi) * 0.5f; val s = -0.5f + wx * m; val up = wy * m
            if (sqrt(s * s + up * up) < t1TrR(T1_CARINA, atan2(up, s))) lo = m else hi = m }
        val d1 = lo
        val d = d0.coerceAtMost(d1) + (d1 - d0.coerceAtMost(d1)) * u
        val s = -0.5f + wx * d; val up = wy * d
        val along = -0.35f * u * u
        out[0] = fx(fcar, along, s, up); out[1] = fy(fcar, along, s, up); out[2] = fz(fcar, along, s, up)
    }
    val cap = T1Builder(); cap.surface(6, 64) { u, v, out -> capPoint(u, v, out) }
    val br = T1Builder(); val holes = T1Builder()
    fun bronchus(cs: Float, r: Float, ang: Float, len: Float) {
        val da = cos(ang); val ds = sin(ang)
        br.surface(10, 20) { u, v, out ->
            val t = len * u; val a = v * TAU
            val ca = cs + ds * t; val al = da * t
            val ls = cos(a) * r * da; val la = -cos(a) * r * ds; val lu = sin(a) * r
            out[0] = fx(fcar, al + la, ca + ls, lu); out[1] = fy(fcar, al + la, ca + ls, lu); out[2] = fz(fcar, al + la, ca + ls, lu)
        }
        val ea = da * len; val es = cs + ds * len
        for (m in 0..1) {
            val o = (if (m == 0) 0.3f else -0.3f) * r / 0.8f
            val rr = if (m == 0) 0.3f * r / 0.8f else 0.42f * r / 0.8f
            val px = fx(fcar, ea - ds * o, es + da * o, 0f); val py = fy(fcar, ea - ds * o, es + da * o, 0f); val pz = fz(fcar, ea - ds * o, es + da * o, 0f)
            val ex = floatArrayOf((fcar.sx * da - fcar.dx * ds) * rr, (fcar.sy * da - fcar.dy * ds) * rr, (fcar.sz * da - fcar.dz * ds) * rr)
            val ey = floatArrayOf(fcar.ux * rr * 1.2f, fcar.uy * rr * 1.2f, fcar.uz * rr * 1.2f)
            val ez = floatArrayOf((fcar.dx * da + fcar.sx * ds) * 0.02f, (fcar.dy * da + fcar.sy * ds) * 0.02f, (fcar.dz * da + fcar.sz * ds) * 0.02f)
            holes.ellipsoid(px, py, pz, ex, ey, ez, 4, 16)
        }
        br.surface(3, 24) { u, v, out ->
            val a = v * TAU; val rr = r * (0.2f + 0.8f * u)
            val ls = cos(a) * rr * da; val la = -cos(a) * rr * ds; val lu = sin(a) * rr
            out[0] = fx(fcar, ea + la + 0.03f * da, es + ls + 0.03f * ds, lu); out[1] = fy(fcar, ea + la + 0.03f * da, es + ls + 0.03f * ds, lu); out[2] = fz(fcar, ea + la + 0.03f * da, es + ls + 0.03f * ds, lu)
        }
        // a dark-red shadow inside each mouth so the two openings read as two
        val depth = if (ang > 0f) 1.3f else 1.1f
        val sa = da * depth; val ss = cs + ds * depth
        holes.ellipsoid(fx(fcar, sa, ss, 0f), fy(fcar, sa, ss, 0f), fz(fcar, sa, ss, 0f),
            floatArrayOf((fcar.sx * da - fcar.dx * ds) * r * 0.9f, (fcar.sy * da - fcar.dy * ds) * r * 0.9f, (fcar.sz * da - fcar.dz * ds) * r * 0.9f),
            floatArrayOf(fcar.ux * r * 0.9f, fcar.uy * r * 0.9f, fcar.uz * r * 0.9f),
            floatArrayOf((fcar.dx * da + fcar.sx * ds) * 0.02f, (fcar.dy * da + fcar.sy * ds) * 0.02f, (fcar.dz * da + fcar.sz * ds) * 0.02f), 4, 20)
    }
    bronchus(0.58f, 0.9f, 25f * DEG, 3.2f)
    bronchus(-0.99f, 0.66f, -45f * DEG, 4.5f)
    val keel = T1Builder()
    val kx = fx(fcar, 0.02f, -0.325f, 0f); val ky = fy(fcar, 0.02f, -0.325f, 0f); val kz = fz(fcar, 0.02f, -0.325f, 0f)
    keel.ellipsoid(kx, ky, kz, floatArrayOf(fcar.sx * 0.06f, fcar.sy * 0.06f, fcar.sz * 0.06f), floatArrayOf(fcar.ux * 0.6f, fcar.uy * 0.6f, fcar.uz * 0.6f),
        floatArrayOf(fcar.dx * 0.3f, fcar.dy * 0.3f, fcar.dz * 0.3f), 8, 12)
    // ciliary glints on the cartilaginous wall (positions; brightness is animated per frame)
    val rnd = java.util.Random(40)
    val n = 900
    val sp = FloatArray(n * 4)
    for (j in 0 until n) {
        val p = T1_TR0 + rnd.nextFloat() * (T1_CARINA - 0.05f - T1_TR0); val th = (-50f + 280f * rnd.nextFloat()) * DEG
        t1RailPoint(p, th, t1TrR(p, th, 0.075f), q)
        sp[4 * j] = q[0]; sp[4 * j + 1] = q[1]; sp[4 * j + 2] = q[2]; sp[4 * j + 3] = p
    }
    return T1Trachea(tube.build(), membr.build(), rings.build(), shell.build(), mucus.build(), LineMesh(folds.toFloatArray()),
        cap.build(), br.build(), keel.build(), holes.build(), sp)
}

internal fun StereoBodyRenderer.drawAirway(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp < 0.45f || rp > 1.76f) return
    val vis = ((1.755f - rp) / 0.03f).coerceIn(0f, 1f)
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
    // vestibular (false) folds: thin mucosal shelves 0.3 (2.4 mm) above the pearly true vocal folds,
    // both converging on the front (up) in a V; the rima glottidis is the opening between the true folds
    for (fold in 0..1) {
        val aF = if (fold == 0) -0.3f else 0f
        val apexU = if (fold == 0) 1.55f else 1.45f
        val procS = if (fold == 0) 1.0f else 0.72f
        val procU = if (fold == 0) -1.1f else -1.15f
        val half = if (fold == 0) 0.28f else 0.2f
        val thick = if (fold == 0) 0.06f else 0.1f
        for (sgn in SIGNS) {
            val dS = sgn * procS; val dU = procU - apexU
            val dl = sqrt(dS * dS + dU * dU)
            var lS = -dU / dl; var lU = dS / dl
            if (lS * sgn < 0f) { lS = -lS; lU = -lU }
            val cS = dS * 0.5f + lS * half; val cU = apexU + dU * 0.5f + lU * half
            t1Shape(sphere, fv, aF, so + cS, uo + cU, 0f, dS / dl, dU / dl, 1f, 0f, 0f,
                half, thick, dl * 0.5f,
                if (fold == 0) T1_MUCOSA else T1_VOCAL, if (fold == 0) T1_MUCOSA_RIM else T1_WHITE, 1f, if (fold == 0) 0.3f else 0.5f)
        }
    }
    // the arytenoid mounds, behind and below the folds' posterior ends
    for (sgn in SIGNS) t1Shape(sphere, fv, 0.15f, so + sgn * 0.72f, uo - 1.55f, 1f, 0f, 0f, 0f, 0f, 1f, 0.3f, 0.3f, 0.26f, T1_MUCOSA, T1_MUCOSA_RIM, 1f, 0.4f)
    // Trachea
    val tr = t1Mesh("trachea2") { t1BuildTracheaMesh() }
    t1LitWorld(tr.tube, T1_TR_GAP, T1_MUCOSA_RIM, 1f, 0.08f)
    t1LitWorld(tr.membr, T1_TRACHEALIS, T1_MUCOSA_RIM, 1f, 0.1f)
    GLES20.glDepthMask(false)
    t1LitWorld(tr.rings, T1_RING_UNDER, T1_WHITE, 0.55f, 0.12f)
    GLES20.glDepthMask(true)
    t1LitWorld(tr.cap, T1_MUCOSA, T1_MUCOSA_RIM, 1f, 0.2f)
    t1LitWorld(tr.bronchi, T1_TR_GAP, T1_MUCOSA_RIM, 1f, 0.12f)
    t1LitWorld(tr.holes, T1_MEATUS, T1_MEATUS, 1f, 0f)
    t1LitWorld(tr.keel, T1_CARTILAGE, T1_WHITE, 1f, 0.6f)
    t1Color(tr.folds, null, 0f, 0f, 0f, 1.5f, false, 0.8f)
    // Ciliary shimmer: metachronal waves running up the wall toward the larynx
    val d = t1Dyn.data
    if (quality < 2) {
        var v = 0
        val sc = floatArrayOf(1f, 1f, 0.94f)
        val sp = tr.sparkle
        for (j in 0 until sp.size / 4) {
            // one travelling wave: bright bands run up the wall toward the larynx at ~1 unit/s
            val w = 0.5f + 0.5f * sin(TAU * (1.5f * seconds + sp[4 * j + 3] * 12f))
            v = t1Put(d, v, sp[4 * j], sp[4 * j + 1], sp[4 * j + 2], sc, 0.06f + 0.94f * w * w * w * w)
        }
        t1DynDraw(v, GLES20.GL_POINTS, 2.6f, 1f, depthWrite = false)
    }
    // the mucus blanket and the dust riding it toward the throat (-along) at 1 cm/min
    var v = 0
    val q = FloatArray(3)
    val span = T1_CARINA - 0.05f - T1_TR0
    val dc = floatArrayOf(0.78f, 0.72f, 0.58f, 1f)
    for (j in 0 until 60) {
        val pp = T1_TR0 + ((t1Hash(j) * span - seconds * 0.0013f) % span + span) % span
        val th = j * 2.39996f
        t1RailPoint(pp, th, t1TrR(pp, th, 0.07f), q)
        v = t1Put(d, v, q[0], q[1], q[2], dc, 0.95f)
    }
    t1DynDraw(v, GLES20.GL_POINTS, 3.2f)
    GLES20.glDepthMask(false)
    t1LitWorld(tr.shell, T1_MUCOSA, T1_MUCOSA_RIM, 0.33f, 0.1f)
    t1LitWorld(tr.mucus, T1_MUCUS, T1_WHITE, 0.14f, 0.2f)
    GLES20.glDepthMask(true)
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
private var t1HoneyCentres = FloatArray(0)
private fun t1Honeycomb(d: Float, xMin: Float, xMax: Float, yMin: Float, yMax: Float, jitter: Float, seed: Int, keep: (Float, Float) -> Boolean): Pair<FloatArray, IntArray> {
    val centres = ArrayList<Float>()
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
            if (keep(x, y)) { centres.add(x); centres.add(y) }
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
    t1HoneyCentres = centres.toFloatArray()
    return xy to out.toIntArray()
}

private class T1DomeNet(val mesh: TriMesh, val lumen: TriMesh, val posts: TriMesh, val paths: List<FloatArray>, val type1: LineMesh, val nuclei: T1Batch)

private fun t1DomeZ(x: Float, y: Float) = T1_DOME_H * t1HexDist(x, y)

/** The hexagonal prism of the sac's side walls: distance from the axis at wall angle th (vertices at 30 + 60k degrees). */
private fun t1HexR(th: Float): Float {
    var a = (th / DEG - 30f) % 60f; if (a < 0f) a += 60f
    return T1_DOME_R * cos(30f * DEG) / cos((a - 30f) * DEG)
}

/** Ribbons (half-width w) along honeycomb edges; optionally pale oval tissue posts in the gaps. */
private fun t1RibbonNet(xy: FloatArray, e: IntArray, w: Float, col: FloatArray, post: FloatArray?, postR: Float,
                        map: (Float, Float, Float, ArrayList<Float>, FloatArray) -> Unit, centres: FloatArray, lift: Float = 0f): Pair<TriMesh, TriMesh> {
    val tri = ArrayList<Float>(); val posts = ArrayList<Float>()
    var k = 0
    while (k < e.size) {
        val a = e[k]; val b = e[k + 1]; k += 2
        val x0 = xy[2 * a]; val y0 = xy[2 * a + 1]; val x1 = xy[2 * b]; val y1 = xy[2 * b + 1]
        val l = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0)).coerceAtLeast(1e-5f)
        val nx = -(y1 - y0) / l * w; val ny = (x1 - x0) / l * w
        val ex = (x1 - x0) / l * w * 0.8f; val ey = (y1 - y0) / l * w * 0.8f
        val sx0 = x0 - ex; val sy0 = y0 - ey; val sx1 = x1 + ex; val sy1 = y1 + ey
        for (m in 0 until 3) {
            val ta = m / 3f; val tb = (m + 1) / 3f
            val ax = sx0 + (sx1 - sx0) * ta; val ay = sy0 + (sy1 - sy0) * ta; val bx = sx0 + (sx1 - sx0) * tb; val by = sy0 + (sy1 - sy0) * tb
            map(ax + nx, ay + ny, lift, tri, col); map(bx + nx, by + ny, lift, tri, col); map(bx - nx, by - ny, lift, tri, col)
            map(ax + nx, ay + ny, lift, tri, col); map(bx - nx, by - ny, lift, tri, col); map(ax - nx, ay - ny, lift, tri, col)
        }
    }
    if (post != null) {
        var c = 0
        while (c < centres.size) {
            val cx = centres[c]; val cy = centres[c + 1]; c += 2
            val rx = postR * (0.8f + 0.4f * t1Hash(c * 3)); val ry = postR * (0.8f + 0.4f * t1Hash(c * 5 + 1)); val rot = t1Hash(c * 7) * PI_F
            for (m in 0 until 8) {
                val a0 = TAU * m / 8f + rot; val a1 = TAU * (m + 1) / 8f + rot
                map(cx, cy, 0.001f, posts, post)
                map(cx + cos(a0) * rx, cy + sin(a0) * ry, 0.001f, posts, post); map(cx + cos(a1) * rx, cy + sin(a1) * ry, 0.001f, posts, post)
            }
        }
    }
    return TriMesh(tri.toFloatArray()) to TriMesh(posts.toFloatArray())
}

private val T1_CAP_COL = floatArrayOf(0.78f, 0.16f, 0.20f, 1f)
private val T1_CAP_LUMEN = floatArrayOf(0.95f, 0.42f, 0.42f, 1f)
private val T1_POST_COL = floatArrayOf(0.97f, 0.86f, 0.86f, 0.25f)

/** The far wall's capillary net (dome-local, just behind the septum's surface) and red-cell paths through it. */
private fun t1BuildDomeNet(): T1DomeNet {
    val lim = T1_DOME_R * 0.95f
    val d = 0.15f
    val (xy, e) = t1Honeycomb(d, -lim, lim, -lim, lim, 0.35f * d * 0.5f, 7) { x, y -> t1HexDist(x, y) < 0.96f }
    val centres = t1HoneyCentres
    val mapD: (Float, Float, Float, ArrayList<Float>, FloatArray) -> Unit =
        { x, y, lift, l, c -> l.add(x); l.add(y); l.add(t1DomeZ(x, y) - 0.012f + lift); l.add(c[0]); l.add(c[1]); l.add(c[2]); l.add(c[3]) }
    val (mesh, posts) = t1RibbonNet(xy, e, 0.035f, T1_CAP_COL, T1_POST_COL, 0.03f, mapD, centres)
    val (lumen, _) = t1RibbonNet(xy, e, 0.012f, T1_CAP_LUMEN, null, 0f, mapD, centres, 0.002f)
    val adj = HashMap<Int, MutableList<Int>>()
    var k = 0
    while (k < e.size) { val a = e[k]; val b = e[k + 1]; k += 2; adj.getOrPut(a) { ArrayList() }.add(b); adj.getOrPut(b) { ArrayList() }.add(a) }
    val rnd = java.util.Random(5)
    val keys = adj.keys.toList()
    val paths = ArrayList<FloatArray>()
    var tries = 0
    while (paths.size < 40 && tries < 1200) {
        tries++
        var cur = keys[rnd.nextInt(keys.size)]
        val hd = t1HexDist(xy[2 * cur], xy[2 * cur + 1])
        if (hd < 0.1f || hd > 0.85f) continue
        var prev = -1
        val pts = ArrayList<Float>()
        for (step in 0 until 22) {
            val x = xy[2 * cur]; val y = xy[2 * cur + 1]
            pts.add(x); pts.add(y); pts.add(t1DomeZ(x, y) - 0.008f)
            val nb = adj[cur]?.filter { it != prev && t1HexDist(xy[2 * it], xy[2 * it + 1]) < 0.9f } ?: break
            if (nb.isEmpty()) break
            prev = cur; cur = nb[rnd.nextInt(nb.size)]
        }
        if (pts.size >= 45) paths.add(pts.toFloatArray())
    }
    // type I pneumocytes: large thin polygons (60 um) on the air side, each with a flat nucleus
    val (xy1, e1) = t1Honeycomb(0.75f, -lim, lim, -lim, lim, 0.12f, 23) { x, y -> t1HexDist(x, y) < 0.97f }
    val c1 = t1HoneyCentres
    val t1l = ArrayList<Float>()
    val lc = floatArrayOf(1f, 0.92f, 0.92f, 0.45f)
    k = 0
    while (k < e1.size) {
        val a = e1[k]; val b = e1[k + 1]; k += 2
        for (m in 0 until 6) for (h in 0..1) {
            val t = (m + h) / 6f
            val x = xy1[2 * a] + (xy1[2 * b] - xy1[2 * a]) * t; val y = xy1[2 * a + 1] + (xy1[2 * b + 1] - xy1[2 * a + 1]) * t
            t1l.add(x); t1l.add(y); t1l.add(t1DomeZ(x, y) + 0.008f); t1l.add(lc[0]); t1l.add(lc[1]); t1l.add(lc[2]); t1l.add(lc[3])
        }
    }
    val nb = T1Builder()
    var c = 0
    while (c < c1.size) {
        val x = c1[c] + 0.08f; val y = c1[c + 1] - 0.05f; c += 2
        nb.ellipsoid(x, y, t1DomeZ(x, y) + 0.01f, floatArrayOf(0.07f, 0f, 0f), floatArrayOf(0f, 0.06f, 0f), floatArrayOf(0f, 0f, 0.018f), 4, 8)
    }
    return T1DomeNet(mesh, lumen, posts, paths, LineMesh(t1l.toFloatArray()), nb.build())
}

/** Capillary net in the sac's six flat side walls (world space), just behind the septal surface. */
private fun StereoBodyRenderer.t1BuildSideNet(): Array<TriMesh> {
    val circ = 10.6f; val len = (T1_SIDE_P1 - T1_SIDE_P0) * 16f
    val d = 0.2f
    val (xy, e) = t1Honeycomb(d, 0f, circ, 0f, len, 0.35f * d * 0.5f, 11) { x, y -> x in 0f..circ && y in 0f..len }
    val q = FloatArray(3)
    val mapS: (Float, Float, Float, ArrayList<Float>, FloatArray) -> Unit = { x, y, lift, l, c ->
        val th = x / circ * TAU; val p = T1_SIDE_P0 + y / 16f
        t1RailPoint(p, th, t1HexR(th) * (1.012f - lift * 4f), q)
        l.add(q[0]); l.add(q[1]); l.add(q[2]); l.add(c[0]); l.add(c[1]); l.add(c[2]); l.add(c[3])
    }
    val (a, b) = t1RibbonNet(xy, e, 0.04f, T1_CAP_COL, T1_POST_COL, 0.045f, mapS, t1HoneyCentres)
    val (l2, _) = t1RibbonNet(xy, e, 0.014f, T1_CAP_LUMEN, null, 0f, mapS, t1HoneyCentres, 0.001f)
    return arrayOf(a, l2, b)
}

/** Septal junction lines where the flat side walls meet, plus the neighbouring alveolus seen through the far wall. */
private fun StereoBodyRenderer.t1BuildSepta(): Array<LineMesh> {
    val side = ArrayList<Float>()
    val col = floatArrayOf(1f, 0.9f, 0.9f, 1f)
    val q = FloatArray(3)
    fun add(list: ArrayList<Float>, x: Float, y: Float, z: Float, c: FloatArray) { list.add(x); list.add(y); list.add(z); list.add(c[0]); list.add(c[1]); list.add(c[2]); list.add(c[3]) }
    for (k in 0 until 6) {
        val th = (30f + 60f * k) * DEG
        val steps = 8
        for (m in 0 until steps) for (h in 0..1) {
            val p = T1_SIDE_P0 + (T1_SIDE_P1 - T1_SIDE_P0) * (m + h) / steps
            t1RailPoint(p, th, T1_DOME_R * 0.99f, q); add(side, q[0], q[1], q[2], col)
        }
    }
    val nb = ArrayList<Float>()
    val nc = floatArrayOf(0.97f, 0.74f, 0.78f, 0.55f)
    val rN = 1.35f
    for (k in 0 until 6) {
        val a0 = (30f + 60f * k) * DEG; val a1 = (90f + 60f * k) * DEG
        val x0 = rN * cos(a0); val y0 = rN * sin(a0); val x1 = rN * cos(a1); val y1 = rN * sin(a1)
        add(nb, x0, y0, -0.25f, nc); add(nb, x0, y0, -3.0f, nc)
        add(nb, x0, y0, -3.0f, nc); add(nb, 0f, 0f, -4.2f, nc)
        add(nb, x0, y0, -0.25f, nc); add(nb, x1, y1, -0.25f, nc)
        add(nb, x0, y0, -3.0f, nc); add(nb, x1, y1, -3.0f, nc)
    }
    return arrayOf(LineMesh(side.toFloatArray()), LineMesh(nb.toFloatArray()))
}

private class T1AlvStatic(val holes: T1Batch, val rims: T1Batch, val type2: T1Batch, val lamellar: T1Batch, val macroGran: T1Batch)

/** The sac's small static parts, batched: mouths and pores (dark), their rims, type II cells and their lamellar bodies. */
private fun StereoBodyRenderer.t1BuildAlvStatic(): T1AlvStatic {
    val holes = T1Builder(); val rims = T1Builder(); val t2 = T1Builder(); val lam = T1Builder(); val gran = T1Builder()
    val q = FloatArray(3)
    fun opening(p: Float, th: Float, rw: Float, a: Float, b: Float) {
        val f = frameAt(p); val c = cos(th); val s = sin(th)
        val rx = f.sx * c + f.ux * s; val ry = f.sy * c + f.uy * s; val rz = f.sz * c + f.uz * s
        val tx = -f.sx * s + f.ux * c; val ty = -f.sy * s + f.uy * c; val tz = -f.sz * s + f.uz * c
        val cx = f.cx + rx * rw; val cy = f.cy + ry * rw; val cz = f.cz + rz * rw
        holes.ellipsoid(cx, cy, cz, floatArrayOf(tx * a, ty * a, tz * a), floatArrayOf(f.dx * b, f.dy * b, f.dz * b), floatArrayOf(rx * 0.01f, ry * 0.01f, rz * 0.01f), 3, 16)
        rims.surface(16, 6) { u, v, out ->
            val g = u * TAU; val h = v * TAU; val w = 0.18f * min(a, b)
            val px = cos(g) * (a + w * cos(h)); val pb = sin(g) * (b + w * cos(h)); val pr = -w * sin(h) * 0.5f - 0.02f
            out[0] = cx + tx * px + f.dx * pb + rx * pr; out[1] = cy + ty * px + f.dy * pb + ry * pr; out[2] = cz + tz * px + f.dz * pb + rz * pr
        }
    }
    // neighbouring alveolar mouths on the duct wall, and two pores of Kohn in the side walls
    val hp = floatArrayOf(1.77f, 40f, 1.81f, 135f, 1.79f, 215f, 1.83f, 320f)
    for (k in 0 until 4) { val p = hp[2 * k]; val th = hp[2 * k + 1] * DEG; opening(p, th, t1WallR(p, th) * 0.96f, 0.42f, 0.55f) }
    for (k in 0..1) { val p = 1.93f + 0.05f * k; val th = (if (k == 0) 75f else 290f) * DEG; opening(p, th, t1HexR(th) * 0.995f, 0.07f, 0.1f) }
    // type II pneumocytes: cuboidal domes set into the corners where septa meet, bulging a little into
    // the air space, lamellar bodies (stored surfactant) on their free surface
    for (k in 0 until 6) {
        val th = (30f + 60f * k) * DEG
        val p = T1_SIDE_P0 + (0.25f + 0.1f * (k % 3)) * (T1_SIDE_P1 - T1_SIDE_P0)
        t1RailPoint(p, th, T1_DOME_R * 0.995f, q)
        val f = frameAt(p); val c = cos(th); val s = sin(th)
        val rx = f.sx * c + f.ux * s; val ry = f.sy * c + f.uy * s; val rz = f.sz * c + f.uz * s
        val tx = -f.sx * s + f.ux * c; val ty = -f.sy * s + f.uy * c; val tz = -f.sz * s + f.uz * c
        t2.ellipsoid(q[0], q[1], q[2], floatArrayOf(tx * 0.13f, ty * 0.13f, tz * 0.13f), floatArrayOf(rx * 0.08f, ry * 0.08f, rz * 0.08f), floatArrayOf(f.dx * 0.12f, f.dy * 0.12f, f.dz * 0.12f), 6, 10)
        for (m in 0 until 5) {
            val b = m * 1.26f
            val lx = q[0] - rx * 0.07f + tx * 0.06f * cos(b) + f.dx * 0.06f * sin(b)
            val ly = q[1] - ry * 0.07f + ty * 0.06f * cos(b) + f.dy * 0.06f * sin(b)
            val lz = q[2] - rz * 0.07f + tz * 0.06f * cos(b) + f.dz * 0.06f * sin(b)
            lam.ellipsoid(lx, ly, lz, floatArrayOf(0.02f, 0f, 0f), floatArrayOf(0f, 0.02f, 0f), floatArrayOf(0f, 0f, 0.02f), 3, 5)
        }
    }
    // dust in the macrophage (in its own local frame, x/y across it, z up from the floor)
    for (m in 0 until 5) {
        val a = m * 1.3f
        gran.ellipsoid(0.13f * cos(a), 0.09f * sin(a), 0.02f, floatArrayOf(0.025f, 0f, 0f), floatArrayOf(0f, 0.025f, 0f), floatArrayOf(0f, 0f, 0.025f), 3, 5)
    }
    return T1AlvStatic(holes.build(), rims.build(), t2.build(), lam.build(), gran.build())
}

internal fun StereoBodyRenderer.drawAlveolus(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp < 1.72f || rp > 2.24f) return
    val vis = t1Smooth(1.725f, 1.75f, rp) * (1f - t1Smooth(2.17f, 2.23f, rp))
    landmarkFade *= vis; colorShader.globalFade *= vis
    val breath = t1Breath(seconds)
    val inflate = 1f + 0.05f * sin(breath * TAU)       // the sac deepens on the inhale, in phase with every other sac
    val st = t1Mesh("alv.static") { t1BuildAlvStatic() }
    t1LitWorld(st.holes, T1_MEATUS, T1_MEATUS, 1f, 0f)
    t1LitWorld(st.rims, T1_SEPTUM, T1_WHITE, 1f, 0.2f)
    // The mouth: the entrance ring (elastic and smooth-muscle fibres) and the septum joining it to the wall.
    val fm = frameAt(T1_MOUTH_P)
    t1Lit(t1Ring(0.07f), fm, 0f, 0f, 0f, T1_SEPTUM, T1_WHITE, 1f, 0.15f, 1.3f, 1.3f, 1.3f)
    t1Lit(t1Ring(0.035f), fm, -0.06f, 0f, 0f, T1_ALV_SM, T1_WHITE, 1f, 0.15f, 1.3f, 1.3f, 1.3f)   // smooth muscle in the entrance ring
    val annulus = t1Mesh("alv.annulus2") { ParamMesh(3, 36) { u, v, out -> val a = v * TAU; val r = 1.33f + (t1HexR(a) - 1.33f) * u; out[0] = cos(a) * r; out[1] = sin(a) * r; out[2] = 0f } }
    GLES20.glDepthMask(false)
    t1Lit(annulus, fm, 0f, 0f, 0f, T1_SEPTUM, T1_WHITE, 0.5f, 0.2f)
    GLES20.glDepthMask(true)
    // Side walls: six flat septa (a hexagonal prism) with the capillary net in them
    val side = t1Mesh("alv.side3") { t1BuildSideNet() }
    t1Color(side[0], null, 0f, 0f, 0f, 1f, false, 1f)
    t1Color(side[1], null, 0f, 0f, 0f, 1f, false, 1f)
    t1Color(side[2], null, 0f, 0f, 0f, 1f, false, 1f)
    t1LitWorld(st.type2, T1_TYPE2, T1_WHITE, 1f, 0.25f)
    t1LitWorld(st.lamellar, T1_WHITE, T1_WHITE, 1f, 0.6f)
    // An alveolar macrophage crawling on the far wall: a flattened cell, kidney nucleus and swallowed
    // dust seen through its cytoplasm, pseudopods feeling about
    val fd = frameAt(T1_DOME_P)
    val mx = 0.55f; val my = -0.5f; val mz = t1DomeZ(mx, my) * inflate
    val ma = -mz - 0.08f
    t1Shape(t1Mesh("mono.nuc") { ParamMesh.torusArc(0.45f, 0.62f) }, fd, ma, mx, my, 1f, 0f, 0f, 0f, 0f, 1f, 0.08f, 0.08f, 0.06f, T1_MONO_NUC, T1_WHITE, 1f, 0.25f)
    t1Model(fd, ma, mx + 0.03f, my); drawLitModel(st.macroGran, T1_DUSTG, T1_DUSTG, landmarkFade, 0f, 0.1f)
    for (k in 0 until 3) {
        val g = 0.9f + k * 1.9f + 0.3f * sin(seconds * 0.7f + k)
        val l = 0.3f + 0.05f * sin(seconds * 0.7f + k * 2f)
        t1Rod(fd, ma, mx + cos(g) * 0.18f, my + sin(g) * 0.14f, ma + 0.02f, mx + cos(g) * l, my + sin(g) * l, 0.03f, T1_MACRO, T1_WHITE, 1f, 0.2f)
    }
    GLES20.glDepthMask(false)
    t1Shape(sphere, fd, ma + 0.02f, mx, my, 1f, 0f, 0f, 0f, 1f, 0f, 0.25f, 0.2f, 0.1f, T1_MACRO, T1_WHITE, 0.6f, 0.2f)
    GLES20.glDepthMask(true)
    // The far wall: the neighbour behind it, its capillary net, the red cells in it, the septum over them
    val septa = t1Mesh("alv.septa2") { t1BuildSepta() }
    t1Color(septa[1], fd, 0f, 0f, 0f, 1.5f, false, 0.45f)
    val cup = t1Mesh("alv.cup") { ParamMesh(6, 36) { u, v, out ->
        val sector = (v * 6f).toInt().coerceAtMost(5); val t = v * 6f - sector
        val a0 = (30f + 60f * sector) * DEG; val a1 = a0 + 60f * DEG
        val rs = T1_DOME_R * u
        out[0] = rs * ((1f - t) * cos(a0) + t * cos(a1)); out[1] = rs * ((1f - t) * sin(a0) + t * sin(a1)); out[2] = T1_DOME_H * u
    } }
    val net = t1Mesh("alv.net3") { t1BuildDomeNet() }
    t1Model(fd, 0f, 0f, 0f); Matrix.scaleM(model, 0, 1f, 1f, inflate)
    Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    colorShader.use(mvp, 1f)
    GLES20.glDisable(GLES20.GL_CULL_FACE)
    net.mesh.draw(colorShader.positionHandle, colorShader.colorHandle)
    net.lumen.draw(colorShader.positionHandle, colorShader.colorHandle)
    net.posts.draw(colorShader.positionHandle, colorShader.colorHandle)
    GLES20.glEnable(GLES20.GL_CULL_FACE)
    // Red cells packed single file in the capillaries, loading oxygen in the first third of a ~0.75 s
    // transit (dark venous red -> bright arterial red). Those on the nearest paths are drawn as discs.
    val d = t1Dyn.data
    var v = 0
    val cDark = floatArrayOf(0.50f, 0.06f, 0.10f); val cBright = floatArrayOf(0.90f, 0.12f, 0.14f); val col = FloatArray(3)
    val nDisc = if (quality == 0) 12 else 6
    t1Cells("alv.rbc2", 80, 0f, COL_RBC_OXY, 0.3f) { b ->
        for ((j, path) in net.paths.withIndex()) {
            val segs = path.size / 3 - 1
            var total = 0f
            for (m in 0 until segs) total += sqrt((path[3 * m + 3] - path[3 * m]).pow(2) + (path[3 * m + 4] - path[3 * m + 1]).pow(2) + (path[3 * m + 5] - path[3 * m + 2]).pow(2))
            val head = (seconds / 0.75f * total * 0.5f + j * 1.7f) % total
            for (c in 0 until 6) {
                var s = ((head - c * 0.12f) % total + total) % total
                val frac = s / total
                val k = t1Smooth(0.12f, 0.3f, frac)
                for (q in 0..2) col[q] = cDark[q] + (cBright[q] - cDark[q]) * k
                var m = 0
                while (m < segs) {
                    val dx = path[3 * m + 3] - path[3 * m]; val dy = path[3 * m + 4] - path[3 * m + 1]; val dz = path[3 * m + 5] - path[3 * m + 2]
                    val l = sqrt(dx * dx + dy * dy + dz * dz)
                    if (s <= l || m == segs - 1) {
                        val t = (s / l).coerceIn(0f, 1f)
                        val x = path[3 * m] + dx * t; val y = path[3 * m + 1] + dy * t
                        val z = (path[3 * m + 2] + dz * t) * inflate
                        val wx = fd.cx + fd.sx * x + fd.ux * y - fd.dx * z; val wy = fd.cy + fd.sy * x + fd.uy * y - fd.dy * z; val wz = fd.cz + fd.sz * x + fd.uz * y - fd.dz * z
                        if (j < nDisc && k > 0.99f) {
                            val e = 0.01f
                            val gx = (t1DomeZ(x + e, y) - t1DomeZ(x - e, y)) / (2f * e); val gy = (t1DomeZ(x, y + e) - t1DomeZ(x, y - e)) / (2f * e)
                            b.add(wx, wy, wz, fd.sx * dx + fd.ux * dy - fd.dx * dz, fd.sy * dx + fd.uy * dy - fd.dy * dz, fd.sz * dx + fd.uz * dy - fd.dz * dz,
                                -fd.sx * gx - fd.ux * gy - fd.dx, -fd.sy * gx - fd.uy * gy - fd.dy, -fd.sz * gx - fd.uz * gy - fd.dz, 0.047f)
                        } else if (v < 2390) v = t1Put(d, v, wx, wy, wz, col, 1f)
                        break
                    }
                    s -= l; m++
                }
            }
        }
    }
    t1DynDraw(v, GLES20.GL_POINTS, 7f)
    // the septum over them (type I cell surface) with the cells' outlines and flat nuclei; surfactant film
    GLES20.glDepthMask(false)
    t1Lit(cup, fd, 0f, 0f, 0f, T1_SEPTUM, T1_WHITE, 0.3f, 0.2f, 1f, 1f, inflate)
    GLES20.glDepthMask(true)
    t1Lit(net.nuclei, fd, 0f, 0f, 0f, T1_SEPTUM, T1_WHITE, 0.9f, 0.3f, 1f, 1f, inflate)
    t1Color(net.type1, fd, 0f, 0f, 0f, 1.5f, false, 0.9f)
    t1Color(septa[0], null, 0f, 0f, 0f, 3f, false, 1f)
    // the flat side walls' surface, pale and glistening (never grey)
    val shell = t1Mesh("alv.shell2") { ParamMesh(4, 48) { u, v, out -> val p = T1_SIDE_P0 + (T1_SIDE_P1 - T1_SIDE_P0) * u; val th = v * TAU
        t1RailPoint(p, th, t1HexR(th), out) } }
    GLES20.glDepthMask(false)
    t1LitWorld(shell, T1_SEPTUM, T1_WHITE, 0.22f, 0.25f)
    t1Lit(cup, fd, -0.03f, 0f, 0f, T1_FILM, T1_WHITE, 0.14f + 0.05f * sin(breath * TAU), 0.3f, 0.985f, 0.985f, inflate)
    GLES20.glDepthMask(true)
}

// ================================================================ stop 3: THE BLOODSTREAM (pulmonary venule, 12 um rung)
// 1 unit = 8 um. A pulmonary venule 26 um across, lined by a continuous, pale endothelium (zig-zag
// cell junctions; each cell's nucleus a smooth mound bulging into the lumen). Capillaries open into
// it through its wall: one behind the craft, the one it left, and one ahead on the upper wall whose
// 5 um bore is seen through the thin wall with red cells coming single file, folded into parachutes
// to fit. The lumen: clear, straw-tinted plasma carrying true-size biconcave red cells (bright,
// oxygenated), concentrated toward the axis with a cell-free plasma layer at the wall, those near the
// wall aligned with the flow, all carried downstream faster than the craft; a few platelets drift.

private val T1_OSTIA = floatArrayOf(2.83f, 170f, 3.06f, 72f)

internal fun StereoBodyRenderer.drawBloodstream(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp < 2.2f || rp > 3.55f) return
    val vis = t1Smooth(2.2f, 2.3f, rp) * (1f - t1Smooth(3.45f, 3.55f, rp))
    landmarkFade *= vis; colorShader.globalFade *= vis
    t1DrawEndothelium("venule3", 2.52f, 3.42f, 6, 4.2f, 3)
    val para = t1Mesh("parachute") { ParamMesh(14, 24) { u, v, out ->
        val top = u < 0.5f
        val rr = (if (top) 1f - u * 2f else (u - 0.5f) * 2f).coerceIn(0f, 0.999f)
        val q = rr * rr
        val h = 0.5f * sqrt(1f - q) * (0.81f + 7.83f * q - 4.39f * q * q) / 3.91f
        val a = v * TAU
        out[0] = cos(a) * rr; out[2] = sin(a) * rr; out[1] = (if (top) h else -h) + 0.45f * q   // the rim folds back: a cup
    } }
    val ca = sin(35f * DEG); val cr = cos(35f * DEG)       // capillary axis: 35 degrees downstream of the wall normal
    for (o in 0..1) {
        val po = T1_OSTIA[2 * o]; val th = T1_OSTIA[2 * o + 1] * DEG
        val fo = frameAt(po)
        val r = t1WallR(po, th) * 0.985f; val c = cos(th); val s = sin(th)
        t1Shape(t1Ring(0.22f), fo, 0f, c * (r - 0.01f), s * (r - 0.01f), 0f, -c, -s, 1f, 0f, 0f, 0.33f, 0.33f, 0.33f, T1_ENDO_BULGE, T1_WHITE, 1f, 0.2f)
        // the opening: its dark lumen, then punch the wall's depth there so the capillary running away
        // behind the wall is seen only through the hole
        t1Shape(t1Disc(), fo, 0.005f, c * r, s * r, 0f, -c, -s, 1f, 0f, 0f, 0.31f, 0.31f, 1f, T1_CAP_DEEP, T1_CAP_DEEP, 1f)
        GLES20.glColorMask(false, false, false, false); GLES20.glDepthFunc(GLES20.GL_ALWAYS); GLES20.glDepthRangef(1f, 1f)
        t1Shape(t1Disc(), fo, 0f, c * (r - 0.01f), s * (r - 0.01f), 0f, -c, -s, 1f, 0f, 0f, 0.31f, 0.31f, 1f, T1_CAP_DEEP, T1_CAP_DEEP, 1f)
        GLES20.glDepthRangef(0f, 1f); GLES20.glDepthFunc(GLES20.GL_LESS); GLES20.glColorMask(true, true, true, true)
        t1Shape(cylinder, fo, -ca * 0.75f, c * (r + cr * 0.75f), s * (r + cr * 0.75f), -ca, c * cr, s * cr, 1f, 0f, 0f,
            0.31f, 0.31f, 0.78f, T1_VESSEL, T1_WHITE, 1f, 0.15f)
        for (k in 0..2) {
            val ph = ((seconds / 2.4f + k / 3f) % 1f)
            val x = -1.4f + 1.6f * ph                       // from deep in the capillary out into the venule
            val al = (1f - t1Smooth(0.75f, 1f, ph)) * (if (x < 0f) 0.75f else 1f)
            t1Shape(para, fo, ca * x, c * (r - cr * x), s * (r - cr * x), 0f, -s, c, -ca, c * cr, s * cr,
                0.34f, 0.34f, 0.34f, T1_RBC_OXY, COL_RBC_RIM, al, 0.2f)
        }
    }
    // red cells in the plasma
    val nCells = when (quality) { 0 -> 120; 1 -> 80; else -> 60 }
    t1Cells("venule3.rbc", 125, 0f, T1_RBC_OXY, 0.06f) { b -> t1FlowCells(b, 2.3f, 3.5f, 1.6f, nCells, 1.9f, seconds, 3, skipNear = 1.8f, span = 2.0f) }
    // plasma: a faint straw haze drifting with the flow
    val d = t1Dyn.data
    var v = 0
    val pc = floatArrayOf(0.98f, 0.90f, 0.60f)
    val q = FloatArray(3)
    for (k in 0 until 60) {
        val pp = rp - 0.1f + ((t1Hash(k) * 0.6f + seconds * 1.6f / 16f) % 0.6f)
        if (pp < 2.3f || pp > 3.5f) continue
        t1RailPoint(pp, t1Hash(k + 9) * TAU, 1.2f * sqrt(t1Hash(k + 17)), q)
        v = t1Put(d, v, q[0], q[1], q[2], pc, 0.16f)
    }
    t1DynDraw(v, GLES20.GL_POINTS, 14f, 1f, depthWrite = false)
}

// ================================================================ stop 4: THE HEART (12 mm rung)
// 1 unit = 8 mm. The left atrium (smooth-walled, glossy endocardium; four pulmonary vein ostia on its
// posterior wall; the appendage's mouth with its pectinate muscles) -> the mitral valve: a D-shaped
// saddle annulus ~3.2 x 2.6 cm, a large anterior leaflet on the aortic side and a crescent posterior
// leaflet with three scallops, a rough coaptation zone along the curved line where they meet; chordae
// from two broad-based papillary muscles to the nearer halves of both leaflets. Next door, in the same
// fibrous skeleton, the aortic valve: its annulus continuous with the anterior mitral leaflet through
// the aorto-mitral curtain and the two fibrous trigones, three semilunar cusps in the sinuses of
// Valsalva, the coronary ostia in two of them; the atrial floor over it is drawn as a cutaway window.
// Past the mitral valve, the trabeculated left ventricle narrows toward its apex. The ride runs
// straight on through the mitral valve (the ventricle's inflow); the outflow is seen, not ridden.
// Timing follows the beat: mitral closed at S1 (heartPhase 0), aortic open 0.05-0.33 s (systole),
// aortic shut at S2, mitral open again after isovolumic relaxation (0.36-0.9 s).

private const val T1_MV_P = 3.97f

private fun t1MvAnnulus(th: Float, out: FloatArray) {      // (side, up, along)
    val sn = sin(th)
    out[0] = 2.0f * cos(th)
    out[1] = if (sn > 0f) min(1.6f * sn, 1.15f) else 1.6f * sn
    out[2] = -0.22f * sn * sn
}

/** A point on a mitral leaflet (frame-local side, up, along) for openness o. */
private fun t1MvLeaf(anterior: Boolean, u0: Float, v: Float, o: Float, h: FloatArray, k: FloatArray, out: FloatArray) {
    val th: Float; val kv: Float; var u = u0
    // V notches at the two commissures: the leaflets part there
    u *= 1f - 0.6f * exp(-(v / 0.1f).pow(2)) - 0.6f * exp(-((1f - v) / 0.1f).pow(2))
    if (anterior) { th = (25f + 130f * v) * DEG; kv = v }
    else {
        th = (155f + 230f * v) * DEG; kv = 1f - v
        val n1 = (v - 0.333f) / 0.035f; val n2 = (v - 0.667f) / 0.035f
        u *= 1f - (0.1f + 0.12f * o) * (exp(-n1 * n1) + exp(-n2 * n2))     // indentations between P1, P2, P3
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
    val antR = T1DynSurface(3, 26); val postR = T1DynSurface(3, 36)
    var mvOpen = -1f; var avOpen = -1f            // re-tessellate only when the valve has moved (not per eye)
}
private var t1Heart: T1Heart? = null

/** The left ventricular cavity radius at distance a past the mitral annulus: a half-ellipsoid to the apex. */
private fun t1LvR(a: Float) = 3.05f * sqrt((1f - (a / 9.8f) * (a / 9.8f)).coerceAtLeast(0.0004f))

// The aortic valve sits beside the mitral valve, anterior to it, in the same fibrous skeleton: its
// annulus touches the mitral annulus at the aorto-mitral curtain, its orifice facing up and back
// (the outflow runs up out of the ventricle, over the atrium). In mitral-frame (along, up):
private const val T1_AV_A = 1.26f
private const val T1_AV_U = 2.0f
private val T1_AV_N = floatArrayOf(-0.55f, 0.835f)       // outflow direction (along, up)

private fun StereoBodyRenderer.t1AvFrame(fm: T1F): T1F {
    val cx = fx(fm, T1_AV_A, 0f, T1_AV_U); val cy = fy(fm, T1_AV_A, 0f, T1_AV_U); val cz = fz(fm, T1_AV_A, 0f, T1_AV_U)
    val dx = fm.dx * T1_AV_N[0] + fm.ux * T1_AV_N[1]; val dy = fm.dy * T1_AV_N[0] + fm.uy * T1_AV_N[1]; val dz = fm.dz * T1_AV_N[0] + fm.uz * T1_AV_N[1]
    // up' = side x d'
    val ux = fm.sy * dz - fm.sz * dy; val uy = fm.sz * dx - fm.sx * dz; val uz = fm.sx * dy - fm.sy * dx
    return StereoBodyRenderer.Frame(cx, cy, cz, dx, dy, dz, fm.sx, fm.sy, fm.sz, ux, uy, uz)
}

private fun StereoBodyRenderer.t1BuildTrabeculae(fm: T1F): T1Batch {
    val b = T1Builder()
    val rnd = java.util.Random(17)
    for (k in 0 until 30) {
        var th = rnd.nextFloat() * TAU
        if (th in 50f * DEG..130f * DEG) th += 90f * DEG          // the smooth outflow region under the aortic valve
        val a0 = 1.2f + rnd.nextFloat() * 5.5f
        val len = 0.9f + rnd.nextFloat() * 1.4f
        val dth = (rnd.nextFloat() - 0.5f) * 0.5f
        val a1 = a0 + len
        val r0 = t1LvR(a0) * 0.985f; val r1 = t1LvR(a1) * 0.985f
        b.rod(r0 * cos(th), r0 * sin(th), -a0, r1 * cos(th + dth), r1 * sin(th + dth), -a1, 0.1f + rnd.nextFloat() * 0.08f)
    }
    // the papillary muscles: broad-based cones from the wall (anterolateral and posteromedial)
    for (sg in SIGNS) {
        val th = if (sg > 0f) -40f * DEG else 220f * DEG
        val ab = 4.9f; val rb = t1LvR(ab) * 0.98f
        val bx = rb * cos(th); val by = rb * sin(th)
        val tx = sg * 1.3f; val ty = -0.5f; val ta = 3.15f
        // axis in local coords (x side, y up, z = -along)
        val ax = tx - bx; val ay = ty - by; val az = -(ta - ab)
        val al = sqrt(ax * ax + ay * ay + az * az)
        val nx = ax / al; val ny = ay / al; val nz = az / al
        var e1x = ny; var e1y = -nx; var e1z = 0f                       // n x z-hat
        val l1 = sqrt(e1x * e1x + e1y * e1y).coerceAtLeast(1e-5f); e1x /= l1; e1y /= l1
        val e2x = ny * e1z - nz * e1y; val e2y = nz * e1x - nx * e1z; val e2z = nx * e1y - ny * e1x
        b.surface(8, 14) { u, v, out ->
            val r = 0.85f + (0.3f - 0.85f) * u
            val c = cos(v * TAU) * r; val s2 = sin(v * TAU) * r
            out[0] = bx + ax * u + e1x * c + e2x * s2
            out[1] = by + ay * u + e1y * c + e2y * s2
            out[2] = -ab + az * u + e1z * c + e2z * s2
        }
    }
    return b.build()
}

private fun StereoBodyRenderer.t1BuildLAStatic(fm: T1F): Array<T1Batch> {
    val holes = T1Builder(); val rims = T1Builder(); val myo = T1Builder()
    fun opening(a: Float, th: Float, ra: Float, rb: Float) {
        val pp = T1_MV_P + a / 16f
        val r = t1WallR(pp, th) * 0.97f; val c = cos(th); val s = sin(th)
        val rx = fm.sx * c + fm.ux * s; val ry = fm.sy * c + fm.uy * s; val rz = fm.sz * c + fm.uz * s
        val tx = -fm.sx * s + fm.ux * c; val ty = -fm.sy * s + fm.uy * c; val tz = -fm.sz * s + fm.uz * c
        val cx = fx(fm, a, c * r, s * r); val cy = fy(fm, a, c * r, s * r); val cz = fz(fm, a, c * r, s * r)
        holes.ellipsoid(cx, cy, cz, floatArrayOf(tx * rb, ty * rb, tz * rb), floatArrayOf(fm.dx * ra, fm.dy * ra, fm.dz * ra), floatArrayOf(rx * 0.01f, ry * 0.01f, rz * 0.01f), 3, 16)
        rims.surface(16, 6) { u, v, out ->
            val g = u * TAU; val h = v * TAU; val w = 0.09f
            val px = cos(g) * (rb + w * cos(h)); val pa = sin(g) * (ra + w * cos(h)); val pr = -w * sin(h) * 0.6f - 0.02f
            out[0] = cx + tx * px + fm.dx * pa + rx * pr; out[1] = cy + ty * px + fm.dy * pa + ry * pr; out[2] = cz + tz * px + fm.dz * pa + rz * pr
        }
    }
    for (k in 0 until 4) opening(if (k % 2 == 0) -2.4f else -1.7f, floatArrayOf(200f, 235f, 305f, 340f)[k] * DEG, 0.62f, 0.64f)
    val th = 140f * DEG; val a = -1.4f
    opening(a, th, 0.9f, 0.55f)
    val pp = T1_MV_P + a / 16f
    val r1 = t1WallR(pp, th) * 0.96f
    for (m in 0 until 6) {                 // pectinate muscles fanning out from the appendage
        val da = -0.75f + 0.3f * m
        val t0 = th + 0.25f * (if (m % 2 == 0) 1f else -1f)
        myo.rod(fx(fm, a + da * 0.4f, cos(th) * r1, sin(th) * r1), fy(fm, a + da * 0.4f, cos(th) * r1, sin(th) * r1), fz(fm, a + da * 0.4f, cos(th) * r1, sin(th) * r1),
            fx(fm, a + da, cos(t0) * r1, sin(t0) * r1), fy(fm, a + da, cos(t0) * r1, sin(t0) * r1), fz(fm, a + da, cos(t0) * r1, sin(t0) * r1), 0.07f, 4, 8)
    }
    return arrayOf(holes.build(), rims.build(), myo.build())
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
    val fm = frameAt(T1_MV_P)
    // ---- the left atrium: four pulmonary vein ostia on its posterior wall, the appendage's mouth with
    // its pectinate muscles on the anterior-left wall; otherwise smooth-walled. (One batch per colour.)
    val la = t1Mesh("la.static") { t1BuildLAStatic(fm) }
    t1LitWorld(la[0], T1_CAP_DEEP, T1_CAP_DEEP, 1f, 0f)
    t1LitWorld(la[1], T1_ENDO_H, T1_WHITE, 1f, 0.2f)
    t1LitWorld(la[2], T1_MYO, T1_MYO_RIM, 1f, 0.12f)
    // In systole the base of the heart - the valve rings and everything hung from them - descends toward
    // the apex, and the ventricle narrows; in diastole they spring back.
    val desc = 1.5f * sys
    val fmS = StereoBodyRenderer.Frame(fm.cx + fm.dx * desc, fm.cy + fm.dy * desc, fm.cz + fm.dz * desc, fm.dx, fm.dy, fm.dz, fm.sx, fm.sy, fm.sz, fm.ux, fm.uy, fm.uz)
    // ---- the fibrous skeleton at the base: the atrial floor (AV junction), opaque except for a
    // cutaway window over the aortic root; the mitral annulus, the aorto-mitral curtain and the fibrous
    // trigones joining it to the aortic annulus.
    val floorA = t1Mesh("mv.floorA") { val h = FloatArray(3); ParamMesh(3, 56) { u, v, out ->
        val th = (125f + 290f * v) * DEG
        t1MvAnnulus(th, h)
        val rr = sqrt(h[0] * h[0] + h[1] * h[1]); val k = 1f + (3.9f / rr - 1f) * u
        out[0] = h[0] * k; out[1] = h[1] * k; out[2] = -h[2] * (1f - u)
    } }
    val floorW = t1Mesh("mv.floorW") { val h = FloatArray(3); ParamMesh(3, 16) { u, v, out ->
        val th = (55f + 70f * v) * DEG
        t1MvAnnulus(th, h)
        val rr = sqrt(h[0] * h[0] + h[1] * h[1]); val k = 1f + (3.9f / rr - 1f) * u
        out[0] = h[0] * k; out[1] = h[1] * k; out[2] = -h[2] * (1f - u)
    } }
    t1Lit(floorA, fmS, 0f, 0f, 0f, T1_ENDO_H, T1_WHITE, 1f, 0.12f)
    val annulus = t1Mesh("mv.annulus2") { val h = FloatArray(3); ParamMesh(48, 8) { u, v, out ->
        t1MvAnnulus(u * TAU, h)
        val b = v * TAU; val rr = sqrt(h[0] * h[0] + h[1] * h[1])
        // thicker along the aorto-mitral side (40-140 degrees), where the fibrous trigones lie within it
        val deg = u * 360f
        val tr = 0.07f + 0.05f * t1Smooth(30f, 50f, deg) * (1f - t1Smooth(130f, 150f, deg))
        out[0] = h[0] * (1f + tr * cos(b) / rr); out[1] = h[1] * (1f + tr * cos(b) / rr); out[2] = -(h[2] + tr * sin(b))
    } }
    t1Lit(annulus, fmS, 0f, 0f, 0f, T1_VALVE, T1_WHITE, 1f, 0.2f)
    val fav = t1AvFrame(fmS)
    // curtain: from the anterior mitral annulus to the aortic annulus, between the two trigones
    val curtain = t1Mesh("av.curtain") { val h = FloatArray(3); ParamMesh(2, 10) { u, v, out ->
        val th = (60f + 60f * v) * DEG
        t1MvAnnulus(th, h)
        // the aortic annulus's lowest arc, in mitral-frame coordinates
        val phi = (90f + (v - 0.5f) * 50f) * DEG
        val ca = cos(phi) * 1.45f; val cu = sin(phi) * 1.45f          // in the AV frame: (side, up')
        val sA = ca; val aA = T1_AV_A + (-0.835f) * cu; val uA = T1_AV_U + (-0.55f) * cu
        out[0] = h[0] + (sA - h[0]) * u; out[1] = h[1] + (uA - h[1]) * u; out[2] = -(h[2] + (aA - h[2]) * u)
    } }
    t1Lit(curtain, fmS, 0f, 0f, 0f, T1_VALVE, T1_WHITE, 1f, 0.22f)
    val avRing = t1Mesh("av.annulus") { ParamMesh(40, 8) { u, v, out ->
        val a = u * TAU; val b = v * TAU
        val hz = 0.9f * (1f - sin(PI_F * (((a - PI_F / 2f + TAU) % (TAU / 3f)) / (TAU / 3f))))   // the crown-shaped annulus: commissures up
        out[0] = cos(a) * (1.48f + 0.06f * cos(b)); out[1] = sin(a) * (1.48f + 0.06f * cos(b)); out[2] = -(hz + 0.06f * sin(b))
    } }
    t1Lit(avRing, fav, 0f, 0f, 0f, T1_VALVE, T1_WHITE, 1f, 0.22f)
    // ---- the aortic valve: three semilunar cusps in the root, open in systole (0.05-0.33 s)
    val root = t1Mesh("aorta.root") { ParamMesh(16, 48) { u, v, out ->
        val a = v * TAU; val z = 3.4f * u
        val bulge = if (z < 1.6f) 0.2f * max(0f, cos(3f * (a - 150f * DEG))) * sin(PI_F * z / 1.6f) else 0f
        val r = 1.5f * (1f + bulge)
        out[0] = cos(a) * r; out[1] = sin(a) * r; out[2] = -z
    } }
    t1Lit(root, fav, 0f, 0f, 0f, T1_INTIMA, T1_WHITE, 1f, 0.15f)
    for (th in floatArrayOf(150f * DEG, 270f * DEG)) {      // left and right coronary ostia in their sinuses
        val r = 1.5f * 1.17f
        t1Shape(t1Disc(), fav, 0.8f, cos(th) * (r - 0.03f), sin(th) * (r - 0.03f), 0f, -cos(th), -sin(th), 1f, 0f, 0f, 0.2f, 0.22f, 1f, T1_CAP_DEEP, T1_CAP_DEEP, 1f)
    }
    val q = FloatArray(3)
    val redoA = abs(oA - hs.avOpen) > 0.004f
    if (redoA) hs.avOpen = oA
    for (j in 0 until 3) {
        if (redoA) hs.cusps[j].update { u, vv, out -> t1AvCusp(j, u, vv, oA, q); out[0] = q[0]; out[1] = q[1]; out[2] = -q[2] }
        t1Lit(hs.cusps[j], fav, 0f, 0f, 0f, T1_VALVE, T1_VALVE_RIM, 1f, 0.2f)
    }
    // ---- the mitral leaflets: fade as the craft or a camera slips through the orifice
    val aShip = t1Along(fmS, shipX, shipY, shipZ); val aCam = t1Along(fmS, camNowX, camNowY, camNowZ)
    val leafA = ((min(abs(aShip), abs(aCam)) - 0.3f) / 0.9f).coerceIn(0f, 1f)
    val h = FloatArray(3); val kk = FloatArray(3)
    if (leafA > 0.02f) {
        if (abs(oM - hs.mvOpen) > 0.004f) {
            hs.mvOpen = oM
            hs.ant.update { u, v, out -> t1MvLeaf(true, u, v, oM, h, kk, q); out[0] = q[0]; out[1] = q[1]; out[2] = -q[2] }
            hs.post.update { u, v, out -> t1MvLeaf(false, u, v, oM, h, kk, q); out[0] = q[0]; out[1] = q[1]; out[2] = -q[2] }
            // the rough (coaptation) zone along each free edge, on the atrial face
            hs.antR.update { u, v, out -> t1MvLeaf(true, 0.72f + 0.28f * u, v, oM, h, kk, q); out[0] = q[0]; out[1] = q[1]; out[2] = -(q[2] - 0.035f) }
            hs.postR.update { u, v, out -> t1MvLeaf(false, 0.72f + 0.28f * u, v, oM, h, kk, q); out[0] = q[0]; out[1] = q[1]; out[2] = -(q[2] - 0.035f) }
        }
        if (leafA < 0.999f) GLES20.glDepthMask(false)
        t1Lit(hs.ant, fmS, 0f, 0f, 0f, T1_VALVE, T1_VALVE_RIM, leafA, 0.18f)
        t1Lit(hs.post, fmS, 0f, 0f, 0f, T1_VALVE_P, T1_VALVE_RIM, leafA, 0.18f)
        t1Lit(hs.antR, fmS, 0f, 0f, 0f, T1_ROUGH, T1_VALVE_RIM, leafA, 0.15f)
        t1Lit(hs.postR, fmS, 0f, 0f, 0f, T1_ROUGH, T1_VALVE_RIM, leafA, 0.15f)
        if (leafA < 0.999f) GLES20.glDepthMask(true)
    }
    // the translucent window over the aortic root (a textbook cutaway of the atrial floor)
    GLES20.glDepthMask(false)
    t1Lit(floorW, fmS, 0f, 0f, 0f, T1_ENDO_H, T1_WHITE, 0.3f, 0.12f)
    GLES20.glDepthMask(true)
    // ---- the chordae: from each papillary tip to the nearer halves of BOTH leaflets, each primary
    // chorda splitting into secondaries; taut when shut, slack when open. Scallop folds on the
    // posterior leaflet and the free edges drawn as lines.
    val sq = 1f - 0.22f * sys                 // the ventricle's cross-section in systole
    val kz = (9.6f - desc) / 9.6f              // its length, base descending toward the fixed apex
    // papillary tips, carried by the contracting wall (they move ~0.5 toward the annulus), in base coordinates
    val tipA = floatArrayOf(1.3f * sq, -0.5f * sq, 3.15f * kz); val tipP = floatArrayOf(-1.3f * sq, -0.5f * sq, 3.15f * kz)
    val d = t1Dyn.data
    var v = 0
    val cc = floatArrayOf(0.98f, 0.96f, 0.90f, 1f)
    fun wp(s: Float, uu: Float, a: Float, c: FloatArray, alpha: Float) { v = t1Put(d, v, fx(fmS, a, s, uu), fy(fmS, a, s, uu), fz(fmS, a, s, uu), c, alpha) }
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
            wp(tip[0], tip[1], tip[2], cc, 1f); wp(bs, bu, ba, cc, 1f)
            wp(bs, bu, ba, cc, 1f); wp(qs, qu, qa, cc, 1f)
            wp(bs, bu, ba, cc, 1f); wp(rs, ru, ra, cc, 1f)
        }
    }
    t1DynDraw(v, GLES20.GL_LINES, 2.2f)
    if (leafA > 0.02f) {
        v = 0
        val ec = floatArrayOf(0.62f, 0.32f, 0.30f, 1f)
        for (leaf in 0..1) for (m in 0 until 24) for (hh in 0..1) {        // free edges
            t1MvLeaf(leaf == 0, 1f, (m + hh) / 24f, oM, h, kk, q); wp(q[0], q[1], q[2] - 0.04f, ec, 1f)
        }
        for (vv in floatArrayOf(0.333f, 0.667f)) for (m in 0 until 8) for (hh in 0..1) {   // folds between P1, P2 and P3
            t1MvLeaf(false, 0.6f * (m + hh) / 8f + 0.08f, vv, oM, h, kk, q); wp(q[0], q[1], q[2] - 0.045f, ec, 1f)
        }
        t1DynDraw(v, GLES20.GL_LINES, 4f, leafA)
    }
    // the cutaway's edge, outlined so the window over the aortic root reads as a cut
    run {
        v = 0
        val oc = floatArrayOf(1f, 0.97f, 0.92f, 1f)
        fun rim(th: Float, k: Float, out: FloatArray) { t1MvAnnulus(th, h); val rr = sqrt(h[0] * h[0] + h[1] * h[1]); val m = 1f + (3.9f / rr - 1f) * k
            out[0] = h[0] * m; out[1] = h[1] * m; out[2] = h[2] * (1f - k) - 0.03f }
        val o1 = FloatArray(3); val o2 = FloatArray(3)
        for (m in 0 until 12) {
            val t0 = (55f + 70f * m / 12f) * DEG; val t1 = (55f + 70f * (m + 1) / 12f) * DEG
            rim(t0, 1f, o1); rim(t1, 1f, o2); wp(o1[0], o1[1], o1[2], oc, 1f); wp(o2[0], o2[1], o2[2], oc, 1f)
        }
        for (th in floatArrayOf(55f * DEG, 125f * DEG)) for (m in 0 until 4) {
            rim(th, m / 4f, o1); rim(th, (m + 1) / 4f, o2); wp(o1[0], o1[1], o1[2], oc, 1f); wp(o2[0], o2[1], o2[2], oc, 1f)
        }
        t1DynDraw(v, GLES20.GL_LINES, 2f)
    }
    // ---- the left ventricle: trabeculated walls narrowing to the apex, a smooth outflow region under
    // the aortic valve; the papillary muscles rise from the wall. Drawn in a little in systole.
    val lvA = t1Mesh("lv.wallA") { ParamMesh(20, 40) { u, v, out ->
        val a = 0.02f + 9.6f * u; val th = (125f + 290f * v) * DEG; val r = t1LvR(a)
        out[0] = cos(th) * r; out[1] = sin(th) * r; out[2] = -a } }
    val lvB = t1Mesh("lv.wallB") { ParamMesh(14, 10) { u, v, out ->
        val a = 3.0f + 6.6f * u; val th = (55f + 70f * v) * DEG; val r = t1LvR(a)
        out[0] = cos(th) * r; out[1] = sin(th) * r; out[2] = -a } }
    t1Lit(lvA, fm, desc, 0f, 0f, T1_ENDO_H, T1_MYO_RIM, 1f, 0.1f, sq, sq, kz)
    t1Lit(lvB, fm, desc, 0f, 0f, T1_ENDO_H, T1_MYO_RIM, 1f, 0.1f, sq, sq, kz)
    val trab = t1Mesh("lv.trab2") { t1BuildTrabeculae(fm) }
    t1Lit(trab, fm, desc, 0f, 0f, T1_TRAB, T1_MYO_RIM, 1f, 0.08f, sq, sq, kz)
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
    t1Cells("venule5.rbc", 60, 0f, T1_RBC_DEOXY, 0.06f) { b -> t1FlowCells(b, 4.45f, 5.6f, 3.0f, when (quality) { 0 -> 56; 1 -> 38; else -> 28 }, 1.2f, seconds, 5, skipNear = 2.0f, span = 1.6f) }
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
    val rc = wN - 0.36f                                        // flattened against the wall
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
    // four separate lobes along a C in the cell's mid-plane, joined by thin chromatin threads
    val lobeAng = floatArrayOf(-75f, -25f, 25f, 75f)
    var px = 0f; var ps = 0f; var pu = 0f
    for (k in 0 until 4) {
        val g = lobeAng[k] * DEG
        rolled(0.42f * cos(g) - 0.18f, 0.02f * (k % 2), 0.42f * sin(g), q)
        val la = aN + q[0]; val ls = cS + q[1]; val lu = cU + q[2]
        // a sausage-shaped lobe lying along the C
        val t2 = FloatArray(3); rolled(-sin(g), 0f, cos(g), t2); val r2 = FloatArray(3); rolled(0f, 1f, 0f, r2)
        t1Shape(sphere, f5, la, ls, lu, t2[0], t2[1], t2[2], r2[0], r2[1], r2[2], 0.09f, 0.09f, 0.18f, T1_NEUT_NUC2, T1_WHITE, 1f, 0.2f)
        if (k > 0) t1Rod(f5, px, ps, pu, la, ls, lu, 0.02f, T1_NEUT_NUC2, T1_WHITE, 1f, 0.2f)
        px = la; ps = ls; pu = lu
    }
    // granules, rotating with the cell
    val gran = t1Mesh("neut.gran") { t1BuildGranules(70, 0.62f, 9, floatArrayOf(0.82f, 0.70f, 0.98f, 1f)) }
    rolled(1f, 0f, 0f, q); val zx = f5.dx * q[0] + f5.sx * q[1] + f5.ux * q[2]; val zy = f5.dy * q[0] + f5.sy * q[1] + f5.uy * q[2]; val zz = f5.dz * q[0] + f5.sz * q[1] + f5.uz * q[2]
    rolled(0f, 0.45f, 0f, q); val yx = f5.dx * q[0] + f5.sx * q[1] + f5.ux * q[2]; val yy = f5.dy * q[0] + f5.sy * q[1] + f5.uy * q[2]; val yz = f5.dz * q[0] + f5.sz * q[1] + f5.uz * q[2]
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
        // the lamellipodium: a broad, thin sheet spread on the endothelium, its edge ruffling
        t1Shape(sphere, f5, la, ls - rS * 0.24f, lu - rU * 0.24f, ddA, ddS, ddU, 0f, rS, rU, 0.45f + 0.03f * sin(seconds * 3f), 0.04f, ext * 0.5f + 0.2f, T1_NEUT, T1_WHITE, 0.85f, 0.25f)
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
    t1Shape(sphere, f5, aN, cS, cU, 1f, 0f, 0f, 0f, rS, rU, 0.8f, 0.3f, 0.8f, T1_NEUT, T1_WHITE, 0.5f, 0.15f)
    GLES20.glDepthMask(true)

    // ---- the monocyte, well downstream on the lower port wall: a flat dome on the endothelium, grey-blue
    // cytoplasm, a kidney-shaped nucleus with its notch toward the cell's centre, thin ruffles at its rim
    val aM = 7.5f + 0.15f * sin(seconds * 0.15f); val thM = 235f * DEG
    val pM = i + aM / 16f; val wM = t1WallR(pM, thM); val rM = wM - 0.3f
    val cM = cos(thM); val sM = sin(thM)
    val tMs = -sM; val tMu = cM                                 // tangential, round the vessel
    t1Shape(t1Mesh("mono.nuc") { ParamMesh.torusArc(0.45f, 0.62f) }, f5, aM, cM * (rM - 0.05f), sM * (rM - 0.05f), 0f, -cM, -sM, 1f, 0f, 0f, 0.5f, 0.5f, 0.35f, T1_MONO_NUC, T1_WHITE, 1f, 0.22f)
    for (k in 0 until 8) {                                      // ruffles: thin fins standing up round the rim
        val a = TAU * k / 8f + 0.2f
        val w = 0.03f * sin(seconds * 0.9f + k * 1.3f)
        val oa = (1.12f + w) * sin(a); val ot = (1.12f + w) * cos(a)
        t1Shape(sphere, f5, aM + oa, cM * (wM - 0.12f) + tMs * ot, sM * (wM - 0.12f) + tMu * ot, sin(a), tMs * cos(a), tMu * cos(a), 0f, -cM, -sM,
            0.02f, 0.12f, 0.25f, T1_MONO, T1_WHITE, 0.9f, 0.2f)
    }
    GLES20.glDepthMask(false)
    for (k in 0 until 3) {                 // pale vacuoles in the cytoplasm
        val a = 2.2f * k + 0.7f
        t1Lit(sphere, f5, aM + 0.6f * sin(a), cM * (rM - 0.05f) + tMs * 0.6f * cos(a), sM * (rM - 0.05f) + tMu * 0.6f * cos(a), T1_WHITE, T1_WHITE, 0.45f, 0.35f, 0.12f, 0.12f, 0.12f)
    }
    t1Shape(sphere, f5, aM, cM * wM, sM * wM, 1f, 0f, 0f, 0f, -cM, -sM, 1.15f, 0.5f, 1.15f, T1_MONO, T1_WHITE, 0.75f, 0.15f)
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
private const val T1_AX_S = 0.8f
private const val T1_AX_U = -0.8f

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

private class T1Vessel(val tube: T1Batch, val feet: T1Batch, val nuclei: T1Batch, val zips: LineMesh, val pericytes: T1Batch)

private fun StereoBodyRenderer.t1BuildVessel(): T1Vessel {
    val tube = T1Builder()
    tube.surface(40, 28) { u, v, out -> t1VesselPoint(u, v * TAU, T1_V_R, out) }
    val feet = T1Builder(); val nuc = T1Builder()
    val rnd = java.util.Random(12)
    val q = FloatArray(3); val q2 = FloatArray(3); val q3 = FloatArray(3)
    // astrocyte end-feet tiling the outside
    for (k in 0 until 40) {
        val t = 0.04f + 0.92f * (k / 40f) + 0.01f * rnd.nextFloat(); val a = k * 2.4f
        t1VesselPoint(t, a, T1_V_R + 0.1f, q); t1VesselPoint(t + 0.03f, a, T1_V_R + 0.1f, q2); t1VesselPoint(t, a + 0.4f, T1_V_R + 0.1f, q3)
        val lx = sqrt((q2[0] - q[0]).pow(2) + (q2[1] - q[1]).pow(2) + (q2[2] - q[2]).pow(2)).coerceAtLeast(1e-4f)
        val lz = sqrt((q3[0] - q[0]).pow(2) + (q3[1] - q[1]).pow(2) + (q3[2] - q[2]).pow(2)).coerceAtLeast(1e-4f)
        val ex = floatArrayOf((q2[0] - q[0]) / lx * 0.55f, (q2[1] - q[1]) / lx * 0.55f, (q2[2] - q[2]) / lx * 0.55f)
        val ez = floatArrayOf((q3[0] - q[0]) / lz * 0.55f, (q3[1] - q[1]) / lz * 0.55f, (q3[2] - q[2]) / lz * 0.55f)
        val nx = ex[1] * ez[2] - ex[2] * ez[1]; val ny = ex[2] * ez[0] - ex[0] * ez[2]; val nz = ex[0] * ez[1] - ex[1] * ez[0]
        val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
        feet.ellipsoid(q[0], q[1], q[2], ex, floatArrayOf(nx / nl * 0.05f, ny / nl * 0.05f, nz / nl * 0.05f), ez, 7, 12)
    }
    // endothelial nuclei bulging into the lumen
    for (k in 0 until 6) {
        val t = 0.1f + 0.14f * k; val a = 0.4f + TAU / 12f + (k * 3 % 6) * TAU / 6f
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
    val seams = FloatArray(6) { 0.4f + it * TAU / 6f }                   // six endothelial cells round the lumen
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
    // pericytes: elongated cell bodies on the outer surface, their processes wrapping the vessel
    val per = T1Builder()
    for (k in 0 until 6) {
        val t = 0.12f + 0.14f * k; val a = 1.1f + k * 2.2f
        t1VesselPoint(t, a, T1_V_R + 0.17f, q); t1VesselPoint(t + 0.05f, a, T1_V_R + 0.17f, q2)
        val ex = floatArrayOf(q2[0] - q[0], q2[1] - q[1], q2[2] - q[2])
        val el = sqrt(ex[0] * ex[0] + ex[1] * ex[1] + ex[2] * ex[2]).coerceAtLeast(1e-4f)
        for (m in 0..2) ex[m] = ex[m] / el * 0.6f
        t1VesselPoint(t, a + 0.3f, T1_V_R + 0.17f, q3)
        val tz = floatArrayOf(q3[0] - q[0], q3[1] - q[1], q3[2] - q[2]); val tl = sqrt(tz[0] * tz[0] + tz[1] * tz[1] + tz[2] * tz[2]).coerceAtLeast(1e-4f)
        for (m in 0..2) tz[m] = tz[m] / tl * 0.25f
        val nx = ex[1] * tz[2] - ex[2] * tz[1]; val ny = ex[2] * tz[0] - ex[0] * tz[2]; val nz = ex[0] * tz[1] - ex[1] * tz[0]
        val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
        per.ellipsoid(q[0], q[1], q[2], ex, floatArrayOf(nx / nl * 0.12f, ny / nl * 0.12f, nz / nl * 0.12f), tz, 6, 10)
        for (m in 0..2) {                         // processes curling round the vessel
            val t0 = t + (m - 1) * 0.02f
            var prev = FloatArray(3); t1VesselPoint(t0, a, T1_V_R + 0.15f, prev)
            for (st in 1..4) {
                val cur = FloatArray(3); t1VesselPoint(t0 + (m - 1) * 0.01f * st, a + (if (m == 1) -1f else 1f) * 0.22f * st, T1_V_R + 0.13f, cur)
                per.rod(prev[0], prev[1], prev[2], cur[0], cur[1], cur[2], 0.04f, 3, 6)
                prev = cur
            }
        }
    }
    return T1Vessel(tube.build(), feet.build(), nuc.build(), LineMesh(z.toFloatArray()), per.build())
}

/** A world point on the axon's course: parallel to the rail at (side T1_AX_S, up T1_AX_U) from the node. */
private fun StereoBodyRenderer.t1AxonPoint(i: Int, a: Float, out: FloatArray) {
    val f = frameAt(i + a / 16f)
    out[0] = f.cx + f.sx * T1_AX_S + f.ux * T1_AX_U; out[1] = f.cy + f.sy * T1_AX_S + f.uy * T1_AX_U; out[2] = f.cz + f.sz * T1_AX_S + f.uz * T1_AX_U
}

private class T1Neuron(val soma: T1Batch, val body: T1Batch, val nucleus: T1Batch, val nucleolus: T1Batch, val axon: T1Batch, val myelin: T1Batch, val spines: LineMesh, val channels: PointMesh, val heads: PointMesh)

private const val T1_AIS0 = 3.4f
private val T1_NODES = floatArrayOf(7.4f, 19.4f, 31.4f)

private fun StereoBodyRenderer.t1BuildNeuron(i: Int): T1Neuron {
    val f6 = frameAt(i.toFloat())
    fun w(a: Float, s: Float, u: Float, out: FloatArray) { out[0] = fx(f6, a, s, u); out[1] = fy(f6, a, s, u); out[2] = fz(f6, a, s, u) }
    val sa = 2.6f; val ss = 2.3f; val su = 1.1f
    val somaB = T1Builder(); val body = T1Builder(); val nuc = T1Builder(); val nol = T1Builder()
    val q = FloatArray(3); val q2 = FloatArray(3)
    // soma: a pyramid - a rounded-triangular section, broad base down, drawn up into the apical trunk
    somaB.surface(24, 30) { t, v, out ->
        val phi = v * TAU
        // a rounded three-sided pyramid: a broad flat base, edges tapering into the apical trunk
        val base = t1Smooth(0f, 0.08f, t)
        var r = (1.25f * (1f - t).pow(1.1f) + 0.26f * t) * (0.25f + 0.75f * sqrt(base))
        r *= 1f + 0.30f * cos(3f * phi)
        w(sa + r * cos(phi), ss + r * sin(phi), su - 1.55f + 3.1f * max(t, 0.02f), out)
    }
    w(sa, ss, su - 0.4f, q)
    nuc.ellipsoid(q[0], q[1], q[2], floatArrayOf(f6.dx * 0.5f, f6.dy * 0.5f, f6.dz * 0.5f), floatArrayOf(f6.ux * 0.52f, f6.uy * 0.52f, f6.uz * 0.52f),
        floatArrayOf(f6.sx * 0.5f, f6.sy * 0.5f, f6.sz * 0.5f), 10, 14)
    w(sa + 0.1f, ss - 0.08f, su - 0.3f, q)
    nol.ellipsoid(q[0], q[1], q[2], floatArrayOf(f6.dx * 0.15f, f6.dy * 0.15f, f6.dz * 0.15f), floatArrayOf(f6.ux * 0.15f, f6.uy * 0.15f, f6.uz * 0.15f),
        floatArrayOf(f6.sx * 0.15f, f6.sy * 0.15f, f6.sz * 0.15f), 6, 8)
    // dendrites: tapering tubes studded with spines (short stubs with a bulbous head)
    val spines = ArrayList<Float>(); val spineHeads = ArrayList<Float>()
    val sc = floatArrayOf(0.80f, 0.70f, 1f, 1f); val hc = floatArrayOf(0.92f, 0.86f, 1f, 1f)
    val rnd = java.util.Random(66)
    val q3 = FloatArray(3)
    fun dend(a0: Float, s0: Float, u0: Float, a1: Float, s1: Float, u1: Float, r0: Float) {
        val r1 = r0 * 0.7f
        w(a0, s0, u0, q); w(a1, s1, u1, q2)
        val ax = q2[0] - q[0]; val ay = q2[1] - q[1]; val az = q2[2] - q[2]
        val al = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-4f)
        val nx = ax / al; val ny = ay / al; val nz = az / al
        var e1x = ny * f6.dz - nz * f6.dy; var e1y = nz * f6.dx - nx * f6.dz; var e1z = nx * f6.dy - ny * f6.dx
        var l1 = sqrt(e1x * e1x + e1y * e1y + e1z * e1z)
        if (l1 < 1e-3f) { e1x = ny * f6.uz - nz * f6.uy; e1y = nz * f6.ux - nx * f6.uz; e1z = nx * f6.uy - ny * f6.ux; l1 = sqrt(e1x * e1x + e1y * e1y + e1z * e1z) }
        e1x /= l1; e1y /= l1; e1z /= l1
        val e2x = ny * e1z - nz * e1y; val e2y = nz * e1x - nx * e1z; val e2z = nx * e1y - ny * e1x
        val bx = q[0]; val by = q[1]; val bz = q[2]
        body.surface(3, 8) { u, v, out ->
            val r = r0 + (r1 - r0) * u; val c = cos(v * TAU) * r; val s2 = sin(v * TAU) * r
            out[0] = bx + ax * u + e1x * c + e2x * s2; out[1] = by + ay * u + e1y * c + e2y * s2; out[2] = bz + az * u + e1z * c + e2z * s2
        }
        val nsp = (al / 0.2f).toInt()
        for (k in 0 until nsp) {
            val t = (k + 0.5f) / nsp; val r = r0 + (r1 - r0) * t
            val g = rnd.nextFloat() * TAU; val c = cos(g); val s2 = sin(g)
            val ox = e1x * c + e2x * s2; val oy = e1y * c + e2y * s2; val oz = e1z * c + e2z * s2
            val sx = bx + ax * t + ox * r; val sy = by + ay * t + oy * r; val sz = bz + az * t + oz * r
            val hx = sx + ox * 0.12f; val hy = sy + oy * 0.12f; val hz = sz + oz * 0.12f
            spines.add(sx); spines.add(sy); spines.add(sz); spines.addAll(sc.toList())
            spines.add(hx); spines.add(hy); spines.add(hz); spines.addAll(sc.toList())
            spineHeads.add(hx); spineHeads.add(hy); spineHeads.add(hz); spineHeads.addAll(hc.toList())
        }
    }
    // the apical dendrite: a thick trunk rising 12 units (~100 um) toward the pia, oblique branches off
    // it, and a tuft at its top
    val ap = floatArrayOf(sa, ss, su + 1.5f, sa - 0.1f, ss + 0.1f, su + 4.3f, sa - 0.2f, ss + 0.2f, su + 7.3f, sa - 0.3f, ss + 0.35f, su + 10.2f, sa - 0.35f, ss + 0.4f, su + 13.5f)
    var ar = 0.3f
    for (k in 0 until 4) { dend(ap[3 * k], ap[3 * k + 1], ap[3 * k + 2], ap[3 * k + 3], ap[3 * k + 4], ap[3 * k + 5], ar); ar *= 0.72f }
    dend(sa - 0.1f, ss + 0.1f, su + 2.8f, sa + 1.7f, ss + 0.9f, su + 3.9f, 0.1f)
    dend(sa - 0.15f, ss + 0.15f, su + 4.4f, sa - 2.0f, ss + 0.6f, su + 5.4f, 0.09f)
    dend(sa - 0.2f, ss + 0.25f, su + 6.3f, sa + 1.4f, ss + 1.5f, su + 7.3f, 0.08f)
    dend(sa - 0.25f, ss + 0.3f, su + 8.5f, sa - 1.5f, ss + 1.3f, su + 9.4f, 0.07f)
    for (k in 0 until 5) {
        val g = TAU * k / 5f + 0.3f; val l = 1.5f + 1.0f * t1Hash(k + 11)
        dend(sa - 0.35f, ss + 0.4f, su + 13.5f, sa - 0.35f + cos(g) * l * 0.8f, ss + 0.4f + sin(g) * l * 0.8f, su + 13.5f + l * 0.6f, 0.07f)
    }
    // basal dendrites: a skirt leaving the base downward-outward (30-60 degrees below horizontal),
    // each forking twice
    fun basal(a0: Float, s0: Float, u0: Float, az: Float, dip: Float, len: Float, r: Float, depth: Int) {
        val ca = cos(az) * cos(dip); val cs = sin(az) * cos(dip); val cu = -sin(dip)
        val ea = a0 + ca * len; val es = s0 + cs * len; val eu = u0 + cu * len
        dend(a0, s0, u0, ea, es, eu, r)
        if (depth > 0) for (sg in SIGNS) basal(ea, es, eu, az + sg * 0.45f, dip + 0.08f, len * 0.75f, r * 0.7f, depth - 1)
    }
    for (k in 0 until 6) {
        val az = (60f * k + 15f) * DEG
        val toLane = sin(az) < -0.3f                                     // keep clear of the craft's lane
        basal(sa + 0.55f * cos(az), ss + 0.55f * sin(az), su - 1.25f, az, (35f + 20f * t1Hash(k)) * DEG, if (toLane) 1.0f else 1.8f, 0.14f, 2)
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
    val aEnd = 34f
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
            val r = 0.125f + 0.07f * sqrt((1f - e.pow(14)).coerceAtLeast(0f))
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
    return T1Neuron(somaB.build(cullBack = true), body.build(), nuc.build(), nol.build(), ax.build(), my.build(), LineMesh(spines.toFloatArray()), PointMesh(ch.toFloatArray()), PointMesh(spineHeads.toFloatArray()))
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
        t1LitWorld(vs.pericytes, T1_PERICYTE, T1_WHITE, 1f, 0.2f, ox, oy, oz)
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
        t1LitWorld(vs.feet, T1_ENDFOOT, T1_WHITE, 0.25f, 0.2f, ox, oy, oz)
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
        t1LitWorld(nr.myelin, T1_MYELIN2, T1_WHITE, 1f, 0.4f, ox, oy, oz)
        t1Color(nr.spines, null, 0f, 0f, 0f, 1.5f, false, 1f, ox = ox, oy = oy, oz = oz)
        t1Color(nr.heads, null, 0f, 0f, 0f, 2.5f, true, 1f, ox = ox, oy = oy, oz = oz)
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
        val off = t1ShipOff(fsn); val so = off[0] - 0.8f; val uo = off[1] - 0.6f
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
        // the bouton's mitochondrion, and the parent structures: the preterminal axon coming down into
        // the bouton, the spine's thin neck running down to its dendrite shaft
        t1Shape(capsule, fsn, -0.6f, so + 4.6f, uo + 3.1f, 0.3f, 0f, 1f, 1f, 0f, 0f, 1.33f, 1.33f, 1.33f, T1_MITO_IN, T1_WHITE, 1f, 0.2f)
        t1Rod(fsn, 0f, so + 3.3f, uo + 4.6f, 0.6f, so + 3.9f, uo + 11f, 1.2f, T1_BOUTON, T1_WHITE, 1f, 0.2f, sphere)
        t1Rod(fsn, 0f, so + 3.2f, uo - 3.9f, 0f, so + 3.2f, uo - 7.0f, 0.6f, T1_SPINE, T1_WHITE, 1f, 0.2f, sphere)
        t1Shape(cylinder, fsn, 0f, so + 3.2f, uo - 9.8f, 1f, 0f, 0f, 0f, 1f, 0f, 3f, 3f, 9f, T1_SPINE, T1_WHITE, 1f, 0.2f)
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
private const val T1_PIT_R = 1.35f
private const val T1_LEAF = 0.047f

private class T1Membrane {
    val pit = T1DynSurface(30, 40)
    val heads = DynMesh(12000)
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
    val transporters: T1Batch, val pumpBatch: T1Batch, val rim: PointMesh, val headsC: PointMesh)

private const val T1_MEM_R = 2.8f                       // the patch's radius: its cut edge shows the bilayer in profile
private const val T1_MEM_TILT = 45f * DEG
private val T1_SHEAR = tan(T1_MEM_TILT)
/** The three Na+/K+ pumps next to the pit, where the camera finds them. */
private val T1_PUMP_XY = floatArrayOf(1.85f, -0.55f, -1.3f, 1.45f, 0.35f, -1.95f)

private fun t1BuildMembrane(): T1MemStatic {
    val rnd = java.util.Random(77)
    val hA = ArrayList<Float>(); val hB = ArrayList<Float>(); val hC = ArrayList<Float>(); val tails = ArrayList<Float>(); val gly = ArrayList<Float>()
    val hc = floatArrayOf(1f, 0.78f, 0.45f, 1f); val tc = floatArrayOf(0.30f, 0.70f, 0.66f, 0.8f); val chol = floatArrayOf(0.96f, 0.96f, 0.55f, 0.95f)
    val gc = floatArrayOf(0.72f, 0.96f, 0.70f, 0.6f)
    fun add(l: ArrayList<Float>, x: Float, y: Float, z: Float, c: FloatArray) { l.add(x); l.add(y); l.add(z); l.add(c[0]); l.add(c[1]); l.add(c[2]); l.add(c[3]) }
    // proteins first, at true size and crowded - a membrane is about half protein by mass - right up to
    // the pit's edge: 1 receptors, 2 channels, 3 carriers, 4 more Na+/K+ pumps
    val prot = ArrayList<FloatArray>()
    for (k in 0 until 3) prot.add(floatArrayOf(T1_PUMP_XY[2 * k], T1_PUMP_XY[2 * k + 1], 0f))
    val want = intArrayOf(0, 160, 70, 110, 60)
    var tries = 0
    for (kind in 1..4) {
        var placed = 0
        while (placed < want[kind] && tries < 60000) {
            tries++
            val r = sqrt(1.42f * 1.42f + rnd.nextFloat() * ((T1_MEM_R - 0.08f).pow(2) - 1.42f * 1.42f)); val a = rnd.nextFloat() * TAU
            val x = cos(a) * r; val y = sin(a) * r
            if (prot.any { (it[0] - x).pow(2) + (it[1] - y).pow(2) < 0.13f * 0.13f }) continue
            // the unnamed machines thin out away from the pit, so the pumps and the pit carry the view
            if (kind >= 2 && r > 2.2f && rnd.nextFloat() < 0.5f) { placed++; continue }
            prot.add(floatArrayOf(x, y, kind.toFloat())); placed++
        }
    }
    // lipid heads on a jittered grid (no lattice pattern): each dot stands for a couple of dozen lipids
    val hx = ArrayList<Float>(); val hy = ArrayList<Float>()
    val sp = 0.04f
    var gy = -T1_MEM_R
    while (gy <= T1_MEM_R) {
        var gx = -T1_MEM_R
        while (gx <= T1_MEM_R) {
            val x = gx + (rnd.nextFloat() - 0.5f) * sp * 0.8f; val y = gy + (rnd.nextFloat() - 0.5f) * sp * 0.8f
            val r2 = x * x + y * y
            if (r2 > T1_PIT_R * T1_PIT_R && r2 < T1_MEM_R * T1_MEM_R && prot.none { (it[0] - x).pow(2) + (it[1] - y).pow(2) < 0.07f * 0.07f }) { hx.add(x); hy.add(y) }
            gx += sp
        }
        gy += sp
    }
    for (k in hx.indices) {
        val x = hx[k]; val y = hy[k]; val a = atan2(y, x)
        for (side in 0..1) {
            val sg = if (side == 0) 1f else -1f             // +z = extracellular (toward the craft)
            add(when ((k * 2 + side) % 3) { 0 -> hA; 1 -> hB; else -> hC }, x, y, sg * T1_LEAF, hc)
            if (rnd.nextFloat() < 0.1f) { add(tails, x, y, sg * 0.036f, chol); add(tails, x, y, sg * 0.016f, chol) }   // cholesterol
            else for (tw in SIGNS) {                       // two fatty-acid tails per lipid
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
    // the cut edge: the bilayer in profile all round the rim - heads out, paired tails in
    val rimH = ArrayList<Float>()
    val nr = (TAU * T1_MEM_R / 0.022f).toInt()
    for (k in 0 until nr) {
        val a = TAU * k / nr
        val x = cos(a) * T1_MEM_R; val y = sin(a) * T1_MEM_R
        for (sg in SIGNS) {
            add(rimH, x, y, sg * T1_LEAF, hc)
            for (tw in SIGNS) { val ox = -sin(a) * 0.005f * tw; val oy = cos(a) * 0.005f * tw
                add(tails, x + ox, y + oy, sg * 0.040f, tc); add(tails, x + ox * 1.2f, y + oy * 1.2f, sg * 0.006f, tc) }
        }
    }
    // receptors (7-TM bundle, stalk, glycosylated ligand-binding head), channels (pentamers round a
    // pore), carriers (two-lobed), pumps (membrane body, big cytoplasmic ATP-binding head, beta subunit)
    val pb = T1Builder(); val cb = T1Builder(); val tb = T1Builder(); val qb = T1Builder()
    for (p in prot) {
        val x = p[0]; val y = p[1]
        when (p[2].toInt()) {
            1 -> {
                pb.ellipsoid(x, y, 0f, floatArrayOf(0.045f, 0f, 0f), floatArrayOf(0f, 0.045f, 0f), floatArrayOf(0f, 0f, 0.06f), 3, 6)
                pb.ellipsoid(x, y, 0.1f, floatArrayOf(0.018f, 0f, 0f), floatArrayOf(0f, 0.018f, 0f), floatArrayOf(0f, 0f, 0.05f), 3, 5)
                pb.ellipsoid(x + 0.01f, y, 0.17f, floatArrayOf(0.045f, 0f, 0f), floatArrayOf(0f, 0.04f, 0f), floatArrayOf(0f, 0f, 0.035f), 4, 7)
                for (b in 0 until 3) {
                    val a = b * 2.1f + x
                    val z0 = 0.2f; val x1 = x + cos(a) * 0.03f; val y1 = y + sin(a) * 0.03f
                    add(gly, x, y, z0, gc); add(gly, x1, y1, z0 + 0.05f, gc)
                    add(gly, x1, y1, z0 + 0.05f, gc); add(gly, x1 + cos(a + 0.8f) * 0.025f, y1 + sin(a + 0.8f) * 0.025f, z0 + 0.08f, gc)
                    add(gly, x1, y1, z0 + 0.05f, gc); add(gly, x1 + cos(a - 0.8f) * 0.025f, y1 + sin(a - 0.8f) * 0.025f, z0 + 0.085f, gc)
                }
            }
            2 -> for (k in 0 until 5) {
                val a = TAU * k / 5f
                cb.ellipsoid(x + cos(a) * 0.035f, y + sin(a) * 0.035f, 0.005f, floatArrayOf(0.022f, 0f, 0f), floatArrayOf(0f, 0.022f, 0f), floatArrayOf(0f, 0f, 0.058f), 3, 5)
            }
            3 -> {
                val a = x * 3.1f + y
                for (sg in SIGNS) tb.ellipsoid(x + cos(a) * 0.028f * sg, y + sin(a) * 0.028f * sg, 0f, floatArrayOf(0.03f, 0f, 0f), floatArrayOf(0f, 0.03f, 0f), floatArrayOf(0f, 0f, 0.06f), 3, 5)
            }
            4 -> {
                qb.ellipsoid(x, y, 0f, floatArrayOf(0.055f, 0f, 0f), floatArrayOf(0f, 0.055f, 0f), floatArrayOf(0f, 0f, 0.062f), 3, 6)
                qb.ellipsoid(x, y, -0.125f, floatArrayOf(0.08f, 0f, 0f), floatArrayOf(0f, 0.08f, 0f), floatArrayOf(0f, 0f, 0.075f), 3, 6)
                qb.ellipsoid(x + 0.015f, y, 0.07f, floatArrayOf(0.035f, 0f, 0f), floatArrayOf(0f, 0.035f, 0f), floatArrayOf(0f, 0f, 0.03f), 3, 5)
            }
        }
    }
    return T1MemStatic(PointMesh(hA.toFloatArray()), PointMesh(hB.toFloatArray()), LineMesh(tails.toFloatArray()), LineMesh(gly.toFloatArray()),
        pb.build(), cb.build(), FloatArray(0), tb.build(), qb.build(), PointMesh(rimH.toFloatArray()), PointMesh(hC.toFloatArray()))
}

/** model *= shear: local x += tan(tilt) * local z (the pit and the vesicle follow the craft's oblique course). */
private val t1ShearM = FloatArray(16); private val t1TmpM = FloatArray(16)
private fun StereoBodyRenderer.t1ApplyShear() {
    Matrix.setIdentityM(t1ShearM, 0); t1ShearM[8] = T1_SHEAR
    Matrix.multiplyMM(t1TmpM, 0, model, 0, t1ShearM, 0); System.arraycopy(t1TmpM, 0, model, 0, 16)
}

internal fun StereoBodyRenderer.drawMembrane(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp < 6.35f || rp > 7.35f) return
    // The membrane stands 45 degrees off square to the course, so its two leaflets, its cut edge and the
    // proteins' profiles read in perspective; it is centred on the craft's lane, and the pit forms
    // along the craft's oblique course.
    val f0 = frameAt(T1_MEM_P)
    val off = t1ShipOff(f0)
    val tc = cos(T1_MEM_TILT); val ts = sin(T1_MEM_TILT)
    val fm = StereoBodyRenderer.Frame(f0.cx + f0.sx * off[0] + f0.ux * off[1], f0.cy + f0.sy * off[0] + f0.uy * off[1], f0.cz + f0.sz * off[0] + f0.uz * off[1],
        f0.dx * tc + f0.sx * ts, f0.dy * tc + f0.sy * ts, f0.dz * tc + f0.sz * ts,
        f0.sx * tc - f0.dx * ts, f0.sy * tc - f0.dy * ts, f0.sz * tc - f0.dz * ts, f0.ux, f0.uy, f0.uz)
    val so = 0f; val uo = 0f
    val m = t1Mem ?: T1Membrane().also { t1Mem = it }
    val st = t1Mesh("membrane3") { t1BuildMembrane() }
    val sShip = t1Along(fm, shipX, shipY, shipZ)
    val pinchH = T1_FIL + sqrt(T1_RV * T1_RV + 2f * T1_RV * T1_FIL)
    val h = max(-T1_RV, sShip)
    val pinched = h >= pinchH - 0.01f
    // ---- proteins (opaque)
    t1Lit(st.proteins, fm, 0f, so, uo, T1_RECEPTOR, T1_WHITE, 1f, 0.2f)
    t1Lit(st.channels, fm, 0f, so, uo, T1_CH_DIM, T1_WHITE, 1f, 0.12f)
    t1Lit(st.transporters, fm, 0f, so, uo, T1_TR_DIM, T1_WHITE, 1f, 0.12f)
    t1Lit(st.pumpBatch, fm, 0f, so, uo, T1_PUMP_DIM, T1_WHITE, 1f, 0.1f)
    // the three pumps by the pit: glowing, pumping 3 Na+ out and 2 K+ in every cycle (slowed)
    val d = t1Dyn.data
    var iv = 0
    val na = floatArrayOf(1f, 0.92f, 0.3f); val kc = floatArrayOf(0.75f, 0.5f, 1f)
    for (k in 0 until 3) {
        val x = T1_PUMP_XY[2 * k]; val y = T1_PUMP_XY[2 * k + 1]
        t1Lit(sphere, fm, 0f, x, y, T1_PUMP, T1_WHITE, 1f, 0.35f, 0.06f, 0.06f, 0.07f)            // transmembrane body (alpha subunit)
        t1Lit(sphere, fm, 0.13f, x, y, T1_PUMP, T1_WHITE, 1f, 0.35f, 0.1f, 0.1f, 0.09f)           // cytoplasmic head: the N and P (ATP-binding) domains
        t1Lit(sphere, fm, -0.085f, x + 0.02f, y, T1_RECEPTOR, T1_WHITE, 1f, 0.3f, 0.04f, 0.04f, 0.04f) // beta subunit, outside
        val ph = ((seconds / 1.5f + k * 0.33f) % 1f)
        if (ph < 0.45f) { val t = ph / 0.45f
            for (j in 0 until 3) { val al = 0.12f * (1f - t) + (-0.2f) * t; val ss = x + 0.02f * (j - 1); val uu = y + 0.015f * (j % 2)
                iv = t1Put(d, iv, fx(fm, al, ss, uu), fy(fm, al, ss, uu), fz(fm, al, ss, uu), na, 1f - t * 0.3f) } }
        else if (ph > 0.5f && ph < 0.95f) { val t = (ph - 0.5f) / 0.45f
            for (j in 0 until 2) { val al = -0.2f * (1f - t) + 0.15f * t; val ss = x + 0.02f * (j * 2 - 1); val uu = y
                iv = t1Put(d, iv, fx(fm, al, ss, uu), fy(fm, al, ss, uu), fz(fm, al, ss, uu), kc, 1f) } }
    }
    t1DynDraw(iv, GLES20.GL_POINTS, 4.5f)
    // ---- flat bilayer: heads (jiggling), tails, and the cut edge in profile
    // three interleaved sets of heads, each jostling on its own: the fluid mosaic
    t1Color(st.headsA, fm, 0f, so + 0.008f * sin(seconds * 8.3f), uo - 0.008f * cos(seconds * 6.1f), 3f, true)
    t1Color(st.headsB, fm, 0f, so - 0.008f * cos(seconds * 7.1f), uo + 0.008f * sin(seconds * 9.2f), 3f, true)
    t1Color(st.headsC, fm, 0f, so + 0.008f * sin(seconds * 5.7f + 2f), uo + 0.008f * cos(seconds * 7.9f + 1f), 3f, true)
    t1Color(st.rim, fm, 0f, so, uo, 3.5f, true)
    if (quality < 2) t1Color(st.tails, fm, 0f, so, uo, 1f, false, 0.9f)
    // ---- the pit (or, once pinched, the flat membrane with the vesicle below)
    if (abs(h - m.lastH) > 0.002f || m.headVerts == 0) {
        m.lastH = h
        t1PitProfile(h, m, pinched)
        val dd = m.heads.data
        var v = 0
        var acc = 0f
        val hc = floatArrayOf(1f, 0.78f, 0.45f)
        for (k in 0 until m.np) {
            if (k > 0) acc += sqrt((m.pr[k] - m.pr[k - 1]).pow(2) + (m.pz[k] - m.pz[k - 1]).pow(2))
            if (k > 0 && acc < 0.055f) continue
            acc = 0f
            val r = m.pr[k]
            val cnt = max(1, (TAU * r / 0.055f).toInt())
            for (j in 0 until cnt) {
                if (v + 2 >= 12000) break
                val a = TAU * (j + t1Hash(k * 977 + j)) / cnt
                val jr = (t1Hash(k * 131 + j * 7) - 0.5f) * 0.04f
                for (sg in SIGNS) {
                    val rr = r + jr + m.nr[k] * T1_LEAF * sg; val zz = m.pz[k] + m.nz[k] * T1_LEAF * sg
                    v = t1Put(dd, v, cos(a) * rr, sin(a) * rr, -zz, hc, 0.95f)
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
    t1Model(fm, 0f, so, uo); t1ApplyShear()
    Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    colorShader.use(mvp, 2.6f, points = true)
    m.heads.draw(colorShader.positionHandle, colorShader.colorHandle, GLES20.GL_POINTS, m.headVerts)
    // cargo receptors gathered in the coated pit, carried down with the membrane as it invaginates
    if (!pinched && m.np > 1) {
        t1Cells("mem.cargo", 28, 0f, T1_RECEPTOR, 0.25f, ball = true) { bb ->
            for (j in 0 until 14) {
                val r0 = 0.6f + 0.7f * t1Hash(j + 300); val ang = TAU * j / 14f + 0.4f * t1Hash(j + 310)
                // the same fraction of the way from the pit's centre to its rim, along the profile
                val fk = (r0 / T1_PIT_R * (m.np - 1)).coerceIn(0f, m.np - 1.001f); val k0 = fk.toInt(); val t = fk - k0
                val pr = m.pr[k0] + (m.pr[k0 + 1] - m.pr[k0]) * t; val pz = m.pz[k0] + (m.pz[k0 + 1] - m.pz[k0]) * t
                val nr = m.nr[k0]; val nz = m.nz[k0]
                for (part in 0..1) {
                    val dOut = if (part == 0) 0f else 0.14f                 // body in the bilayer, head outside
                    val rr = pr + nr * dOut; val zl = -(pz + nz * dOut)
                    val lx = cos(ang) * rr + T1_SHEAR * zl; val ly = sin(ang) * rr
                    val wx = fm.cx + fm.sx * lx + fm.ux * ly - fm.dx * zl; val wy = fm.cy + fm.sy * lx + fm.uy * ly - fm.dy * zl; val wz = fm.cz + fm.sz * lx + fm.uz * ly - fm.dz * zl
                    bb.add(wx, wy, wz, fm.dx, fm.dy, fm.dz, fm.sx, fm.sy, fm.sz, if (part == 0) 0.05f else 0.045f)
                }
            }
        }
    }
    // clathrin coat on the cytoplasmic face of the pit (on the vesicle after scission, until it falls away)
    val psi = if (pinched) PI_F else if (h <= -T1_RV + 0.01f) 0f else {
        val dz = h - T1_FIL; val rF = sqrt(((T1_RV + T1_FIL).pow(2) - dz * dz).coerceAtLeast(0f)); atan2(rF, T1_FIL - h)
    }
    val coat = if (pinched) 1f - t1Smooth(7.14f, 7.2f, rp) else t1Smooth(0.2f, 0.6f, psi)
    if (coat > 0.02f && psi > 0.1f) {
        if (abs(psi - m.lastPsi) > 0.01f) {
            m.lastPsi = psi
            val e = t1Clathrin; val dd = m.cage.data
            var v = 0
            val cr = T1_RV + 0.2f
            val cc = floatArrayOf(0.88f, 0.92f, 1f)
            var k = 0
            val cosLim = cos(psi)
            while (k < e.size && v + 2 < 4200) {
                if (e[k + 2] >= cosLim && e[k + 5] >= cosLim) {
                    v = t1Put(dd, v, e[k] * cr, e[k + 1] * cr, -(e[k + 2] * cr), cc, 0.9f)
                    v = t1Put(dd, v, e[k + 3] * cr, e[k + 4] * cr, -(e[k + 5] * cr), cc, 0.9f)
                }
                k += 6
            }
            m.cageVerts = v
        }
        if (pinched) t1Model(fm, sShip, so - T1_SHEAR * sShip, uo)
        else { t1Model(fm, 0f, so, uo); t1ApplyShear(); Matrix.translateM(model, 0, 0f, 0f, -h) }
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
        if (neck < 0.45f) {
            t1Model(fm, 0f, so, uo); t1ApplyShear(); Matrix.translateM(model, 0, 0f, 0f, -T1_FIL)
            Matrix.scaleM(model, 0, neck + 0.08f, neck + 0.08f, 0.25f)
            drawLitModel(t1Ring(0.3f), T1_DYNAMIN, T1_WHITE, t1Smooth(0.45f, 0.3f, neck) * landmarkFade, 0f, 0.3f)
        }
    }
    // translucent bilayer cores: the flat sheet, the pit, and after scission the vesicle round the craft
    val core = t1Mesh("mem.core2") { ParamMesh(4, 48) { u, v, out -> val r = T1_PIT_R + (T1_MEM_R - T1_PIT_R) * u; val a = v * TAU; out[0] = cos(a) * r; out[1] = sin(a) * r; out[2] = 0f } }
    GLES20.glDepthMask(false)
    t1Lit(core, fm, 0f, so, uo, T1_BILAYER, T1_WHITE, 0.35f, 0.12f)
    t1Model(fm, 0f, so, uo); t1ApplyShear()
    drawLitModel(m.pit, T1_BILAYER, T1_WHITE, 0.3f * landmarkFade, 0f, 0.12f)
    if (pinched) {
        val va = 1f - t1Smooth(7.18f, 7.26f, rp)
        if (va > 0.01f) {
            t1Lit(sphere, fm, sShip, so - T1_SHEAR * sShip, uo, T1_BILAYER, T1_WHITE, 0.3f * va, 0.15f, T1_RV, T1_RV, T1_RV)
            t1Lit(sphere, fm, sShip, so - T1_SHEAR * sShip, uo, floatArrayOf(1f, 0.78f, 0.45f, 1f), T1_WHITE, 0.16f * va, 0.2f, T1_RV + T1_LEAF, T1_RV + T1_LEAF, T1_RV + T1_LEAF)
        }
    }
    if (quality < 2) t1Color(st.glyco, fm, 0f, so, uo, 1.5f, false, 0.8f, depthWrite = false)
    GLES20.glDepthMask(true)
}

// ================================================================ stop 8: THE MITOCHONDRION (40 nm rung)
// The craft drops half a power of ten on the approach, to 40 nm (1 unit = 26.7 nm), so the turbines
// read. A mitochondrion 1 um across, entered at its rounded tip: the outer membrane riddled with porins,
// a 20 nm intermembrane space, the inner boundary membrane, and lamellar cristae across the long axis
// like the bellows of an accordion - each a flattened sac (two membranes round a 20 nm intracristal
// space) hanging from the inner membrane by narrow crista junctions, of varying depth, leaving a
// channel of matrix down the middle. ATP synthase dimers line every crista rim (the enzyme 12 nm
// tall: F0 c-ring in the membrane, central stalk, alpha3-beta3 head in the matrix, peripheral stalk);
// on the crista ahead the c-rings and central stalks visibly turn (1 rev/s here; ~100 in life) under
// fixed heads, fed by protons from the intracristal space. Respiratory complexes sit in the crista
// membranes; a nucleoid of mtDNA and dense matrix granules float between the cristae. Before the drop
// the same organelle is drawn at 120 nm, a third the size, dead ahead.

private const val T1_MITO_TIP = 7.80f
private const val T1_MO_R = 18.75f
private const val T1_MI_R = 18.0f
private val T1_CRISTA_A = floatArrayOf(2.6f, 6.1f, 9.8f, 13.3f, 16.9f)
private val T1_CRISTA_IN = floatArrayOf(2.35f, 2.7f, 2.2f, 2.6f, 2.3f)
private const val T1_CR_HT = 0.63f          // half-thickness of a crista (two 7 nm membranes round a 20 nm space)

private fun t1CapR(a: Float, R: Float, lead: Float): Float {
    val x = a - lead
    return if (x <= 0f) 0f else if (x >= R) R else sqrt((2f * R * x - x * x).coerceAtLeast(0f))
}

private fun t1CristaGap(j: Int) = (if (j % 2 == 0) 90f else 270f) * DEG

private class T1Mito(val outer: T1Batch, val inner: T1Batch, val sacs: T1Batch, val lumens: T1Batch, val junctions: T1Batch, val heads: T1Batch,
                     val stalks: T1Batch, val etc: T1Batch, val porins: PointMesh, val dna: LineMesh, val granules: T1Batch, val synth: FloatArray, val rims: LineMesh)

private fun StereoBodyRenderer.t1BuildMito(): T1Mito {
    val outer = T1Builder(); val inner = T1Builder(); val sacs = T1Builder(); val lum = T1Builder(); val junc = T1Builder()
    val heads = T1Builder(); val stalks = T1Builder(); val etc = T1Builder(); val gran = T1Builder()
    fun shell(b: T1Builder, R: Float, lead: Float) = b.surface(40, 40) { u, v, out ->
        val a = lead + 22f * u * u; val r = t1CapR(a, R, lead).coerceAtLeast(0.02f)
        t1RailPoint(T1_MITO_TIP + a / 16f, v * TAU, r, out)
    }
    shell(outer, T1_MO_R, 0f); shell(inner, T1_MI_R, 0.75f)
    val q = FloatArray(3); val q2 = FloatArray(3)
    val synth = ArrayList<Float>()        // per synthase: crista index, base xyz, head direction xyz
    val rnd = java.util.Random(88)
    for ((j, aj) in T1_CRISTA_A.withIndex()) {
        val f = frameAt(T1_MITO_TIP + aj / 16f)
        val rin = T1_CRISTA_IN[j]; val rout = t1CapR(aj, T1_MI_R, 0.75f) - 0.6f
        val gap = t1CristaGap(j); val span = 150f * DEG
        fun sac(b: T1Builder, ht: Float, inset: Float) = b.surface(14, 44) { u, v, out ->
            val bb = u * TAU; val th = gap + PI_F - span + 2f * span * v
            val k = sin(PI_F * v).coerceAtLeast(0f).pow(0.3f)
            val cb = cos(bb); val sb = sin(bb)
            val r0 = rin + inset; val r1 = rout - inset
            val rc = (r0 + r1) * 0.5f; val hw = (r1 - r0) * 0.5f
            val r = rc + hw * k * sign(cb) * abs(cb).pow(0.25f) + (1f - k) * 0.5f
            val z = ht * k * sign(sb) * abs(sb).pow(0.6f)
            val c = cos(th); val s = sin(th)
            out[0] = f.cx + f.dx * z + (f.sx * c + f.ux * s) * r
            out[1] = f.cy + f.dy * z + (f.sy * c + f.uy * s) * r
            out[2] = f.cz + f.dz * z + (f.sz * c + f.uz * s) * r
        }
        sac(sacs, T1_CR_HT, 0f); sac(lum, T1_CR_HT - 0.26f, 0.2f)
        // crista junctions: narrow necks (~25 nm) to the inner boundary membrane
        for (m in 0 until 5) {
            val th = gap + PI_F - span * 0.85f + span * 1.7f * m / 4f
            t1RailPoint(T1_MITO_TIP + aj / 16f, th, rout - 0.2f, q); t1RailPoint(T1_MITO_TIP + aj / 16f, th, t1CapR(aj, T1_MI_R, 0.75f) + 0.05f, q2)
            junc.rod(q[0], q[1], q[2], q2[0], q2[1], q2[2], 0.45f, 4, 8)
        }
        // ATP synthase dimers along the rim, every ~24 nm; the two heads splay ~86 degrees apart
        var th = gap + PI_F - span + 0.2f
        while (th < gap + PI_F + span - 0.2f) {
            val c = cos(th); val s = sin(th)
            val rx = f.sx * c + f.ux * s; val ry = f.sy * c + f.uy * s; val rz = f.sz * c + f.uz * s
            for (sg in SIGNS) {
                val bx = f.cx + rx * (rin + 0.1f) + f.dx * sg * 0.42f; val by = f.cy + ry * (rin + 0.1f) + f.dy * sg * 0.42f; val bz = f.cz + rz * (rin + 0.1f) + f.dz * sg * 0.42f
                val cd = cos(43f * DEG); val sd = sin(43f * DEG)
                var nx = -rx * cd + f.dx * sg * sd; var ny = -ry * cd + f.dy * sg * sd; var nz = -rz * cd + f.dz * sg * sd
                val nl = sqrt(nx * nx + ny * ny + nz * nz); nx /= nl; ny /= nl; nz /= nl
                // F0 c-ring, central stalk, head, peripheral stalk
                stalks.rod(bx - nx * 0.05f, by - ny * 0.05f, bz - nz * 0.05f, bx + nx * 0.05f, by + ny * 0.05f, bz + nz * 0.05f, 0.2f, 3, 10)   // F0 c-ring disc
                stalks.rod(bx, by, bz, bx + nx * 0.22f, by + ny * 0.22f, bz + nz * 0.22f, 0.045f, 3, 5)
                val tx = -f.sx * s + f.ux * c; val ty = -f.sy * s + f.uy * c; val tz = -f.sz * s + f.uz * c
                stalks.rod(bx + tx * 0.2f, by + ty * 0.2f, bz + tz * 0.2f, bx + nx * 0.46f + tx * 0.12f, by + ny * 0.46f + ty * 0.12f, bz + nz * 0.46f + tz * 0.12f, 0.03f, 3, 5)
                val hx = bx + nx * 0.36f; val hy = by + ny * 0.36f; val hz = bz + nz * 0.36f
                heads.ellipsoid(hx, hy, hz, floatArrayOf(tx * 0.19f, ty * 0.19f, tz * 0.19f), floatArrayOf(nx * 0.16f, ny * 0.16f, nz * 0.16f),
                    floatArrayOf((ny * tz - nz * ty) * 0.19f, (nz * tx - nx * tz) * 0.19f, (nx * ty - ny * tx) * 0.19f), 5, 8)
                synth.add(j.toFloat()); synth.add(bx); synth.add(by); synth.add(bz); synth.add(nx); synth.add(ny); synth.add(nz); synth.add(tx); synth.add(ty); synth.add(tz)
            }
            th += 0.9f / rin
        }
        // respiratory-chain complexes in the crista faces: I (L-shaped, a membrane arm and a matrix arm), III, IV
        for (m in 0 until 6) {
            val th2 = gap + PI_F - span * 0.8f + span * 1.6f * rnd.nextFloat()
            val r = rin + 0.9f + rnd.nextFloat() * 2.2f
            val sg = if (m % 2 == 0) 1f else -1f
            val c = cos(th2); val s = sin(th2)
            val rx = f.sx * c + f.ux * s; val ry = f.sy * c + f.uy * s; val rz = f.sz * c + f.uz * s
            val tx = -f.sx * s + f.ux * c; val ty = -f.sy * s + f.uy * c; val tz = -f.sz * s + f.uz * c
            val px = f.cx + rx * r + f.dx * sg * T1_CR_HT; val py = f.cy + ry * r + f.dy * sg * T1_CR_HT; val pz = f.cz + rz * r + f.dz * sg * T1_CR_HT
            when (m % 3) {
                0 -> {
                    etc.ellipsoid(px, py, pz, floatArrayOf(tx * 0.36f, ty * 0.36f, tz * 0.36f), floatArrayOf(f.dx * 0.12f, f.dy * 0.12f, f.dz * 0.12f), floatArrayOf(rx * 0.14f, ry * 0.14f, rz * 0.14f), 5, 8)
                    etc.ellipsoid(px + tx * 0.34f + f.dx * sg * 0.28f, py + ty * 0.34f + f.dy * sg * 0.28f, pz + tz * 0.34f + f.dz * sg * 0.28f,
                        floatArrayOf(tx * 0.14f, ty * 0.14f, tz * 0.14f), floatArrayOf(f.dx * 0.28f, f.dy * 0.28f, f.dz * 0.28f), floatArrayOf(rx * 0.14f, ry * 0.14f, rz * 0.14f), 5, 8)
                }
                1 -> for (w in SIGNS) etc.ellipsoid(px + tx * 0.13f * w, py + ty * 0.13f * w, pz + tz * 0.13f * w, floatArrayOf(tx * 0.13f, ty * 0.13f, tz * 0.13f),
                    floatArrayOf(f.dx * 0.2f, f.dy * 0.2f, f.dz * 0.2f), floatArrayOf(rx * 0.13f, ry * 0.13f, rz * 0.13f), 5, 8)
                else -> etc.ellipsoid(px, py, pz, floatArrayOf(tx * 0.16f, ty * 0.16f, tz * 0.16f), floatArrayOf(f.dx * 0.17f, f.dy * 0.17f, f.dz * 0.17f), floatArrayOf(rx * 0.16f, ry * 0.16f, rz * 0.16f), 5, 8)
            }
        }
    }
    // porins on the outer membrane near the tip
    val por = ArrayList<Float>()
    val pc = floatArrayOf(1f, 0.86f, 0.62f, 0.95f)
    for (k in 0 until 1400) {
        val a = 7f * rnd.nextFloat().pow(0.6f); val th = rnd.nextFloat() * TAU
        t1RailPoint(T1_MITO_TIP + a / 16f, th, t1CapR(a, T1_MO_R, 0f) + 0.02f, q)
        por.add(q[0]); por.add(q[1]); por.add(q[2]); por.addAll(pc.toList())
    }
    // the nucleoid: a compact tangle of mtDNA (~100 nm) in the matrix between cristae 2 and 3
    val dna = ArrayList<Float>()
    val dc = floatArrayOf(0.88f, 0.78f, 1f, 1f)
    val aN = (T1_CRISTA_A[1] + T1_CRISTA_A[2]) * 0.5f
    val fN = frameAt(T1_MITO_TIP + aN / 16f)
    val th0 = 215f * DEG
    val ncx = fN.cx + (fN.sx * cos(th0) + fN.ux * sin(th0)) * 5.5f; val ncy = fN.cy + (fN.sy * cos(th0) + fN.uy * sin(th0)) * 5.5f; val ncz = fN.cz + (fN.sz * cos(th0) + fN.uz * sin(th0)) * 5.5f
    for (k in 0 until 300) for (hh in 0..1) {
        val t = TAU * (k + hh) / 300f
        val a = 0.7f * sin(3f * t) + 0.3f * sin(13f * t); val s = 1.4f * cos(2f * t) + 0.35f * cos(9f * t); val u = 1.4f * sin(t) + 0.4f * sin(7f * t + 1f)
        dna.add(ncx + fN.dx * a + fN.sx * s + fN.ux * u); dna.add(ncy + fN.dy * a + fN.sy * s + fN.uy * u); dna.add(ncz + fN.dz * a + fN.sz * s + fN.uz * u); dna.addAll(dc.toList())
    }
    // dense matrix granules (30-50 nm)
    for (k in 0 until 4) {
        val aa = T1_CRISTA_A[k] + 1.8f; val th = (40f + 95f * k) * DEG; val r = 4.5f + 1.5f * (k % 2)
        t1RailPoint(T1_MITO_TIP + aa / 16f, th, r, q)
        val rr = 0.6f + 0.25f * (k % 3)
        gran.ellipsoid(q[0], q[1], q[2], floatArrayOf(rr, 0f, 0f), floatArrayOf(0f, rr, 0f), floatArrayOf(0f, 0f, rr), 6, 10)
    }
    // bright lines: each crista's free edge, and the two membranes of the envelope at a few rings, so the
    // outer and inner boundary membranes read as a double wall
    val rim = ArrayList<Float>()
    val rc = floatArrayOf(1f, 0.85f, 0.60f, 1f); val oc = floatArrayOf(0.98f, 0.82f, 0.60f, 0.9f); val ic = floatArrayOf(0.95f, 0.52f, 0.28f, 1f)
    fun addp(p: FloatArray, c: FloatArray) { rim.add(p[0]); rim.add(p[1]); rim.add(p[2]); rim.addAll(c.toList()) }
    for ((j, aj) in T1_CRISTA_A.withIndex()) {
        val gap = t1CristaGap(j); val span = 150f * DEG
        for (m in 0 until 30) for (hh in 0..1) {
            val th = gap + PI_F - span + 2f * span * (m + hh) / 30f
            t1RailPoint(T1_MITO_TIP + aj / 16f, th, T1_CRISTA_IN[j] - 0.02f, q); addp(q, rc)
        }
    }
    for (a in floatArrayOf(3f, 8f, 13f, 18f)) for (m in 0 until 48) for (hh in 0..1) {
        val th = TAU * (m + hh) / 48f
        t1RailPoint(T1_MITO_TIP + a / 16f, th, t1CapR(a, T1_MO_R, 0f) - 0.02f, q); addp(q, oc)
        t1RailPoint(T1_MITO_TIP + a / 16f, th, t1CapR(a, T1_MI_R, 0.75f) - 0.02f, q); addp(q, ic)
    }
    return T1Mito(outer.build(), inner.build(), sacs.build(), lum.build(), junc.build(), heads.build(), stalks.build(), etc.build(),
        PointMesh(por.toFloatArray()), LineMesh(dna.toFloatArray()), gran.build(), synth.toFloatArray(), LineMesh(rim.toFloatArray()))
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

// Scale about the craft: everything is built at 40 nm; at 120 nm it is drawn a third the size.
private var t1K = 1f; private var t1Ox = 0f; private var t1Oy = 0f; private var t1Oz = 0f
private fun StereoBodyRenderer.t1ModelK() {
    Matrix.setIdentityM(model, 0)
    Matrix.translateM(model, 0, shipX, shipY, shipZ)
    Matrix.scaleM(model, 0, t1K, t1K, t1K)
    Matrix.translateM(model, 0, t1Ox - shipX, t1Oy - shipY, t1Oz - shipZ)
}
private fun StereoBodyRenderer.t1LitK(mesh: LitMesh, base: FloatArray, accent: FloatArray, alpha: Float, glow: Float) {
    t1ModelK(); drawLitModel(mesh, base, accent, alpha * landmarkFade, 0f, glow)
}
private fun StereoBodyRenderer.t1ColorK(mesh: ColorVboMesh, size: Float, points: Boolean, alpha: Float, depthWrite: Boolean = true) {
    t1ModelK()
    Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    val keep = colorShader.globalFade; colorShader.globalFade = keep * alpha
    if (!depthWrite) GLES20.glDepthMask(false)
    if (points) colorShader.use(mvp, size, points = true) else { colorShader.use(mvp, 1f); lineWidth(size) }
    mesh.draw(colorShader.positionHandle, colorShader.colorHandle)
    if (!points) lineWidth(1f)
    if (!depthWrite) GLES20.glDepthMask(true)
    colorShader.globalFade = keep
}
private fun StereoBodyRenderer.t1Kx(x: Float) = shipX + t1K * (x + t1Ox - shipX)
private fun StereoBodyRenderer.t1Ky(y: Float) = shipY + t1K * (y + t1Oy - shipY)
private fun StereoBodyRenderer.t1Kz(z: Float) = shipZ + t1K * (z + t1Oz - shipZ)

internal fun StereoBodyRenderer.drawMitochondrion(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp < 7.28f || rp > 8.26f) return
    val vis = t1Smooth(7.28f, 7.4f, rp) * (1f - t1Smooth(8.18f, 8.24f, rp))
    landmarkFade *= vis; colorShader.globalFade *= vis
    val ow = t1ShipOffWorld(); t1Ox = ow[0]; t1Oy = ow[1]; t1Oz = ow[2]
    t1K = (4e-8 / shipLengthM(rp)).toFloat().coerceIn(0.2f, 1f)
    val mt = t1Mesh("mito3") { t1BuildMito() }
    // opaque: intracristal spaces, junctions, synthases, complexes, nucleoid, granules
    t1LitK(mt.lumens, T1_ICS2, T1_WHITE, 1f, 0.12f)
    t1LitK(mt.junctions, T1_CRISTA, T1_WHITE, 1f, 0.15f)
    t1LitK(mt.stalks, T1_FO, T1_WHITE, 1f, 0.25f)
    t1LitK(mt.heads, T1_F1_A, T1_WHITE, 1f, 0.3f)
    t1LitK(mt.etc, T1_ETC, T1_WHITE, 1f, 0.25f)
    t1LitK(mt.granules, T1_GRANULE, T1_WHITE, 1f, 0.2f)
    t1ColorK(mt.dna, 2f, false, 1f)
    t1ColorK(mt.rims, 2f, false, 1f)
    // the synthases on the crista ahead of the craft: c-ring and central stalk turning (1 rev/s here,
    // ~100 in life) under the fixed alpha3-beta3 head; protons flooding in from the intracristal side
    val aShip = (rp - T1_MITO_TIP) * 16f
    var jN = T1_CRISTA_A.indexOfFirst { it > aShip + 1.2f }
    if (jN < 0) jN = T1_CRISTA_A.lastIndex
    val cring = t1Mesh("atp.c") { t1BuildLobes(8, 1f, floatArrayOf(0.32f, 0.32f, 1f), 0f) }
    val f1a = t1Mesh("atp.a") { t1BuildLobes(3, 0.5f, floatArrayOf(0.5f, 0.5f, 0.85f), 0f) }
    val f1b = t1Mesh("atp.b") { t1BuildLobes(3, 0.5f, floatArrayOf(0.5f, 0.5f, 0.85f), PI_F / 3f) }
    val spin = seconds * TAU
    val d = t1Dyn.data
    var v = 0
    val pcol = floatArrayOf(1f, 0.95f, 0.55f)
    val sy = mt.synth
    var shown = 0
    var seen = 0
    for (k in 0 until sy.size / 10) {
        val o = k * 10
        if (sy[o].toInt() != jN) continue
        seen++
        if (seen % 3 != 1 || shown >= 6) continue        // every third enzyme along the crista's rim
        shown++
        val bx = t1Kx(sy[o + 1]); val by = t1Ky(sy[o + 2]); val bz = t1Kz(sy[o + 3])
        val nx = sy[o + 4]; val ny = sy[o + 5]; val nz = sy[o + 6]; val tx = sy[o + 7]; val ty = sy[o + 8]; val tz = sy[o + 9]
        val p2x = ny * tz - nz * ty; val p2y = nz * tx - nx * tz; val p2z = nx * ty - ny * tx
        val cw = cos(spin + k); val sw = sin(spin + k)
        val yx = tx * cw + p2x * sw; val yy = ty * cw + p2y * sw; val yz = tz * cw + p2z * sw
        val K = t1K
        drawBasis(bx, by, bz, nx, ny, nz, yx, yy, yz, 0.15f * K, 0.15f * K, 0.11f * K, cring, T1_FO, T1_WHITE, 1f, 0f, 0.35f)
        val ex = yx * 0.05f * K; val ey = yy * 0.05f * K; val ez = yz * 0.05f * K
        drawStrut(bx + ex, by + ey, bz + ez, bx + nx * 0.26f * K + ex, by + ny * 0.26f * K + ey, bz + nz * 0.26f * K + ez, 0.05f * K, T1_AP, T1_WHITE, 0.6f)
        // the head, drawn whole (alternating alpha and beta subunits), held still
        val hx = bx + nx * 0.36f * K; val hy = by + ny * 0.36f * K; val hz = bz + nz * 0.36f * K
        drawBasis(hx, hy, hz, nx, ny, nz, tx, ty, tz, 0.205f * K, 0.205f * K, 0.17f * K, f1a, T1_F1_A, T1_WHITE, 1f, 0f, 0.3f)
        drawBasis(hx, hy, hz, nx, ny, nz, tx, ty, tz, 0.205f * K, 0.205f * K, 0.17f * K, f1b, T1_F1_B, T1_WHITE, 1f, 0f, 0.3f)
        for (m in 0..1) {
            val t = ((seconds * 1.4f + m * 0.5f + k * 0.17f) % 1f)
            // from inside the crista (the intracristal space, away from the matrix) into the c-ring
            val sx = bx - nx * 0.5f * K; val sy2 = by - ny * 0.5f * K; val sz = bz - nz * 0.5f * K
            v = t1Put(d, v, sx + (bx - sx) * t, sy2 + (by - sy2) * t, sz + (bz - sz) * t, pcol, 1f - t * 0.4f)
        }
    }
    t1DynDraw(v, GLES20.GL_POINTS, 4f)
    // translucent: the cristae's membranes, the inner boundary membrane, the outer membrane and its porins
    GLES20.glDepthMask(false)
    t1LitK(mt.sacs, T1_CRISTA, T1_WHITE, 0.8f, 0.15f)
    t1LitK(mt.inner, T1_MATRIX, T1_MATRIX, 0.5f, 0f)                 // the matrix: a dark space the cristae cross
    t1LitK(mt.outer, T1_MITO_OUT2, T1_WHITE, 0.3f, 0.18f)
    GLES20.glDepthMask(true)
    if (quality < 2) t1ColorK(mt.porins, 2.4f, true, 0.9f, depthWrite = false)
}

// ================================================================ stop 9: THE NUCLEUS (12 nm rung)
// 1 unit = 8 nm. The nuclear envelope - one continuous membrane folded into the outer and inner
// membranes (each a bilayer 7 nm thick) round a 35 nm perinuclear space, fused at the pore - with a
// nuclear pore complex in it, right ahead of the craft: eightfold cytoplasmic ring, spokes lining a
// 48 nm channel, nucleoplasmic ring, luminal ring, cytoplasmic filaments, the nuclear basket, and
// FG-repeat strands filling the channel (they part round the craft). Inside, chromatin as compact
// 10 nm fibres - nucleosomes, each a histone octamer (6.5 nm) with 147 bp of DNA wrapped 1.65
// left-handed turns round its outside (superhelix radius 4.2 nm), H1 at the entry and exit, joined by
// short linkers - and a stretch of bare B-DNA (2 nm wide, 3.4 nm per turn, right-handed, major and
// minor grooves) being read by RNA polymerase II at ~20 nt/s: the DNA runs through the clamp, its
// strands parted over ~14 bp (the transcription bubble), the RNA paired with the template strand for
// 8 bp before it leaves through the exit channel and trails behind.

private const val T1_NPC_P = 8.975f

private class T1Nucleus(val envA: ParamMesh, val envB: ParamMesh, val heads: PointMesh, val npc: T1Batch, val cyto: LineMesh, val basket: LineMesh,
                        val histones: T1Batch, val h1: T1Batch, val dna: T1Batch, val cut: LineMesh, val lamina: LineMesh, val hetero: T1Batch, val periLamina: LineMesh)

private fun t1EnvProfile(s: Float, out: FloatArray) {        // (r, along) of the envelope's mid-surface; s in [0, 1]
    val l1 = 9.8f - 8.4f; val l2 = PI_F * 2.2f; val tot = 2f * l1 + l2
    val d = s * tot
    when {
        d < l1 -> { out[0] = 9.8f - d; out[1] = -2.2f }
        d < l1 + l2 -> { val g = (d - l1) / 2.2f; out[0] = 8.4f - 2.2f * sin(g); out[1] = -2.2f * cos(g) }
        else -> { out[0] = 8.4f + (d - l1 - l2); out[1] = 2.2f }
    }
}

/** A tube of radius r swept along a polyline (parallel-transported frames). */
private fun T1Builder.t1Tube(pts: FloatArray, r: Float, sides: Int = 6) {
    val n = pts.size / 3
    if (n < 2) return
    val px = FloatArray(n); val py = FloatArray(n); val pz = FloatArray(n)
    val nx = FloatArray(n); val ny = FloatArray(n); val nz = FloatArray(n)
    for (k in 0 until n) { px[k] = pts[3 * k]; py[k] = pts[3 * k + 1]; pz[k] = pts[3 * k + 2] }
    // tangent and a normal carried along
    var tx = px[1] - px[0]; var ty = py[1] - py[0]; var tz = pz[1] - pz[0]
    var tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f); tx /= tl; ty /= tl; tz /= tl
    var ax = if (abs(tx) < 0.9f) 1f else 0f; var ay = if (abs(tx) < 0.9f) 0f else 1f; var az = 0f
    var d = ax * tx + ay * ty + az * tz; ax -= d * tx; ay -= d * ty; az -= d * tz
    var al = sqrt(ax * ax + ay * ay + az * az); ax /= al; ay /= al; az /= al
    for (k in 0 until n) {
        val k0 = max(0, k - 1); val k1 = min(n - 1, k + 1)
        tx = px[k1] - px[k0]; ty = py[k1] - py[k0]; tz = pz[k1] - pz[k0]
        tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f); tx /= tl; ty /= tl; tz /= tl
        d = ax * tx + ay * ty + az * tz; ax -= d * tx; ay -= d * ty; az -= d * tz
        al = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-6f); ax /= al; ay /= al; az /= al
        nx[k] = ax; ny[k] = ay; nz[k] = az
    }
    surface(n - 1, sides) { u, v, out ->
        val fk = u * (n - 1); val k = fk.toInt().coerceAtMost(n - 2); val t = fk - k
        val cx = px[k] + (px[k + 1] - px[k]) * t; val cy = py[k] + (py[k + 1] - py[k]) * t; val cz = pz[k] + (pz[k + 1] - pz[k]) * t
        val ex = nx[k]; val ey = ny[k]; val ez = nz[k]
        var tx2 = px[k + 1] - px[k]; var ty2 = py[k + 1] - py[k]; var tz2 = pz[k + 1] - pz[k]
        val l2 = sqrt(tx2 * tx2 + ty2 * ty2 + tz2 * tz2).coerceAtLeast(1e-6f); tx2 /= l2; ty2 /= l2; tz2 /= l2
        val bx = ty2 * ez - tz2 * ey; val by = tz2 * ex - tx2 * ez; val bz = tx2 * ey - ty2 * ex
        val c = cos(v * TAU) * r; val s = sin(v * TAU) * r
        out[0] = cx + ex * c + bx * s; out[1] = cy + ey * c + by * s; out[2] = cz + ez * c + bz * s
    }
}

private fun t1BuildNucleus(): T1Nucleus {
    val tmp = FloatArray(2)
    // the bilayer's two faces
    fun env(off: Float) = ParamMesh(40, 72) { u, v, out ->
        t1EnvProfile(u, tmp)
        val t3 = FloatArray(2); t1EnvProfile((u + 0.01f).coerceAtMost(1f), t3)
        val tr = t3[0] - tmp[0]; val ta = t3[1] - tmp[1]; val tl = sqrt(tr * tr + ta * ta).coerceAtLeast(1e-5f)
        val r = tmp[0] - ta / tl * off; val al = tmp[1] + tr / tl * off
        val a = (260f + 300f * v) * DEG; out[0] = cos(a) * r; out[1] = sin(a) * r; out[2] = -al }   // a 60-degree wedge cut away
    val envA = env(0.45f); val envB = env(-0.45f)
    val heads = ArrayList<Float>()
    val hc = floatArrayOf(1f, 0.80f, 0.55f, 0.9f)
    fun add(l: ArrayList<Float>, x: Float, y: Float, z: Float, c: FloatArray) { l.add(x); l.add(y); l.add(z); l.add(c[0]); l.add(c[1]); l.add(c[2]); l.add(c[3]) }
    val t2 = FloatArray(2); val t3 = FloatArray(2)
    val np = 90
    for (k in 0 until np) {
        t1EnvProfile(k / (np - 1f), t2); t1EnvProfile(((k + 0.5f) / (np - 1f)).coerceAtMost(1f), t3)
        val tr = t3[0] - t2[0]; val ta = t3[1] - t2[1]; val tl = sqrt(tr * tr + ta * ta).coerceAtLeast(1e-5f)
        val nr = -ta / tl; val na = tr / tl
        val cnt = (TAU * t2[0] / 0.2f).toInt()
        for (m in 0 until cnt) {
            val a = TAU * (m + 0.5f * (k % 2)) / cnt
            val deg = a / DEG
            if (deg > 200f && deg < 260f) continue
            for (sg in SIGNS) {
                val r = t2[0] + nr * 0.45f * sg; val al = t2[1] + na * 0.45f * sg
                add(heads, cos(a) * r, sin(a) * r, -al, hc)
            }
        }
    }
    // the cut faces of the wedge: the envelope in section - two membranes 0.9 (7 nm) apart, fused round
    // the pore's rim, the perinuclear space between them
    val cut = ArrayList<Float>()
    val cc = floatArrayOf(0.80f, 0.70f, 1f, 1f)
    for (ang in floatArrayOf(200f * DEG, 260f * DEG)) for (sg in SIGNS) for (k in 0 until 40) for (hh in 0..1) {
        val u = (k + hh) / 40f
        t1EnvProfile(u, t2); t1EnvProfile((u + 0.01f).coerceAtMost(1f), t3)
        val tr = t3[0] - t2[0]; val ta = t3[1] - t2[1]; val tl = sqrt(tr * tr + ta * ta).coerceAtLeast(1e-5f)
        val r = t2[0] - ta / tl * 0.45f * sg; val al = t2[1] + tr / tl * 0.45f * sg
        add(cut, cos(ang) * r, sin(ang) * r, -al, cc)
    }
    // the nuclear lamina: a meshwork just inside the inner membrane
    val lam = ArrayList<Float>()
    val lcol = floatArrayOf(0.80f, 0.60f, 1f, 0.7f)
    run {
        var x = -9.75f
        while (x <= 9.75f) {
            var y = -9.75f
            while (y < 9.75f) {
                for ((ax, ay, bx, by) in listOf(floatArrayOf(x, y, x, y + 0.5f), floatArrayOf(y, x, y + 0.5f, x))) {
                    val ra = sqrt(ax * ax + ay * ay); val rb = sqrt(bx * bx + by * by)
                    val da = atan2(ay, ax) / DEG; val db = atan2(by, bx) / DEG
                    fun inWedge(d: Float) = (d + 360f) % 360f in 200f..260f
                    if (ra > 7.0f && rb > 7.0f && ra < 9.7f && rb < 9.7f && !inWedge(da) && !inWedge(db)) {
                        add(lam, ax, ay, -2.9f, lcol); add(lam, bx, by, -2.9f, lcol)
                    }
                }
                y += 0.5f
            }
            x += 0.5f
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
    val cyto = ArrayList<Float>(); val basket = ArrayList<Float>()
    val fc = floatArrayOf(0.80f, 0.76f, 1f, 1f)
    for (k in 0 until 8) {
        val a = TAU * k / 8f + 0.2f
        for (m in 0 until 14) for (h in 0..1) {             // cytoplasmic filaments, curling out
            val t = (m + h) / 14f
            val r = 5.0f + 0.6f * sin(t * 5f + k); val aa = a + 0.25f * sin(t * 4f)
            add(cyto, cos(aa) * r, sin(aa) * r, 3.6f + 3.0f * t, fc)
        }
        for (m in 0 until 10) for (h in 0..1) {             // basket filaments converging on the distal ring
            val t = (m + h) / 10f
            val r = 5.0f + (2.4f - 5.0f) * t
            add(basket, cos(a) * r, sin(a) * r, -(3.4f + 5.6f * t), fc)
        }
    }
    for (m in 0 until 32) for (h in 0..1) { val a = TAU * (m + h) / 32f; add(basket, cos(a) * 2.4f, sin(a) * 2.4f, -9.0f, fc) }
    // chromatin: compact 10 nm fibres (node-9-frame local: x side, y up, z = -along), gently curving,
    // 2 units or more off the craft's lane
    val hb = T1Builder(); val h1 = T1Builder(); val dna = T1Builder()
    val rnd = java.util.Random(19)
    for (fib in 0 until 18) {
        val ang = fib * 0.349f + 0.4f + 0.2f * rnd.nextFloat()
        val rad = 3.4f + (fib % 3) * 0.9f
        var px = cos(ang) * rad; var py = sin(ang) * rad; var pz = -(2.0f + (fib / 6) * 3.5f + rnd.nextFloat() * 1.5f)
        var dx = -sin(ang); var dy = cos(ang); var dz = -0.6f
        var dl = sqrt(dx * dx + dy * dy + dz * dz); dx /= dl; dy /= dl; dz /= dl
        var path = ArrayList<Float>()
        path.add(px); path.add(py); path.add(pz)
        for (nuc in 0 until 16) {
            // turn by up to ~20 degrees per nucleosome
            val rx = rnd.nextFloat() - 0.5f; val ry = rnd.nextFloat() - 0.5f; val rz = rnd.nextFloat() - 0.5f
            dx += rx * 0.6f; dy += ry * 0.6f; dz += rz * 0.6f
            // keep the fibre in the shell 2.5..7 off the lane, and between the envelope and the far side
            val rr0 = sqrt(px * px + py * py)
            if (rr0 < 3.0f) { dx += px / rr0 * 0.3f; dy += py / rr0 * 0.3f }
            if (rr0 > 5.4f) { dx -= px / rr0 * 0.3f; dy -= py / rr0 * 0.3f }
            if (pz > -2f) dz -= 0.3f
            if (pz < -14f) dz += 0.3f
            dl = sqrt(dx * dx + dy * dy + dz * dz); dx /= dl; dy /= dl; dz /= dl
            val step = 1.8f + rnd.nextFloat() * 0.6f
            val cx = px + dx * step * 0.5f; val cy = py + dy * step * 0.5f; val cz = pz + dz * step * 0.5f
            // octamer: a disc (radius 0.41, half-thickness 0.3) whose axis is across the fibre
            var axx = dy; var axy = -dx; var axz = 0.35f * (if (nuc % 2 == 0) 1f else -1f)
            val al = sqrt(axx * axx + axy * axy + axz * axz); axx /= al; axy /= al; axz /= al
            var e1x = dy * axz - dz * axy; var e1y = dz * axx - dx * axz; var e1z = dx * axy - dy * axx
            val el = sqrt(e1x * e1x + e1y * e1y + e1z * e1z).coerceAtLeast(1e-5f); e1x /= el; e1y /= el; e1z /= el
            val e2x = axy * e1z - axz * e1y; val e2y = axz * e1x - axx * e1z; val e2z = axx * e1y - axy * e1x
            hb.ellipsoid(cx, cy, cz, floatArrayOf(e1x * 0.41f, e1y * 0.41f, e1z * 0.41f), floatArrayOf(axx * 0.3f, axy * 0.3f, axz * 0.3f),
                floatArrayOf(e2x * 0.41f, e2y * 0.41f, e2z * 0.41f), 6, 10)
            // 1.65 left-handed turns of DNA round the octamer's outside (superhelix radius 0.52, pitch ~0.2)
            val turns = 1.65f; val steps = 16
            val th0 = atan2(-(dx * e2x + dy * e2y + dz * e2z), -(dx * e1x + dy * e1y + dz * e1z))
            for (m in 0..steps) {
                val t = th0 - turns * TAU * m / steps
                val hgt = 0.33f * m / steps - 0.165f
                path.add(cx + (e1x * cos(t) + e2x * sin(t)) * 0.52f + axx * hgt)
                path.add(cy + (e1y * cos(t) + e2y * sin(t)) * 0.52f + axy * hgt)
                path.add(cz + (e1z * cos(t) + e2z * sin(t)) * 0.52f + axz * hgt)
            }
            // H1 on the dyad, where the DNA enters and leaves
            h1.ellipsoid(cx + (e1x * cos(th0) + e2x * sin(th0)) * 0.72f, cy + (e1y * cos(th0) + e2y * sin(th0)) * 0.72f, cz + (e1z * cos(th0) + e2z * sin(th0)) * 0.72f,
                floatArrayOf(0.15f, 0f, 0f), floatArrayOf(0f, 0.15f, 0f), floatArrayOf(0f, 0f, 0.15f), 4, 6)
            px = cx + dx * step * 0.5f; py = cy + dy * step * 0.5f; pz = cz + dz * step * 0.5f
        }
        dna.t1Tube(path.toFloatArray(), 0.125f, 5)
    }
    // heterochromatin: denser, darker nucleosomes packed against the lamina at the nuclear periphery,
    // and the lamina itself lining the periphery (node-9-frame local coordinates)
    val het = T1Builder(); val pl = ArrayList<Float>()
    var zz = -2.6f
    while (zz > -12f) {
        var a = rnd.nextFloat() * 0.3f
        while (a < TAU) {
            val rr = 5.9f + 0.6f * rnd.nextFloat()
            het.ellipsoid(cos(a) * rr, sin(a) * rr, zz + (rnd.nextFloat() - 0.5f) * 0.4f, floatArrayOf(0.41f, 0f, 0f), floatArrayOf(0f, 0.41f, 0f), floatArrayOf(0f, 0f, 0.3f), 4, 8)
            a += 1.2f / rr
        }
        zz -= 1.2f
    }
    val plc = floatArrayOf(0.80f, 0.60f, 1f, 0.6f)
    for (k in 0 until 88) for (m in 0 until 20) for (hh in 0..1) {
        val a = TAU * k / 88f; val z = -2.6f - 9.5f * (m + hh) / 20f
        add(pl, cos(a) * 6.9f, sin(a) * 6.9f, z, plc)
    }
    for (m in 0..19) for (k in 0 until 88) for (hh in 0..1) {
        val a = TAU * (k + hh) / 88f; val z = -2.6f - 0.5f * m
        add(pl, cos(a) * 6.9f, sin(a) * 6.9f, z, plc)
    }
    return T1Nucleus(envA, envB, PointMesh(heads.toFloatArray()), b.build(), LineMesh(cyto.toFloatArray()), LineMesh(basket.toFloatArray()), hb.build(), h1.build(), dna.build(),
        LineMesh(cut.toFloatArray()), LineMesh(lam.toFloatArray()), het.build(), LineMesh(pl.toFloatArray()))
}

/** Colour lines whose vertex ranges can be drawn in part (the helix, minus the bubble inside Pol II). */
private class T1RangeLines(data: FloatArray) {
    val vbo = makeVbo(data)
    fun draw(ph: Int, ch: Int, first: Int, count: Int) {
        if (count <= 0) return
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(ph, 3, GLES20.GL_FLOAT, false, 28, 0)
        GLES20.glEnableVertexAttribArray(ph)
        GLES20.glVertexAttribPointer(ch, 4, GLES20.GL_FLOAT, false, 28, 12)
        GLES20.glEnableVertexAttribArray(ch)
        GLES20.glDrawArrays(GLES20.GL_LINES, first, count)
        GLES20.glDisableVertexAttribArray(ph); GLES20.glDisableVertexAttribArray(ch)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}

// B-DNA beside the lane in the node-9 frame: axis at (side, up) below, along from -2.5; 10.5 bp/turn.
private const val T1_HX_S = -2.0f
private const val T1_HX_U = 0.9f
private const val T1_HX_A0 = -2.5f
private const val T1_BP = 0.0425f           // 3.4 A rise
private const val T1_NBP = 320
private const val T1_HX_R = 0.125f

/** Backbone angles of the two strands at base pair k (the minor groove spans 143 degrees). */
private fun t1HxAng(k: Int) = -TAU * k / 10.5f

private fun t1BuildHelix(): T1RangeLines {
    val out = ArrayList<Float>()
    fun add(x: Float, y: Float, z: Float, c: FloatArray) { out.add(x); out.add(y); out.add(z); out.add(c[0]); out.add(c[1]); out.add(c[2]); out.add(c[3]) }
    val at = floatArrayOf(0.4f, 0.95f, 0.5f, 1f); val gc = floatArrayOf(0.45f, 0.7f, 1f, 1f)
    val bA = floatArrayOf(1f, 0.74f, 0.45f, 1f); val bB = floatArrayOf(0.55f, 0.82f, 1f, 1f)
    val rnd = java.util.Random(4)
    for (k in 0 until T1_NBP) {
        val w = t1HxAng(k); val w0 = t1HxAng(k - 1)
        val z = -(T1_HX_A0 + k * T1_BP); val z0 = z + T1_BP
        // backbone segments from the previous pair (8 vertices per pair, always)
        add(T1_HX_S + cos(w0) * T1_HX_R, T1_HX_U + sin(w0) * T1_HX_R, z0, bA); add(T1_HX_S + cos(w) * T1_HX_R, T1_HX_U + sin(w) * T1_HX_R, z, bA)
        add(T1_HX_S + cos(w0 + 2.5f) * T1_HX_R, T1_HX_U + sin(w0 + 2.5f) * T1_HX_R, z0, bB); add(T1_HX_S + cos(w + 2.5f) * T1_HX_R, T1_HX_U + sin(w + 2.5f) * T1_HX_R, z, bB)
        val c = if (rnd.nextBoolean()) at else gc
        val ax = cos(w) * T1_HX_R; val ay = sin(w) * T1_HX_R; val bx = cos(w + 2.5f) * T1_HX_R; val by = sin(w + 2.5f) * T1_HX_R
        add(T1_HX_S + ax, T1_HX_U + ay, z, c); add(T1_HX_S + (ax + bx) * 0.5f, T1_HX_U + (ay + by) * 0.5f, z, c)
        add(T1_HX_S + (ax + bx) * 0.5f, T1_HX_U + (ay + by) * 0.5f, z, c); add(T1_HX_S + bx, T1_HX_U + by, z, c)
    }
    return T1RangeLines(out.toFloatArray())
}

internal fun StereoBodyRenderer.drawNucleus(n: TourNode, i: Int, seconds: Float) {
    val rp = routeProgress
    if (rp < 8.2f || rp > 9.75f) return
    val vis = t1Smooth(8.2f, 8.3f, rp) * (1f - t1Smooth(9.55f, 9.75f, rp))
    landmarkFade *= vis; colorShader.globalFade *= vis
    val nu = t1Mesh("nucleus3") { t1BuildNucleus() }
    val fp = frameAt(T1_NPC_P)
    val off = t1ShipOff(fp); val so = off[0]; val uo = off[1]
    t1Lit(nu.npc, fp, 0f, so, uo, T1_NPC, T1_WHITE, 1f, 0.25f)
    t1Color(nu.cyto, fp, 0f, so, uo, 1.5f, false, 0.55f)
    val sA = t1Along(fp, shipX, shipY, shipZ)
    t1Color(nu.basket, fp, 0f, so, uo, 1.5f, false, 0.5f * (1f - t1Smooth(3f, 4f, sA)))
    t1Color(nu.heads, fp, 0f, so, uo, 2f, true, 0.9f)
    // FG-repeat strands filling the channel, pushed aside where the craft is
    val d = t1Dyn.data
    var v = 0
    val fg = floatArrayOf(0.84f, 0.84f, 1f, 0.3f)
    // a gel of unstructured chains filling the channel, parting round the craft and gone once it is through
    val fgFade = 1f - t1Smooth(1.5f, 3f, sA)
    val cx = FloatArray(9); val cy = FloatArray(9); val cz = FloatArray(9)
    if (fgFade > 0.01f) for (k in 0 until 30) {
        val a0 = TAU * k / 30f; val al0 = -1.6f + 3.2f * t1Hash(k)
        for (m in 0..8) {
            val t = m / 8f
            val r = 3.0f - 1.6f * t + 0.25f * sin(seconds * 1.7f + k * 1.3f + m * 1.9f)
            val a = a0 + 0.6f * t * (if (k % 2 == 0) 1f else -1f) + 0.12f * sin(m * 2.3f + k)
            val al = al0 + 0.35f * sin(t * 6f + k) + 0.1f * sin(seconds * 2.1f + m)
            var rr = r.coerceAtMost(3.0f)
            val dsh = al - sA
            if (abs(dsh) < 1.4f) rr = max(rr, 1.3f * (1f - abs(dsh) / 1.4f) + rr * abs(dsh) / 1.4f)
            if (abs(dsh) < 1.4f && rr < 1.25f) rr = 1.25f
            cx[m] = cos(a) * rr; cy[m] = sin(a) * rr; cz[m] = al
        }
        var px = 0f; var py = 0f; var pz = 0f
        for (m in 0 until 8) for (sub in 0..3) {
            val t = sub / 4f
            val m0 = max(m - 1, 0); val m2 = min(m + 1, 8); val m3 = min(m + 2, 8)
            fun cr(p0: Float, p1: Float, p2: Float, p3: Float) = 0.5f * (2f * p1 + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t + (-p0 + 3f * p1 - 3f * p2 + p3) * t * t * t)
            val xx = so + cr(cx[m0], cx[m], cx[m2], cx[m3]); val yy = uo + cr(cy[m0], cy[m], cy[m2], cy[m3]); val al = cr(cz[m0], cz[m], cz[m2], cz[m3])
            val wx = fx(fp, al, xx, yy); val wy = fy(fp, al, xx, yy); val wz = fz(fp, al, xx, yy)
            if (m > 0 || sub > 0) { v = t1Put(d, v, px, py, pz, fg, fg[3]); v = t1Put(d, v, wx, wy, wz, fg, fg[3]) }
            px = wx; py = wy; pz = wz
        }
    }
    t1DynDraw(v, GLES20.GL_LINES, 1.5f, fgFade, depthWrite = false)
    // chromatin, the gene and its polymerase (node frame)
    val f9 = frameAt(i.toFloat())
    val o9 = t1ShipOff(f9); val s9 = o9[0]; val u9 = o9[1]
    t1Lit(nu.histones, f9, 0f, s9, u9, T1_HISTONE, T1_WHITE, 1f, 0.2f)
    t1Lit(nu.h1, f9, 0f, s9, u9, T1_H1, T1_WHITE, 1f, 0.25f)
    t1Lit(nu.hetero, f9, 0f, s9, u9, T1_HETERO, T1_WHITE, 1f, 0.15f)
    t1Color(nu.periLamina, f9, 0f, s9, u9, 1f, false, 0.6f)
    t1Lit(nu.dna, f9, 0f, s9, u9, T1_DNA, T1_WHITE, 1f, 0.3f)
    // Pol II slides 0.8 units/s (6.4 nm/s ~ 20 nt/s); the helix is drawn in two parts either side of
    // the bubble inside the enzyme
    val hx = t1Mesh("helix2") { t1BuildHelix() }
    val slide = (seconds * 0.8f) % 12.0f
    val kP = ((slide + 0.3f) / T1_BP).toInt().coerceIn(10, T1_NBP - 10)
    val k0 = kP - 7; val k1 = kP + 7
    t1Model(f9, 0f, s9, u9)
    Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    colorShader.use(mvp, 1f); lineWidth(2.5f)
    hx.draw(colorShader.positionHandle, colorShader.colorHandle, 0, k0 * 8)
    hx.draw(colorShader.positionHandle, colorShader.colorHandle, (k1 + 1) * 8, (T1_NBP - k1 - 1) * 8)
    lineWidth(1f)
    // the bubble: template (below) and coding (above) strands parted by 0.35; RNA paired to the template
    v = 0
    val bA = floatArrayOf(1f, 0.74f, 0.45f); val bB = floatArrayOf(0.55f, 0.82f, 1f); val rc = floatArrayOf(1f, 0.45f, 0.40f)
    fun hp(k: Int, strand: Int, split: Float, c: FloatArray, al: Float) {
        val w = t1HxAng(k) + (if (strand == 0) 0f else 2.5f)
        val a = T1_HX_A0 + k * T1_BP
        val sp = if (strand == 0) -split else split
        val s = s9 + T1_HX_S + cos(w) * T1_HX_R; val u = u9 + T1_HX_U + sin(w) * T1_HX_R + sp
        v = t1Put(d, v, fx(f9, a, s, u), fy(f9, a, s, u), fz(f9, a, s, u), c, al)
    }
    for (k in k0..k1) {
        val t = (k - k0).toFloat() / (k1 - k0)
        val split = 0.175f * sin(PI_F * t).coerceAtLeast(0f).pow(0.5f)
        hp(k, 0, split, bA, 1f); hp(k + 1, 0, 0.175f * sin(PI_F * ((k + 1 - k0).toFloat() / (k1 - k0))).coerceAtLeast(0f).pow(0.5f), bA, 1f)
        hp(k, 1, split, bB, 1f); hp(k + 1, 1, 0.175f * sin(PI_F * ((k + 1 - k0).toFloat() / (k1 - k0))).coerceAtLeast(0f).pow(0.5f), bB, 1f)
    }
    // RNA-DNA hybrid: the transcript paired to the template for 8 bp behind the active site
    for (k in kP - 8 until kP) {
        for (kk in k..k + 1) {
            val w = t1HxAng(kk); val a = T1_HX_A0 + kk * T1_BP
            val s = s9 + T1_HX_S + cos(w) * T1_HX_R * 0.5f; val u = u9 + T1_HX_U + sin(w) * T1_HX_R * 0.5f - 0.1f
            v = t1Put(d, v, fx(f9, a, s, u), fy(f9, a, s, u), fz(f9, a, s, u), rc, 1f)
        }
    }
    t1DynDraw(v, GLES20.GL_LINES, 3f)
    // the enzyme: a clamp of two jaws either side of the DNA, and a bridge over it
    val aP = T1_HX_A0 + kP * T1_BP
    for (sg in SIGNS) t1Lit(sphere, f9, aP, s9 + T1_HX_S + sg * 0.62f, u9 + T1_HX_U - 0.05f, T1_POL, T1_WHITE, 0.92f, 0.22f, 0.45f, 0.6f, 0.9f)
    t1Lit(sphere, f9, aP + 0.15f, s9 + T1_HX_S, u9 + T1_HX_U + 0.55f, T1_POL, T1_WHITE, 0.92f, 0.22f, 0.55f, 0.28f, 0.75f)
    // the transcript: out of the exit channel above the enzyme, trailing behind it
    var w = 0
    val rc4 = floatArrayOf(1f, 0.55f, 0.48f, 1f)
    val len = min(slide, 7f)
    val segs = 30
    var qx = 0f; var qy = 0f; var qz = 0f
    for (m in 0..segs) {
        val t = len * m / segs
        val a = aP - 0.35f - t * 0.8f; val sx = s9 + T1_HX_S + 0.2f + 0.25f * sin(t * 2.3f); val uy = u9 + T1_HX_U + 0.85f + t * 0.25f + 0.2f * sin(t * 1.7f)
        val wx = fx(f9, a, sx, uy); val wy = fy(f9, a, sx, uy); val wz = fz(f9, a, sx, uy)
        if (m > 0) { w = t1Put(d, w, qx, qy, qz, rc4, 1f); w = t1Put(d, w, wx, wy, wz, rc4, 1f) }
        qx = wx; qy = wy; qz = wz
    }
    t1DynDraw(w, GLES20.GL_LINES, 3f)
    // the envelope's two faces (translucent) last
    GLES20.glDepthMask(false)
    t1Color(nu.cut, fp, 0f, so, uo, 2f, false)
    t1Color(nu.lamina, fp, 0f, so, uo, 1f, false, 0.7f)
    t1Lit(nu.envA, fp, 0f, so, uo, T1_ENVELOPE, T1_WHITE, 0.45f, 0.15f)
    t1Lit(nu.envB, fp, 0f, so, uo, T1_ENVELOPE, T1_WHITE, 0.45f, 0.15f)
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
private const val T1_RIBO_U = -0.5f

private class T1Ribo(val large: T1Batch, val small: T1Batch, val prot: T1Batch, val mrna: LineMesh)

private fun t1BuildRibo(): T1Ribo {
    val L = T1Builder(); val S = T1Builder(); val P = T1Builder()
    fun e(b: T1Builder, s: Float, u: Float, a: Float, rs: Float, ru: Float, ra: Float) =
        b.ellipsoid(s, u, -a, floatArrayOf(rs, 0f, 0f), floatArrayOf(0f, ru, 0f), floatArrayOf(0f, 0f, ra), 10, 14)
    // the 60S body: its underside cut flat into the intersubunit face, with a groove across it (along
    // the messenger's direction) where the tRNAs sit
    L.surface(14, 20) { u, v, out ->
        val ph = PI_F * u; val th = TAU * v
        val x = 1.3f * sin(ph) * cos(th); var y = 0.62f + 0.95f * cos(ph); val z = 1.4f * sin(ph) * sin(th)
        val floor = -0.05f + 0.22f * exp(-((x + 0.1f) / 0.3f).pow(2))
        if (y < floor) y = floor
        out[0] = x; out[1] = y; out[2] = z
    }
    e(L, -0.2f, 1.45f, -0.3f, 0.45f, 0.45f, 0.45f)                     // central protuberance
    L.rod(0.6f, 0.95f, -1.1f, 1.0f, 1.35f, -1.9f, 0.14f)                 // P stalk (A-site side)
    L.rod(-0.5f, 0.95f, 1.1f, -0.95f, 1.35f, 1.9f, 0.16f)                // L1 stalk (E-site side)
    e(S, 0.3f, -0.72f, 0f, 0.95f, 0.5f, 1.25f)                           // body
    e(S, -0.98f, -0.8f, -0.15f, 0.5f, 0.48f, 0.55f)                       // head, across a narrow neck
    e(S, -1.42f, -0.92f, -0.45f, 0.2f, 0.18f, 0.22f)                      // beak
    e(S, 0.95f, -0.55f, 0.6f, 0.35f, 0.3f, 0.35f)                         // platform
    // ribosomal proteins: flattened patches on the rRNA's outer surface (not on the subunit interface)
    val rnd = java.util.Random(3)
    for (k in 0 until 14) {
        val lg = k < 9
        val th = rnd.nextFloat() * TAU; val ph = (0.1f + rnd.nextFloat() * 0.35f) * PI_F
        val cs: Float; val cu: Float; val rs: Float; val ru: Float; val ra: Float
        if (lg) { cs = 0f; cu = 0.62f; rs = 1.3f; ru = 0.95f; ra = 1.4f } else { cs = 0.3f; cu = -0.72f; rs = 0.95f; ru = 0.5f; ra = 1.25f }
        val sgn = if (lg) 1f else -1f
        val lx = sin(ph) * cos(th); val ly = cos(ph) * sgn; val lz = sin(ph) * sin(th)
        val x = cs + rs * lx; val y = cu + ru * ly; val z = -(ra * lz)
        // surface normal of the ellipsoid there, and two tangents
        var nx = lx / rs; var ny = ly / ru; var nz = -lz / ra
        val nl = sqrt(nx * nx + ny * ny + nz * nz); nx /= nl; ny /= nl; nz /= nl
        var t1x = -nz; var t1y = 0f; var t1z = nx
        val tl = sqrt(t1x * t1x + t1z * t1z).coerceAtLeast(1e-4f); t1x /= tl; t1z /= tl
        val t2x = ny * t1z - nz * t1y; val t2y = nz * t1x - nx * t1z; val t2z = nx * t1y - ny * t1x
        val w = 0.26f + 0.08f * rnd.nextFloat()
        P.ellipsoid(x + nx * 0.02f, y + ny * 0.02f, z + nz * 0.02f, floatArrayOf(t1x * w, t1y * w, t1z * w), floatArrayOf(nx * 0.08f, ny * 0.08f, nz * 0.08f),
            floatArrayOf(t2x * w * 0.8f, t2y * w * 0.8f, t2z * w * 0.8f), 5, 8)
    }
    // the messenger (ribosome-local), with the codon ticks drawn per frame
    val m = ArrayList<Float>()
    val mc = floatArrayOf(1f, 0.58f, 0.50f, 1f)
    val segs = 200
    for (k in 0 until segs) for (h in 0..1) {
        val a = -17f + 29f * (k + h) / segs
        val wig = if (abs(a) < 1.6f || abs(a - 6f) < 1.6f || abs(a + 6f) < 1.6f) 0f else 1f
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
    val rb = t1Mesh("ribo3") { t1BuildRibo() }
    // The ribosome's frame: centred beside the lane and turned 50 degrees about the vertical, so the
    // messenger runs across the view and the A, P and E sites between the subunits stand side by side.
    val f0 = frameAt(i + T1_RIBO_A / 16f)
    val off = t1ShipOff(f0)
    val cs0 = off[0] + T1_RIBO_S; val cu0 = off[1] + T1_RIBO_U
    val rc = cos(90f * DEG); val rs = sin(90f * DEG)
    val f = StereoBodyRenderer.Frame(fx(f0, 0f, cs0, cu0), fy(f0, 0f, cs0, cu0), fz(f0, 0f, cs0, cu0),
        f0.dx * rc + f0.sx * rs, f0.dy * rc + f0.sy * rs, f0.dz * rc + f0.sz * rs,
        f0.sx * rc - f0.dx * rs, f0.sy * rc - f0.dy * rs, f0.sz * rc - f0.dz * rs, f0.ux, f0.uy, f0.uz)
    val so = 0f; val uo = 0f
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
        v = t1Put(d, v, fx(f, a, so - 0.2f, uo - 0.42f), fy(f, a, so - 0.2f, uo - 0.42f), fz(f, a, so - 0.2f, uo - 0.42f), tc, 1f)
        v = t1Put(d, v, fx(f, a, so - 0.2f, uo - 0.55f), fy(f, a, so - 0.2f, uo - 0.55f), fz(f, a, so - 0.2f, uo - 0.55f), tc, 1f)
    }
    t1DynDraw(v, GLES20.GL_LINES, 1.5f)
    // tRNAs (L-shaped): incoming with EF-Tu -> A -> P, and P -> E -> away
    val q = FloatArray(9)
    fun trna(x: Float, da: Float, ds: Float, du: Float, alpha: Float) {
        t1TrnaAt(x, q)
        // tinted by site: A (arriving) light, P deeper, E (leaving) darkest
        val col = if (x > 0.5f) T1_TRNA_A else if (x > -0.5f) T1_TRNA_P else T1_TRNA_E
        t1Rod(f, q[2] + da, so + q[0] + ds, uo + q[1] + du, q[5] + da, so + q[3] + ds, uo + q[4] + du, 0.16f, col, T1_WHITE, alpha, 0.6f)
        t1Rod(f, q[5] + da, so + q[3] + ds, uo + q[4] + du, q[8] + da, so + q[6] + ds, uo + q[7] + du, 0.16f, col, T1_WHITE, alpha, 0.6f)
    }
    val tIn = t1Smooth(0f, 0.3f, ph)
    val move = t1Smooth(0.45f, 0.75f, ph)
    val away = t1Smooth(0.75f, 1f, ph)
    if (ph < 0.3f) {
        val k = 1f - tIn
        trna(1f, 2.4f * k, -1.8f * k, -1.4f * k, 1f)
        t1Lit(sphere, f, 0.14f + 2.4f * k + 0.1f, so + 0.55f - 1.8f * k, uo + 0.5f - 1.4f * k, T1_EFTU, T1_WHITE, 1f, 0.3f, 0.36f, 0.32f, 0.36f)
    } else trna(1f - move, 0f, 0f, 0f, 1f)
    if (ph < 0.75f) trna(-move, 0f, 0f, 0f, 1f) else trna(-1f, -1.6f * away, -1.4f * away, 0.6f * away, 0.6f * (1f - away))
    // the peptidyl-transferase centre: RNA, not protein, doing the chemistry - an opaque patch of 60S rRNA
    // round the acceptor ends, flashing as the peptide bond forms (ph 0.3-0.45)
    t1TrnaAt(0.5f, q)
    t1Lit(sphere, f, q[8], so + q[6], uo + q[7] + 0.28f, T1_RRNA_L, T1_WHITE, 1f, 0.25f, 0.42f, 0.24f, 0.42f)
    val ptc = t1Smooth(0.28f, 0.34f, ph) * (1f - t1Smooth(0.42f, 0.48f, ph))
    if (ptc > 0.01f) t1Lit(sphere, f, q[8], so + q[6], uo + q[7] + 0.02f, T1_PTC, T1_PTC, ptc, 1.2f, 0.12f, 0.12f, 0.12f)
    // nascent chain: from the P-site acceptor up the exit tunnel, ~30 residues out of it, then already
    // collapsed into a compact folded domain still attached to it, growing as residues are added
    val holder = if (ph > 0.4f) 1f - move else 0f
    t1TrnaAt(holder, q)
    val pa0 = q[8]; val ps0 = q[6]; val pu0 = q[7]
    var w = 0
    val cols = arrayOf(floatArrayOf(1f, 0.78f, 0.42f, 1f), floatArrayOf(0.5f, 0.92f, 0.82f, 1f), floatArrayOf(0.95f, 0.55f, 0.75f, 1f), floatArrayOf(0.7f, 0.8f, 1f, 1f))
    val step = 0.045f
    val grow = ph * step
    var endA = 0f; var endS = 0f; var endU = 0f
    for (k in 0 until 60) {
        val t = k * step + grow
        val sP: Float; val uP: Float; val aP: Float
        if (t < 1.3f) { val g = t / 1.3f; sP = ps0 + (0.35f - ps0) * g; uP = pu0 + (1.6f - pu0) * g; aP = pa0 + (0.3f - pa0) * g }
        else {
            val e = (t - 1.3f).coerceAtMost(1.35f)            // ~30 residues, extended, out of the tunnel
            sP = 0.35f + 0.1f * sin(e * 9f); uP = 1.6f + e * 0.45f; aP = 0.3f + 0.1f * cos(e * 7f)
            if (t - 1.3f > 1.35f) break
        }
        endA = aP; endS = sP; endU = uP
        w = t1Put(d, w, fx(f, aP, so + sP, uo + uP), fy(f, aP, so + sP, uo + uP), fz(f, aP, so + sP, uo + uP), cols[(k + floor(cyc).toInt()) and 3], 1f)
    }
    t1DynDraw(w, GLES20.GL_POINTS, 4f)
    val kr = 0.28f + 0.1f * ((seconds / 12f) % 1f)
    t1Lit(blob, f, endA, so + endS, uo + endU + kr * 0.9f, T1_FOLD, T1_WHITE, 1f, 0.2f, kr, kr * 0.85f, kr * 0.95f)
    // subunits: proteins opaque, rRNA translucent so the tRNAs and the tunnel show through
    t1Lit(rb.prot, f, 0f, so, uo, T1_RPROT, T1_WHITE, 1f, 0.15f)
    for (k in 0..1) {       // two more ribosomes along the same messenger, turned about it: a spiral polysome
        val da = if (k == 0) 6f else -6f
        t1Model(f, da, so, uo); Matrix.rotateM(model, 0, if (k == 0) 35f else -35f, 0f, 0f, 1f)
        drawLitModel(rb.prot, T1_RPROT, T1_WHITE, landmarkFade, 0f, 0.15f)
        t1Model(f, da, so, uo); Matrix.rotateM(model, 0, if (k == 0) 35f else -35f, 0f, 0f, 1f)
        drawLitModel(rb.large, T1_RRNA_L, T1_WHITE, landmarkFade, 0f, 0.2f)
        t1Model(f, da, so, uo); Matrix.rotateM(model, 0, if (k == 0) 35f else -35f, 0f, 0f, 1f)
        drawLitModel(rb.small, T1_RRNA_S, T1_WHITE, landmarkFade, 0f, 0.2f)
    }
    GLES20.glDepthMask(false)
    t1Lit(rb.large, f, 0f, so, uo, T1_RRNA_L, T1_WHITE, 0.35f, 0.2f)
    t1Lit(rb.small, f, 0f, so, uo, T1_RRNA_S, T1_WHITE, 0.5f, 0.2f)
    GLES20.glDepthMask(true)
}

// ================================================================ stop 11: THE ATOM (12 pm rung)
// 1 unit = 8 pm. A carbon atom in the aromatic ring of a base: no orbits, a probability cloud.
// Points are sampled from Slater-type orbital densities (Clementi-Raimondi exponents): the dense 1s
// core (2 electrons, peak at 9 pm) and the valence shell as three sp2 hybrid lobes in the ring plane
// plus the p orbital across it (peaks near 65 pm, thinning out beyond 100 pm), reaching toward its
// neighbours - C at 140 pm, N at 135 pm, H at 108 pm - with the shared sigma-bond density (gold)
// along each bond and the neighbours' own clouds, dimmer. The other ring atoms' nuclei complete the
// hexagon. Nuclei are fixed-size points (a carbon nucleus is ~3 fm: 25,000 times smaller than the
// atom). Drawn additively so the density builds up; nothing orbits or rotates - four samplings of the
// same distribution take turns, a flicker of probability. The ring plane faces us, tipped 30 degrees.

private const val BOHR_PM = 52.92f
private const val T1_RING_TILT = 30f * DEG
// local x = side, y = up, z = -along: the ring plane contains x and e2; the p orbital lies along its normal
private val T1_E2 = floatArrayOf(0f, cos(T1_RING_TILT), -sin(T1_RING_TILT))
private val T1_PI_N = floatArrayOf(0f, sin(T1_RING_TILT), cos(T1_RING_TILT))
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
    fun add(x: Float, y: Float, z: Float, c: FloatArray, a: Float) {
        // thin the far haze: beyond 100 pm keep one point in five
        val rr2 = x * x + y * y + z * z
        if (rr2 > 100f && rnd.nextFloat() > 0.1f) return
        pts.add(x); pts.add(y); pts.add(z); pts.add(c[0]); pts.add(c[1]); pts.add(c[2]); pts.add(a)
    }
    fun dir(out: FloatArray) {
        while (true) {
            val x = rnd.nextFloat() * 2f - 1f; val y = rnd.nextFloat() * 2f - 1f; val z = rnd.nextFloat() * 2f - 1f
            val l = x * x + y * y + z * z
            if (l in 0.01f..1f) { val s = sqrt(l); out[0] = x / s; out[1] = y / s; out[2] = z / s; return }
        }
    }
    val u = FloatArray(3)
    val cC = floatArrayOf(0.55f, 0.85f, 1f); val cB = floatArrayOf(1f, 0.84f, 0.45f); val cN = floatArrayOf(0.45f, 0.65f, 0.85f)
    val cCore = floatArrayOf(0.85f, 0.95f, 1f); val cPi = floatArrayOf(0.70f, 0.60f, 1f)
    val bonds = t1AtomBonds()
    fun cloud(cx: Float, cy: Float, cz: Float, zeta1: Float, n1: Int, zeta2: Float, nHyb: Int, hyb: Boolean, col: FloatArray, a1: Float, a2: Float) {
        for (k in 0 until n1) {                                    // 1s: r^2 e^{-2 zeta r} -> Gamma(3, 2 zeta)
            val r = t1Gamma(3, 2f * zeta1, rnd) * BOHR_PM / 8f
            dir(u); add(cx + u[0] * r, cy + u[1] * r, cz + u[2] * r, if (hyb) cCore else col, if (hyb) 0.45f else a1)
        }
        if (zeta2 <= 0f) return
        var placed = 0
        while (placed < nHyb) {                                    // valence: r^4 e^{-2 zeta r} -> Gamma(5, 2 zeta)
            val r = t1Gamma(5, 2f * zeta2, rnd) * BOHR_PM / 8f
            dir(u)
            val accept: Float; var pc = col; var pa = a2
            if (hyb) {
                // three sp2 hybrids (1/sqrt3 s + sqrt(2/3) p) sampled 1.5x, and one p (pi) orbital across the plane
                val which = if (rnd.nextFloat() < 0.82f) rnd.nextInt(3) else 3
                accept = if (which < 3) { val c = u[0] * bonds[which][0] + u[1] * bonds[which][1] + u[2] * bonds[which][2]; val a = 0.57735f + 1.41421f * c; a * a / 3.96f }
                else { val c = u[1] * T1_PI_N[1] + u[2] * T1_PI_N[2]; c * c }
                if (which == 3) { pc = cPi; pa = 0.3f } else { pc = cC; pa = 0.3f }
            } else accept = 1f
            if (rnd.nextFloat() > accept) continue
            add(cx + u[0] * r, cy + u[1] * r, cz + u[2] * r, pc, pa)
            placed++
        }
    }
    // the carbon (Clementi-Raimondi: 1s 5.673, 2p 1.568 per bohr)
    cloud(0f, 0f, 0f, 5.673f, 1500, 1.568f, 3200, true, cC, 0.3f, 0.2f)
    // sigma-bond density along each bond, peaking between the nuclei
    val bl = floatArrayOf(140f, 135f, 108f)
    for (k in 0..2) {
        val d = bl[k] / 8f
        var placed = 0
        while (placed < 900) {
            val t = rnd.nextFloat()
            if (rnd.nextFloat() > 0.35f + 0.65f * sin(PI_F * t)) continue
            dir(u)
            val g = t1Gamma(2, 1.5f, rnd)
            val c = u[0] * bonds[k][0] + u[1] * bonds[k][1] + u[2] * bonds[k][2]
            val px = u[0] - c * bonds[k][0]; val py = u[1] - c * bonds[k][1]; val pz = u[2] - c * bonds[k][2]
            add(bonds[k][0] * d * t + px * g, bonds[k][1] * d * t + py * g, bonds[k][2] * d * t + pz * g, cB, 0.5f)
            placed++
        }
    }
    // the neighbours' own clouds, dimmer: C, N, and the exocyclic H
    for (k in 0..2) {
        val d = bl[k] / 8f
        val x = bonds[k][0] * d; val y = bonds[k][1] * d; val z = bonds[k][2] * d
        when (k) {
            0 -> cloud(x, y, z, 5.673f, 300, 1.568f, 400, false, cN, 0.25f, 0.25f)
            1 -> cloud(x, y, z, 6.665f, 300, 1.917f, 400, false, cN, 0.25f, 0.25f)
            else -> cloud(x, y, z, 1.24f, 300, 0f, 0, false, cN, 0.25f, 0.25f)
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
    val a0 = 6.9f; val s0 = off[0] + 0.5f; val u0 = off[1] + 0.3f        // ~9 units (six ship-lengths) ahead as the craft arrives
    // There is no wall at an atom: forget the (black, invisible) passage's depth so nothing drifting
    // beyond it is hidden. Both eyes' viewports share the depth buffer; this eye's scene so far holds
    // only the wall, and the other eye is either finished or not yet begun.
    if (rp > 10.5f) {
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        // and paint the passage out: a sphere round the craft in the background colour (black on the
        // waveguide), so the neighbouring stops' tinted walls do not show as a tube round the atom
        val void = t1Mesh("atom.void") {
            val d = ArrayList<Float>()
            val c = floatArrayOf(0.01f, 0f, 0.012f, 1f)
            val st = 12; val sl = 16
            fun pt(i: Int, j: Int) { val ph = PI_F * i / st; val th = TAU * j / sl
                d.add(sin(ph) * cos(th)); d.add(cos(ph)); d.add(sin(ph) * sin(th)); d.addAll(c.toList()) }
            for (i in 0 until st) for (j in 0 until sl) { pt(i, j); pt(i + 1, j); pt(i + 1, j + 1); pt(i, j); pt(i + 1, j + 1); pt(i, j + 1) }
            TriMesh(d.toFloatArray())
        }
        Matrix.setIdentityM(model, 0); Matrix.translateM(model, 0, camNowX, camNowY, camNowZ); Matrix.scaleM(model, 0, 60f, 60f, 60f)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(false); GLES20.glDisable(GLES20.GL_CULL_FACE)
        val keep = colorShader.globalFade; colorShader.globalFade = min(1f, k * 20f)
        colorShader.use(mvp, 1f)
        void.draw(colorShader.positionHandle, colorShader.colorHandle)
        colorShader.globalFade = keep
        GLES20.glEnable(GLES20.GL_CULL_FACE); GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(true)
    }
    // additive
    GLES20.glDepthMask(false); GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
    val tick = floor(seconds / 0.12f).toInt()
    val lead = (t1Hash(tick) * 4f).toInt().coerceIn(0, 3)
    for (m in 0 until 4) {
        val cl = t1Mesh("atom2.$m") { t1BuildAtom(m) }
        t1Model(f, a0, s0, u0, k)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        val keep = colorShader.globalFade; colorShader.globalFade = keep * (if (m == lead) 1f else 0.35f)
        colorShader.use(mvp, 2.6f, points = true)
        cl.draw(colorShader.positionHandle, colorShader.colorHandle)
        colorShader.globalFade = keep
    }
    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    // nuclei: fixed-size points of light, never resolved - the carbon, its three neighbours, and
    // (fainter) the rest of the aromatic ring
    val nuc = t1Mesh("atom3.nuclei") {
        val dd = FloatArray(7 * 7)
        t1Put(dd, 0, 0f, 0f, 0f, floatArrayOf(1f, 0.96f, 0.88f), 1f)
        val bl = floatArrayOf(140f, 135f, 108f); val bd = t1AtomBonds()
        for (q in 0..2) { val d = bl[q] / 8f; t1Put(dd, q + 1, bd[q][0] * d, bd[q][1] * d, bd[q][2] * d, floatArrayOf(1f, 0.9f, 0.8f), 1f) }
        // ring centre: along the bisector of the two ring bonds, 140 pm from each ring atom
        var cx = bd[0][0] + bd[1][0]; var cy = bd[0][1] + bd[1][1]; var cz = bd[0][2] + bd[1][2]
        val cl = sqrt(cx * cx + cy * cy + cz * cz); cx /= cl; cy /= cl; cz /= cl
        val R = 140f / 8f
        // in-plane unit vector perpendicular to the bisector
        val px = T1_PI_N[1] * cz - T1_PI_N[2] * cy; val py = T1_PI_N[2] * cx - 0f * cz; val pz = 0f * cy - T1_PI_N[1] * cx
        val pl = sqrt(px * px + py * py + pz * pz)
        for (q in 0..2) {
            val th = (q - 1) * 60f * DEG                   // the three ring atoms beyond the two neighbours
            val ex = cx * cos(th) + px / pl * sin(th); val ey = cy * cos(th) + py / pl * sin(th); val ez = cz * cos(th) + pz / pl * sin(th)
            t1Put(dd, q + 4, cx * R + ex * R, cy * R + ey * R, cz * R + ez * R, floatArrayOf(1f, 0.9f, 0.8f), 0.6f)
        }
        PointMesh(dd)
    }
    t1Model(f, a0, s0, u0, k)
    Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    val keep = colorShader.globalFade
    colorShader.globalFade = keep * 0.3f
    colorShader.use(mvp, 12f, points = true); nuc.draw(colorShader.positionHandle, colorShader.colorHandle)
    colorShader.globalFade = keep
    colorShader.use(mvp, 8f, points = true); nuc.draw(colorShader.positionHandle, colorShader.colorHandle)
    // the rest of the aromatic ring: dim bonding density between its atoms; and a faint contour at the
    // radius holding 90% of the carbon's own electron density (~70 pm), in the ring plane and across it
    val ring = t1Mesh("atom3.ring") {
        val bd = t1AtomBonds()
        var cx = bd[0][0] + bd[1][0]; var cy = bd[0][1] + bd[1][1]; var cz = bd[0][2] + bd[1][2]
        val cl = sqrt(cx * cx + cy * cy + cz * cz); cx /= cl; cy /= cl; cz /= cl
        val R = 140f / 8f
        val px0 = T1_PI_N[1] * cz - T1_PI_N[2] * cy; val py0 = T1_PI_N[2] * cx; val pz0 = -T1_PI_N[1] * cx
        val pl = sqrt(px0 * px0 + py0 * py0 + pz0 * pz0)
        fun atom(th: Float, out: FloatArray) { val ex = cx * cos(th) + px0 / pl * sin(th); val ey = cy * cos(th) + py0 / pl * sin(th); val ez = cz * cos(th) + pz0 / pl * sin(th)
            out[0] = cx * R + ex * R; out[1] = cy * R + ey * R; out[2] = cz * R + ez * R }
        val rnd = java.util.Random(9)
        val pts = ArrayList<Float>()
        val gold = floatArrayOf(1f, 0.84f, 0.45f)
        val a = FloatArray(3); val b = FloatArray(3)
        for (e in 0 until 4) {                     // ring edges not touching our carbon: 60..120, 0..60, -60..0, -120..-60
            val th0 = (120f - 60f * e) * DEG; val th1 = (60f - 60f * e) * DEG
            atom(th0, a); atom(th1, b)
            for (k in 0 until 220) {
                val t = rnd.nextFloat(); if (rnd.nextFloat() > 0.35f + 0.65f * sin(PI_F * t)) continue
                val g = t1Gamma(2, 1.5f, rnd); val ga = rnd.nextFloat() * TAU
                pts.add(a[0] + (b[0] - a[0]) * t + cos(ga) * g * 0.7f); pts.add(a[1] + (b[1] - a[1]) * t + sin(ga) * g * 0.5f); pts.add(a[2] + (b[2] - a[2]) * t + sin(ga) * g * 0.5f)
                pts.add(gold[0]); pts.add(gold[1]); pts.add(gold[2]); pts.add(0.3f)
            }
        }
        PointMesh(pts.toFloatArray())
    }
    val keep2 = colorShader.globalFade; colorShader.globalFade = keep2 * 0.8f
    colorShader.use(mvp, 2.6f, points = true); ring.draw(colorShader.positionHandle, colorShader.colorHandle)
    colorShader.globalFade = keep2
    val contour = t1Mesh("atom3.contour") {
        val l = ArrayList<Float>()
        val c = floatArrayOf(0.5f, 0.7f, 1f, 0.25f)
        for (plane in 0..1) for (k in 0 until 64) for (hh in 0..1) {
            val a = TAU * (k + hh) / 64f; val r = 9f
            val e2 = if (plane == 0) T1_E2 else T1_PI_N
            l.add(cos(a) * r + e2[0] * sin(a) * r); l.add(e2[1] * sin(a) * r); l.add(e2[2] * sin(a) * r); l.addAll(c.toList())
        }
        LineMesh(l.toFloatArray())
    }
    colorShader.use(mvp, 1f); contour.draw(colorShader.positionHandle, colorShader.colorHandle)
    GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(true)
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
    // (drawn over the world, so only once the craft is really at the look-back, never from the stop before)
    val cosmos = if (routeProgress > i - 0.35f) ((h - 30f) / 30f).coerceIn(0f, 1f) else 0f
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
    // (Only once the craft is really here: the plate draws over the world, so a fading-in look-back
    // must not show it from the previous stop.)
    if (map.id == 3 && routeProgress > i - 0.35f) drawPlate("portrait", frameAt(i + 0.12f), -tunnelRadius(i + 0.12f) * 0.34f, tunnelRadius(i + 0.12f) * 0.10f, 3.6f, seconds)
}

/**
 * The figure's geometry in figure space, shaded once: each vertex carries the colour the lit shader
 * would give it under the craft's bow lamp (diffuse, rim, organ mottling, glow), so the whole person
 * draws in a few calls instead of ~80 per eye. Every primitive is a canonical shape placed by an
 * orthonormal basis and per-axis scales, so its normals stay exact; triangles are wound outward so
 * back faces can be culled like the old sphere shells.
 */
private class PersonBaker {
    private var d = FloatArray(1 shl 17); private var n = 0
    private val P = FloatArray(3); private val N = FloatArray(3)
    // Lamp and eye in figure space: the craft is in front of her (+z), off her right side (-x).
    private val lx = -0.33f; private val ly = 0.30f; private val lz = 0.89f
    private val vx = -0.29f; private val vy = 0.05f; private val vz = 0.955f

    private fun put(x: Float, y: Float, z: Float, c: FloatArray, o: Int) {
        if (n + 7 > d.size) d = d.copyOf(d.size * 2)
        d[n] = x; d[n + 1] = y; d[n + 2] = z; d[n + 3] = c[o]; d[n + 4] = c[o + 1]; d[n + 5] = c[o + 2]; d[n + 6] = c[o + 3]; n += 7
    }

    /** The lit shader's colour for unit normal (nx,ny,nz); [m] is the mesh's own normal (for the mottling). */
    private fun shade(nx: Float, ny: Float, nz: Float, m: FloatArray, base: FloatArray, accent: FloatArray, a: Float,
                      pattern: Float, glow: Float, out: FloatArray, o: Int) {
        val s = if (pattern > 0f) {
            val t = ((sin(m[0] * 11f + m[1] * 7f) * sin(m[2] * 9f + m[0] * 5f) - 0.35f) / 0.45f).coerceIn(0f, 1f)
            t * t * (3f - 2f * t) * pattern * 0.6f
        } else 0f
        val diff = max(nx * lx + ny * ly + nz * lz, 0f)
        val rim = (1f - max(nx * vx + ny * vy + nz * vz, 0f)).pow(2.5f)
        for (q in 0..2) {
            val cc = base[q] + (accent[q] - base[q]) * s
            out[o + q] = cc * (0.24f + 0.76f * diff) + accent[q] * rim * 0.45f + cc * glow
        }
        out[o + 3] = base[3] * a
    }

    /**
     * Canonical surface [fn] (writes position and unit normal for u,v in [0,1]) placed at c with axes
     * X, Y, Z (orthonormal) scaled sx, sy, sz. [twoSided] also emits the inside (open shells: hair).
     */
    fun prim(stacks: Int, slices: Int, cx: Float, cy: Float, cz: Float, X: FloatArray, Y: FloatArray, Z: FloatArray,
             sx: Float, sy: Float, sz: Float, base: FloatArray, accent: FloatArray, a: Float, pattern: Float, glow: Float,
             twoSided: Boolean = false, fn: (Float, Float, FloatArray, FloatArray) -> Unit) {
        val row = slices + 1; val cnt = (stacks + 1) * row
        val pos = FloatArray(cnt * 3); val nor = FloatArray(cnt * 3); val col = FloatArray(cnt * 4)
        for (i in 0..stacks) for (j in 0..slices) {
            fn(i.toFloat() / stacks, j.toFloat() / slices, P, N)
            val k = i * row + j; val k3 = k * 3
            val px = P[0] * sx; val py = P[1] * sy; val pz = P[2] * sz
            pos[k3] = cx + X[0] * px + Y[0] * py + Z[0] * pz
            pos[k3 + 1] = cy + X[1] * px + Y[1] * py + Z[1] * pz
            pos[k3 + 2] = cz + X[2] * px + Y[2] * py + Z[2] * pz
            // normal through the inverse transpose of the scaled basis
            val qx = N[0] / sx; val qy = N[1] / sy; val qz = N[2] / sz
            var nx = X[0] * qx + Y[0] * qy + Z[0] * qz; var ny = X[1] * qx + Y[1] * qy + Z[1] * qz; var nz = X[2] * qx + Y[2] * qy + Z[2] * qz
            val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9f); nx /= l; ny /= l; nz /= l
            nor[k3] = nx; nor[k3 + 1] = ny; nor[k3 + 2] = nz
            shade(nx, ny, nz, N, base, accent, a, pattern, glow, col, k * 4)
        }
        fun tri(a0: Int, b0: Int, e0: Int) {
            val A = a0 * 3; val B = b0 * 3; val E = e0 * 3
            val ux = pos[B] - pos[A]; val uy = pos[B + 1] - pos[A + 1]; val uz = pos[B + 2] - pos[A + 2]
            val wx = pos[E] - pos[A]; val wy = pos[E + 1] - pos[A + 1]; val wz = pos[E + 2] - pos[A + 2]
            val fx = uy * wz - uz * wy; val fy = uz * wx - ux * wz; val fz = ux * wy - uy * wx
            if (fx * fx + fy * fy + fz * fz < 1e-20f) return            // degenerate (a pole or a seam)
            val out = fx * (nor[A] + nor[B] + nor[E]) + fy * (nor[A + 1] + nor[B + 1] + nor[E + 1]) + fz * (nor[A + 2] + nor[B + 2] + nor[E + 2])
            val (p, q) = if (out >= 0f) b0 to e0 else e0 to b0
            put(pos[A], pos[A + 1], pos[A + 2], col, a0 * 4)
            put(pos[p * 3], pos[p * 3 + 1], pos[p * 3 + 2], col, p * 4); put(pos[q * 3], pos[q * 3 + 1], pos[q * 3 + 2], col, q * 4)
            if (twoSided) {
                put(pos[A], pos[A + 1], pos[A + 2], col, a0 * 4)
                put(pos[q * 3], pos[q * 3 + 1], pos[q * 3 + 2], col, q * 4); put(pos[p * 3], pos[p * 3 + 1], pos[p * 3 + 2], col, p * 4)
            }
        }
        for (i in 0 until stacks) for (j in 0 until slices) {
            val k00 = i * row + j; val k10 = k00 + row; val k11 = k10 + 1; val k01 = k00 + 1
            tri(k00, k10, k11); tri(k00, k11, k01)
        }
    }

    /** Axis-aligned ellipsoid (or, with the angle ranges, a piece of one: polar angle from +y, azimuth from +x toward +z). */
    fun ell(x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float, base: FloatArray, accent: FloatArray, a: Float,
            pattern: Float = 0f, glow: Float = 0f, ph0: Float = 0f, ph1: Float = PI_F, th0: Float = 0f, th1: Float = TAU, twoSided: Boolean = false) {
        val big = max(rx, max(ry, rz)) > 0.04f
        val st = max(3, ((if (big) 12 else 7) * (ph1 - ph0) / PI_F).roundToInt())
        val sl = max(4, ((if (big) 18 else 10) * (th1 - th0) / TAU).roundToInt())
        prim(st, sl, x, y, z, EX, EY, EZ, rx, ry, rz, base, accent, a, pattern, glow, twoSided) { u, v, p, q ->
            val ph = ph0 + u * (ph1 - ph0); val th = th0 + v * (th1 - th0)
            q[0] = sin(ph) * cos(th); q[1] = cos(ph); q[2] = sin(ph) * sin(th); p[0] = q[0]; p[1] = q[1]; p[2] = q[2]
        }
    }

    /** Ellipsoid with its ry axis along [along] and its rx axis toward [across] (made perpendicular). */
    fun ellAxis(c: FloatArray, along: FloatArray, across: FloatArray, rx: Float, ry: Float, rz: Float,
                base: FloatArray, accent: FloatArray, a: Float, pattern: Float = 0f, glow: Float = 0f) {
        val Y = along.copyOf(); norm(Y)
        val dd = across[0] * Y[0] + across[1] * Y[1] + across[2] * Y[2]
        val X = floatArrayOf(across[0] - dd * Y[0], across[1] - dd * Y[1], across[2] - dd * Y[2]); norm(X)
        val Z = cross(X, Y)
        prim(7, 10, c[0], c[1], c[2], X, Y, Z, rx, ry, rz, base, accent, a, pattern, glow) { u, v, p, q ->
            val ph = u * PI_F; val th = v * TAU
            q[0] = sin(ph) * cos(th); q[1] = cos(ph); q[2] = sin(ph) * sin(th); p[0] = q[0]; p[1] = q[1]; p[2] = q[2]
        }
    }

    /** A capsule of radius r whose axis runs from p0 to p1. */
    fun seg(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, base: FloatArray, accent: FloatArray, a: Float, glow: Float = 0f) {
        val Z = floatArrayOf(x1 - x0, y1 - y0, z1 - z0); val len = norm(Z)
        val X = perp(Z); val Y = cross(Z, X)
        val h = len * 0.5f; val m = 4
        prim(2 * m + 1, 10, (x0 + x1) * 0.5f, (y0 + y1) * 0.5f, (z0 + z1) * 0.5f, X, Y, Z, 1f, 1f, 1f, base, accent, a, 0f, glow) { u, v, p, q ->
            val i = (u * (2 * m + 1) + 0.5f).toInt()
            val top = i <= m
            val ph = if (top) i.toFloat() / m * PI_F / 2f else PI_F / 2f + (i - m - 1).toFloat() / m * PI_F / 2f
            val th = v * TAU
            q[0] = sin(ph) * cos(th); q[1] = sin(ph) * sin(th); q[2] = cos(ph)
            p[0] = q[0] * r; p[1] = q[1] * r; p[2] = q[2] * r + if (top) h else -h
        }
    }
    fun seg(p0: FloatArray, p1: FloatArray, r: Float, base: FloatArray, accent: FloatArray, a: Float, glow: Float = 0f) =
        seg(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], r, base, accent, a, glow)

    /** An upright cylinder, radius r, from y0 to y1; [capped] closes it top and bottom (a solid), else an open wall. */
    fun cyl(x: Float, y0: Float, y1: Float, z: Float, r: Float, base: FloatArray, accent: FloatArray, a: Float, capped: Boolean, glow: Float = 0f) {
        // rows: bottom centre, bottom rim (down), bottom rim (out), top rim (out), top rim (up), top centre
        prim(5, 16, x, 0f, z, EX, EY, EZ, 1f, 1f, 1f, base, accent, a, 0f, glow, twoSided = !capped) { u, v, p, q ->
            val i = (u * 5f + 0.5f).toInt(); val th = v * TAU
            val c = cos(th); val s = sin(th)
            val rr = if ((i == 0 || i == 5) && capped) 0f else r
            p[0] = c * rr; p[2] = s * rr; p[1] = if (i <= 2) y0 else y1
            if (i == 2 || i == 3 || !capped) { q[0] = c; q[1] = 0f; q[2] = s } else { q[0] = 0f; q[1] = if (i <= 1) -1f else 1f; q[2] = 0f }
        }
    }

    /** A box between two corners, flat-shaded faces. */
    fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, base: FloatArray, a: Float) {
        val c = FloatArray(4); val m = FloatArray(3)
        fun face(nx: Float, ny: Float, nz: Float, vararg p: Float) {
            shade(nx, ny, nz, m, base, base, a, 0f, 0f, c, 0)
            // p = 4 corners in order; wind so the face points along (nx,ny,nz)
            val ux = p[3] - p[0]; val uy = p[4] - p[1]; val uz = p[5] - p[2]
            val wx = p[6] - p[0]; val wy = p[7] - p[1]; val wz = p[8] - p[2]
            val ok = (uy * wz - uz * wy) * nx + (uz * wx - ux * wz) * ny + (ux * wy - uy * wx) * nz >= 0f
            val order = if (ok) intArrayOf(0, 1, 2, 0, 2, 3) else intArrayOf(0, 2, 1, 0, 3, 2)
            for (k in order) put(p[k * 3], p[k * 3 + 1], p[k * 3 + 2], c, 0)
        }
        face(0f, 0f, 1f, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1)
        face(0f, 0f, -1f, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0)
        face(1f, 0f, 0f, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1)
        face(-1f, 0f, 0f, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1)
        face(0f, 1f, 0f, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1)
        face(0f, -1f, 0f, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1)
    }

    /** An arc of a ring (rib, mandible, pelvic brim): ParamMesh.torusArc(0.045, 0.80) placed as drawBasis placed it. */
    fun arc(x: Float, y: Float, z: Float, zAxis: FloatArray, yHint: FloatArray, sx: Float, sy: Float, sz: Float, base: FloatArray, accent: FloatArray, a: Float, sweep: Float = 0.80f) {
        val Z = zAxis.copyOf(); norm(Z)
        val dd = yHint[0] * Z[0] + yHint[1] * Z[1] + yHint[2] * Z[2]
        val Y = floatArrayOf(yHint[0] - dd * Z[0], yHint[1] - dd * Z[1], yHint[2] - dd * Z[2]); norm(Y)
        val X = cross(Y, Z)
        val minor = 0.045f
        prim(16, 8, x, y, z, X, Y, Z, sx, sy, sz, base, accent, a, 0f, 0f) { u, v, p, q ->
            val an = (u - 0.5f) * sweep * TAU - PI_F / 2f; val b = v * TAU
            q[0] = cos(an) * cos(b); q[1] = sin(an) * cos(b); q[2] = sin(b)
            p[0] = cos(an) + minor * q[0]; p[1] = sin(an) + minor * q[1]; p[2] = minor * q[2]
        }
    }

    fun data(): FloatArray = d.copyOf(n)

    companion object {
        val EX = floatArrayOf(1f, 0f, 0f); val EY = floatArrayOf(0f, 1f, 0f); val EZ = floatArrayOf(0f, 0f, 1f)
        fun norm(v: FloatArray): Float { val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-9f); v[0] /= l; v[1] /= l; v[2] /= l; return l }
        fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun perp(z: FloatArray): FloatArray {
            val h = if (abs(z[1]) < 0.9f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
            val c = cross(h, z); norm(c); return c
        }
    }
}

/** The baked figure of one tour: organs and bones (opaque), then stop markers and skin (translucent), and the route. */
private class PersonMeshes(val solid: TriMesh, val glass: TriMesh, val route: LineMesh?)
private var personCache: PersonMeshes? = null
private var personKey: Any? = null
private var personTour = -1

private val COL_OESOPHAGUS = floatArrayOf(0.86f, 0.50f, 0.50f, 1f)
private val COL_COLON = floatArrayOf(0.82f, 0.56f, 0.46f, 1f)
private val COL_PANCREAS = floatArrayOf(0.93f, 0.78f, 0.55f, 1f)
private val COL_SPLEEN = floatArrayOf(0.55f, 0.20f, 0.30f, 1f)
private val COL_QUADS = floatArrayOf(0.80f, 0.40f, 0.40f, 1f)
private val COL_HAIR_HER = floatArrayOf(0.34f, 0.22f, 0.15f, 1f)
private val COL_HAIR_HIM = floatArrayOf(0.42f, 0.36f, 0.31f, 1f)
private val COL_WOOD = floatArrayOf(0.72f, 0.52f, 0.32f, 1f)
private val COL_WORKTOP = floatArrayOf(0.88f, 0.86f, 0.82f, 1f)
private val COL_FLOOR = floatArrayOf(0.45f, 0.40f, 0.38f, 1f)
private val COL_PERSON_GLASS = floatArrayOf(0.80f, 0.90f, 1.0f, 1f)
private val COL_WATER = floatArrayOf(0.62f, 0.80f, 0.95f, 1f)
private val COL_LUNG_SCARRED = floatArrayOf(0.72f, 0.60f, 0.62f, 1f)
private val COL_SCAR = floatArrayOf(0.62f, 0.58f, 0.62f, 1f)
private val COL_LB_CAVITY = floatArrayOf(0.42f, 0.30f, 0.26f, 1f)
private val COL_PLEURA = floatArrayOf(0.75f, 0.85f, 0.95f, 1f)
private val COL_STORED_BLOOD = floatArrayOf(0.55f, 0.06f, 0.11f, 1f)

private fun v3(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
private fun FloatArray.plus(o: FloatArray, k: Float) = floatArrayOf(this[0] + o[0] * k, this[1] + o[1] * k, this[2] + o[2] * k)

/**
 * One arm from shoulder S through elbow E to wrist W ([sgn] = +1 her left, -1 her right), in the
 * anatomical position unless posed: radius on the thumb (lateral) side, ulna medial, palm forward.
 * [bones] draws the skeleton into one baker; otherwise the superficial veins and the skin go in.
 */
private fun personArm(b: PersonBaker, sgn: Float, S: FloatArray, E: FloatArray, W: FloatArray, bones: Boolean) {
    val fore = floatArrayOf(W[0] - E[0], W[1] - E[1], W[2] - E[2]); PersonBaker.norm(fore)
    val lat = PersonBaker.cross(fore, PersonBaker.EZ); PersonBaker.norm(lat)
    for (q in 0..2) lat[q] *= -sgn                                           // toward the thumb side
    val front = PersonBaker.cross(fore, lat); for (q in 0..2) front[q] *= sgn
    val palm = W.plus(fore, 0.024f)
    // Digits: four fingers fanned from the knuckles (index on the thumb side), the thumb angled
    // ~35 degrees toward the radius. Each is (base, tip).
    val digits = ArrayList<Pair<FloatArray, FloatArray>>()
    val offs = floatArrayOf(0.009f, 0.003f, -0.003f, -0.009f); val lens = floatArrayOf(0.038f, 0.042f, 0.040f, 0.032f)
    val fans = floatArrayOf(0.10f, 0.03f, -0.03f, -0.10f)
    for (j in 0..3) {
        val base = W.plus(fore, 0.046f).plus(lat, offs[j])
        val dir = fore.plus(lat, fans[j]); PersonBaker.norm(dir)
        digits.add(base to base.plus(dir, lens[j]))
    }
    val tDir = fore.plus(lat, 0.70f).plus(front, 0.25f); PersonBaker.norm(tDir)
    val tBase = W.plus(fore, 0.012f).plus(lat, 0.012f)
    digits.add(tBase to tBase.plus(tDir, 0.03f))
    if (bones) {
        b.seg(S, E, 0.009f, COL_BONE, COL_LAMP, 1f)                                                   // humerus
        b.seg(E.plus(lat, 0.0065f), W.plus(lat, 0.0065f), 0.0055f, COL_BONE, COL_LAMP, 1f)             // radius (thumb side)
        b.seg(E.plus(lat, -0.0065f), W.plus(lat, -0.0065f), 0.006f, COL_BONE, COL_LAMP, 1f)            // ulna
        b.ellAxis(palm, fore, lat, 0.011f, 0.02f, 0.005f, COL_BONE, COL_LAMP, 1f)                      // carpals and metacarpals
        for ((p0, p1) in digits) b.seg(p0, p1, 0.0028f, COL_BONE, COL_LAMP, 1f)                         // phalanges
        return
    }
    // Superficial veins, just under the skin: cephalic up the thumb side, basilic up the little-finger
    // side, and the median cubital vein crossing the front of the elbow between them (the vein for a needle).
    val up = floatArrayOf(E[0] - S[0], E[1] - S[1], E[2] - S[2]); PersonBaker.norm(up)
    val vc = COL_VEIN_BLUE
    val cS = S.plus(lat, 0.026f).plus(front, 0.012f); val cE = E.plus(lat, 0.022f).plus(front, 0.014f); val cW = W.plus(lat, 0.016f).plus(front, 0.01f)
    val bM = floatArrayOf((S[0] + E[0]) * 0.5f, (S[1] + E[1]) * 0.5f, (S[2] + E[2]) * 0.5f).plus(lat, -0.024f).plus(front, 0.01f)
    val bE = E.plus(lat, -0.02f).plus(front, 0.014f); val bW = W.plus(lat, -0.015f).plus(front, 0.01f)
    b.seg(cS, cE, 0.0032f, vc, COL_LAMP, 0.75f); b.seg(cE, cW, 0.003f, vc, COL_LAMP, 0.75f)
    b.seg(bM, bE, 0.0035f, vc, COL_LAMP, 0.75f); b.seg(bE, bW, 0.003f, vc, COL_LAMP, 0.75f)
    b.seg(bE.plus(fore, 0.018f).plus(front, 0.004f), cE.plus(up, 0.012f).plus(front, 0.004f), 0.0035f, vc, COL_LAMP, 0.8f)
    val sk = COL_SKIN_SHELL; val rim = COL_SKIN_RIM
    b.seg(S.plus(up, -0.005f), E, 0.034f, sk, rim, 0.3f, glow = 0.35f)                                // upper arm
    b.seg(E, W, 0.027f, sk, rim, 0.3f, glow = 0.35f)                                                   // forearm
    b.ellAxis(palm, fore, lat, 0.020f, 0.026f, 0.010f, sk, rim, 0.3f, glow = 0.35f)                    // palm
    for ((p0, p1) in digits) b.seg(p0, p1, 0.0055f, sk, rim, 0.28f, glow = 0.35f)                      // fingers and thumb
}

/**
 * Build the figure once per tour (and again if the GL context was recreated). Figure-space x is
 * across (+x = her LEFT, on the viewer's right), y is up from the soles, z is toward the viewer;
 * all in fractions of her height. Proportions follow the eight-head canon. Organs are opaque and
 * drawn first; bones next; the skin is a translucent shell over everything, so the viscera read
 * through it. Tours I and II show the volunteer (a woman; in Tour II in her kitchen, wiping her
 * mouth after the drink the tour began with); Chapter III shows Bethune.
 */
private fun StereoBodyRenderer.personMeshes(): PersonMeshes {
    val hit = personCache
    if (hit != null && personKey === sphere && personTour == map.id) return hit
    // (a recreated context already freed the old buffers; their ids may now belong to others)
    if (hit != null && personKey === sphere) { hit.solid.release(); hit.glass.release(); hit.route?.release() }
    val him = map.id == 3
    val kitchen = map.id == 2
    val s = PersonBaker()
    // A little self-light on the organs so they read through the skin at the look-back's size.
    fun part(x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float, col: FloatArray, acc: FloatArray, a: Float, pat: Float = 0f, glow: Float = 0.12f) =
        s.ell(x, y, z, rx, ry, rz, col, acc, a, pat, glow)
    fun seg(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, col: FloatArray, acc: FloatArray, a: Float, glow: Float = 0.12f) =
        s.seg(x0, y0, z0, x1, y1, z1, r, col, acc, a, glow)

    // Arms: hanging in the anatomical position, except in Tour II, where her right hand (-x) is at
    // her mouth, the back of the wrist to the lips.
    val arms = SIGNS.map { sgn ->
        if (kitchen && sgn < 0f) Triple(v3(-0.13f, 0.80f, 0f), v3(-0.15f, 0.68f, 0.10f), v3(-0.05f, 0.83f, 0.10f))
        else Triple(v3(sgn * 0.13f, 0.80f, 0f), v3(sgn * 0.155f, 0.635f, 0f), v3(sgn * 0.17f, 0.47f, 0.01f))
    }

    // ---- the kitchen (Tour II): a counter behind her with the glass on it, wall cupboards, the floor
    if (kitchen) {
        s.box(-0.30f, 0f, -0.42f, 0.40f, 0.51f, -0.16f, COL_WOOD, 1f)                                   // base cabinets
        s.box(-0.31f, 0.51f, -0.43f, 0.41f, 0.535f, -0.14f, COL_WORKTOP, 1f)                            // worktop
        s.box(-0.30f, 0.80f, -0.44f, 0.40f, 1.05f, -0.30f, COL_WOOD, 1f)                                // wall cupboards
        s.box(-0.6f, -0.01f, -0.5f, 0.7f, 0f, 0.45f, COL_FLOOR, 0.55f)                                  // floor
    }

    // ---- organs (opaque), in their true places
    part(0f, 0.922f, 0.005f, 0.046f, 0.038f, 0.052f, COL_ORG_BRAIN, COL_LAMP, 1f, 0.8f)                   // brain
    s.arc(0f, 0.892f, 0.022f, v3(0f, 1f, 0f), v3(0f, 0f, -1f), 0.034f, 0.036f, 0.012f,
        COL_BONE, COL_LAMP, 0.85f)                                                                           // mandible, open behind
    seg(0f, 0.83f, 0.01f, 0f, 0.76f, 0.01f, 0.010f, COL_BONE, COL_LAMP, 0.9f)                              // trachea
    // Oesophagus: behind the trachea, down through the diaphragm and over to the stomach on her left.
    seg(0f, 0.835f, -0.018f, 0.004f, 0.66f, -0.02f, 0.0065f, COL_OESOPHAGUS, COL_LAMP, 1f)
    seg(0.004f, 0.66f, -0.02f, 0.03f, 0.625f, 0.004f, 0.0065f, COL_OESOPHAGUS, COL_LAMP, 1f)
    // Lungs either side of the mediastinum: the right (her -x) is the larger, three-lobed lung; the
    // left is narrower with a cardiac notch where the heart sits. Bases on the diaphragm's domes,
    // apices rising above the clavicles (~0.84).
    part(-0.068f, 0.718f, -0.004f, 0.046f, 0.074f, 0.046f, COL_ORG_LUNG, COL_LAMP, 0.95f, 0.5f)            // right lung
    part(-0.060f, 0.800f, -0.008f, 0.032f, 0.045f, 0.032f, COL_ORG_LUNG, COL_LAMP, 0.95f, 0.5f)            //   apex
    if (!him) {
        part(0.074f, 0.725f, -0.006f, 0.040f, 0.068f, 0.044f, COL_ORG_LUNG, COL_LAMP, 0.95f, 0.5f)         // left lung (notched)
        part(0.064f, 0.800f, -0.008f, 0.030f, 0.045f, 0.031f, COL_ORG_LUNG, COL_LAMP, 0.95f, 0.5f)         //   apex
    } else {
        // Bethune's left lung: collapsed by artificial pneumothorax in 1927 and never the same after,
        // so smaller, drawn in toward the hilum, and greyed; in its apex the old tuberculous cavity,
        // a hollow with a pale caseous rim in a puckered scar. (The pleural space it left is drawn
        // translucent at the lung's full size, with the skin.)
        part(0.070f, 0.722f, -0.006f, 0.036f, 0.061f, 0.040f, COL_ORG_LUNG, COL_LAMP, 0.95f, 0.5f)
        part(0.061f, 0.795f, -0.008f, 0.027f, 0.040f, 0.028f, COL_LUNG_SCARRED, COL_LAMP, 0.95f, 0.5f)
        part(0.064f, 0.784f, 0.012f, 0.022f, 0.02f, 0.018f, COL_LUNG_SCARRED, COL_SCAR, 1f, 0.8f)          // scar zone
        part(0.064f, 0.784f, 0.027f, 0.014f, 0.016f, 0.006f, COL_CASEUM, COL_LAMP, 1f)                      // caseous rim
        part(0.064f, 0.784f, 0.030f, 0.0095f, 0.0115f, 0.006f, COL_LB_CAVITY, COL_LB_CAVITY, 1f, 0f, 0f)          // the cavity
    }
    // (the heart is drawn live, so it can beat)
    seg(0.005f, 0.70f, -0.018f, 0.005f, 0.48f, -0.022f, 0.009f, COL_ORG_HEART, COL_LAMP, 1f, 0.25f)       // descending aorta
    seg(-0.012f, 0.70f, -0.012f, -0.012f, 0.48f, -0.016f, 0.010f, COL_VEIN_BLUE, COL_LAMP, 1f)            // inferior vena cava
    part(-0.045f, 0.615f, 0.012f, 0.072f, 0.034f, 0.05f, COL_ORG_LIVER, COL_LAMP, 1f)                     // liver, her right
    part(0.045f, 0.608f, 0.016f, 0.038f, 0.028f, 0.03f, COL_ORG_STOMACH, COL_LAMP, 1f)                    // stomach, her left
    part(0.074f, 0.612f, -0.022f, 0.02f, 0.03f, 0.012f, COL_SPLEEN, COL_LAMP, 1f)                         // spleen, behind the stomach
    part(0.018f, 0.592f, -0.014f, 0.042f, 0.009f, 0.012f, COL_PANCREAS, COL_LAMP, 1f, 0.6f)               // pancreas, across the back wall
    for (sgn in SIGNS) part(sgn * 0.044f, 0.565f, -0.032f, 0.017f, 0.030f, 0.015f, COL_ORG_KIDNEY, COL_LAMP, 1f) // kidneys, behind
    part(0f, 0.515f, 0.022f, 0.052f, 0.04f, 0.036f, COL_ORG_GUT, COL_LAMP, 1f, 1f)                        // small intestine
    // Large intestine framing it: ascending on her right, transverse under the liver and stomach,
    // descending on her left, and the sigmoid curling to the midline.
    seg(-0.068f, 0.47f, 0.012f, -0.07f, 0.572f, 0.014f, 0.013f, COL_COLON, COL_LAMP, 1f)
    seg(-0.07f, 0.572f, 0.026f, 0.07f, 0.572f, 0.026f, 0.012f, COL_COLON, COL_LAMP, 1f)
    seg(0.07f, 0.572f, 0.014f, 0.07f, 0.47f, 0.012f, 0.012f, COL_COLON, COL_LAMP, 1f)
    seg(0.07f, 0.47f, 0.012f, 0.02f, 0.455f, 0.02f, 0.010f, COL_COLON, COL_LAMP, 1f)
    part(0f, 0.466f, 0.026f, 0.021f, 0.018f, 0.018f, COL_ORG_BLADDER, COL_LAMP, 1f)                       // bladder
    // Diaphragm: two domes under the lungs, the right higher over the liver, joined by the central
    // tendon under the heart (translucent, so the organs above and below read through it).
    s.ell(-0.045f, 0.636f, 0f, 0.062f, 0.032f, 0.064f, COL_DIAPHRAGM, COL_LAMP, 0.6f, 0f, 0.1f, 0f, PI_F / 2f)
    s.ell(0.047f, 0.630f, 0f, 0.060f, 0.030f, 0.064f, COL_DIAPHRAGM, COL_LAMP, 0.6f, 0f, 0.1f, 0f, PI_F / 2f)
    s.ell(0f, 0.652f, 0.01f, 0.03f, 0.005f, 0.04f, COL_DIAPHRAGM, COL_LAMP, 0.6f)

    // ---- skeleton: spine, ribs open at the sternum, clavicles, limbs
    // Vertebral column: 7 cervical, 12 thoracic, 5 lumbar, growing downward, on the spine's curves
    // (cervical and lumbar lordosis forward, thoracic kyphosis back); the sacrum a wedge below.
    for (k in 0 until 7) { val y = 0.890f - k * 0.0075f; part(0f, y, -0.034f - k * 0.0012f, 0.009f, 0.0034f, 0.009f, COL_BONE, COL_LAMP, 1f, 0.6f) }
    for (k in 0 until 12) {
        val y = 0.830f - k * 0.0205f
        part(0f, y, -0.050f - 0.008f * sin(k / 11f * PI_F), 0.011f + k * 0.0003f, 0.0082f, 0.011f, COL_BONE, COL_LAMP, 1f, 0.6f)
    }
    for (k in 0 until 5) part(0f, 0.590f - k * 0.024f, -0.046f + 0.006f * sin(k / 4f * PI_F), 0.014f, 0.010f, 0.013f, COL_BONE, COL_LAMP, 1f, 0.6f)
    s.ellAxis(v3(0f, 0.463f, -0.05f), v3(0f, 1f, 0.35f), v3(1f, 0f, 0f), 0.028f, 0.024f, 0.008f, COL_BONE, COL_LAMP, 1f)   // sacrum
    // Twelve pairs of ribs, each a ring in the horizontal plane with its gap at the front, tilted
    // forward ~22 degrees (ribs slope down toward the front); widest at the 7th-8th, the last two
    // short (floating).
    for (k in 0 until 12) {
        val y = 0.80f - k * 0.018f
        val w = 0.078f + 0.030f * sin((k + 1.5f) / 13f * PI_F)
        val sweep = if (k >= 10) 0.42f else 0.80f
        s.arc(0f, y, -0.004f, v3(0f, 0.927f, 0.375f), v3(0f, 0f, 1f), w, 0.068f, 0.012f, COL_BONE, COL_LAMP, 0.75f, sweep)
        // Costal cartilage from the rib's front end: ribs 1-7 to the sternum, 8-10 up to the cartilage above.
        if (k < 10) for (sgn in SIGNS) {
            val ex = sgn * w * 0.588f; val ey = y - 0.021f; val ez = 0.047f
            if (k < 7) seg(ex, ey, ez, sgn * 0.011f, max(0.672f, y - 0.004f), 0.052f, 0.0035f, COL_CARTILAGE, COL_LAMP, 0.9f)
            else seg(ex, ey, ez, sgn * 0.036f, 0.668f + (k - 7) * 0.004f, 0.05f, 0.0032f, COL_CARTILAGE, COL_LAMP, 0.9f)
        }
    }
    part(0f, 0.738f, 0.052f, 0.012f, 0.066f, 0.005f, COL_BONE, COL_LAMP, 1f)                              // sternum
    s.arc(0f, 0.50f, 0f, v3(0f, 1f, 0f), v3(0f, 0f, 1f), 0.11f, 0.07f, 0.014f, COL_BONE, COL_LAMP, 0.85f) // pelvic brim
    for ((k, sgn) in SIGNS.withIndex()) {
        part(sgn * 0.075f, 0.515f, -0.01f, 0.05f, 0.04f, 0.012f, COL_BONE, COL_LAMP, 0.85f)                 // iliac wings
        seg(sgn * 0.012f, 0.822f, 0.03f, sgn * 0.07f, 0.828f, 0.035f, 0.007f, COL_BONE, COL_LAMP, 1f)        // clavicle, S-curved,
        seg(sgn * 0.07f, 0.828f, 0.035f, sgn * 0.125f, 0.838f, 0.0f, 0.007f, COL_BONE, COL_LAMP, 1f)         //   rising to the shoulder
        part(sgn * 0.075f, 0.765f, -0.062f, 0.034f, 0.048f, 0.005f, COL_BONE, COL_LAMP, 0.8f)                // scapula, behind the upper ribs
        part(sgn * 0.077f, 0.275f, 0.030f, 0.012f, 0.014f, 0.006f, COL_BONE, COL_LAMP, 1f)                    // patella
        seg(sgn * 0.07f, 0.47f, 0f, sgn * 0.078f, 0.28f, 0f, 0.012f, COL_BONE, COL_LAMP, 1f)                 // femur
        seg(sgn * 0.075f, 0.265f, 0.004f, sgn * 0.078f, 0.055f, 0.004f, 0.009f, COL_BONE, COL_LAMP, 1f)      // tibia
        seg(sgn * 0.090f, 0.255f, -0.006f, sgn * 0.090f, 0.06f, -0.006f, 0.005f, COL_BONE, COL_LAMP, 1f)     // fibula (lateral)
        val (S, E, W) = arms[k]; personArm(s, sgn, S, E, W, bones = true)
    }
    val solid = TriMesh(s.data())

    // ---- translucent: the cranium, this tour's stops, the thigh muscles, the skin
    val g = PersonBaker()
    g.ell(0f, 0.93f, 0f, 0.051f, 0.05f, 0.058f, COL_BONE, COL_LAMP, 0.35f)                                 // cranium, see-through
    if (him) {
        // The pleural space his collapsed left lung left, and the bottle of stored blood
        // (the chapter's stop outside any body), with its marker inside it.
        g.ell(0.074f, 0.728f, -0.006f, 0.041f, 0.074f, 0.045f, COL_PLEURA, COL_LAMP, 0.2f, 0f, 0.1f)
        g.cyl(0.24f, 0.628f, 0.663f, 0.008f, 0.016f, COL_STORED_BLOOD, COL_LAMP, 0.85f, capped = true, glow = 0.2f)
        g.cyl(0.24f, 0.626f, 0.678f, 0.008f, 0.018f, COL_PERSON_GLASS, COL_LAMP, 0.35f, capped = false, glow = 0.3f)
    }
    if (kitchen) {                                                                                         // the glass on the worktop
        g.cyl(-0.19f, 0.536f, 0.566f, -0.22f, 0.019f, COL_WATER, COL_LAMP, 0.6f, capped = true, glow = 0.2f)
        g.cyl(-0.19f, 0.535f, 0.61f, -0.22f, 0.022f, COL_PERSON_GLASS, COL_LAMP, 0.3f, capped = false, glow = 0.3f)
    }
    // Stops where they happened. Map coordinates follow the 2D inset: a figure facing you,
    // image-left = her right (-x); just under the skin (limbs are thin).
    fun mx(t: TourNode) = (t.mapX - 50f) / 150f
    fun my(t: TourNode) = 1f - t.mapY / 150f
    fun mz(x: Float) = if (abs(x) > 0.12f) 0.008f else 0.035f
    val route = ArrayList<Float>()
    for (k in 0 until nodes.size - 1) {
        val a0 = nodes[k]; val x = mx(a0); val y = my(a0)
        g.ell(x, y, mz(x), 0.011f, 0.011f, 0.011f, COL_LAMP, COL_LAMP, 1f, 0f, 0.9f)
        if (k + 1 < nodes.size - 1) {
            val b0 = nodes[k + 1]
            for ((qx, qy) in listOf(x to y, mx(b0) to my(b0))) {
                route.add(qx); route.add(qy); route.add(mz(qx)); route.add(1f); route.add(0.77f); route.add(0.42f); route.add(0.5f)
            }
        }
    }
    for (sgn in SIGNS) g.ell(sgn * 0.074f, 0.375f, 0.014f, 0.036f, 0.088f, 0.034f, COL_QUADS, COL_LAMP, 0.3f, 0f, 0.1f)  // quadriceps
    val sk = COL_SKIN_SHELL; val rim = COL_SKIN_RIM
    // Hair: over the crown and down the back of the head, open at the face (drawn both sides).
    val hair = if (him) COL_HAIR_HIM else COL_HAIR_HER
    g.ell(0f, 0.932f, -0.002f, 0.060f, 0.072f, 0.068f, hair, hair, 0.5f, 0f, 0.1f, 0f, if (him) 0.36f * PI_F else 0.42f * PI_F, twoSided = true)
    g.ell(0f, 0.932f, -0.004f, 0.061f, 0.073f, 0.069f, hair, hair, 0.5f, 0f, 0.1f, 0.2f * PI_F, if (him) 0.62f * PI_F else 0.86f * PI_F, PI_F, TAU, twoSided = true)
    g.ell(0f, 0.928f, 0f, 0.056f, 0.068f, 0.064f, sk, rim, 0.3f, glow = 0.35f)                             // head
    // A face: eyes (sclera + iris), the nose and the lips, so the figure reads as a person, not a mannequin.
    for (sgn in SIGNS) {
        g.ell(sgn * 0.021f, 0.938f, 0.052f, 0.009f, 0.006f, 0.006f, COL_SCLERA, COL_LAMP, 0.9f, 0f, 0.3f)
        g.ell(sgn * 0.021f, 0.938f, 0.057f, 0.0045f, 0.0045f, 0.003f, COL_IRIS, COL_LAMP, 1f, 0f, 0.2f)
    }
    g.ell(0f, 0.922f, 0.062f, 0.008f, 0.013f, 0.009f, sk, rim, 0.6f, glow = 0.35f)                         // nose
    if (him) g.ell(0f, 0.911f, 0.060f, 0.017f, 0.004f, 0.006f, COL_HAIR_HIM, COL_HAIR_HIM, 0.85f, 0f, 0.1f) // Bethune's moustache
    g.ell(0f, 0.902f, 0.056f, 0.015f, 0.0045f, 0.006f, COL_LIPS, COL_LAMP, 0.9f, 0f, 0.3f)                 // lips
    g.seg(0f, 0.845f, 0f, 0f, 0.878f, 0f, 0.028f, sk, rim, 0.3f, glow = 0.35f)                             // neck
    g.ell(0f, 0.735f, 0f, if (him) 0.12f else 0.112f, 0.095f, 0.072f, sk, rim, 0.2f, glow = 0.35f)       // chest and shoulders
    if (!him) for (sgn in SIGNS) g.ell(sgn * 0.05f, 0.722f, 0.056f, 0.036f, 0.032f, 0.028f, sk, rim, 0.2f, glow = 0.35f) // breasts
    g.ell(0f, 0.545f, 0f, if (him) 0.106f else 0.114f, 0.105f, 0.066f, sk, rim, 0.2f, glow = 0.35f)       // abdomen and pelvis, one shell
    for ((k, sgn) in SIGNS.withIndex()) {
        val (S, E, W) = arms[k]; personArm(g, sgn, S, E, W, bones = false)
        g.seg(sgn * 0.072f, 0.475f, 0f, sgn * 0.078f, 0.27f, 0f, 0.056f, sk, rim, 0.27f, glow = 0.35f)     // thigh
        g.seg(sgn * 0.078f, 0.27f, 0f, sgn * 0.08f, 0.05f, 0f, 0.040f, sk, rim, 0.28f, glow = 0.35f)       // lower leg
        g.ell(sgn * 0.082f, 0.018f, 0.03f, 0.029f, 0.018f, 0.058f, sk, rim, 0.3f, glow = 0.35f)            // foot
    }
    val made = PersonMeshes(solid, TriMesh(g.data()), if (route.isEmpty()) null else LineMesh(route.toFloatArray()))
    personCache = made; personKey = sphere; personTour = map.id
    return made
}

/**
 * An upright person facing the craft, H units tall, soles at the bottom (see [personMeshes] for
 * the figure itself). Only her placement changes per frame: one model matrix for the whole body,
 * plus the heart, drawn live so it beats.
 */
internal fun StereoBodyRenderer.drawPerson(n: TourNode, i: Int, H: Float, alpha: Float, seconds: Float) {
    val f = frameAt(routeProgress)
    // The craft holds station a fixed PHYSICAL distance from her while it grows: its bow 1.5 m from
    // her. In scene units that distance shrinks as the craft grows (1.5 units = the Mote), so she
    // keeps her angular size and only shrinks relative to the hull — which is what a growing ship
    // holding station in front of a person would actually see. She stands a little to one side of
    // the axis so the hull never blocks her from the chase camera.
    val lengthM = shipLengthM(routeProgress).toFloat()
    val ahead = 0.75f + 1.5f / lengthM * 1.5f
    val side = 0.6f + 0.3f * H
    // Figure axes in world space. She faces the craft, so her LEFT is on the viewer's RIGHT, and
    // the rail's side vector is the viewer's right: across = +side. Up = world up; toward the viewer = -dir.
    val ax = f.sx; val az = f.sz
    val tx = -f.dx; val tz = -f.dz
    val sway = 0.004f * sin(seconds * 0.6f)
    val ox = shipX + f.dx * ahead + f.sx * side + ax * sway * H
    val oy = shipY - H * 0.5f
    val oz = shipZ + f.dz * ahead + f.sz * side + az * sway * H
    // The heart, apex down, forward and to her left, beating with the ship's heartbeat clock.
    val beat = 1f + 0.07f * exp(-heartPhase * 7f)
    fun wx(x: Float, z: Float) = ax * x + tx * z
    fun wz(x: Float, z: Float) = az * x + tz * z
    drawBasis(ox + wx(0.018f, 0.028f) * H, oy + 0.690f * H, oz + wz(0.018f, 0.028f) * H,
        wx(0.55f, 0.6f), -0.5f, wz(0.55f, 0.6f), 0f, 1f, 0f,
        0.028f * H * beat, 0.026f * H * beat, 0.036f * H * beat, blob, COL_ORG_HEART, COL_LAMP, alpha, 0f, 0.37f)
    val meshes = personMeshes()
    model[0] = ax * H; model[1] = 0f; model[2] = az * H; model[3] = 0f
    model[4] = 0f; model[5] = H; model[6] = 0f; model[7] = 0f
    model[8] = tx * H; model[9] = 0f; model[10] = tz * H; model[11] = 0f
    model[12] = ox; model[13] = oy; model[14] = oz; model[15] = 1f
    Matrix.multiplyMM(mv, 0, view, 0, model, 0)
    Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    // The baker winds triangles outward in figure space; if the placement mirrors, so does the winding.
    val det = ax * tz - az * tx
    GLES20.glFrontFace(if (det >= 0f) GLES20.GL_CCW else GLES20.GL_CW)
    GLES20.glEnable(GLES20.GL_CULL_FACE)
    val keep = colorShader.globalFade
    colorShader.globalFade = keep * alpha * landmarkFade
    colorShader.use(mvp, 1f)
    meshes.solid.draw(colorShader.positionHandle, colorShader.colorHandle)
    GLES20.glDepthMask(false)
    meshes.route?.let {
        lineWidth(2f)
        it.draw(colorShader.positionHandle, colorShader.colorHandle)
        lineWidth(1f)
    }
    meshes.glass.draw(colorShader.positionHandle, colorShader.colorHandle)
    GLES20.glDepthMask(true)
    GLES20.glFrontFace(GLES20.GL_CCW)
    colorShader.globalFade = keep
}
