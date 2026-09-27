package com.rayneo.innercosmos

import android.opengl.GLES20
import android.opengl.Matrix
import kotlin.math.*

// Tour II — The Living Machine: mouth to mitosis.
// Landmark scenes, drawn by StereoBodyRenderer.drawLandmarks via the stop's Scene.
//
// Every scene is sized from real dimensions at the stop's stated scale (1 unit = Mote length / 1.5):
//   mouth 8 mm, gut 0.8 mm, phage 80 nm, liver / kidney / marrow 8 µm, muscle / division 0.8 µm,
//   V(D)J / factory 8 nm, highway 80 nm, ATP synthase 0.8 nm per unit.
// Static anatomy is baked once into vertex-coloured meshes that follow the curve of the rail
// (see t2Bend); moving parts are drawn per frame in the rail frame at their own position. Several
// structures are far bigger than the passage (a bacterium, a glomerulus, a trabecula, ATP synthase,
// a dividing cell): while its stop is the current one a scene clears the depth buffer, draws its
// anatomy over the passage wall, and then puts the wall's depth back (t2Open).

/** What drifts past at each stop of this tour, by stop index; stops not listed use DriftSpec.forAmb. */
internal val MACHINE_DRIFT: Map<Int, DriftSpec> = mapOf(
    // Gut at 0.8 mm a unit: bacteria and chylomicrons are sub-pixel; the scene draws the chyme.
    1 to DriftSpec.NONE,
    // Phage stop at 80 nm a unit: a whole E. coli is 25 units, so only lumen proteins drift.
    2 to DriftSpec.of(BodyField.PROTEIN to 1f, density = 0.2f, flow = 0.15f),
    // A liver sinusoid carries mixed portal and arterial blood.
    3 to DriftSpec.of(BodyField.RED_CELL to 0.95f, BodyField.PLATELET to 0.05f, density = 0.6f, flow = 1.0f, oxy = false),
    // Bowman's space holds filtrate, not cells (the scene draws the filtrate).
    4 to DriftSpec.NONE,
    // Inside a skeletal-muscle fibre: no blood cells.
    5 to DriftSpec.NONE,
    // Red marrow: a few free cells drifting between the cords.
    6 to DriftSpec.of(BodyField.RED_CELL to 0.6f, BodyField.PLATELET to 0.4f, density = 0.1f, flow = 0.2f),
    // The nucleoplasm around a gene: proteins only.
    // The nucleoplasm around a gene and the beta cell's ER: at 8 nm a unit a protein is as big as
    // the craft, so nothing drifts (the scenes draw the molecules that matter).
    7 to DriftSpec.NONE,
    9 to DriftSpec.NONE,
)

// ------------------------------------------------------------------------------------ palette
internal val T2_ENAMEL = floatArrayOf(0.97f, 0.95f, 0.88f, 1f)
internal val T2_ENAMEL_NECK = floatArrayOf(0.93f, 0.88f, 0.74f, 1f)
internal val T2_GUM = floatArrayOf(0.96f, 0.52f, 0.58f, 1f)
internal val T2_PALATE = floatArrayOf(0.95f, 0.6f, 0.64f, 1f)
internal val T2_SOFT_PALATE = floatArrayOf(0.9f, 0.44f, 0.52f, 1f)
internal val T2_RUGA = floatArrayOf(0.99f, 0.72f, 0.74f, 1f)
internal val T2_LIP = floatArrayOf(0.86f, 0.34f, 0.42f, 1f)
internal val T2_SKIN = floatArrayOf(0.95f, 0.7f, 0.6f, 1f)
internal val T2_TONGUE = floatArrayOf(0.93f, 0.5f, 0.56f, 1f)
internal val T2_PAPILLA = floatArrayOf(0.99f, 0.62f, 0.64f, 1f)
internal val T2_FUNGIFORM = floatArrayOf(0.98f, 0.36f, 0.44f, 1f)
internal val T2_TONSIL = floatArrayOf(0.93f, 0.52f, 0.54f, 1f)
internal val T2_EPIGLOTTIS = floatArrayOf(0.98f, 0.82f, 0.8f, 1f)
internal val T2_VOCAL_CORD = floatArrayOf(0.98f, 0.97f, 0.94f, 1f)
internal val T2_LARYNX = floatArrayOf(0.9f, 0.5f, 0.56f, 1f)
internal val T2_VILLUS = floatArrayOf(0.95f, 0.6f, 0.62f, 1f)
internal val T2_VILLUS_TIP = floatArrayOf(0.99f, 0.69f, 0.69f, 1f)
internal val T2_CRYPT = floatArrayOf(0.62f, 0.24f, 0.3f, 1f)
internal val T2_LACTEAL = floatArrayOf(0.98f, 0.98f, 0.9f, 1f)
internal val T2_CHYME = floatArrayOf(0.86f, 0.76f, 0.55f, 1f)
internal val T2_ECOLI = floatArrayOf(0.5f, 0.78f, 0.42f, 1f)
internal val T2_ECOLI_OM = floatArrayOf(0.78f, 0.97f, 0.66f, 1f)
internal val T2_PHAGE_HEAD = floatArrayOf(0.74f, 0.7f, 1f, 1f)
internal val T2_PHAGE_TAIL = floatArrayOf(0.82f, 0.82f, 0.93f, 1f)
internal val T2_PHAGE_PLATE = floatArrayOf(0.64f, 0.66f, 0.9f, 1f)
internal val T2_DNA = floatArrayOf(0.62f, 0.82f, 1f, 1f)
internal val T2_FLAGELLUM = floatArrayOf(0.85f, 0.95f, 0.72f, 1f)
internal val T2_HEPATOCYTE = floatArrayOf(0.88f, 0.46f, 0.44f, 1f)
internal val T2_HEPATOCYTE_BACK = floatArrayOf(0.62f, 0.27f, 0.28f, 1f)
internal val T2_NUCLEUS = floatArrayOf(0.52f, 0.34f, 0.72f, 1f)
internal val T2_CANALICULUS = floatArrayOf(0.5f, 0.97f, 0.36f, 1f)
internal val T2_ENDOTHELIUM = floatArrayOf(0.97f, 0.88f, 0.86f, 1f)
internal val T2_KUPFFER = floatArrayOf(0.8f, 0.84f, 0.7f, 1f)
internal val T2_CAPILLARY = floatArrayOf(0.88f, 0.22f, 0.28f, 1f)
internal val T2_MESANGIUM = floatArrayOf(0.62f, 0.24f, 0.3f, 1f)
internal val T2_PODOCYTE_A = floatArrayOf(0.84f, 0.6f, 0.82f, 1f)
internal val T2_PODOCYTE_B = floatArrayOf(0.66f, 0.52f, 0.9f, 1f)
internal val T2_PARIETAL = floatArrayOf(0.66f, 0.5f, 0.52f, 1f)
internal val T2_TUBULE = floatArrayOf(0.92f, 0.6f, 0.6f, 1f)
internal val T2_BRUSH = floatArrayOf(0.99f, 0.88f, 0.84f, 1f)
internal val T2_ARTERIOLE = floatArrayOf(0.82f, 0.16f, 0.2f, 1f)
internal val T2_FILTRATE = floatArrayOf(0.8f, 0.94f, 1f, 1f)
internal val T2_ZDISC = floatArrayOf(0.98f, 0.92f, 0.58f, 1f)
internal val T2_MYOSIN = floatArrayOf(0.72f, 0.24f, 0.3f, 1f)
internal val T2_MYOSIN_HEAD = floatArrayOf(0.98f, 0.5f, 0.52f, 1f)
internal val T2_ACTIN = floatArrayOf(0.95f, 0.84f, 0.78f, 1f)
internal val T2_MLINE = floatArrayOf(0.6f, 0.22f, 0.3f, 1f)
internal val T2_TTUBULE = floatArrayOf(0.3f, 0.62f, 0.7f, 1f)
internal val T2_SR = floatArrayOf(0.42f, 0.66f, 0.72f, 1f)
internal val T2_CALCIUM = floatArrayOf(0.6f, 1f, 1f, 1f)
internal val T2_MITO = floatArrayOf(0.96f, 0.56f, 0.26f, 1f)
internal val T2_MITO_CRISTA = floatArrayOf(0.99f, 0.74f, 0.4f, 1f)
internal val T2_BONE = floatArrayOf(0.96f, 0.91f, 0.78f, 1f)
internal val T2_OSTEOBLAST = floatArrayOf(0.76f, 0.62f, 0.9f, 1f)
internal val T2_OSTEOCLAST = floatArrayOf(0.88f, 0.52f, 0.64f, 1f)
internal val T2_SINUSOID = floatArrayOf(0.96f, 0.62f, 0.66f, 1f)
internal val T2_MEGA = floatArrayOf(0.9f, 0.74f, 0.9f, 1f)
internal val T2_MEGA_NUC = floatArrayOf(0.5f, 0.32f, 0.72f, 1f)
internal val T2_PLATELET = floatArrayOf(0.97f, 0.82f, 0.52f, 1f)
internal val T2_MACROPHAGE = floatArrayOf(0.82f, 0.84f, 0.7f, 1f)
internal val T2_PROERYTHRO = floatArrayOf(0.56f, 0.46f, 0.86f, 1f)
internal val T2_NORMOBLAST = floatArrayOf(0.9f, 0.4f, 0.46f, 1f)
internal val T2_RETIC = floatArrayOf(0.84f, 0.2f, 0.24f, 1f)
internal val T2_MYELOID = floatArrayOf(0.84f, 0.72f, 0.92f, 1f)
internal val T2_LYMPHOID = floatArrayOf(0.72f, 0.78f, 0.98f, 1f)
internal val T2_FAT = floatArrayOf(1f, 0.96f, 0.8f, 1f)
internal val T2_STROMA = floatArrayOf(0.92f, 0.86f, 0.7f, 1f)
internal val T2_HISTONE = floatArrayOf(0.64f, 0.52f, 0.86f, 1f)
internal val T2_DNA_WRAP = floatArrayOf(0.78f, 0.74f, 0.98f, 1f)
internal val T2_SEG_SPACER = floatArrayOf(0.7f, 0.66f, 0.9f, 1f)
internal val T2_SEG_V = floatArrayOf(0.98f, 0.46f, 0.62f, 1f)
internal val T2_SEG_D = floatArrayOf(0.48f, 0.92f, 0.52f, 1f)
internal val T2_SEG_J = floatArrayOf(0.46f, 0.7f, 1f, 1f)
internal val T2_SEG_C = floatArrayOf(0.78f, 0.56f, 0.98f, 1f)
internal val T2_RSS = floatArrayOf(1f, 1f, 1f, 1f)
internal val T2_RAG1 = floatArrayOf(0.99f, 0.8f, 0.36f, 1f)
internal val T2_RAG2 = floatArrayOf(0.96f, 0.58f, 0.3f, 1f)
internal val T2_KU = floatArrayOf(0.52f, 0.92f, 0.9f, 1f)
internal val T2_LIGASE = floatArrayOf(0.86f, 0.96f, 0.56f, 1f)
internal val T2_TUBULIN = floatArrayOf(0.5f, 0.88f, 0.66f, 1f)
internal val T2_GTP_CAP = floatArrayOf(0.95f, 1f, 0.75f, 1f)
internal val T2_GTURC = floatArrayOf(0.62f, 0.62f, 0.95f, 1f)
internal val T2_DYNEIN = floatArrayOf(0.62f, 0.78f, 0.98f, 1f)
internal val T2_LYSOSOME = floatArrayOf(0.9f, 0.48f, 0.6f, 1f)
internal val T2_LYSO_CORE = floatArrayOf(0.66f, 0.32f, 0.44f, 1f)
internal val T2_ER = floatArrayOf(0.62f, 0.66f, 0.95f, 1f)
internal val T2_RIBO_60S = floatArrayOf(0.36f, 0.64f, 0.78f, 1f)
internal val T2_RIBO_40S = floatArrayOf(0.5f, 0.82f, 0.74f, 1f)
internal val T2_GOLGI_CIS = floatArrayOf(0.86f, 0.78f, 0.42f, 1f)
internal val T2_GOLGI_TRANS = floatArrayOf(0.98f, 0.62f, 0.3f, 1f)
internal val T2_NPC = floatArrayOf(0.7f, 0.7f, 0.92f, 1f)
internal val T2_ENVELOPE = floatArrayOf(0.54f, 0.58f, 0.86f, 1f)
internal val T2_POL2 = floatArrayOf(0.42f, 0.8f, 0.62f, 1f)
internal val T2_MRNA = floatArrayOf(0.98f, 0.42f, 0.72f, 1f)
internal val T2_SEC61 = floatArrayOf(0.64f, 0.64f, 0.98f, 1f)
internal val T2_SIGNAL = floatArrayOf(1f, 0.96f, 0.3f, 1f)
internal val T2_CHAIN_A = floatArrayOf(1f, 0.74f, 0.42f, 1f)
internal val T2_CHAIN_B = floatArrayOf(0.5f, 0.9f, 0.8f, 1f)
internal val T2_BIP = floatArrayOf(0.6f, 0.92f, 0.6f, 1f)
internal val T2_GRANULE = floatArrayOf(0.8f, 0.92f, 0.96f, 1f)
internal val T2_INSULIN = floatArrayOf(0.96f, 0.88f, 0.5f, 1f)
internal val T2_PM = floatArrayOf(0.62f, 0.82f, 0.96f, 1f)
internal val T2_LIPID_HEAD = floatArrayOf(0.86f, 0.66f, 0.44f, 1f)
internal val T2_LIPID_TAIL = floatArrayOf(0.5f, 0.86f, 0.8f, 1f)
internal val T2_C_RING = floatArrayOf(1f, 0.95f, 0.62f, 1f)
internal val T2_C_MARK = floatArrayOf(1f, 0.7f, 0.4f, 1f)
internal val T2_GAMMA = floatArrayOf(0.98f, 0.96f, 0.76f, 1f)
internal val T2_ALPHA = floatArrayOf(0.56f, 0.9f, 0.86f, 1f)
internal val T2_BETA = floatArrayOf(0.36f, 0.7f, 0.76f, 1f)
internal val T2_STATOR = floatArrayOf(0.66f, 0.68f, 0.86f, 1f)
internal val T2_SUB_A = floatArrayOf(0.58f, 0.6f, 0.76f, 1f)
internal val T2_PROTON = floatArrayOf(1f, 0.96f, 0.55f, 1f)
internal val T2_PHOSPHATE = floatArrayOf(1f, 0.82f, 0.28f, 1f)
internal val T2_ADENINE = floatArrayOf(0.6f, 0.8f, 1f, 1f)
internal val T2_CELL = floatArrayOf(0.62f, 0.86f, 0.92f, 1f)
internal val T2_CHROMATID = floatArrayOf(0.62f, 0.4f, 0.92f, 1f)
internal val T2_SPINDLE = floatArrayOf(0.8f, 0.96f, 0.9f, 1f)
internal val T2_CENTROSOME = floatArrayOf(1f, 0.85f, 0.5f, 1f)
internal val T2_ACTOMYOSIN = floatArrayOf(0.95f, 0.72f, 0.66f, 1f)
internal val T2_EPITHELIUM = floatArrayOf(0.92f, 0.7f, 0.7f, 1f)

// =============================================================================== toolkit

/** A small 3-vector for building geometry (build time only; per-frame code works in floats). */
private class T2V(@JvmField val x: Float, @JvmField val y: Float, @JvmField val z: Float) {
    operator fun plus(o: T2V) = T2V(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: T2V) = T2V(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = T2V(x * k, y * k, z * k)
    infix fun dot(o: T2V) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: T2V) = T2V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(x * x + y * y + z * z)
    fun unit(): T2V { val l = len(); return if (l < 1e-9f) T2V(0f, 1f, 0f) else T2V(x / l, y / l, z / l) }
}

private fun t2v(x: Float, y: Float, z: Float) = T2V(x, y, z)
private fun t2perp(n: T2V): T2V = (if (abs(n.y) < 0.9f) n cross T2V(0f, 1f, 0f) else n cross T2V(1f, 0f, 0f)).unit()
private fun t2sm(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
private fun t2mix(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t, a[3] + (b[3] - a[3]) * t)
private const val T2PI = PI.toFloat()

/**
 * Accumulates triangles in a local frame (x = side, y = up, z = along the rail, deeper +) with a
 * normal and a colour per vertex, and exports them either with lighting baked in (for the
 * vertex-colour shader: many colours, one draw call) or as position + normal (for the lit shader).
 */
private class T2Geo {
    var d = FloatArray(1 shl 16); var n = 0
    private fun room(k: Int) { if (n + k > d.size) d = d.copyOf(max(d.size * 2, n + k)) }
    fun vtx(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, c: FloatArray, a: Float) {
        room(10)
        d[n] = x; d[n + 1] = y; d[n + 2] = z; d[n + 3] = nx; d[n + 4] = ny; d[n + 5] = nz
        d[n + 6] = c[0]; d[n + 7] = c[1]; d[n + 8] = c[2]; d[n + 9] = c[3] * a; n += 10
    }
    fun tri(a: T2V, b: T2V, c: T2V, col: FloatArray, al: Float = 1f) {
        val nr = (b - a) cross (c - a)
        vtx(a.x, a.y, a.z, nr.x, nr.y, nr.z, col, al); vtx(b.x, b.y, b.z, nr.x, nr.y, nr.z, col, al); vtx(c.x, c.y, c.z, nr.x, nr.y, nr.z, col, al)
    }
    fun quad(a: T2V, b: T2V, c: T2V, e: T2V, col: FloatArray, al: Float = 1f) { tri(a, b, c, col, al); tri(a, c, e, col, al) }

    /** A (nu+1) x (nv+1) grid of points (xyz), normals from the grid, colour [col] or per row [rowCol]. */
    fun grid(nu: Int, nv: Int, P: FloatArray, col: FloatArray, al: Float = 1f, rowCol: ((Int) -> FloatArray)? = null) {
        val w = nv + 1
        val N = FloatArray(P.size)
        for (i in 0..nu) for (j in 0..nv) {
            val a0 = (max(i - 1, 0) * w + j) * 3; val a1 = (min(i + 1, nu) * w + j) * 3
            val b0 = (i * w + max(j - 1, 0)) * 3; val b1 = (i * w + min(j + 1, nv)) * 3
            val ux = P[a1] - P[a0]; val uy = P[a1 + 1] - P[a0 + 1]; val uz = P[a1 + 2] - P[a0 + 2]
            val vx = P[b1] - P[b0]; val vy = P[b1 + 1] - P[b0 + 1]; val vz = P[b1 + 2] - P[b0 + 2]
            val k = (i * w + j) * 3
            N[k] = uy * vz - uz * vy; N[k + 1] = uz * vx - ux * vz; N[k + 2] = ux * vy - uy * vx
        }
        for (i in 0..nu) for (j in 0..nv) {
            val k = (i * w + j) * 3
            if (N[k] * N[k] + N[k + 1] * N[k + 1] + N[k + 2] * N[k + 2] < 1e-16f) {
                val ii = if (i == 0) min(1, nu) else if (i == nu) max(nu - 1, 0) else i
                val kk = (ii * w + j) * 3
                N[k] = N[kk]; N[k + 1] = N[kk + 1]; N[k + 2] = N[kk + 2]
            }
        }
        for (i in 0 until nu) {
            val c0 = rowCol?.invoke(i) ?: col; val c1 = rowCol?.invoke(i + 1) ?: col
            for (j in 0 until nv) {
                val p00 = i * w + j; val p10 = (i + 1) * w + j; val p11 = (i + 1) * w + j + 1; val p01 = i * w + j + 1
                emit(P, N, p00, c0, al); emit(P, N, p10, c1, al); emit(P, N, p11, c1, al)
                emit(P, N, p00, c0, al); emit(P, N, p11, c1, al); emit(P, N, p01, c0, al)
            }
        }
    }
    private fun emit(P: FloatArray, N: FloatArray, idx: Int, c: FloatArray, al: Float) {
        val k = idx * 3; vtx(P[k], P[k + 1], P[k + 2], N[k], N[k + 1], N[k + 2], c, al)
    }

    /** Parametric surface f(u, v) sampled on an nu x nv grid. */
    fun surf(nu: Int, nv: Int, col: FloatArray, al: Float = 1f, rowCol: ((Int) -> FloatArray)? = null, f: (Float, Float) -> T2V) {
        val w = nv + 1; val P = FloatArray((nu + 1) * w * 3)
        for (i in 0..nu) for (j in 0..nv) {
            val p = f(i.toFloat() / nu, j.toFloat() / nv); val k = (i * w + j) * 3
            P[k] = p.x; P[k + 1] = p.y; P[k + 2] = p.z
        }
        grid(nu, nv, P, col, al, rowCol)
    }

    /** Ellipsoid with semi-axes given as vectors. */
    fun ell(c: T2V, ax: T2V, ay: T2V, az: T2V, col: FloatArray, al: Float = 1f, nu: Int = 8, nv: Int = 12) {
        val w = nv + 1; val P = FloatArray((nu + 1) * w * 3)
        for (i in 0..nu) {
            val th = T2PI * i / nu; val cy = cos(th); val sr = sin(th)
            for (j in 0..nv) {
                val ph = 2f * T2PI * j / nv; val sx = sr * cos(ph); val sz = sr * sin(ph); val k = (i * w + j) * 3
                P[k] = c.x + ax.x * sx + ay.x * cy + az.x * sz
                P[k + 1] = c.y + ax.y * sx + ay.y * cy + az.y * sz
                P[k + 2] = c.z + ax.z * sx + ay.z * cy + az.z * sz
            }
        }
        grid(nu, nv, P, col, al)
    }
    fun ball(c: T2V, r: Float, col: FloatArray, al: Float = 1f, nu: Int = 6, nv: Int = 9) =
        ell(c, T2V(r, 0f, 0f), T2V(0f, r, 0f), T2V(0f, 0f, r), col, al, nu, nv)
    /** Axis-aligned-radii ellipsoid oriented by an axis [ay] (unit) and a hint for the x axis. */
    fun ellAxis(c: T2V, axis: T2V, ra: Float, rAxis: Float, rb: Float, col: FloatArray, al: Float = 1f, nu: Int = 8, nv: Int = 12, hint: T2V? = null) {
        val y = axis.unit(); val x = (hint?.let { (it - y * (it dot y)).unit() } ?: t2perp(y)); val z = x cross y
        ell(c, x * ra, y * rAxis, z * rb, col, al, nu, nv)
    }

    /** A tube along a polyline, radius rad(t) (t = 0..1 along it), optional rounded caps. */
    fun path(pts: List<T2V>, rad: (Float) -> Float, col: FloatArray, al: Float = 1f, sides: Int = 7, caps: Boolean = true, rowCol: ((Float) -> FloatArray)? = null, up: T2V? = null) {
        val m = pts.size; if (m < 2) return
        val T = Array(m) { k -> (pts[min(k + 1, m - 1)] - pts[max(k - 1, 0)]).unit() }
        val nor = arrayOfNulls<T2V>(m)
        nor[0] = up?.let { (it - T[0] * (it dot T[0])).let { q -> if (q.len() < 1e-5f) t2perp(T[0]) else q.unit() } } ?: t2perp(T[0])
        for (k in 1 until m) {
            val p = nor[k - 1]!!; val q = p - T[k] * (p dot T[k])
            nor[k] = if (q.len() < 1e-6f) t2perp(T[k]) else q.unit()
        }
        val cen = ArrayList<T2V>(); val tan = ArrayList<T2V>(); val nn = ArrayList<T2V>(); val rr = ArrayList<Float>(); val tt = ArrayList<Float>()
        fun ring(c: T2V, t: T2V, q: T2V, r: Float, u: Float) { cen.add(c); tan.add(t); nn.add(q); rr.add(r); tt.add(u) }
        val r0 = rad(0f); val r1 = rad(1f)
        if (caps) { ring(pts[0] - T[0] * r0, T[0], nor[0]!!, 0f, 0f); ring(pts[0] - T[0] * (r0 * 0.7f), T[0], nor[0]!!, r0 * 0.71f, 0f) }
        for (k in 0 until m) { val u = k.toFloat() / (m - 1); ring(pts[k], T[k], nor[k]!!, rad(u), u) }
        if (caps) { ring(pts[m - 1] + T[m - 1] * (r1 * 0.7f), T[m - 1], nor[m - 1]!!, r1 * 0.71f, 1f); ring(pts[m - 1] + T[m - 1] * r1, T[m - 1], nor[m - 1]!!, 0f, 1f) }
        val nu = cen.size - 1; val w = sides + 1; val P = FloatArray((nu + 1) * w * 3)
        for (i in 0..nu) {
            val c = cen[i]; val q = nn[i]; val b = tan[i] cross q; val r = rr[i]
            for (j in 0..sides) {
                val a = 2f * T2PI * j / sides; val ca = cos(a) * r; val sa = sin(a) * r; val k = (i * w + j) * 3
                P[k] = c.x + q.x * ca + b.x * sa; P[k + 1] = c.y + q.y * ca + b.y * sa; P[k + 2] = c.z + q.z * ca + b.z * sa
            }
        }
        grid(nu, sides, P, col, al, rowCol?.let { f -> { i: Int -> f(tt[i]) } })
    }
    fun tube(a: T2V, b: T2V, r0: Float, r1: Float, col: FloatArray, al: Float = 1f, sides: Int = 7, caps: Boolean = true) =
        path(listOf(a, b), { t -> r0 + (r1 - r0) * t }, col, al, sides, caps)

    /** Torus (or an arc of one) about [nrm] through [c]. */
    fun torus(c: T2V, nrm: T2V, R: Float, r: Float, col: FloatArray, al: Float = 1f, nu: Int = 24, nv: Int = 7, a0: Float = 0f, a1: Float = 2f * T2PI, e1In: T2V? = null) {
        val n = nrm.unit(); val e1 = e1In?.let { (it - n * (it dot n)).unit() } ?: t2perp(n); val e2 = n cross e1
        surf(nu, nv, col, al) { u, v ->
            val a = a0 + (a1 - a0) * u; val b = 2f * T2PI * v
            val dir = e1 * cos(a) + e2 * sin(a)
            c + dir * (R + r * cos(b)) + n * (r * sin(b))
        }
    }

    /** Flat disc facing [nrm]. */
    fun disc(c: T2V, nrm: T2V, r: Float, col: FloatArray, al: Float = 1f, seg: Int = 14, e1In: T2V? = null, rb: Float = r) {
        val n = nrm.unit(); val e1 = e1In?.let { (it - n * (it dot n)).unit() } ?: t2perp(n); val e2 = n cross e1
        for (k in 0 until seg) {
            val a0 = 2f * T2PI * k / seg; val a1 = 2f * T2PI * (k + 1) / seg
            tri(c, c + e1 * (cos(a0) * r) + e2 * (sin(a0) * rb), c + e1 * (cos(a1) * r) + e2 * (sin(a1) * rb), col, al)
        }
    }

    /** Closed cylinder (a disc with thickness) about [nrm]. */
    fun slab(c: T2V, nrm: T2V, r: Float, h: Float, col: FloatArray, al: Float = 1f, seg: Int = 14) {
        val n = nrm.unit()
        tube(c - n * (h * 0.5f), c + n * (h * 0.5f), r, r, col, al, seg, false)
        disc(c - n * (h * 0.5f), n, r, col, al, seg); disc(c + n * (h * 0.5f), n, r, col, al, seg)
    }

    /** Vertex-colour data (7 floats per vertex) with a key light baked in; positions mapped by [xf]. */
    fun baked(xf: ((FloatArray, Int) -> Unit)? = null): FloatArray {
        val cnt = n / 10; val out = FloatArray(cnt * 7)
        val lx = 0.30f; val ly = 0.83f; val lz = -0.47f
        for (v in 0 until cnt) {
            val s = v * 10; val o = v * 7
            val nx = d[s + 3]; val ny = d[s + 4]; val nz = d[s + 5]
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            val dd = if (l > 1e-12f) abs(nx * lx + ny * ly + nz * lz) / l else 0.6f
            val sh = 0.44f + 0.62f * dd
            out[o] = d[s]; out[o + 1] = d[s + 1]; out[o + 2] = d[s + 2]
            out[o + 3] = min(1f, d[s + 6] * sh); out[o + 4] = min(1f, d[s + 7] * sh); out[o + 5] = min(1f, d[s + 8] * sh); out[o + 6] = d[s + 9]
            if (xf != null) xf(out, o)
        }
        return out
    }

    /** Position + unit normal (6 floats per vertex) for the lit shader. */
    fun lit(): FloatArray {
        val cnt = n / 10; val out = FloatArray(cnt * 6)
        for (v in 0 until cnt) {
            val s = v * 10; val o = v * 6
            val nx = d[s + 3]; val ny = d[s + 4]; val nz = d[s + 5]
            val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12f)
            out[o] = d[s]; out[o + 1] = d[s + 1]; out[o + 2] = d[s + 2]
            out[o + 3] = nx / l; out[o + 4] = ny / l; out[o + 5] = nz / l
        }
        return out
    }
}

/** A baked triangle mesh for the lit shader (single colour per draw), drawn double-sided. */
private class T2Lit(data: FloatArray) : LitMesh() {
    val vbo = makeVbo(data)
    val count = data.size / 6
    override fun draw(positionHandle: Int, normalHandle: Int) {
        GLES20.glDisable(GLES20.GL_CULL_FACE)
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

// Meshes are built lazily on the GL thread, per tour (the rail differs), and rebuilt if the GL
// context was recreated (the renderer's own sphere mesh is then a new object).
private val t2Cache = HashMap<String, Any>()
private var t2Owner: Any? = null

@Suppress("UNCHECKED_CAST")
private fun <T : Any> StereoBodyRenderer.t2Get(key: String, build: () -> T): T {
    if (t2Owner !== sphere) { t2Cache.clear(); t2Owner = sphere }
    val k = "$key#${map.id}"
    val hit = t2Cache[k]
    if (hit != null) return hit as T
    val made = build()
    t2Cache[k] = made
    return made
}

/**
 * The rail around one stop, tabulated by arc length (units along the rail from the node):
 * centre, direction, side, up and the rail parameter p, 13 floats per sample.
 */
private class T2Rail(val aMin: Float, val step: Float, val f: FloatArray) {
    val cnt = f.size / 13
    fun at(a: Float, o: FloatArray) {
        var t = (a - aMin) / step; var ex = 0f
        if (t < 0f) { ex = t * step; t = 0f } else if (t > cnt - 1f) { ex = (t - (cnt - 1f)) * step; t = cnt - 1f }
        val k = min(t.toInt(), cnt - 2); val w = t - k; val b0 = k * 13; val b1 = b0 + 13
        for (q in 0 until 13) o[q] = f[b0 + q] + (f[b1 + q] - f[b0 + q]) * w
        if (ex != 0f) { o[0] += o[3] * ex; o[1] += o[4] * ex; o[2] += o[5] * ex }
    }
}

private fun StereoBodyRenderer.t2Rail(i: Int): T2Rail = t2Get("rail$i") {
    val last = nodes.lastIndex.toFloat()
    val p0 = max(0f, i - 1.7f); val p1 = min(last, i + 1.7f)
    val dp = 0.002f; val m = ((p1 - p0) / dp).toInt() + 1
    val P = FloatArray(m); val L = FloatArray(m); val q = FloatArray(3)
    var px = 0f; var py = 0f; var pz = 0f
    for (k in 0 until m) {
        P[k] = min(p1, p0 + k * dp); curvePoint(P[k], q)
        if (k > 0) L[k] = L[k - 1] + sqrt((q[0] - px) * (q[0] - px) + (q[1] - py) * (q[1] - py) + (q[2] - pz) * (q[2] - pz))
        px = q[0]; py = q[1]; pz = q[2]
    }
    val ki = ((i - p0) / dp).coerceIn(0f, (m - 1).toFloat()); val kk = min(ki.toInt(), max(m - 2, 0))
    val li = L[kk] + (L[min(kk + 1, m - 1)] - L[kk]) * (ki - kk)
    val aMin = -34f; val step = 0.25f; val cnt = 273
    val out = FloatArray(cnt * 13)
    var seek = 0
    for (j in 0 until cnt) {
        val s = aMin + j * step + li
        var ex = 0f; val p: Float
        if (s <= L[0]) { p = P[0]; ex = s - L[0] }
        else if (s >= L[m - 1]) { p = P[m - 1]; ex = s - L[m - 1] }
        else {
            while (seek < m - 2 && L[seek + 1] < s) seek++
            p = P[seek] + dp * (s - L[seek]) / max(1e-6f, L[seek + 1] - L[seek])
        }
        val fr = frameAt(p)
        val o = j * 13
        out[o] = fr.cx + fr.dx * ex; out[o + 1] = fr.cy + fr.dy * ex; out[o + 2] = fr.cz + fr.dz * ex
        out[o + 3] = fr.dx; out[o + 4] = fr.dy; out[o + 5] = fr.dz
        out[o + 6] = fr.sx; out[o + 7] = fr.sy; out[o + 8] = fr.sz
        out[o + 9] = fr.ux; out[o + 10] = fr.uy; out[o + 11] = fr.uz
        out[o + 12] = p
    }
    T2Rail(aMin, step, out)
}

/**
 * Maps local (x side, y up, z along) to world: near the rail (radius < [near]) the geometry
 * follows the rail's curve; beyond [far] it is rigid in the node's frame (big structures keep
 * their shape); in between it blends. far <= 0 means rigid everywhere.
 */
private fun StereoBodyRenderer.t2Bend(i: Int, near: Float, far: Float): (FloatArray, Int) -> Unit {
    val rail = t2Rail(i); val f0 = FloatArray(13); rail.at(0f, f0); val fr = FloatArray(13)
    return { o, k ->
        val x = o[k]; val y = o[k + 1]; val z = o[k + 2]
        var wx = f0[0] + f0[3] * z + f0[6] * x + f0[9] * y
        var wy = f0[1] + f0[4] * z + f0[7] * x + f0[10] * y
        var wz = f0[2] + f0[5] * z + f0[8] * x + f0[11] * y
        if (far > 0f) {
            val w = 1f - t2sm((sqrt(x * x + y * y) - near) / max(1e-3f, far - near))
            if (w > 0f) {
                rail.at(z, fr)
                wx += (fr[0] + fr[6] * x + fr[9] * y - wx) * w
                wy += (fr[1] + fr[7] * x + fr[10] * y - wy) * w
                wz += (fr[2] + fr[8] * x + fr[11] * y - wz) * w
            }
        }
        o[k] = wx; o[k + 1] = wy; o[k + 2] = wz
    }
}

private fun StereoBodyRenderer.t2Bake(g: T2Geo, i: Int, near: Float = 3f, far: Float = 9f) = TriMesh(g.baked(t2Bend(i, near, far)))

private val t2F = FloatArray(13)
private val t2G = FloatArray(13)
private val t2Q = FloatArray(3)

/** Rail frame at [a] units from stop [i] (bent with the rail), into [out]. */
private fun StereoBodyRenderer.t2Frame(i: Int, a: Float, out: FloatArray = t2F): FloatArray { t2Rail(i).at(a, out); return out }
/** The node's own (rigid) frame, displaced [a] along its axis. */
private fun StereoBodyRenderer.t2Rigid(i: Int, a: Float, out: FloatArray = t2G): FloatArray {
    t2Rail(i).at(0f, out); out[0] += out[3] * a; out[1] += out[4] * a; out[2] += out[5] * a; return out
}
/** World position of local (x, y) in frame [fr] (plus [z] along the frame's axis), into t2Q. */
private fun t2W(fr: FloatArray, x: Float, y: Float, z: Float = 0f): FloatArray {
    t2Q[0] = fr[0] + fr[6] * x + fr[9] * y + fr[3] * z
    t2Q[1] = fr[1] + fr[7] * x + fr[10] * y + fr[4] * z
    t2Q[2] = fr[2] + fr[8] * x + fr[11] * y + fr[5] * z
    return t2Q
}

/** model = frame [fr] (x side, y up, z along) at local (x, y, z), turned [rot] radians about up (+x toward +z). */
private fun StereoBodyRenderer.t2Model(fr: FloatArray, x: Float, y: Float, z: Float = 0f, rot: Float = 0f) {
    val c = cos(rot); val s = sin(rot)
    model[0] = fr[6] * c + fr[3] * s; model[1] = fr[7] * c + fr[4] * s; model[2] = fr[8] * c + fr[5] * s; model[3] = 0f
    model[4] = fr[9]; model[5] = fr[10]; model[6] = fr[11]; model[7] = 0f
    model[8] = fr[3] * c - fr[6] * s; model[9] = fr[4] * c - fr[7] * s; model[10] = fr[5] * c - fr[8] * s; model[11] = 0f
    model[12] = fr[0] + fr[6] * x + fr[9] * y + fr[3] * z
    model[13] = fr[1] + fr[7] * x + fr[10] * y + fr[4] * z
    model[14] = fr[2] + fr[8] * x + fr[11] * y + fr[5] * z
    model[15] = 1f
}

/** Draw a vertex-colour mesh with the current model matrix (double-sided). */
private fun StereoBodyRenderer.t2Draw(m: ColorVboMesh, translucent: Boolean = false, size: Float = 1f, points: Boolean = false) {
    Matrix.multiplyMM(mv, 0, view, 0, model, 0)
    Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    colorShader.use(mvp, size, points)
    GLES20.glDisable(GLES20.GL_CULL_FACE)
    if (translucent) GLES20.glDepthMask(false)
    m.draw(colorShader.positionHandle, colorShader.colorHandle)
    GLES20.glDepthMask(true)
    GLES20.glEnable(GLES20.GL_CULL_FACE)
}
private fun StereoBodyRenderer.t2DrawWorld(m: ColorVboMesh, translucent: Boolean = false, size: Float = 1f, points: Boolean = false) {
    Matrix.setIdentityM(model, 0); t2Draw(m, translucent, size, points)
}

/** Draw a lit mesh with the current model matrix (double-sided; translucent when alpha < 1). */
private fun StereoBodyRenderer.t2DrawLit(mesh: LitMesh, base: FloatArray, accent: FloatArray, alpha: Float = 1f, glow: Float = 0f, pattern: Float = 0f) {
    GLES20.glDisable(GLES20.GL_CULL_FACE)
    if (alpha < 0.99f) GLES20.glDepthMask(false)
    drawLitModel(mesh, base, accent, alpha * landmarkFade, pattern, glow)
    GLES20.glDepthMask(true)
    GLES20.glEnable(GLES20.GL_CULL_FACE)
}

/** An ellipsoid at local (x, y) in frame [fr]: radii rx (side), ry (up), rz (along). */
private fun StereoBodyRenderer.t2Blob(fr: FloatArray, x: Float, y: Float, rx: Float, ry: Float, rz: Float, base: FloatArray, accent: FloatArray = COL_LAMP, alpha: Float = 1f, glow: Float = 0f, dz: Float = 0f) {
    val p = t2W(fr, x, y, dz)
    GLES20.glDisable(GLES20.GL_CULL_FACE)
    if (alpha < 0.99f) GLES20.glDepthMask(false)
    drawBasis(p[0], p[1], p[2], fr[3], fr[4], fr[5], fr[9], fr[10], fr[11], rx, ry, rz, sphere, base, accent, alpha, 0f, glow)
    GLES20.glDepthMask(true)
    GLES20.glEnable(GLES20.GL_CULL_FACE)
}

/** Oriented lit mesh: local z along (zx,zy,zz), local y toward (yx,yy,yz). */
private fun StereoBodyRenderer.t2Basis(p: FloatArray, zx: Float, zy: Float, zz: Float, yx: Float, yy: Float, yz: Float, sx: Float, sy: Float, sz: Float,
                                       mesh: LitMesh, base: FloatArray, accent: FloatArray = COL_LAMP, alpha: Float = 1f, glow: Float = 0f) {
    val px = p[0]; val py = p[1]; val pz = p[2]
    GLES20.glDisable(GLES20.GL_CULL_FACE)
    if (alpha < 0.99f) GLES20.glDepthMask(false)
    drawBasis(px, py, pz, zx, zy, zz, yx, yy, yz, sx, sy, sz, mesh, base, accent, alpha, 0f, glow)
    GLES20.glDepthMask(true)
    GLES20.glEnable(GLES20.GL_CULL_FACE)
}

/**
 * While stop [i] is the current one, its anatomy may be far bigger than the passage: clear depth
 * so it draws over the wall, then write the wall's depth back so later drawing (drift, the craft,
 * the next stop's landmark) is still occluded by the passage. Otherwise the wall hides it as usual.
 */
private inline fun StereoBodyRenderer.t2Open(i: Int, seconds: Float, body: (Boolean) -> Unit) {
    val own = abs(routeProgress - i) < 0.5f
    if (own) { GLES20.glDepthMask(true); GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT) }
    body(own)
    if (own) {
        GLES20.glColorMask(false, false, false, false)
        drawTunnel(seconds)
        GLES20.glColorMask(true, true, true, true)
    }
}

/**
 * This tour's scenes show only around their own stop: beyond 0.85 of a stop the passage ahead is
 * left clear instead of hanging the next stop's anatomy down it. Fades the landmark in over 0.3.
 */
private fun StereoBodyRenderer.t2Near(i: Int): Boolean {
    val d = abs(routeProgress - i)
    if (d > 0.85f) return false
    val f = ((0.85f - d) / 0.3f).coerceIn(0f, 1f)
    landmarkFade *= f; colorShader.globalFade *= f
    return true
}

// Per-frame lines and points (world coordinates), and per-frame triangles.
private val t2Lines by lazy { DynMesh(3000) }
private var t2Ln = 0
private fun t2LinesBegin() { t2Ln = 0 }
private fun t2Vert(x: Float, y: Float, z: Float, c: FloatArray, a: Float) {
    if (t2Ln >= 3000) return
    val d = t2Lines.data; val o = t2Ln * 7
    d[o] = x; d[o + 1] = y; d[o + 2] = z; d[o + 3] = c[0]; d[o + 4] = c[1]; d[o + 5] = c[2]; d[o + 6] = a
    t2Ln++
}
private fun t2Seg(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, c: FloatArray, a0: Float, a1: Float = a0) {
    if (t2Ln + 2 > 3000) return
    t2Vert(ax, ay, az, c, a0); t2Vert(bx, by, bz, c, a1)
}
private fun StereoBodyRenderer.t2LinesEnd(width: Float = 1.5f, points: Boolean = false, size: Float = 1f) {
    if (t2Ln == 0) return
    Matrix.setIdentityM(model, 0)
    Matrix.multiplyMM(mv, 0, view, 0, model, 0)
    Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    colorShader.use(mvp, size, points)
    if (!points) lineWidth(width)
    GLES20.glDepthMask(false)
    t2Lines.draw(colorShader.positionHandle, colorShader.colorHandle, if (points) GLES20.GL_POINTS else GLES20.GL_LINES, t2Ln)
    GLES20.glDepthMask(true)
    lineWidth(1f)
    t2Ln = 0
}

private val t2Tris by lazy { DynMesh(4000) }
private val t2ChromTris by lazy { DynMesh(30000) }

/** A unit sphere as a lit mesh, double-sided (for cells seen from inside). */
private fun StereoBodyRenderer.t2BallMesh(): LitMesh = t2Get("ballmesh") {
    ParamMesh(14, 22) { u, v, out ->
        val th = u * T2PI; val ph = v * 2f * T2PI
        out[0] = sin(th) * cos(ph); out[1] = cos(th); out[2] = sin(th) * sin(ph)
    }
}

/** Wall radius of the passage at rail parameter p and angle ang (from side toward up), as buildTunnel makes it. */
private fun StereoBodyRenderer.t2WallR(p: Float, ang: Float): Float {
    val step = 0.08f; val segs = 14
    val kr = max(0f, p) / step; val r0 = floor(kr); val fr = kr - r0
    var a = ang % (2f * T2PI); if (a < 0f) a += 2f * T2PI
    val ka = a / (2f * T2PI) * segs; val a0 = floor(ka); val fa = ka - a0
    val k0 = a0.toInt() % segs; val k1 = (k0 + 1) % segs
    val pa = r0 * step; val pb = pa + step
    fun bump(k: Int, pp: Float) = 1f + 0.07f * sin(k * 3.1f + pp * 9.3f) + 0.04f * sin(k * 7.7f + pp * 21f)
    val ra = tunnelRadius(pa); val rb = tunnelRadius(pb)
    val b00 = bump(k0, pa) * ra; val b10 = bump(k1, pa) * ra; val b01 = bump(k0, pb) * rb; val b11 = bump(k1, pb) * rb
    return (b00 * (1f - fa) + b10 * fa) * (1f - fr) + (b01 * (1f - fa) + b11 * fa) * fr
}

// ============================================================================ stop 0: MOUTH
// 8 mm a unit (the Mote is 12 mm). The craft sits in the gape between the incisors; ahead lie
// the oral cavity (tongue below, palate above, the dental arches either side), the fauces and
// uvula, and beyond them the epiglottis over the laryngeal inlet.

private const val T2_INC = 0
private const val T2_CAN = 1
private const val T2_PREM = 2
private const val T2_MOL = 3

/** One crown: mesiodistal width, labiolingual depth, crown height (units), class. */
private class T2Tooth(val w: Float, val t: Float, val h: Float, val kind: Int)

// Midline to second molar. Upper central incisor 8.5 x 7 x 10.5 mm, first molar 10 x 11 x 7.5 mm, etc.
private val T2_UPPER = listOf(
    T2Tooth(1.06f, 0.88f, 1.30f, T2_INC), T2Tooth(0.82f, 0.76f, 1.12f, T2_INC), T2Tooth(0.95f, 1.0f, 1.32f, T2_CAN),
    T2Tooth(0.88f, 1.12f, 1.03f, T2_PREM), T2Tooth(0.84f, 1.1f, 0.98f, T2_PREM), T2Tooth(1.28f, 1.4f, 0.92f, T2_MOL), T2Tooth(1.12f, 1.35f, 0.88f, T2_MOL)
)
private val T2_LOWER = listOf(
    T2Tooth(0.68f, 0.74f, 1.12f, T2_INC), T2Tooth(0.74f, 0.78f, 1.18f, T2_INC), T2Tooth(0.86f, 0.95f, 1.37f, T2_CAN),
    T2Tooth(0.88f, 0.96f, 1.05f, T2_PREM), T2Tooth(0.9f, 1.0f, 1.0f, T2_PREM), T2Tooth(1.38f, 1.3f, 0.95f, T2_MOL), T2Tooth(1.28f, 1.25f, 0.9f, T2_MOL)
)

/** A parabolic dental arch z = z0 + k x², tabulated by arc length from the midline. */
private class T2Arch(val z0: Float, val k: Float) {
    private val xs = FloatArray(1600); private val ss = FloatArray(1600)
    init {
        var s = 0f
        for (q in 0 until 1600) {
            val x = q * 0.004f
            if (q > 0) { val xp = x - 0.004f; val dz = k * (x * x - xp * xp); s += sqrt(1.6e-5f + dz * dz) }
            xs[q] = x; ss[q] = s
        }
    }
    fun x(s: Float): Float {
        var lo = 0; var hi = 1599
        if (s >= ss[hi]) return xs[hi]
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (ss[m] < s) lo = m else hi = m }
        val t = (s - ss[lo]) / max(1e-6f, ss[hi] - ss[lo]); return xs[lo] + (xs[hi] - xs[lo]) * t
    }
    fun z(x: Float) = z0 + k * x * x
}

/** A crown standing on [base], e1 along the arch, e2 labial, e3 toward the occlusal plane. */
private fun T2Geo.crown(base: T2V, e1: T2V, e2: T2V, e3: T2V, tt: T2Tooth) {
    val W = tt.w; val T = tt.t; val H = tt.h
    val ex = when (tt.kind) { T2_MOL -> 3.4f; T2_PREM -> 2.6f; else -> 2.2f }
    fun side(hn: Float, out: FloatArray) {
        when (tt.kind) {
            // Incisor: a spade, widening toward a thin straight cutting edge.
            T2_INC -> { out[0] = W * 0.5f * (0.74f + 0.26f * hn); out[1] = T * 0.5f * (1f - 0.8f * hn.pow(1.4f)) + 0.03f }
            // Canine: the longest crown, tapering to a single pointed cusp.
            T2_CAN -> { out[0] = W * 0.5f * (0.86f + 0.14f * sin(T2PI * hn * 0.8f)) * (1f - 0.78f * t2sm((hn - 0.45f) / 0.55f)); out[1] = T * 0.5f * (1f - 0.5f * hn.pow(1.6f)) }
            // Premolars and molars: bulging, boxy crowns.
            else -> { val b = sin(T2PI * min(1f, hn * 0.9f + 0.1f)); out[0] = W * 0.5f * (0.8f + 0.2f * b); out[1] = T * 0.5f * (0.82f + 0.18f * b) }
        }
    }
    val rim = FloatArray(2); side(1f, rim)
    val cx0 = FloatArray(4); val cz0 = FloatArray(4); val ca = FloatArray(4); var nc = 0
    if (tt.kind == T2_MOL) {   // four cusps (mesio/disto, buccal/lingual)
        cx0[0] = 0.45f * rim[0]; cz0[0] = 0.42f * rim[1]; ca[0] = 1f
        cx0[1] = -0.45f * rim[0]; cz0[1] = 0.42f * rim[1]; ca[1] = 0.95f
        cx0[2] = 0.45f * rim[0]; cz0[2] = -0.42f * rim[1]; ca[2] = 0.9f
        cx0[3] = -0.45f * rim[0]; cz0[3] = -0.42f * rim[1]; ca[3] = 0.85f; nc = 4
    } else if (tt.kind == T2_PREM) {   // a buccal and a (lower) lingual cusp
        cx0[0] = 0f; cz0[0] = 0.45f * rim[1]; ca[0] = 1f
        cx0[1] = 0f; cz0[1] = -0.45f * rim[1]; ca[1] = 0.75f; nc = 2
    }
    val sig = 0.34f * min(rim[0], rim[1]).coerceAtLeast(0.05f)
    val tmp = FloatArray(2)
    surf(12, 16, T2_ENAMEL, 1f, { i -> if (i <= 2) T2_ENAMEL_NECK else T2_ENAMEL }) { u, v ->
        val ph = 2f * T2PI * v; val c = cos(ph); val s = sin(ph)
        val cx = sign(c) * abs(c).pow(2f / ex); val cz = sign(s) * abs(s).pow(2f / ex)
        val lx: Float; val lz: Float; val h: Float
        if (u <= 0.7f) {
            val hn = u / 0.7f; side(hn, tmp); lx = cx * tmp[0]; lz = cz * tmp[1]; h = H * hn
        } else {
            val t = (u - 0.7f) / 0.3f
            lx = cx * rim[0] * (1f - t); lz = cz * rim[1] * (1f - t)
            h = when (tt.kind) {
                T2_INC -> H + 0.03f * sin(T2PI * t)
                T2_CAN -> H + 0.2f * H * (1f - (1f - t) * (1f - t))
                else -> {
                    var b = 0f
                    for (q in 0 until nc) { val dx = lx - cx0[q]; val dz = lz - cz0[q]; b += ca[q] * 0.16f * H * exp(-(dx * dx + dz * dz) / (sig * sig)) }
                    H + sin(T2PI * 0.5f * t) * (b - 0.07f * H)
                }
            }
        }
        base + e1 * lx + e2 * lz + e3 * h
    }
}

/** Mid-line height of the tongue's dorsum along the mouth. */
private fun t2TongueMid(z: Float): Float = when {
    z < 3.2f -> -1.5f + 0.38f * t2sm((z - 1.35f) / 1.85f)
    z < 5.8f -> -1.12f
    else -> -1.12f - 2.18f * ((z - 5.8f) / 4.1f).coerceIn(0f, 1f).pow(1.5f)
}
private fun t2TongueHalf(z: Float): Float =
    if (z < 8f) 2.25f * min(1f, sqrt(max(0.02f, z - 1.3f) / 2f)) else 2.25f - 0.5f * ((z - 8f) / 1.9f).coerceIn(0f, 1f)
private fun t2TongueY(xn: Float, z: Float): Float {
    val hw = t2TongueHalf(z); val a = abs(xn)
    return if (a <= 1f) t2TongueMid(z) - 0.5f * a * a - (if (z < 6.6f) 0.07f * exp(-(a * hw / 0.13f) * (a * hw / 0.13f)) else 0f)
    else t2TongueMid(z) - 0.5f - 1.9f * (a - 1f) / 0.3f
}

private fun StereoBodyRenderer.t2MouthMesh(i: Int): TriMesh = t2Get("mouth$i") {
    val g = T2Geo()
    val upper = T2Arch(0.55f, 0.52f); val lower = T2Arch(0.85f, 0.58f)
    // ---- the two dental arches: 14 crowns each (incisors, canines, premolars, first and second
    //      molars), each set in a gum ridge with interdental papillae.
    for (arch in 0..1) {
        val up = arch == 0
        val tab = if (up) upper else lower
        val specs = if (up) T2_UPPER else T2_LOWER
        val vdir = if (up) -1f else 1f
        val necks = ArrayList<Pair<Float, T2V>>()      // (signed arc position, gum-line point)
        val labs = ArrayList<Pair<T2V, T2V>>()
        for (sd in SIGNS) {
            var s = 0f
            for (tt in specs) {
                val sc = s + tt.w * 0.5f; s += tt.w
                val x = tab.x(sc); val z = tab.z(x)
                val tl = t2v(sd, 0f, 2f * tab.k * x).unit()
                val lab = t2v(sd * 2f * tab.k * x, 0f, -1f).unit()
                val tilt = when (tt.kind) { T2_INC -> if (up) 0.24f else 0.12f; T2_CAN -> 0.1f; else -> 0f }
                val occ = t2v(0f, vdir, 0f) * cos(tilt) + lab * sin(tilt)
                val yOcc = if (up) 1.35f else -1.35f + 0.12f * (z - 0.85f)
                val tip = t2v(sd * x, yOcc, z)
                val base = tip - occ * tt.h
                g.crown(base, tl, lab, occ, tt)
                necks.add(sd * sc to (base - occ * 0.16f))
                labs.add(base to lab * (tt.t * 0.42f))
            }
        }
        necks.sortBy { it.first }
        val gum = ArrayList<T2V>()
        for (k in 0 until necks.size - 1) {
            val a = necks[k].second; val b = necks[k + 1].second
            for (q in 0 until 3) gum.add(a + (b - a) * (q / 3f))
            // interdental papilla between neighbouring crowns, labial and lingual
            val mid = (a + b) * 0.5f
            val tan = (b - a).unit(); val nrm = t2v(-tan.z, 0f, tan.x).unit()
            for (sgn in SIGNS) g.ellAxis(mid + nrm * (0.36f * sgn) - t2v(0f, vdir, 0f) * 0.26f, t2v(0f, 1f, 0f), 0.17f, 0.3f, 0.12f, T2_GUM, 1f, 5, 8, tan)
        }
        gum.add(necks.last().second)
        g.path(gum, { 0.56f }, T2_GUM, 1f, 9, true)
    }
    // ---- hard palate: a vault over the upper arch; transverse rugae on its front third, a median
    //      raphe and the incisive papilla behind the central incisors.
    val zF = 1.25f; val zB = 6.3f
    fun palHalf(z: Float) = max(0.45f, sqrt(max(0f, (z - upper.z0) / upper.k)) - 0.62f)
    fun palY(u: Float, z: Float): Float {
        val edge = 2.62f; val apex = 2.75f + 0.95f * t2sm((z - zF) / 2.2f)
        return edge + (apex - edge) * (1f - u * u).coerceAtLeast(0f).pow(0.6f)
    }
    g.surf(12, 16, T2_PALATE) { v, u -> val z = zF + (zB - zF) * v; val uu = u * 2f - 1f; t2v(uu * palHalf(z), palY(uu, z), z) }
    for (zr in floatArrayOf(1.62f, 2.08f, 2.54f, 3.0f)) for (sd in SIGNS) {
        val pts = ArrayList<T2V>()
        for (q in 0..7) { val xx = 0.12f + q / 7f * (palHalf(zr) * 0.78f - 0.12f); val z = zr + 0.08f * sin(xx * 4f); pts.add(t2v(sd * xx, palY(xx / palHalf(z), z) - 0.05f, z)) }
        g.path(pts, { t -> 0.075f * (1f - 0.4f * t) }, T2_RUGA, 1f, 5, true)
    }
    g.path((0..12).map { q -> val z = 1.4f + q / 12f * 4.7f; t2v(0f, palY(0f, z) - 0.03f, z) }, { 0.045f }, T2_RUGA, 1f, 5, true)
    g.ell(t2v(0f, palY(0f, 1.35f) - 0.06f, 1.42f), t2v(0.22f, 0f, 0f), t2v(0f, 0.1f, 0f), t2v(0f, 0f, 0.3f), T2_RUGA)
    // ---- soft palate, sloping down and back to its arched free edge, with the uvula at its midline.
    val spHalf = { z: Float -> palHalf(zB) + (2.0f - palHalf(zB)) * ((z - zB) / 2.7f) }
    g.surf(8, 16, T2_SOFT_PALATE) { v, u ->
        val z = zB + 2.7f * v; val uu = u * 2f - 1f
        val ym = palY(0f, zB) + (2.45f - palY(0f, zB)) * v
        val edge = palY(1f, zB) * (1f - v) + 1.9f * v
        t2v(uu * spHalf(z), edge + (ym - edge) * (1f - uu * uu).pow(0.7f), z)
    }
    g.path(listOf(t2v(0f, 2.5f, 8.95f), t2v(0f, 1.95f, 9.05f), t2v(0f, 1.45f, 9.15f)), { t -> 0.26f + 0.08f * t }, T2_SOFT_PALATE, 1f, 8, true)
    // ---- the fauces: palatoglossal (front) and palatopharyngeal (back) arches with the palatine
    //      tonsil between them, framing the throat.
    for (sd in SIGNS) {
        g.path(listOf(t2v(sd * 1.95f, 2.0f, 8.7f), t2v(sd * 2.25f, 1.0f, 8.35f), t2v(sd * 2.4f, 0f, 8.0f), t2v(sd * 2.35f, -1.1f, 7.7f)), { 0.26f }, T2_SOFT_PALATE, 1f, 7, true)
        g.path(listOf(t2v(sd * 1.55f, 2.2f, 9.1f), t2v(sd * 2.1f, 0.9f, 9.8f), t2v(sd * 2.55f, -0.6f, 10.4f), t2v(sd * 2.7f, -2.4f, 10.9f)), { 0.26f }, T2_SOFT_PALATE, 1f, 7, true)
        g.ell(t2v(sd * 2.62f, 0.15f, 9.2f), t2v(0.45f, 0f, 0f), t2v(0f, 1.0f, 0f), t2v(0f, 0f, 0.6f), T2_TONSIL)
    }
    // ---- the tongue filling the floor of the mouth inside the lower arch: a convex dorsum with a
    //      median groove, the V of circumvallate papillae at the back of its oral part, fungiform
    //      papillae dotted over the front, the root curving down into the pharynx.
    g.surf(18, 30, T2_TONGUE) { v, u ->
        val z = 1.3f + 8.6f * v; val xn = (u * 2f - 1f) * 1.3f; val hw = t2TongueHalf(z)
        val a = abs(xn); val x = if (a <= 1f) xn * hw else sign(xn) * hw * (1f + 0.06f * (a - 1f) / 0.3f)
        t2v(x, t2TongueY(xn, z), z)
    }
    g.ell(t2v(0f, -1.95f, 1.52f), t2v(0.95f, 0f, 0f), t2v(0f, 0.5f, 0f), t2v(0f, 0f, 0.45f), T2_TONGUE)
    for (q in -4..4) {
        val side = q / 4f; val z = 6.6f - 0.85f * abs(side); val x = 1.45f * side
        g.ball(t2v(x, t2TongueY(x / t2TongueHalf(z), z) + 0.06f, z), 0.17f, T2_PAPILLA, 1f, 5, 8)
    }
    run {
        val rnd = java.util.Random(7L)
        repeat(46) {
            val z = 1.8f + rnd.nextFloat() * 4.2f; val xn = (rnd.nextFloat() * 2f - 1f) * 0.85f
            g.ball(t2v(xn * t2TongueHalf(z), t2TongueY(xn, z) + 0.02f, z), 0.055f, T2_FUNGIFORM, 1f, 3, 5)
        }
    }
    // ---- the laryngeal inlet behind the epiglottis: aryepiglottic folds, arytenoid swellings,
    //      the false (vestibular) cords and, deepest, the pearly vocal folds meeting in front.
    for (sd in SIGNS) {
        g.path(listOf(t2v(sd * 0.85f, -2.95f, 10.35f), t2v(sd * 0.8f, -3.1f, 11.0f), t2v(sd * 0.55f, -3.25f, 11.7f), t2v(sd * 0.3f, -3.35f, 12.1f)), { 0.2f }, T2_LARYNX, 1f, 7, true)
        g.ball(t2v(sd * 0.36f, -3.2f, 12.05f), 0.25f, T2_LARYNX)
        g.tube(t2v(sd * 0.25f, -3.72f, 10.75f), t2v(sd * 0.75f, -3.72f, 11.8f), 0.13f, 0.13f, T2_LARYNX)
        g.tube(t2v(sd * 0.05f, -4.05f, 10.7f), t2v(sd * 0.5f, -4.05f, 11.85f), 0.1f, 0.1f, T2_VOCAL_CORD)
    }
    // ---- the lips: an upper lip with its cupid's bow and a lower lip, meeting at the commissures,
    //      set in the skin of the face (philtral columns above the upper lip).
    for (upLip in 0..1) {
        val pts = ArrayList<T2V>()
        for (q in 0..40) {
            val xn = q / 40f * 2f - 1f; val x = xn * 3.05f
            val env = (1f - xn * xn).coerceAtLeast(0f).pow(0.55f)
            val bow = if (upLip == 0) -0.2f * exp(-(x / 0.24f) * (x / 0.24f)) + 0.1f * exp(-((abs(x) - 0.5f) / 0.26f) * ((abs(x) - 0.5f) / 0.26f)) else 0f
            val y = if (upLip == 0) 1.95f * env + bow else -2.05f * env
            pts.add(t2v(x, y, -0.2f - 0.32f * env))
        }
        g.path(pts, { t -> 0.2f + 0.36f * sqrt(sin(T2PI * t).coerceAtLeast(0f)) }, T2_LIP, 1f, 8, true)
    }
    for (sd in SIGNS) g.ball(t2v(sd * 3.1f, 0f, -0.18f), 0.24f, T2_LIP)
    g.surf(8, 40, T2_SKIN) { t, u ->
        val a = u * 2f * T2PI; val c = cos(a); val s = sin(a)
        val rx = 3.45f + (6.8f - 3.45f) * t; val ry = (if (s > 0f) 2.45f else 2.5f) + (5.8f - 2.45f) * t
        t2v(c * rx, s * ry, -0.3f + 0.9f * t * t)
    }
    for (sd in SIGNS) g.path(listOf(t2v(sd * 0.36f, 2.3f, -0.52f), t2v(sd * 0.3f, 3.2f, -0.5f), t2v(sd * 0.27f, 4.0f, -0.42f)), { 0.11f }, T2_SKIN, 1f, 6, true)
    // ---- beyond the larynx the pharynx narrows into the oesophagus: a collapsed muscular tube
    //      whose mucosa lies in longitudinal folds
    g.surf(10, 48, T2_LARYNX) { v, u ->
        val z = 12.4f + 3.4f * v; val a = u * 2f * T2PI
        val r = (3.3f - 1.6f * t2sm(v)) * (1f + 0.1f * cos(a * 12f))
        t2v(cos(a) * r, sin(a) * r * 0.9f - 0.3f, z)
    }
    t2Bake(g, i, 3f, 9f)
}

private fun StereoBodyRenderer.t2TongueWave(): TriMesh = t2Get("tonguewave") {
    val g = T2Geo(); g.ell(t2v(0f, 0f, 0f), t2v(1.45f, 0f, 0f), t2v(0f, 0.5f, 0f), t2v(0f, 0f, 1.0f), T2_TONGUE, 1f, 8, 14); TriMesh(g.baked())
}

/** The epiglottis: a leaf of elastic cartilage, stalk at the hinge (origin), free tip up (+y). */
private fun StereoBodyRenderer.t2Epiglottis(): TriMesh = t2Get("epiglottis") {
    val g = T2Geo(); val len = 2.0f
    g.surf(10, 12, T2_EPIGLOTTIS) { u, v ->
        val vv = v * 2f - 1f
        var w = 0.95f * sin(T2PI * (0.15f + 0.85f * u) * 0.62f)
        if (u > 0.72f) w *= sqrt(max(0f, 1f - ((u - 0.72f) / 0.29f).pow(2)))
        t2v(vv * w, u * len, 0.14f * vv * vv - 0.26f * u * u * u)
    }
    TriMesh(g.baked())
}

/** Tour II stop 1: the mouth from inside the gape — teeth, gums, palate, tongue, fauces, epiglottis — swallowing every 9 s. */
internal fun StereoBodyRenderer.drawMouth(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    drawSphereAt(n.x, n.y + 2.5f, n.z + 14f, 5f, 5f, 5f, COL_BAY, COL_LAMP, 0.35f, 0f, 0f, 1f, 0f, sphere, 0f, 0.8f)   // daylight behind the face
    t2Open(i, seconds) {
        t2DrawWorld(t2MouthMesh(i))
        // The swallow: the tongue presses up and rolls a wave backward, then the epiglottis folds
        // back over the laryngeal inlet while the bolus passes, and springs up again.
        val cyc = (seconds % 9f) / 9f
        val wave = cyc / 0.22f
        if (wave < 1f) {
            val zb = 2f + 6.5f * t2sm(wave); val env = sin(T2PI * wave)
            val fr = t2Frame(i, zb); t2Model(fr, 0f, t2TongueMid(zb) - 0.42f + 0.4f * env); t2Draw(t2TongueWave())
        }
        val tilt = t2sm((cyc - 0.14f) / 0.1f) * (1f - t2sm((cyc - 0.36f) / 0.14f))
        val fe = t2Frame(i, 10.15f); t2Model(fe, 0f, -3.25f)
        Matrix.rotateM(model, 0, 8f + 92f * tilt, 1f, 0f, 0f)
        t2Draw(t2Epiglottis())
    }
}

// ============================================================================== stop 1: GUT
// 0.8 mm a unit (Mote 1.2 mm). A carpet of ~1300 leaf-shaped villi 0.7-1.1 mm tall and about
// 0.2 mm wide rising from the wall between crypt openings; the nearest few are drawn see-through
// with their lacteal and capillary loop; lumps of chyme drift down the lumen.

private val T2_CORE = arrayOf(
    floatArrayOf(-0.15f, 2.6f), floatArrayOf(0.35f, 4.0f), floatArrayOf(3.0f, 3.0f),
    floatArrayOf(-3.0f, 4.4f), floatArrayOf(-0.75f, 5.6f), floatArrayOf(2.45f, 5.8f)
)

private fun t2CoreSlot(ang: Float, z: Float): Boolean {
    for (c in T2_CORE) {
        var d = abs(ang - c[0]) % (2f * T2PI); if (d > T2PI) d = 2f * T2PI - d
        if (d < 0.1f && abs(z - c[1]) < 0.32f) return true
    }
    return false
}

private fun T2Geo.villus(base: T2V, axis: T2V, maj: T2V, h: Float, ra: Float, rb: Float) {
    val ts = floatArrayOf(0f, 0.3f, 0.62f, 0.86f, 0.95f, 1f)
    val sc = floatArrayOf(1.1f, 1f, 0.95f, 0.9f, 0.62f, 0f)
    val mn = axis cross maj
    val sides = 6; val w = sides + 1
    val P = FloatArray(6 * w * 3)
    for (i in 0 until 6) {
        val c = base + axis * (h * ts[i])
        for (j in 0..sides) {
            val a = 2f * T2PI * j / sides; val k = (i * w + j) * 3
            val ox = maj * (cos(a) * ra * sc[i]) + mn * (sin(a) * rb * sc[i])
            P[k] = c.x + ox.x; P[k + 1] = c.y + ox.y; P[k + 2] = c.z + ox.z
        }
    }
    grid(5, sides, P, T2_VILLUS, 1f) { i -> if (i >= 4) T2_VILLUS_TIP else T2_VILLUS }
}

private fun StereoBodyRenderer.t2GutMesh(i: Int, half: Int): TriMesh = t2Get("gut$i.$half") {
    val g = T2Geo(); val rnd = java.util.Random(101L + half * 17L)
    val rail = t2Rail(i); val fr = FloatArray(13)
    val nAround = 54; val dzr = 0.42f
    var row = 0; var z = -4.2f
    while (z <= 9.5f) {
        rail.at(z, fr); val p = fr[12]
        for (k in 0 until nAround) {
            if ((row + k) % 2 != half) { rnd.nextFloat(); rnd.nextFloat(); rnd.nextFloat(); rnd.nextFloat(); rnd.nextFloat(); continue }
            val ang = 2f * T2PI * (k + 0.5f * (row % 2) + (rnd.nextFloat() - 0.5f) * 0.4f) / nAround
            val zz = z + (rnd.nextFloat() - 0.5f) * 0.18f
            val h = 0.85f + rnd.nextFloat() * 0.45f
            val tilt = (rnd.nextFloat() - 0.5f) * 0.36f
            val q = rnd.nextFloat() * T2PI
            if (t2CoreSlot(ang, zz)) continue
            val ca = cos(ang); val sa = sin(ang)
            val rw = t2WallR(p, ang) - 0.05f
            val inward = t2v(-ca, -sa, 0f); val tang = t2v(-sa, ca, 0f)
            val axis = (inward + tang * tilt + t2v(0f, 0f, 0.14f)).unit()
            val majRaw = tang * cos(q) + t2v(0f, 0f, 1f) * sin(q)
            val maj = (majRaw - axis * (majRaw dot axis)).unit()
            g.villus(t2v(ca * rw, sa * rw, zz), axis, maj, h, 0.125f, 0.09f)
            // a crypt of Lieberkühn opening between villus bases
            val ang2 = ang + T2PI / nAround; val rw2 = t2WallR(p, ang2) - 0.03f
            g.disc(t2v(cos(ang2) * rw2, sin(ang2) * rw2, zz + dzr * 0.5f), t2v(-cos(ang2), -sin(ang2), 0f), 0.07f, T2_CRYPT, 1f, 6)
        }
        z += dzr; row++
    }
    t2Bake(g, i, 30f, 40f)
}

/** A villus for the lit shader: base at z = 0, tip at z = 1, radius 1 (scaled per villus). */
private fun StereoBodyRenderer.t2VillusMesh(): LitMesh = t2Get("villusmesh") {
    ParamMesh(12, 10) { u, v, out ->
        val r = if (u < 0.86f) 1.08f - 0.18f * u else 0.92f * sqrt(max(0f, 1f - ((u - 0.86f) / 0.14f).pow(2)))
        val a = v * 2f * T2PI
        out[0] = cos(a) * r; out[1] = sin(a) * r; out[2] = u
    }
}

/** Tour II stop 2: the villous carpet of the small intestine, the nearest villi opened to show the lacteal and capillaries. */
internal fun StereoBodyRenderer.drawGut(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2DrawWorld(t2GutMesh(i, 0))
    if (quality == 0) t2DrawWorld(t2GutMesh(i, 1))
    val vm = t2VillusMesh()
    t2LinesBegin()
    for ((k, c) in T2_CORE.withIndex()) {
        val ang = c[0]; val z = c[1]
        val fr = t2Frame(i, z)
        val rw = t2WallR(fr[12], ang) - 0.05f
        val ca = cos(ang); val sa = sin(ang)
        val bx = fr[0] + (fr[6] * ca + fr[9] * sa) * rw; val by = fr[1] + (fr[7] * ca + fr[10] * sa) * rw; val bz = fr[2] + (fr[8] * ca + fr[11] * sa) * rw
        // inward, swaying with the flow
        val sw = 0.14f * sin(seconds * 1.1f + k * 1.9f); val sl = 0.1f + 0.06f * sin(seconds * 0.8f + k)
        val ix = -(fr[6] * ca + fr[9] * sa); val iy = -(fr[7] * ca + fr[10] * sa); val iz = -(fr[8] * ca + fr[11] * sa)
        val tx = -fr[6] * sa + fr[9] * ca; val ty = -fr[7] * sa + fr[10] * ca; val tz = -fr[8] * sa + fr[11] * ca
        var ax = ix + tx * sw + fr[3] * sl; var ay = iy + ty * sw + fr[4] * sl; var az = iz + tz * sw + fr[5] * sl
        val al = sqrt(ax * ax + ay * ay + az * az); ax /= al; ay /= al; az /= al
        val h = 1.1f + 0.12f * (k % 3)
        // the lacteal (lymph, pale) up the core; the capillary loop just under the surface
        drawStrut(bx + ax * 0.05f, by + ay * 0.05f, bz + az * 0.05f, bx + ax * h * 0.84f, by + ay * h * 0.84f, bz + az * h * 0.84f, 0.035f, T2_LACTEAL, T2_LACTEAL, 0.5f)
        var px = 0f; var py = 0f; var pz = 0f
        for (q in 0..36) {
            val t = q / 36f; val up = if (t < 0.5f) t * 2f else (1f - t) * 2f
            val a = t * 6f * T2PI + (if (t < 0.5f) 0f else T2PI)
            val rr = 0.085f
            val hx = bx + ax * (h * 0.88f * up + 0.04f) + (tx * cos(a) + (ay * tz - az * ty) * sin(a)) * rr
            val hy = by + ay * (h * 0.88f * up + 0.04f) + (ty * cos(a) + (az * tx - ax * tz) * sin(a)) * rr
            val hz = bz + az * (h * 0.88f * up + 0.04f) + (tz * cos(a) + (ax * ty - ay * tx) * sin(a)) * rr
            if (q > 0) t2Seg(px, py, pz, hx, hy, hz, T2_ARTERIOLE, 1f)
            px = hx; py = hy; pz = hz
        }
        t2Q[0] = bx; t2Q[1] = by; t2Q[2] = bz
        t2Basis(t2Q, ax, ay, az, tx, ty, tz, 0.14f, 0.1f, h, vm, T2_VILLUS, T2_VILLUS_TIP, 0.42f, 0.05f)
    }
    // mucus strands drifting over the villus tips
    for (m in 0 until 4) {
        var px = 0f; var py = 0f; var pz = 0f
        for (q in 0..12) {
            val z = -2f + q * 0.8f + ((seconds * 0.4f + m * 3f) % 4f)
            val a = m * 1.7f + 0.3f * sin(q * 0.7f + seconds * 0.5f)
            val fr = t2Frame(i, z); val r = 2.05f + 0.15f * sin(q * 1.3f + m)
            val w = t2W(fr, cos(a) * r, sin(a) * r)
            if (q > 0) t2Seg(px, py, pz, w[0], w[1], w[2], T2_LACTEAL, 0.22f)
            px = w[0]; py = w[1]; pz = w[2]
        }
    }
    t2LinesEnd(3f)
    // chyme: irregular lumps of partly digested food carried down the lumen
    for (k in 0 until (if (quality == 0) 10 else 5)) {
        val z = ((seconds * 0.45f + k * 1.37f) % 14f) - 4f
        val a = k * 2.1f + 0.4f; val r = 1.1f + 0.35f * sin(k * 1.7f)
        val fr = t2Frame(i, z)
        val s = 0.1f + 0.06f * ((k * 7) % 3)
        t2Blob(fr, cos(a) * r, sin(a) * r, s * 1.3f, s * 0.8f, s, T2_CHYME, T2_CHYME, 1f, 0.05f)
    }
}

// ============================================================================ stop 2: PHAGE
// 80 nm a unit (Mote 120 nm). Overhead, the underside of one E. coli: a rod 2 µm x 1 µm (25 x 12.5
// units) with a translucent outer membrane and peritrichous flagella trailing in lazy left-handed
// helices. T4 phages (prolate icosahedral head 111 x 86 nm, contractile tail 100 nm, hexagonal
// baseplate, six kinked long tail fibres) sit on it: one lands, contracts its sheath and injects;
// the rest are at other stages. Off to port another infected cell fills with progeny and bursts.

private fun StereoBodyRenderer.t2PhageHead(): LitMesh = t2Get("phagehead") {
    val g = T2Geo(); val e = 0.16f; val sc = 0.6f
    val top = t2v(0f, 0f, (1f + e) * sc); val bot = t2v(0f, 0f, -(1f + e) * sc)
    val up = Array(5) { k -> val a = 2f * T2PI * k / 5f; t2v(0.894f * cos(a) * sc, 0.894f * sin(a) * sc, (0.447f + e) * sc) }
    val lo = Array(5) { k -> val a = 2f * T2PI * (k + 0.5f) / 5f; t2v(0.894f * cos(a) * sc, 0.894f * sin(a) * sc, -(0.447f + e) * sc) }
    for (k in 0 until 5) {
        val k1 = (k + 1) % 5
        g.tri(top, up[k], up[k1], T2_PHAGE_HEAD); g.tri(up[k], lo[k], up[k1], T2_PHAGE_HEAD)
        g.tri(up[k1], lo[k], lo[k1], T2_PHAGE_HEAD); g.tri(bot, lo[k1], lo[k], T2_PHAGE_HEAD)
    }
    T2Lit(g.lit())
}

private fun StereoBodyRenderer.t2Sheath(): LitMesh = t2Get("sheath") {
    ParamMesh(48, 12) { u, v, out ->
        val r = 1f - 0.14f * (0.5f + 0.5f * cos(u * 2f * T2PI * 12f)); val a = v * 2f * T2PI
        out[0] = cos(a) * r; out[1] = sin(a) * r; out[2] = u * 2f - 1f
    }
}

/** A closed hexagonal prism, circumradius 1, z from -1 to 1 (baseplate, collar). */
private fun StereoBodyRenderer.t2Hex(): LitMesh = t2Get("hex") {
    ParamMesh(12, 24) { u, v, out ->
        val a = v * 2f * T2PI; val seg = (a % (T2PI / 3f)) - T2PI / 6f; val rh = cos(T2PI / 6f) / cos(seg)
        val r: Float; val z: Float
        if (u < 0.25f) { r = rh * u / 0.25f; z = -1f } else if (u < 0.75f) { r = rh; z = (u - 0.25f) / 0.5f * 2f - 1f } else { r = rh * (1f - u) / 0.25f; z = 1f }
        out[0] = cos(a) * r; out[1] = sin(a) * r; out[2] = z
    }
}

private fun StereoBodyRenderer.t2Capsule(): LitMesh = t2Get("capsule50") { ParamMesh.capsule(0.5f) }

/** A left-handed flagellar filament: helix radius 2.2 (0.35 µm), 1.5 turns over 40 units (3.2 µm), 20 nm thick. */
private fun StereoBodyRenderer.t2Flagellum(): LitMesh = t2Get("flagellum") {
    ParamMesh(150, 6) { u, v, out ->
        val rh = 2.2f * min(1f, u / 0.06f); val a = -u * 1.5f * 2f * T2PI; val b = v * 2f * T2PI
        out[0] = rh * cos(a) + 0.13f * cos(b); out[1] = rh * sin(a) + 0.13f * sin(b); out[2] = 40f * u
    }
}

/** A small curved plate of cell wall (for the burst). */
private fun StereoBodyRenderer.t2WallPlate(): LitMesh = t2Get("wallplate") {
    ParamMesh(5, 7) { u, v, out ->
        val th = (u - 0.5f) * 0.7f; val ph = (v - 0.5f) * 0.9f
        out[0] = sin(ph) * cos(th); out[1] = sin(th); out[2] = cos(ph) * cos(th)
    }
}

/** A complete phage (head, collar, sheath, baseplate) along +z from the baseplate at z = 0. */
private fun T2Geo.miniPhage(c: T2V, ax: T2V, col: FloatArray) {
    val a = ax.unit(); val e1 = t2perp(a); val e2 = a cross e1
    tube(c, c + a * 1.25f, 0.13f, 0.13f, col, 1f, 6, false)
    ell(c + a * 0.04f, e1 * 0.33f, a * 0.07f, e2 * 0.33f, col, 1f, 4, 6)
    val hc = c + a * 2.05f
    val sc = 0.6f; val e = 0.16f
    val top = hc + a * ((1f + e) * sc); val bot = hc - a * ((1f + e) * sc)
    val up = Array(5) { k -> val q = 2f * T2PI * k / 5f; hc + (e1 * cos(q) + e2 * sin(q)) * (0.894f * sc) + a * ((0.447f + e) * sc) }
    val lo = Array(5) { k -> val q = 2f * T2PI * (k + 0.5f) / 5f; hc + (e1 * cos(q) + e2 * sin(q)) * (0.894f * sc) - a * ((0.447f + e) * sc) }
    for (k in 0 until 5) { val k1 = (k + 1) % 5; tri(top, up[k], up[k1], col); tri(up[k], lo[k], up[k1], col); tri(up[k1], lo[k], lo[k1], col); tri(bot, lo[k1], lo[k], col) }
}

private fun StereoBodyRenderer.t2MiniPhage(): LitMesh = t2Get("miniphage") {
    val g = T2Geo(); g.miniPhage(t2v(0f, 0f, -1f), t2v(0f, 0f, 1f), T2_PHAGE_HEAD); T2Lit(g.lit())
}

/** Progeny packed inside a host (host-local: axis +z, radius 6). */
private fun StereoBodyRenderer.t2Progeny(): LitMesh = t2Get("progeny") {
    val g = T2Geo(); val rnd = java.util.Random(23L)
    repeat(34) {
        val r = 3.8f * sqrt(rnd.nextFloat()); val a = rnd.nextFloat() * 2f * T2PI; val z = (rnd.nextFloat() * 2f - 1f) * 8.5f
        val d = t2v(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).unit()
        g.miniPhage(t2v(cos(a) * r, sin(a) * r, z) - d * 1f, d, T2_PHAGE_HEAD)
    }
    T2Lit(g.lit())
}

/**
 * One T4 at surface point P (outward normal n, reference e ⟂ n): baseplate [lift] off the surface,
 * sheath contracted by [contract] (1.25 -> 0.53 units, the tail tube then 0.72 into the envelope),
 * tail fibres from folded (0) to planted (1), head emptied by [empty], DNA [dna] of the way in.
 */
private fun StereoBodyRenderer.t2PhageAt(P: FloatArray, n: FloatArray, e: FloatArray, lift: Float, contract: Float, spread: Float, empty: Float, dna: Float, alpha: Float) {
    if (alpha < 0.02f) return
    val px = P[0]; val py = P[1]; val pz = P[2]; val nx = n[0]; val ny = n[1]; val nz = n[2]; val ex = e[0]; val ey = e[1]; val ez = e[2]
    val fx = ny * ez - nz * ey; val fy = nz * ex - nx * ez; val fz = nx * ey - ny * ex
    val q = FloatArray(3)
    fun at(d: Float): FloatArray { q[0] = px + nx * d; q[1] = py + ny * d; q[2] = pz + nz * d; return q }
    val ls = 1.25f - 0.72f * contract; val rs = 0.13f + 0.07f * contract
    t2Basis(at(lift + 0.07f), nx, ny, nz, ex, ey, ez, 0.33f, 0.33f, 0.06f, t2Hex(), T2_PHAGE_PLATE, COL_LAMP, alpha)
    t2Basis(at(lift + 0.14f + ls * 0.5f), nx, ny, nz, ex, ey, ez, rs, rs, ls * 0.5f, t2Sheath(), T2_PHAGE_TAIL, COL_LAMP, alpha)
    t2Basis(at(lift + 0.19f + ls), nx, ny, nz, ex, ey, ez, 0.2f, 0.2f, 0.05f, t2Hex(), T2_PHAGE_PLATE, COL_LAMP, alpha)
    t2Basis(at(lift + 0.24f + ls + 0.695f), nx, ny, nz, ex, ey, ez, 1f, 1f, 1f, t2PhageHead(), T2_PHAGE_HEAD, COL_LAMP, alpha * (1f - 0.6f * empty), 0.05f)
    if (contract > 0.01f) {   // the inner tail tube punched through the outer membrane
        val lt = 0.72f * contract
        t2Basis(at(lift - lt * 0.5f + 0.02f), nx, ny, nz, ex, ey, ez, 0.05f, 0.05f, lt * 0.5f, cylinder, T2_GTP_CAP, COL_LAMP, alpha, 0.3f)
    }
    // Six long tail fibres from the baseplate corners, kinked at the knee, their tips planted on the host.
    val bx = px + nx * (lift + 0.07f); val by = py + ny * (lift + 0.07f); val bz = pz + nz * (lift + 0.07f)
    for (k in 0 until 6) {
        val a = T2PI / 6f + k * T2PI / 3f
        val dx = ex * cos(a) + fx * sin(a); val dy = ey * cos(a) + fy * sin(a); val dz = ez * cos(a) + fz * sin(a)
        val rx = bx + dx * 0.33f; val ry = by + dy * 0.33f; val rz = bz + dz * 0.33f
        val kr = 0.5f + 0.35f * spread; val ku = 0.55f - 0.1f * spread
        val kx = rx + dx * kr + nx * ku; val ky = ry + dy * kr + ny * ku; val kz = rz + dz * kr + nz * ku
        val fr0 = 1.45f; val footX = px + dx * fr0; val footY = py + dy * fr0; val footZ = pz + dz * fr0
        val hx = kx + dx * 0.5f - nx * 0.55f; val hy = ky + dy * 0.5f - ny * 0.55f; val hz = kz + dz * 0.5f - nz * 0.55f
        val tx = hx + (footX - hx) * spread; val ty = hy + (footY - hy) * spread; val tz = hz + (footZ - hz) * spread
        t2Seg(rx, ry, rz, kx, ky, kz, T2_PHAGE_TAIL, 0.95f * alpha); t2Seg(kx, ky, kz, tx, ty, tz, T2_PHAGE_TAIL, 0.95f * alpha)
    }
    if (dna > 0.01f) {   // the genome running down the tube and into the cell
        val h = at(lift + 0.24f + ls + 0.695f); val hx0 = h[0]; val hy0 = h[1]; val hz0 = h[2]
        val d = at(-(0.72f * contract + 1.3f * dna))
        t2Seg(hx0, hy0, hz0, d[0], d[1], d[2], T2_DNA, 1f)
    }
}

/** Tour II stop 3: T4 phages on the E. coli overhead — landing, contracting, injecting — and a second host bursting. */
internal fun StereoBodyRenderer.drawPhage(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) { own ->
        val f0 = t2Rigid(i, 0f, FloatArray(13))
        val dx = f0[3]; val dy = f0[4]; val dz = f0[5]; val ux = f0[9]; val uy = f0[10]; val uz = f0[11]
        val cap = t2Capsule()
        // ---- host A overhead: cytoplasm and a translucent outer membrane 20 nm out
        val hc = t2W(f0, 0f, 9.6f, 3f).copyOf()
        t2Basis(hc, dx, dy, dz, ux, uy, uz, 12f, 12f, 12f, cap, T2_ECOLI, T2_ECOLI_OM, 1f, 0.05f)
        if (own) {   // flagella: long left-handed helices trailing from the body, turning (slowed ~200x)
            val fl = t2Flagellum()
            for ((k, s) in arrayOf(floatArrayOf(2.6f, 9f), floatArrayOf(3.5f, 12f), floatArrayOf(1.9f, 5f), floatArrayOf(4.3f, 7f), floatArrayOf(3.14f, 14.3f)).withIndex()) {
                val phi = s[0]; val zo = s[1]
                val lx = sin(phi) * 5.9f; val ly = 9.6f - cos(phi) * 5.9f
                val o = t2W(f0, lx, ly, zo).copyOf()
                val ax = sin(phi) * 0.3f; val ay = -cos(phi) * 0.3f
                var zx = f0[6] * ax + f0[9] * ay + dx; var zy = f0[7] * ax + f0[10] * ay + dy; var zz = f0[8] * ax + f0[11] * ay + dz
                val zl = sqrt(zx * zx + zy * zy + zz * zz); zx /= zl; zy /= zl; zz /= zl
                // e1 ⟂ axis, e2 = axis x e1; the helix turns counter-clockwise seen from its tip (a run)
                var e1x = uy * zz - uz * zy; var e1y = uz * zx - ux * zz; var e1z = ux * zy - uy * zx
                val el = sqrt(e1x * e1x + e1y * e1y + e1z * e1z).coerceAtLeast(1e-4f); e1x /= el; e1y /= el; e1z /= el
                val e2x = zy * e1z - zz * e1y; val e2y = zz * e1x - zx * e1z; val e2z = zx * e1y - zy * e1x
                val th = seconds * 3.1f + k * 1.3f; val c = cos(th); val sn = sin(th)
                t2Basis(o, zx, zy, zz, e1x * c + e2x * sn, e1y * c + e2y * sn, e1z * c + e2z * sn, 1f, 1f, 1f, fl, T2_FLAGELLUM, COL_LAMP, 1f, 0.1f)
            }
        }
        t2Basis(hc, dx, dy, dz, ux, uy, uz, 12.5f, 12.5f, 12.5f, cap, T2_ECOLI_OM, T2_ECOLI_OM, 0.28f, 0.1f)
        // ---- phages on host A: angle phi from the underside (+ toward starboard), along z
        t2LinesBegin()
        val P = FloatArray(3); val N = FloatArray(3); val E = FloatArray(3)
        fun site(phi: Float, z: Float, rad: Float = 6.25f) {
            val w = t2W(f0, sin(phi) * rad, 9.6f - cos(phi) * rad, z); P[0] = w[0]; P[1] = w[1]; P[2] = w[2]
            val lx = sin(phi); val ly = -cos(phi)
            N[0] = f0[6] * lx + f0[9] * ly; N[1] = f0[7] * lx + f0[10] * ly; N[2] = f0[8] * lx + f0[11] * ly
            E[0] = dx; E[1] = dy; E[2] = dz
        }
        site(0.62f, 4.8f); t2PhageAt(P, N, E, 0f, 1f, 1f, 1f, 0f, 1f)            // spent: contracted, head empty
        site(0.95f, -0.8f); t2PhageAt(P, N, E, 0f, 0f, 1f, 0f, 0f, 1f)           // fibres planted, sheath still extended
        site(-1.05f, 7.2f); t2PhageAt(P, N, E, 0f, 1f, 1f, 1f, 0f, 1f)
        site(0.78f, 10.5f); t2PhageAt(P, N, E, 0f, 0f, 1f, 0f, 0f, 1f)
        // the featured one: lands, plants its fibres, contracts, injects (a 20 s cycle)
        run {
            val t = (seconds / 20f) % 1f
            val lift = 3f * (1f - t2sm(t / 0.25f)); val spread = t2sm((t - 0.1f) / 0.15f)
            val con = t2sm((t - 0.36f) / 0.08f); val dna = t2sm((t - 0.46f) / 0.3f); val emp = t2sm((t - 0.5f) / 0.3f)
            val a = t2sm(t / 0.04f) * (1f - t2sm((t - 0.94f) / 0.06f))
            site(-0.66f, 2.2f); t2PhageAt(P, N, E, lift, con, spread, emp, dna, a)
        }
        // one still arriving, tail first, from the port side
        run {
            val t = (seconds / 28f) % 1f
            val lift = 6f * (1f - t2sm(t / 0.6f)); val spread = t2sm((t - 0.4f) / 0.2f)
            val a = t2sm(t / 0.05f) * (1f - t2sm((t - 0.94f) / 0.06f))
            site(-1.3f, 9.2f); t2PhageAt(P, N, E, lift, 0f, spread, 0f, 0f, a)
        }
        t2LinesEnd(2f)
        if (!own) return@t2Open
        // ---- host B to port: filling with progeny, bulging, bursting (lysis clock / the "lysis" cue)
        val ph = lysisClock / LYSIS_PERIOD
        val bl = FloatArray(3); run { val w = t2W(f0, -12f, -2f, 19f); bl[0] = w[0]; bl[1] = w[1]; bl[2] = w[2] }
        var ax = f0[6] * 0.35f + f0[9] * 0.1f + dx; var ay = f0[7] * 0.35f + f0[10] * 0.1f + dy; var az = f0[8] * 0.35f + f0[11] * 0.1f + dz
        val al = sqrt(ax * ax + ay * ay + az * az); ax /= al; ay /= al; az /= al
        if (ph < 0.62f) {
            val vis = t2sm((ph - 0.1f) / 0.3f); val swell = 1f + 0.1f * t2sm((ph - 0.35f) / 0.27f)
            t2Basis(bl, ax, ay, az, ux, uy, uz, 1f, 1f, 1f, t2Progeny(), T2_PHAGE_HEAD, COL_LAMP, 1f, 0.1f * vis)
            t2Basis(bl, ax, ay, az, ux, uy, uz, 12f * swell, 12f * swell, 12f, cap, T2_ECOLI, T2_ECOLI_OM, 1f - 0.62f * vis, 0.05f)
            t2Basis(bl, ax, ay, az, ux, uy, uz, 12.5f * swell, 12.5f * swell, 12.5f, cap, T2_ECOLI_OM, T2_ECOLI_OM, 0.28f, 0.1f + 0.5f * t2sm((ph - 0.5f) / 0.12f))
        } else {
            val tb = (ph - 0.62f) / 0.38f
            val plate = t2WallPlate(); val mini = t2MiniPhage()
            val rnd = java.util.Random(5L)
            val q = FloatArray(3)
            for (k in 0 until 10) {
                var vx = rnd.nextFloat() - 0.5f; var vy = rnd.nextFloat() - 0.5f; var vz = rnd.nextFloat() - 0.5f
                val vl = sqrt(vx * vx + vy * vy + vz * vz); vx /= vl; vy /= vl; vz /= vl
                val d = 6f + 3.5f * tb
                q[0] = bl[0] + vx * d; q[1] = bl[1] + vy * d; q[2] = bl[2] + vz * d
                val sp = tb * 2f + k
                t2Basis(q, vx, vy, vz, cos(sp) * ux + sin(sp) * dx, cos(sp) * uy + sin(sp) * dy, cos(sp) * uz + sin(sp) * dz, 6.2f, 6.2f, 6.2f, plate, T2_ECOLI, T2_ECOLI_OM, 1f - tb)
            }
            for (k in 0 until (if (quality == 0) 18 else 8)) {
                var vx = rnd.nextFloat() - 0.5f; var vy = rnd.nextFloat() - 0.5f; var vz = rnd.nextFloat() - 0.5f
                val vl = sqrt(vx * vx + vy * vy + vz * vz); vx /= vl; vy /= vl; vz /= vl
                val d = 1.5f + 3f * rnd.nextFloat() + 6f * tb
                q[0] = bl[0] + vx * d; q[1] = bl[1] + vy * d; q[2] = bl[2] + vz * d
                val sp = tb * 3f + k * 0.7f
                t2Basis(q, vx * cos(sp) + ux * sin(sp), vy * cos(sp) + uy * sin(sp), vz * cos(sp) + uz * sin(sp), ux, uy, uz, 1f, 1f, 1f, mini, T2_PHAGE_HEAD, COL_LAMP, 1f - t2sm((tb - 0.6f) / 0.4f), 0.1f)
            }
        }
    }
}

// ============================================================================ stop 3: LIVER
// 8 µm a unit (Mote 12 µm). A sinusoid ~35 µm across, walled all round by hepatocytes (~25 µm
// polygonal cells, one per plate thickness, nuclei central, some binucleate) seen through the
// fenestrated endothelium and the space of Disse; bile canaliculi run between neighbouring
// hepatocytes at mid-depth, never facing the blood; stellate Kupffer cells squat on the lining.

private fun StereoBodyRenderer.t2LiverMeshes(i: Int): Array<ColorVboMesh> = t2Get("liver$i") {
    val opaque = T2Geo(); val glass = T2Geo(); val lining = T2Geo()
    val rf = 2.2f; val ro = 4.9f; val rm = 3.5f
    val cols = 6; val circ = 2f * T2PI * rf; val rh = circ / cols / 1.5f; val rowH = sqrt(3f) * rh
    fun cyl(arc: Float, z: Float, rho: Float): T2V { val th = arc / rf; return t2v(cos(th) * rho, sin(th) * rho, z) }
    val canal = HashSet<Long>()
    val rnd = java.util.Random(31L)
    var cellNo = 0
    val micro = ArrayList<Float>()
    for (row in -3..4) for (c in 0 until cols) {
        val ac = c * 1.5f * rh + 0.35f; val zc = row * rowH + (c % 2) * rowH * 0.5f + 1.2f
        if (zc < -6.5f || zc > 11.5f) continue
        cellNo++
        val ca = FloatArray(18); val cz = FloatArray(18)          // corners, each edge in 3
        val ra = FloatArray(18); val rz = FloatArray(18)          // unshrunk (shared edges coincide)
        for (k in 0 until 6) {
            val a0 = k * T2PI / 3f; val a1 = (k + 1) * T2PI / 3f
            for (q in 0 until 3) {
                val t = q / 3f
                val x = cos(a0) * (1 - t) + cos(a1) * t; val y = sin(a0) * (1 - t) + sin(a1) * t
                ra[k * 3 + q] = ac + rh * x; rz[k * 3 + q] = zc + rh * y
                ca[k * 3 + q] = ac + rh * 0.955f * x; cz[k * 3 + q] = zc + rh * 0.955f * y
            }
        }
        val face = T2_HEPATOCYTE
        for (m in 0 until 18) {
            val m1 = (m + 1) % 18
            // blood-facing face (translucent), back face (opaque), lateral walls (translucent)
            val c0 = cyl(ac, zc, rf); val mA = cyl(ac + (ca[m] - ac) * 0.5f, zc + (cz[m] - zc) * 0.5f, rf); val mB = cyl(ac + (ca[m1] - ac) * 0.5f, zc + (cz[m1] - zc) * 0.5f, rf)
            val oA = cyl(ca[m], cz[m], rf); val oB = cyl(ca[m1], cz[m1], rf)
            glass.tri(c0, mA, mB, face, 0.55f); glass.tri(mA, oA, oB, face, 0.55f); glass.tri(mA, oB, mB, face, 0.55f)
            val bA = cyl(ca[m], cz[m], ro); val bB = cyl(ca[m1], cz[m1], ro)
            opaque.tri(cyl(ac, zc, ro), cyl(ca[m], cz[m], ro), cyl(ca[m1], cz[m1], ro), T2_HEPATOCYTE_BACK)
            glass.quad(oA, oB, bB, bA, T2_HEPATOCYTE_BACK, 0.4f)
        }
        // nucleus (or two), central in the cell
        if (cellNo % 4 == 0) for (sd in SIGNS) opaque.ball(cyl(ac + sd * 0.55f, zc, 3.1f), 0.46f, T2_NUCLEUS, 1f, 9, 14)
        else opaque.ball(cyl(ac, zc, 3.1f), 0.52f, T2_NUCLEUS, 1f, 9, 14)
        // bile canaliculi: a belt along every shared lateral face, half-way through the plate
        for (k in 0 until 6) {
            val a0 = k * T2PI / 3f; val a1 = (k + 1) * T2PI / 3f
            val xa = ac + rh * cos(a0); val za = zc + rh * sin(a0); val xb = ac + rh * cos(a1); val zb = zc + rh * sin(a1)
            val key = (((xa + xb) * 50f).roundToLong() * 100003L) + ((za + zb) * 50f).roundToLong()
            if (!canal.add(key)) continue
            val pts = (0..4).map { q -> val t = q / 4f; cyl(xa + (xb - xa) * t, za + (zb - za) * t, rm) }
            opaque.path(pts, { 0.085f }, T2_CANALICULUS, 1f, 5, true)
        }
        // microvilli from the blood face into the space of Disse
        repeat(26) {
            val u = (rnd.nextFloat() * 2f - 1f) * rh * 0.8f; val w = (rnd.nextFloat() * 2f - 1f) * rh * 0.7f
            val a = cyl(ac + u, zc + w, rf - 0.005f); val b = cyl(ac + u, zc + w, rf - 0.14f)
            micro.addAll(listOf(a.x, a.y, a.z, 0.95f, 0.7f, 0.66f, 0.45f, b.x, b.y, b.z, 0.95f, 0.7f, 0.66f, 0.45f))
        }
    }
    // the sinusoidal endothelium: a thin translucent lining with flat nuclei, and fenestrae
    // (sub-micrometre holes, drawn as dark sieve-plate dots)
    lining.surf(40, 36, T2_ENDOTHELIUM, 0.2f) { v, u -> val a = u * 2f * T2PI; t2v(cos(a) * 2.04f, sin(a) * 2.04f, -6.5f + 18f * v) }
    repeat(9) {
        val a = rnd.nextFloat() * 2f * T2PI; val z = -4f + rnd.nextFloat() * 14f
        val c = t2v(cos(a) * 1.98f, sin(a) * 1.98f, z)
        lining.ell(c, t2v(-sin(a), cos(a), 0f) * 0.35f, t2v(cos(a), sin(a), 0f) * 0.12f, t2v(0f, 0f, 0.85f), T2_NUCLEUS, 0.75f, 5, 8)
    }
    val fen = ArrayList<Float>()
    repeat(80) {
        val a = rnd.nextFloat() * 2f * T2PI; val z = -5f + rnd.nextFloat() * 15f
        repeat(12) {
            val da = (rnd.nextFloat() - 0.5f) * 0.16f; val dz = (rnd.nextFloat() - 0.5f) * 0.32f
            fen.addAll(listOf(cos(a + da) * 2.02f, sin(a + da) * 2.02f, z + dz, 0.45f, 0.1f, 0.16f, 0.95f))
        }
    }
    val bend = t2Bend(i, 30f, 40f)
    val fenA = fen.toFloatArray(); for (k in 0 until fenA.size / 7) bend(fenA, k * 7)
    val micA = micro.toFloatArray(); for (k in 0 until micA.size / 7) bend(micA, k * 7)
    arrayOf(t2Bake(opaque, i, 30f, 40f), t2Bake(glass, i, 30f, 40f), t2Bake(lining, i, 30f, 40f), PointMesh(fenA), LineMesh(micA))
}

private val T2_KUPFFER_AT = arrayOf(floatArrayOf(-0.95f, 5.0f), floatArrayOf(-2.45f, 7.6f), floatArrayOf(1.4f, 9.8f))

/** Tour II stop 4: a liver sinusoid between hepatocyte plates, canaliculi between the cells, Kupffer cells on the lining. */
internal fun StereoBodyRenderer.drawLiver(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) {
        val m = t2LiverMeshes(i)
        t2DrawWorld(m[0])
        t2DrawWorld(m[4], false, 1f)
        t2DrawWorld(m[1], true)
        t2DrawWorld(m[2], true)
        t2DrawWorld(m[3], true, 2.5f, true)
        // Kupffer cells: flattened, stellate, processes spread along the lining; the first one
        // catches a worn-out red cell every 15 s and draws it in.
        val eat = (seconds % 15f) / 15f
        for ((k, kc) in T2_KUPFFER_AT.withIndex()) {
            val ang = kc[0]; val z = kc[1]
            val fr = t2Frame(i, z)
            val ca = cos(ang); val sa = sin(ang)
            val rxw = fr[6] * ca + fr[9] * sa; val ryw = fr[7] * ca + fr[10] * sa; val rzw = fr[8] * ca + fr[11] * sa
            val b = t2W(fr, ca * 1.74f, sa * 1.74f).copyOf()
            t2Basis(b, fr[3], fr[4], fr[5], rxw, ryw, rzw, 1.0f, 0.42f, 0.95f, sphere, T2_KUPFFER, T2_NUCLEUS)
            for (p in 0 until 5) {
                var da = (p - 2f) * 0.32f; var dzp = if (p % 2 == 0) 1.2f else -1.1f
                var gx: Float; var gy: Float; var gz: Float
                val pa = ang + da; val w = t2W(fr, cos(pa) * 1.96f, sin(pa) * 1.96f, dzp + 0.15f * sin(seconds * 0.7f + p))
                gx = w[0]; gy = w[1]; gz = w[2]
                if (k == 0 && p < 2 && eat > 0.4f && eat < 0.85f) {   // pseudopods closing round the red cell
                    val cw = t2W(fr, ca * 1.5f, sa * 1.5f, -0.75f)
                    val s = t2sm((eat - 0.4f) / 0.15f); gx += (cw[0] - gx) * s; gy += (cw[1] - gy) * s; gz += (cw[2] - gz) * s
                }
                drawStrut(b[0], b[1], b[2], gx, gy, gz, 0.09f, T2_KUPFFER, T2_KUPFFER)
            }
            if (k == 0 && eat < 0.9f) {
                val arrive = t2sm(eat / 0.4f); val swallow = t2sm((eat - 0.6f) / 0.28f)
                val rz = -7f + (z - 0.75f + 7f) * arrive
                val fr2 = t2Frame(i, rz, t2G)
                val rr = 1.3f + 0.2f * (1f - arrive) - 0.3f * swallow
                val q = t2W(fr2, ca * rr, sa * rr, 0f).copyOf()
                val s = 0.46f * (1f - 0.7f * swallow)
                t2Basis(q, fr2[3], fr2[4], fr2[5], rxw, ryw, rzw, s, s, s, rbc, COL_RBC_DEOXY, COL_RBC_RIM, 1f - swallow * 0.8f)
            }
        }
    }
}

// =========================================================================== stop 4: KIDNEY
// 8 µm a unit. The Mote rides Bowman's space, 25-40 µm wide here, between a glomerular tuft
// ~190 µm across (capillary loops 7 µm thick, grouped in lobules, clothed by podocytes with
// interdigitating foot processes) and the squamous parietal layer of the capsule. Filtrate beads
// leave the tuft and flow to the urinary pole, where the proximal tubule begins (cuboidal cells,
// brush border). The afferent and efferent arterioles enter at the vascular pole on the far side.

private val T2_TUFT = floatArrayOf(16.8f, 0.5f, 8.5f)   // tuft centre (rigid node frame), radius 12
private val T2_CAPS = floatArrayOf(13.8f, 0.5f, 6.5f)     // capsule centre, radius 18

/** Where the rail leaves Bowman's capsule (the urinary pole), in the node's rigid frame: x, y, z, arc position. */
private fun StereoBodyRenderer.t2UrinaryPole(i: Int): FloatArray = t2Get("upole$i") {
    val rail = t2Rail(i); val f0 = FloatArray(13); rail.at(0f, f0); val fr = FloatArray(13)
    var a = 0f; var out = floatArrayOf(0f, 0f, 10f, 10f)
    while (a < 30f) {
        rail.at(a, fr)
        val rx = fr[0] - f0[0]; val ry = fr[1] - f0[1]; val rz = fr[2] - f0[2]
        val x = rx * f0[6] + ry * f0[7] + rz * f0[8]; val y = rx * f0[9] + ry * f0[10] + rz * f0[11]; val z = rx * f0[3] + ry * f0[4] + rz * f0[5]
        val dx = x - T2_CAPS[0]; val dy = y - T2_CAPS[1]; val dz = z - T2_CAPS[2]
        if (dx * dx + dy * dy + dz * dz > 324f) { out = floatArrayOf(x, y, z, a); break }
        a += 0.05f
    }
    out
}

private fun StereoBodyRenderer.t2KidneyMeshes(i: Int): Array<ColorVboMesh> = t2Get("kidney$i") {
    val g = T2Geo(); val tub = T2Geo(); val rnd = java.util.Random(47L)
    val T = t2v(T2_TUFT[0], T2_TUFT[1], T2_TUFT[2]); val R = 12f
    val K = t2v(T2_CAPS[0], T2_CAPS[1], T2_CAPS[2]); val RC = 18f
    val up = t2UrinaryPole(i); val E = t2v(up[0], up[1], up[2])
    // ---- tuft core (mesangium and deeper loops) and ~110 capillary loops in six lobules
    g.ball(T, R - 0.4f, T2_MESANGIUM, 1f, 18, 26)
    val lobules = ArrayList<T2V>()
    while (lobules.size < 7) {
        val d = t2v(rnd.nextFloat() * 2f - 1f, rnd.nextFloat() * 2f - 1f, rnd.nextFloat() * 2f - 1f)
        if (d.len() in 0.2f..1f && d.unit().x < 0.2f) lobules.add(d.unit())
    }
    val nLoops = 120
    val loopTops = ArrayList<T2V>()
    for (l in 0 until nLoops) {
        val lob = lobules[l % lobules.size]
        val nrm = (lob + t2v(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f) * 0.55f).unit()
        val e1 = t2perp(nrm); val e2 = nrm cross e1
        val th = rnd.nextFloat() * 2f * T2PI; val dir = e1 * cos(th) + e2 * sin(th); val side = nrm cross dir
        val len = 2.8f + rnd.nextFloat() * 2.2f; val wid = 0.55f + rnd.nextFloat() * 0.3f; val layer = rnd.nextFloat() * 0.55f
        val pts = ArrayList<T2V>()
        for (q in 0..22) {
            val t = q / 22f
            // a hairpin: out along one limb, round the bend, back along the other
            val (u, v) = when {
                t < 0.4f -> Pair(len * (t / 0.4f), -wid)
                t < 0.6f -> { val a = (t - 0.4f) / 0.2f * T2PI; Pair(len + wid * sin(a), -wid * cos(a)) }
                else -> Pair(len * (1f - (t - 0.6f) / 0.4f), wid)
            }
            val p0 = T + nrm * R + dir * (u - len * 0.5f) + side * v
            val rad = R + layer + 0.12f * sin(t * 3f * T2PI + l)
            pts.add(T + (p0 - T).unit() * rad)
        }
        val shade = 0.9f + 0.1f * rnd.nextFloat()
        g.path(pts, { 0.42f }, floatArrayOf(T2_CAPILLARY[0] * shade, T2_CAPILLARY[1] * shade, T2_CAPILLARY[2] * shade, 1f), 1f, 8, true)
        loopTops.add(pts[11])
    }
    // ---- podocytes on the loops: cell body, primary processes, comb-like foot processes;
    //      neighbouring cells alternate colour so their interdigitating feet can be told apart.
    var pn = 0
    for (lt in loopTops) {
        if (pn >= 26) break
        val nrm = (lt - T).unit()
        if (nrm.x > 0.05f) continue
        val col = if (pn % 2 == 0) T2_PODOCYTE_A else T2_PODOCYTE_B
        val body = T + nrm * (R + 1.05f)
        val e1 = t2perp(nrm); val e2 = nrm cross e1
        g.ellAxis(body, nrm, 0.95f, 0.55f, 0.8f, col, 1f, 6, 9, e1)
        for (pp in 0 until 4) {
            val a = pp * T2PI / 2f + rnd.nextFloat() * 0.8f
            val dir = e1 * cos(a) + e2 * sin(a)
            val plen = 2.0f + rnd.nextFloat()
            val pts = (0..5).map { q -> val t = q / 5f; val p = body + dir * (plen * t); T + (p - T).unit() * (R + 0.75f - 0.1f * t) }
            g.path(pts, { t -> 0.14f - 0.06f * t }, col, 1f, 5, true)
            val nf = 9
            for (f in 1..nf) {
                val t = f / (nf + 1f); val c = pts[0] + (pts[5] - pts[0]) * t
                val cc = T + (c - T).unit() * (R + 0.66f)
                val perpD = (nrm cross dir).unit()
                for (sd in SIGNS) {
                    val tip = T + (cc + perpD * (0.34f * sd) - T).unit() * (R + 0.58f)
                    g.tube(cc, tip, 0.045f, 0.035f, col, 1f, 4, false)
                }
            }
        }
        pn++
    }
    // ---- vascular pole, opposite the urinary pole: the afferent arteriole (wider) and the
    //      efferent arteriole leave the tuft and cross the capsule wall.
    val vp = (K - E).unit(); val vpe1 = t2perp(vp)
    for ((k, rr) in floatArrayOf(1.25f, 0.85f).withIndex()) {
        val off = vpe1 * (if (k == 0) 1.5f else -1.4f)
        val pts = (0..6).map { q -> val t = q / 6f; T + vp * (R * 0.7f + t * 10f) + off * (1f + t) }
        g.path(pts, { rr }, T2_ARTERIOLE, 1f, 9, true)
    }
    // ---- Bowman's capsule: parietal layer (simple squamous), open at the urinary pole
    val ue = (E - K).unit(); val holeCos = cos(asin(3.1f / RC))
    val ce1 = t2perp(ue); val ce2 = ue cross ce1
    g.surf(28, 40, T2_PARIETAL) { u, v ->
        val th = acos(holeCos) + u * (T2PI - acos(holeCos)); val ph = v * 2f * T2PI
        K + (ue * cos(th) + (ce1 * cos(ph) + ce2 * sin(ph)) * sin(th)) * RC
    }
    val lines = ArrayList<Float>()
    val lat = 16; val lon = 34
    val grid = Array(lat + 1) { la -> Array(lon) { lo ->
        val th = acos(holeCos) + 0.1f + (la + 0.5f * ((lo + la) % 2) * 0.4f) / lat * (T2PI - acos(holeCos) - 0.1f); val ph = (lo + 0.5f * (la % 2) + (rnd.nextFloat() - 0.5f) * 0.3f) / lon * 2f * T2PI
        K + (ue * cos(th) + (ce1 * cos(ph) + ce2 * sin(ph)) * sin(th)) * (RC - 0.06f)
    } }
    fun ln(a: T2V, b: T2V) { lines.addAll(listOf(a.x, a.y, a.z, 1f, 0.9f, 0.86f, 0.5f, b.x, b.y, b.z, 1f, 0.9f, 0.86f, 0.5f)) }
    for (la in 0..lat) for (lo in 0 until lon) {
        ln(grid[la][lo], grid[la][(lo + 1) % lon])
        if (la < lat) ln(grid[la][lo], grid[la + 1][lo])
        if ((la * 3 + lo) % 5 == 0) {
            val c = (grid[la][lo] + grid[la][(lo + 1) % lon]) * 0.5f
            g.ellAxis(c + (K - c).unit() * 0.08f, (K - c).unit(), 0.5f, 0.12f, 0.35f, T2_NUCLEUS, 1f, 4, 7)
        }
    }
    // ---- the proximal tubule leaving the urinary pole along the rail: cuboidal cells with round
    //      nuclei and a dense brush border lining a lumen ~20 µm across.
    val brush = ArrayList<Float>()
    val a0 = up[3] - 0.6f
    val nc = 11
    for (row in 0 until 7) {
        val zc = a0 + row * 1.35f
        for (c in 0 until nc) {
            val th0 = (c + 0.5f * (row % 2)) * 2f * T2PI / nc; val th1 = th0 + 2f * T2PI / nc * 0.95f
            val z0 = zc; val z1 = zc + 1.3f
            fun P(th: Float, z: Float, r: Float) = t2v(cos(th) * r, sin(th) * r, z)
            tub.quad(P(th0, z0, 1.6f), P(th1, z0, 1.6f), P(th1, z1, 1.6f), P(th0, z1, 1.6f), T2_TUBULE)
            tub.quad(P(th0, z0, 1.6f), P(th0, z0, 3.0f), P(th0, z1, 3.0f), P(th0, z1, 1.6f), T2_HEPATOCYTE_BACK)
            tub.quad(P(th0, z0, 1.6f), P(th1, z0, 1.6f), P(th1, z0, 3.0f), P(th0, z0, 3.0f), T2_HEPATOCYTE_BACK)
            tub.ball(P((th0 + th1) * 0.5f, zc + 0.65f, 2.45f), 0.42f, T2_NUCLEUS, 1f, 5, 8)
            repeat(14) {
                val th = th0 + (th1 - th0) * rnd.nextFloat(); val z = z0 + 1.3f * rnd.nextFloat()
                brush.addAll(listOf(cos(th) * 1.6f, sin(th) * 1.6f, z, 0.99f, 0.88f, 0.84f, 0.85f, cos(th) * 1.36f, sin(th) * 1.36f, z, 0.99f, 0.88f, 0.84f, 0.85f))
            }
        }
    }
    val rigid = t2Bend(i, 0f, 0f); val bent = t2Bend(i, 30f, 40f)
    val lA = lines.toFloatArray(); for (k in 0 until lA.size / 7) rigid(lA, k * 7)
    val bA = brush.toFloatArray(); for (k in 0 until bA.size / 7) bent(bA, k * 7)
    arrayOf(TriMesh(g.baked(rigid)), LineMesh(lA), TriMesh(tub.baked(bent)), LineMesh(bA))
}

/** Tour II stop 5: inside Bowman's capsule beside a glomerular tuft, filtrate flowing to the proximal tubule. */
internal fun StereoBodyRenderer.drawKidney(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) { own ->
        val m = t2KidneyMeshes(i)
        t2DrawWorld(m[0]); t2DrawWorld(m[2]); t2DrawWorld(m[1], true); t2DrawWorld(m[3], true)
        // filtrate: plasma water squeezed out between the podocyte feet into Bowman's space,
        // drifting to the urinary pole and down the tubule
        val f0 = t2Rigid(i, 0f, FloatArray(13)); val up = t2UrinaryPole(i)
        t2LinesBegin()
        val cnt = if (quality == 0) 70 else 35
        for (k in 0 until cnt) {
            val t = ((seconds * 0.07f + k * 0.6180339f) % 1f)
            val zs = -1f + (k * 7.31f % 13f); val ys = -3f + (k * 3.7f % 6f)
            val dz = zs - T2_TUFT[2]; val dy = ys - T2_TUFT[1]
            val xs = T2_TUFT[0] - sqrt(max(1f, 144f - dz * dz - dy * dy)) - 0.8f
            var x: Float; var y: Float; var z: Float
            if (t < 0.15f) { val s = t / 0.15f; x = xs - 1.2f * s; y = ys; z = zs }
            else { val s = t2sm((t - 0.15f) / 0.7f); x = (xs - 1.2f) + (up[0] - xs + 1.2f) * s; y = ys + (up[1] - ys) * s; z = zs + (up[2] - zs) * s }
            val w = if (t > 0.85f) { val fr = t2Frame(i, up[3] + (t - 0.85f) / 0.15f * 6f); t2W(fr, 0.7f * sin(k * 1.3f), 0.7f * cos(k * 1.3f)) } else t2W(f0, x, y, z)
            t2Vert(w[0], w[1], w[2], T2_FILTRATE, 0.9f * t2sm(t / 0.05f))
        }
        t2LinesEnd(1f, true, 5f)
    }
}

// =========================================================================== stop 5: MUSCLE
// 0.8 µm a unit (Mote 1.2 µm). The Mote flies in the sarcoplasm between myofibrils (1.4 µm
// across) that run to the horizon, all in register. Each sarcomere is built from rigid parts:
// the thick-filament unit (myosin, 1.6 µm, bipolar with heads and a bare zone, the M line, the
// triads at each A-I junction) and the Z unit (Z disc with the 1.0 µm actin filaments of both
// neighbouring half-sarcomeres, mitochondria at the I band). On each twitch the sarcomere
// shortens from 2.4 to 1.9 µm by moving the units together: filament lengths never change, so
// the A band keeps its width while the I band and H zone narrow — the sliding filaments.

private const val T2_THICK_HALF = 1.0f   // 1.6 µm thick filament
private const val T2_THIN_LEN = 1.25f    // 1.0 µm thin filament from each Z disc

private fun t2Fibrils(): List<FloatArray> {
    val out = ArrayList<FloatArray>()
    for (k in 0 until 6) { val a = k * T2PI / 3f; out.add(floatArrayOf(cos(a) * 2.7f, sin(a) * 2.7f, 1f)) }
    for (k in 0 until 12) { val a = (k + 0.5f) * T2PI / 6f; out.add(floatArrayOf(cos(a) * 4.75f, sin(a) * 4.75f, 0f)) }
    return out
}

/** Thick-filament positions of a detailed myofibril: a hexagonal lattice, 7 filaments. */
private fun t2ThickLattice(d: Float): List<FloatArray> {
    val out = ArrayList<FloatArray>(); out.add(floatArrayOf(0f, 0f))
    for (k in 0 until 6) { val a = k * T2PI / 3f; out.add(floatArrayOf(cos(a) * d, sin(a) * d)) }
    return out
}

/** Thin-filament positions: the trigonal points of the lattice (each thick filament ringed by six thin). */
private fun t2ThinLattice(d: Float, maxR: Float): List<FloatArray> {
    val out = ArrayList<FloatArray>()
    for (p in t2ThickLattice(d)) for (k in 0 until 6) {
        val a = T2PI / 6f + k * T2PI / 3f; val x = p[0] + cos(a) * d / sqrt(3f); val y = p[1] + sin(a) * d / sqrt(3f)
        if (x * x + y * y > maxR * maxR) continue
        if (out.any { (it[0] - x) * (it[0] - x) + (it[1] - y) * (it[1] - y) < 0.01f }) continue
        out.add(floatArrayOf(x, y))
    }
    return out
}

/** [0] thick unit (centred on the M line), [1] Z unit (centred on the Z disc), [2] SR for the calcium flash. */
private fun StereoBodyRenderer.t2MuscleMeshes(): Array<TriMesh> = t2Get("muscle") {
    val th = T2Geo(); val zu = T2Geo(); val sr = T2Geo()
    val zAx = t2v(0f, 0f, 1f)
    for (fb in t2Fibrils()) {
        if (fb[2] < 0.5f) continue
        val cx = fb[0]; val cy = fb[1]
        for (p in t2ThickLattice(0.44f)) {
            val x = cx + p[0]; val y = cy + p[1]
            th.tube(t2v(x, y, -T2_THICK_HALF), t2v(x, y, T2_THICK_HALF), 0.07f, 0.07f, T2_MYOSIN, 1f, 6, false)
            // myosin heads on both halves, leaning away from the bare zone at the M line
            for (sgn in SIGNS) for (c in 0 until 5) {
                val z = sgn * (0.2f + c * 0.19f)
                for (h in 0 until 2) {
                    val a = c * 1.3f + h * T2PI + (x + y) * 3f
                    val dx = cos(a); val dy = sin(a)
                    th.tube(t2v(x + dx * 0.06f, y + dy * 0.06f, z), t2v(x + dx * 0.2f, y + dy * 0.2f, z + sgn * 0.07f), 0.03f, 0.024f, T2_MYOSIN_HEAD, 1f, 4, false)
                }
            }
        }
        // M line: a ring and spokes cross-linking the thick filaments at the centre of the A band
        th.torus(t2v(cx, cy, 0f), zAx, 0.44f, 0.03f, T2_MLINE, 1f, 18, 4)
        for (k in 0 until 6) { val a = k * T2PI / 3f; th.tube(t2v(cx, cy, 0f), t2v(cx + cos(a) * 0.44f, cy + sin(a) * 0.44f, 0f), 0.022f, 0.022f, T2_MLINE, 1f, 3, false) }
        // T tubules circling the fibril at each A-I junction (the triads' centre)
        for (sgn in SIGNS) th.torus(t2v(cx, cy, sgn * 1f), zAx, 0.98f, 0.025f, T2_TTUBULE, 1f, 22, 4)
        // for the calcium flash: terminal cisternae flanking each T tubule and the longitudinal SR
        for (sgn in SIGNS) { sr.torus(t2v(cx, cy, sgn * 0.9f), zAx, 0.98f, 0.045f, T2_CALCIUM, 1f, 22, 4); sr.torus(t2v(cx, cy, sgn * 1.1f), zAx, 0.98f, 0.045f, T2_CALCIUM, 1f, 22, 4) }
        for (k in 0 until 4) { val a = k * T2PI / 2f + 0.5f; sr.tube(t2v(cx + cos(a) * 0.98f, cy + sin(a) * 0.98f, -0.9f), t2v(cx + cos(a) * 0.98f, cy + sin(a) * 0.98f, 0.9f), 0.025f, 0.025f, T2_CALCIUM, 1f, 4, false) }
        // Z disc with the actin (thin) filaments of both neighbouring half-sarcomeres
        zu.slab(t2v(cx, cy, 0f), zAx, 0.9f, 0.08f, T2_ZDISC, 1f, 18)
        for (q in t2ThinLattice(0.44f, 0.8f)) zu.tube(t2v(cx + q[0], cy + q[1], -T2_THIN_LEN), t2v(cx + q[0], cy + q[1], T2_THIN_LEN), 0.034f, 0.034f, T2_ACTIN, 1f, 4, false)
    }
    // mitochondria between the inner fibrils, beside each Z line (at the I band)
    for (k in 0 until 6) {
        val a = T2PI / 6f + k * T2PI / 3f
        for (sgn in SIGNS) zu.ell(t2v(cos(a) * 3.3f, sin(a) * 3.3f, sgn * 0.55f), t2v(0.3f, 0f, 0f), t2v(0f, 0.3f, 0f), t2v(0f, 0f, 0.42f), T2_MITO, 1f, 6, 9)
    }
    arrayOf(TriMesh(th.baked()), TriMesh(zu.baked()), TriMesh(sr.baked()))
}

private val t2BandTris by lazy { DynMesh(40000) }
private var t2BandL = -1f
private var t2BandT = -1f
private var t2BandVerts = 0
private val T2_BAND_I = floatArrayOf(0.97f, 0.88f, 0.82f, 1f)
private val T2_BAND_OVL = floatArrayOf(0.6f, 0.17f, 0.24f, 1f)
private val T2_BAND_H = floatArrayOf(0.82f, 0.42f, 0.46f, 1f)

/**
 * The outer ring of myofibrils as banded cylinders, rebuilt (~15 Hz) whenever the sarcomere
 * length changes: Z line, I band (actin only, pale), overlap (actin + myosin, darkest), H zone
 * (myosin only), M line. The A band (overlap + H + overlap) is always 1.6 µm; only the I band and
 * H zone change as the fibre shortens.
 */
private fun StereoBodyRenderer.t2Bands(i: Int, L: Float, zRef: Float, seconds: Float) {
    if (abs(L - t2BandL) < 0.002f && t2BandVerts > 0) return
    if (t2BandVerts > 0 && abs(seconds - t2BandT) < 0.066f) return
    t2BandL = L; t2BandT = seconds
    val d = t2BandTris.data; var v = 0
    val fibs = t2Fibrils().filter { it[2] < 0.5f }
    val sides = 7; val r = 0.9f
    val fa = FloatArray(13); val fb = FloatArray(13)
    fun seg(z0: Float, z1: Float, c: FloatArray) {
        val a0 = max(z0, -9f); val a1 = min(z1, 17f); if (a1 <= a0) return
        t2Frame(i, a0, fa); t2Frame(i, a1, fb)
        for (f in fibs) for (s in 0 until sides) {
            if (v + 6 > 40000) return
            val q0 = s * 2f * T2PI / sides; val q1 = (s + 1) * 2f * T2PI / sides
            for ((fr, q) in arrayOf(fa to q0, fb to q0, fb to q1, fa to q0, fb to q1, fa to q1)) {
                val cq = cos(q); val sq = sin(q); val x = f[0] + cq * r; val y = f[1] + sq * r
                val sh = 0.5f + 0.55f * abs(cq * 0.3f + sq * 0.83f)
                val o = v * 7
                d[o] = fr[0] + fr[6] * x + fr[9] * y; d[o + 1] = fr[1] + fr[7] * x + fr[10] * y; d[o + 2] = fr[2] + fr[8] * x + fr[11] * y
                d[o + 3] = min(1f, c[0] * sh); d[o + 4] = min(1f, c[1] * sh); d[o + 5] = min(1f, c[2] * sh); d[o + 6] = 1f
                v++
            }
        }
    }
    val kMin = floor((-9f - zRef) / L).toInt() - 1; val kMax = ceil((17f - zRef) / L).toInt()
    for (k in kMin..kMax) {
        val z = zRef + k * L; val zn = z + L; val m = z + L * 0.5f
        seg(z - 0.05f, z + 0.05f, T2_ZDISC)
        seg(z + 0.05f, m - T2_THICK_HALF, T2_BAND_I)
        val o1 = min(z + T2_THIN_LEN, m); val o2 = max(zn - T2_THIN_LEN, m)
        seg(m - T2_THICK_HALF, o1, T2_BAND_OVL)
        if (o2 - o1 > 0.08f) { seg(o1, m - 0.03f, T2_BAND_H); seg(m - 0.03f, m + 0.03f, T2_MLINE); seg(m + 0.03f, o2, T2_BAND_H) }
        else seg(o1, o2, T2_BAND_OVL)
        seg(o2, m + T2_THICK_HALF, T2_BAND_OVL)
        seg(m + T2_THICK_HALF, zn - 0.05f, T2_BAND_I)
    }
    t2BandVerts = v
}

/** Sarcomere length (units) and calcium flash for a twitch every 6 s. */
private fun t2SarcL(seconds: Float): Float {
    val t = seconds % 6f
    val c = t2sm((t - 0.2f) / 0.45f) * (1f - t2sm((t - 2.2f) / 1.0f))
    return 3.0f - 0.6f * c        // 2.4 µm relaxed -> 1.9 µm contracted
}
private fun t2Flash(seconds: Float): Float {
    val t = seconds % 6f
    return if (t < 1.4f) t2sm(t / 0.15f) * (1f - t2sm((t - 0.5f) / 0.8f)) else 0f
}

/** Tour II stop 6: myofibrils in register, sliding filaments on every twitch. */
internal fun StereoBodyRenderer.drawMuscle(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) {
        val m = t2MuscleMeshes()
        val L = t2SarcL(seconds); val flash = t2Flash(seconds)
        val zRef = 1.0f     // this Z line holds still; the fibre shortens toward it
        val kMin = floor((-9f - zRef) / L).toInt(); val kMax = ceil((17f - zRef) / L).toInt()
        val keep = colorShader.globalFade
        t2Bands(i, L, zRef, seconds)
        if (t2BandVerts > 0) {
            Matrix.setIdentityM(model, 0); Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
            colorShader.use(mvp, 1f); GLES20.glDisable(GLES20.GL_CULL_FACE)
            t2BandTris.draw(colorShader.positionHandle, colorShader.colorHandle, GLES20.GL_TRIANGLES, t2BandVerts)
            GLES20.glEnable(GLES20.GL_CULL_FACE)
        }
        for (k in kMin..kMax) {
            val zz = zRef + k * L
            if (zz in -9f..17f) { t2Model(t2Frame(i, zz), 0f, 0f); t2Draw(m[1]) }
            val zm = zz + L * 0.5f
            if (zm in -9f..17f) {
                t2Model(t2Frame(i, zm), 0f, 0f); t2Draw(m[0])
                if (flash > 0.02f) { colorShader.globalFade = keep * flash; t2Draw(m[2], true); colorShader.globalFade = keep }
            }
        }
        // calcium released from the terminal cisternae, spreading into the filaments
        if (flash > 0.02f) {
            t2LinesBegin()
            for (k in kMin..kMax) {
                val zm = zRef + k * L + L * 0.5f
                if (zm < -3f || zm > 12f) continue
                for (f in 0 until 6) {
                    val fa = f * T2PI / 3f; val cx = cos(fa) * 2.7f; val cy = sin(fa) * 2.7f
                    for (sgn in SIGNS) for (q in 0 until 5) {
                        val a = q * 1.26f + f; val r = 1.0f - 0.45f * (1f - flash) ; val dz = sgn * (1f + (q - 2) * 0.08f * (1.5f - flash))
                        val w = t2W(t2Frame(i, zm + dz), cx + cos(a) * r, cy + sin(a) * r)
                        t2Vert(w[0], w[1], w[2], T2_CALCIUM, flash)
                    }
                }
            }
            t2LinesEnd(1f, true, 4f)
        }
    }
}

// =========================================================================== stop 6: MARROW
// 8 µm a unit (Mote 12 µm). Red marrow inside the femur: cream trabeculae 80-100 µm thick in the
// distance lined by osteoblasts, an osteoclast in its pit; between them haematopoietic cords of
// mixed precursors, fat cells, a sinusoid with blood flowing; a megakaryocyte (~50 µm, lobed
// polyploid nucleus) pushing beaded proplatelets through the sinusoid wall; an erythroblastic
// island (a macrophage ringed by maturing erythroblasts, one extruding its nucleus); a young B
// cell against a stromal cell by the vessel wall; a stem cell dividing. The same scene serves
// Chapter III's marrow stop. Everything is laid out in the node's rigid frame and kept clear of
// the actual (curving) rail.

private val T2_MK_C = floatArrayOf(7.4f, 0.2f, 8.6f)
private val T2_MK_R = floatArrayOf(3.1f, 2.8f, 3.0f)
private val T2_SIN = floatArrayOf(3.4f, -4.9f, 2.8f)        // sinusoid axis (x, y) and radius, along z
private val T2_ISLAND = floatArrayOf(-3.8f, -2.4f, 4.2f)
private val T2_HSC = floatArrayOf(-3.2f, 2.8f, 5.5f)
private val T2_BCELL = floatArrayOf(0.4f, -2.6f, 10.5f)

/** The rail centre at arc position z, in the node's rigid frame (x, y). */
private fun StereoBodyRenderer.t2RailOffset(i: Int, z: Float, out: FloatArray) {
    val rail = t2Rail(i); val f0 = t2G; rail.at(0f, f0); val fr = t2F; rail.at(z, fr)
    val rx = fr[0] - f0[0]; val ry = fr[1] - f0[1]; val rz = fr[2] - f0[2]
    out[0] = rx * f0[6] + ry * f0[7] + rz * f0[8]; out[1] = rx * f0[9] + ry * f0[10] + rz * f0[11]
}

private fun StereoBodyRenderer.t2MarrowMeshes(i: Int): Array<ColorVboMesh> = t2Get("marrow$i") {
    val g = T2Geo(); val glass = T2Geo(); val rnd = java.util.Random(61L)
    val off = FloatArray(2)
    // ---- trabeculae: thick lumpy bony struts across the far field, osteoblasts on one surface,
    //      an osteoclast (large, several nuclei) in a resorption pit on another.
    val beams = arrayOf(floatArrayOf(-22f, 13f, 4f, 20f, 15f, 28f, 5.5f), floatArrayOf(-14f, -22f, 27f, -12f, 18f, 31f, 5f), floatArrayOf(17f, -19f, 9f, 15f, 4f, 35f, 5f))
    for (b in beams) {
        val a = t2v(b[0], b[1], b[2]); val c = t2v(b[3], b[4], b[5]); val r = b[6]
        g.path((0..10).map { q -> a + (c - a) * (q / 10f) + t2v(sin(q * 1.7f), cos(q * 2.3f), sin(q * 1.1f)) * 0.6f }, { t -> r * (0.9f + 0.15f * sin(t * 9f)) }, T2_BONE, 1f, 14, true)
        for (q in 0 until 6) g.ball(a + (c - a) * (q / 5f) + t2v(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f) * (r * 0.9f), r * 0.55f, T2_BONE, 1f, 7, 10)
    }
    run {   // osteoblasts along the underside of the upper beam, facing the marrow
        val a = t2v(beams[0][0], beams[0][1], beams[0][2]); val c = t2v(beams[0][3], beams[0][4], beams[0][5])
        for (q in 0 until 14) {
            val t = 0.3f + q * 0.03f; val p = a + (c - a) * t
            g.ellAxis(p + t2v(0f, -beams[0][6] * 0.93f, 0f), t2v(0f, 1f, 0f), 0.95f, 0.85f, 0.9f, T2_OSTEOBLAST, 1f, 5, 8)
        }
        val a3 = t2v(beams[2][0], beams[2][1], beams[2][2]); val c3 = t2v(beams[2][3], beams[2][4], beams[2][5])
        val p = a3 + (c3 - a3) * 0.42f; val nrm = t2v(-1f, 0.2f, -0.3f).unit()
        val oc = p + nrm * (beams[2][6] * 0.9f)
        g.ellAxis(oc, nrm, 3.4f, 1.2f, 2.4f, T2_OSTEOCLAST, 1f, 8, 12)
        for (k in 0 until 5) { val e = t2perp(nrm); val f = nrm cross e; val a = k * 1.26f; g.ball(oc + nrm * 1.0f + (e * cos(a) + f * sin(a)) * 1.4f, 0.42f, T2_NUCLEUS, 1f, 5, 7) }
    }
    // ---- the sinusoid: a wide thin-walled vessel below the lane, endothelial nuclei on its wall
    val sx = T2_SIN[0]; val sy = T2_SIN[1]; val sr = T2_SIN[2]
    glass.surf(30, 26, T2_SINUSOID, 0.26f) { v, u -> val a = u * 2f * T2PI; t2v(sx + cos(a) * sr, sy + sin(a) * sr, -9f + 26f * v) }
    repeat(10) { val a = rnd.nextFloat() * 2f * T2PI; val z = -6f + rnd.nextFloat() * 20f
        glass.ellAxis(t2v(sx + cos(a) * sr, sy + sin(a) * sr, z), t2v(cos(a), sin(a), 0f), 0.35f, 0.14f, 0.8f, T2_NUCLEUS, 0.7f, 4, 7) }
    // ---- megakaryocyte nucleus: six fused lobes (polyploid); the cytoplasm is drawn translucent
    val mk = t2v(T2_MK_C[0], T2_MK_C[1], T2_MK_C[2])
    for (k in 0 until 6) { val a = k * 1.05f; g.ball(mk + t2v(cos(a) * 0.9f, sin(a * 1.3f) * 0.6f, sin(a) * 0.9f), 1.05f + 0.15f * (k % 2), T2_MEGA_NUC, 1f, 7, 10) }
    // proplatelets: beaded cytoplasmic strands pushed through the sinusoid wall, trailing downstream
    for (s in 0 until 3) {
        val st = mk + t2v(-2.1f + s * 0.4f, -2.0f, -0.8f + s * 0.8f)
        val pts = ArrayList<T2V>()
        for (q in 0..10) { val t = q / 10f
            val p = if (t < 0.4f) st + (t2v(sx + 1.2f, sy + 1.3f, st.z) - st) * (t / 0.4f) else t2v(sx + 1.2f - s * 0.5f, sy + 1.3f - s * 0.6f, st.z + (t - 0.4f) * 7f)
            pts.add(p) }
        g.path(pts, { 0.06f }, T2_PLATELET, 1f, 5, true)
        for (q in 1..10) g.ball(pts[q], 0.2f + 0.03f * (q % 2), T2_PLATELET, 1f, 5, 7)
    }
    // ---- erythroblastic island: central macrophage ringed by erythroblasts maturing round it
    val isl = t2v(T2_ISLAND[0], T2_ISLAND[1], T2_ISLAND[2])
    g.ball(isl, 1.3f, T2_MACROPHAGE, 1f, 9, 12)
    for (k in 0 until 8) {
        val a = k * T2PI / 4.5f; val d = t2v(cos(a), sin(a) * 0.55f, sin(a) * 0.85f).unit()
        val t = k / 8f; val c = t2mix(T2_PROERYTHRO, T2_NORMOBLAST, t)
        g.ball(isl + d * 1.85f, 0.62f - 0.18f * t, c, 1f, 6, 9)
        g.tube(isl + d * 1.1f, isl + d * 1.6f, 0.08f, 0.06f, T2_MACROPHAGE, 1f, 4, false)
    }
    // ---- B-cell precursor against a reticular stromal cell by the sinusoid wall
    val bc = t2v(T2_BCELL[0], T2_BCELL[1], T2_BCELL[2])
    g.ball(bc, 0.36f, T2_PROERYTHRO, 1f, 6, 9)
    glass.ball(bc, 0.46f, T2_LYMPHOID, 0.5f, 6, 9)
    val stc = bc + t2v(-0.9f, 0.5f, 0.6f)
    g.ellAxis(stc, t2v(0.3f, 0.2f, 1f), 0.32f, 1.2f, 0.35f, T2_STROMA, 1f, 5, 8)
    for (k in 0 until 4) { val a = k * 1.57f + 0.4f; g.path(listOf(stc, stc + t2v(cos(a) * 1.2f, sin(a) * 1.0f, 0.6f), stc + t2v(cos(a) * 2.4f, sin(a) * 1.6f + 0.3f, 1.3f)), { 0.06f }, T2_STROMA, 1f, 4, true) }
    // ---- adipocytes: big clear fat cells with the nucleus squeezed to the rim
    val fats = arrayOf(floatArrayOf(-7.8f, 5.2f, 12f, 3.6f), floatArrayOf(8.2f, 6.6f, 15f, 3.9f), floatArrayOf(-8.2f, -6.8f, 14f, 3.4f), floatArrayOf(2.5f, 9.3f, 3f, 3.2f))
    for (f in fats) { glass.ball(t2v(f[0], f[1], f[2]), f[3], T2_FAT, 0.3f, 10, 14); g.ellAxis(t2v(f[0], f[1], f[2]) + t2v(-f[0], -f[1], 0f).unit() * (f[3] - 0.15f), t2v(-f[0], -f[1], 0f), 0.7f, 0.2f, 0.5f, T2_NUCLEUS, 1f, 5, 8) }
    // ---- haematopoietic cords: mixed precursors packed around everything, clear of the lane
    // H&E-like: myeloid precursors pale lilac, erythroid precursors from blue-purple to pink-red,
    // lymphoid small and blue, with a few mature red cells
    val palette = arrayOf(T2_PROERYTHRO, T2_NORMOBLAST, T2_MYELOID, T2_MYELOID, T2_MYELOID, T2_LYMPHOID, T2_PROERYTHRO, T2_RETIC)
    var placed = 0; var tries = 0
    while (placed < 70 && tries < 6000) {
        tries++
        val x = (rnd.nextFloat() * 2f - 1f) * 9f; val y = (rnd.nextFloat() * 2f - 1f) * 8f; val z = -6f + rnd.nextFloat() * 22f
        val r = 0.4f + rnd.nextFloat() * 0.35f
        t2RailOffset(i, z, off)
        val lx = x - off[0]; val ly = y - off[1]
        if (lx * lx + ly * ly < 2.9f * 2.9f) continue
        if (x * x + y * y > 9.5f * 9.5f) continue
        val dsx = x - sx; val dsy = y - sy; if (sqrt(dsx * dsx + dsy * dsy) < sr + r + 0.2f) continue
        val mx = (x - T2_MK_C[0]) / (T2_MK_R[0] + r + 0.3f); val my = (y - T2_MK_C[1]) / (T2_MK_R[1] + r + 0.3f); val mz = (z - T2_MK_C[2]) / (T2_MK_R[2] + r + 0.3f)
        if (mx * mx + my * my + mz * mz < 1f) continue
        if ((t2v(x, y, z) - isl).len() < 3.0f) continue
        if ((t2v(x, y, z) - t2v(T2_HSC[0], T2_HSC[1], T2_HSC[2])).len() < 1.6f) continue
        if ((t2v(x, y, z) - bc).len() < 2.2f) continue
        if (fats.any { f -> (t2v(x, y, z) - t2v(f[0], f[1], f[2])).len() < f[3] + r + 0.1f }) continue
        g.ball(t2v(x, y, z), r, palette[rnd.nextInt(palette.size)], 1f, 5, 8)
        placed++
    }
    val rigid = t2Bend(i, 0f, 0f)
    arrayOf(TriMesh(g.baked(rigid)), TriMesh(glass.baked(rigid)))
}

/** Tour II stop 7 (and Chapter III's marrow): the blood factory inside the bone. */
internal fun StereoBodyRenderer.drawMarrow(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) { own ->
        val m = t2MarrowMeshes(i)
        val f0 = t2Rigid(i, 0f, FloatArray(13))
        t2DrawWorld(m[0])
        // blood in the sinusoid: red cells and freshly shed platelets flowing downstream
        val sx = T2_SIN[0]; val sy = T2_SIN[1]
        for (k in 0 until (if (quality == 0) 9 else 4)) {
            val z = ((seconds * 0.9f + k * 2.9f) % 26f) - 9f
            val a = k * 2.3f; val r = 1.4f * ((k * 37) % 10) / 10f
            val p = t2W(f0, sx + cos(a) * r, sy + sin(a) * r, z).copyOf()
            val tb = seconds * 0.4f + k
            t2Basis(p, f0[3], f0[4], f0[5], f0[6] * cos(tb) + f0[9] * sin(tb), f0[7] * cos(tb) + f0[10] * sin(tb), f0[8] * cos(tb) + f0[11] * sin(tb), 0.47f, 0.47f, 0.47f, rbc, COL_RBC_DEOXY, COL_RBC_RIM)
        }
        for (s in 0 until 3) {   // a platelet breaking off each proplatelet tip every few seconds
            val t = ((seconds / 3.2f) + s * 0.33f) % 1f
            val z = T2_MK_C[2] - 0.8f + s * 0.8f + 6f + t * 9f
            val p = t2W(f0, sx + 1.2f - s * 0.5f, sy + 1.3f - s * 0.6f - t * 0.4f, z).copyOf()
            t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.2f, 0.08f, 0.16f, sphere, T2_PLATELET, COL_LAMP, 1f - t * 0.5f)
        }
        // erythroblastic island: every 12 s the ripest erythroblast extrudes its nucleus (the
        // macrophage eats it) and leaves as a reticulocyte for the sinusoid
        run {
            val t = (seconds % 12f) / 12f
            val isl = T2_ISLAND; val a = 1.1f
            val dx = cos(a); val dy = sin(a) * 0.55f; val dz = sin(a) * 0.85f; val dl = sqrt(dx * dx + dy * dy + dz * dz)
            val ex = isl[0] + dx / dl * 1.85f; val ey = isl[1] + dy / dl * 1.85f; val ez = isl[2] + dz / dl * 1.85f
            val out = t2sm((t - 0.2f) / 0.2f); val go = t2sm((t - 0.45f) / 0.45f)
            val nx = ex + (isl[0] - ex) * out * 0.5f; val ny = ey + (isl[1] - ey) * out * 0.5f + 0.3f * out; val nz = ez + (isl[2] - ez) * out * 0.5f
            val nr = 0.24f * (1f - t2sm((t - 0.4f) / 0.3f))
            if (nr > 0.02f) t2Basis(t2W(f0, nx, ny, nz).copyOf(), f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], nr, nr, nr, sphere, T2_NUCLEUS)
            val cx = ex + (sx - 0.8f - ex) * go; val cy = ey + (sy + 0.6f - ey) * go; val cz = ez + (ez + 3f - ez) * go
            val p = t2W(f0, cx, cy, cz).copyOf()
            val al = 1f - t2sm((t - 0.85f) / 0.15f)
            if (out < 0.5f) t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.44f, 0.44f, 0.44f, sphere, T2_NORMOBLAST, COL_LAMP, al)
            else t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.46f, 0.46f, 0.46f, rbc, T2_RETIC, COL_RBC_RIM, al)
        }
        // a haematopoietic stem cell dividing (16 s): chromosomes gather on a plate, part, and the
        // cell pinches in two; one daughter stays, the other drifts off to differentiate.
        run {
            val t = (seconds % 16f) / 16f
            val h = T2_HSC
            val split = t2sm((t - 0.45f) / 0.15f); val pinch = t2sm((t - 0.6f) / 0.15f); val away = t2sm((t - 0.78f) / 0.2f)
            val sep = 0.25f * split + 0.3f * pinch
            for (sgn in SIGNS) {
                val dzc = sgn * (sep + (if (sgn > 0f) away * 1.6f else 0f))
                val p = t2W(f0, h[0] + (if (sgn > 0f) away * 0.8f else 0f), h[1], h[2] + dzc).copyOf()
                val al = if (sgn > 0f) 1f - t2sm((t - 0.9f) / 0.1f) else 1f
                val plate = t2W(f0, h[0], h[1], h[2] + sgn * 0.3f * split + dzc * pinch).copyOf()
                if (t > 0.2f && t < 0.8f) t2Basis(plate, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.3f, 0.3f, 0.06f, sphere, T2_CHROMATID, COL_LAMP, t2sm((t - 0.2f) / 0.1f) * (1f - t2sm((t - 0.7f) / 0.1f)), 0.3f)
                if (pinch > 0.02f || sgn < 0f) {
                    val r = 0.5f - 0.1f * pinch
                    t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], r, r, r * (1f + 0.4f * split * (1f - pinch)), sphere, T2_LYMPHOID, COL_LAMP, (if (pinch < 0.02f) 0.55f else 0.6f) * al)
                    if (t < 0.2f || t > 0.8f) t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.28f, 0.28f, 0.28f, sphere, T2_PROERYTHRO, COL_LAMP, al)
                }
            }
        }
        t2DrawWorld(m[1], true)
        // megakaryocyte cytoplasm, translucent over its lobed nucleus
        val mkp = t2W(f0, T2_MK_C[0], T2_MK_C[1], T2_MK_C[2]).copyOf()
        t2Basis(mkp, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], T2_MK_R[0], T2_MK_R[1], T2_MK_R[2], sphere, T2_MEGA, COL_LAMP, 0.5f, 0.05f)
    }
}

// ============================================================================== stop 7: V(D)J
// 8 nm a unit (Mote 12 nm). The heavy-chain locus of a pro-B cell as a real double helix (2 nm
// wide, 3.4 nm pitch) running beside the craft, nucleosome-packed on either side, open (no
// nucleosomes) across the recombining region; gene segments are colour-coded stretches of helix
// with their recombination signal sequences (white). In two steps per round — D to J, then V to
// DJ — the RAG1/RAG2 complex pairs a 12-RSS with a 23-RSS, the DNA between loops out, both are
// cut, the loop leaves as a closed signal-joint circle, Ku rings grab the coding ends, TdT adds
// a few random N nucleotides and ligase IV seals the joint. The segment lengths and the gaps
// between them are compressed ~10x along the thread so a whole round fits the window.

private const val T2K_SP = 0
private const val T2K_V = 1
private const val T2K_D = 2
private const val T2K_J = 3
private const val T2K_C = 4
private const val T2K_R12 = 5
private const val T2K_R23 = 6
private const val T2K_N = 7

private class T2Piece(val len: Float, val kind: Int, val id: Int)

private fun t2Germline(): List<T2Piece> {
    val l = ArrayList<T2Piece>()
    l.add(T2Piece(0.5f, T2K_SP, 0))
    for (v in 3 downTo 1) { l.add(T2Piece(1.4f, T2K_V, v)); l.add(T2Piece(0.3f, T2K_R23, v)); l.add(T2Piece(if (v > 1) 0.4f else 0.9f, T2K_SP, 0)) }
    for (d in 1..3) { l.add(T2Piece(0.2f, T2K_R12, 10 + d)); l.add(T2Piece(0.3f, T2K_D, d)); l.add(T2Piece(0.2f, T2K_R12, 20 + d)); l.add(T2Piece(if (d < 3) 0.3f else 0.9f, T2K_SP, 0)) }
    for (j in 1..4) { l.add(T2Piece(0.3f, T2K_R23, 30 + j)); l.add(T2Piece(0.45f, T2K_J, j)); l.add(T2Piece(if (j < 4) 0.3f else 1.0f, T2K_SP, 0)) }
    l.add(T2Piece(2.0f, T2K_C, 0)); l.add(T2Piece(0.5f, T2K_SP, 0))
    return l
}

private fun t2PieceCol(p: T2Piece): FloatArray = when (p.kind) {
    T2K_V -> T2_SEG_V; T2K_D -> T2_SEG_D; T2K_J -> T2_SEG_J; T2K_C -> T2_SEG_C
    T2K_R12, T2K_R23 -> T2_RSS; T2K_N -> T2_SIGNAL; else -> T2_SEG_SPACER
}

/** Two turns of B-DNA, length 1 along z, radius 0.125 (backbones with a major and a minor groove, base pairs across). */
private fun StereoBodyRenderer.t2HelixChunk(): LitMesh = t2Get("helixchunk") {
    val g = T2Geo(); val turns = 2f; val rb = 0.1f
    for (strand in 0..1) {
        val off = if (strand == 0) 0f else 2.3f      // ~130 degrees apart: minor groove one side, major the other
        g.path((0..40).map { q -> val t = q / 40f; val a = t * turns * 2f * T2PI + off; t2v(cos(a) * rb, sin(a) * rb, t - 0.5f) }, { 0.03f }, T2_DNA, 1f, 5, false)
    }
    for (q in 0 until 20) {
        val t = (q + 0.5f) / 20f; val a = t * turns * 2f * T2PI
        g.tube(t2v(cos(a) * rb, sin(a) * rb, t - 0.5f), t2v(cos(a + 2.3f) * rb, sin(a + 2.3f) * rb, t - 0.5f), 0.018f, 0.018f, T2_DNA, 1f, 3, false)
    }
    T2Lit(g.lit())
}

private fun StereoBodyRenderer.t2RagMesh(): LitMesh = t2Get("rag") {
    val g = T2Geo()
    // RAG1 dimer (the V) with RAG2 on its arms (the Y), stem below: ~2.2 units (18 nm) across
    for (sd in SIGNS) {
        g.ellAxis(t2v(sd * 0.5f, 0.1f, 0f), t2v(sd * 0.6f, 1f, 0f), 0.5f, 0.8f, 0.5f, T2_RAG1, 1f, 7, 10)
        g.ball(t2v(sd * 0.98f, 0.95f, 0f), 0.42f, T2_RAG2, 1f, 7, 10)
    }
    g.ball(t2v(0f, -0.5f, 0f), 0.36f, T2_RAG1, 1f, 6, 9)
    T2Lit(g.lit())
}

/** Nucleosome-packed chromatin on both sides of the working region (local coordinates, bent with the rail). */
private fun StereoBodyRenderer.t2Chromatin(i: Int): TriMesh = t2Get("chromatin$i") {
    val g = T2Geo(); val bx = 1.8f; val by = -0.4f
    for (sd in SIGNS) {
        val start = if (sd < 0f) -7.6f else 11.9f
        var prev: T2V? = null
        for (k in 0 until 7) {
            val z = start + sd * (0.9f + k * 2.3f)
            val zig = if (k % 2 == 0) 0.55f else -0.55f
            val c = t2v(bx + zig, by + zig * 0.6f, z)
            val ax = t2v(0.6f, 1f, 0.2f * sd).unit()
            // histone octamer (11 nm x 6 nm) with 1.65 left-handed turns of DNA wrapped round it
            g.ellAxis(c, ax, 0.66f, 0.34f, 0.66f, T2_HISTONE, 1f, 7, 12)
            val e1 = t2perp(ax); val e2 = ax cross e1
            val wrap = (0..30).map { q -> val t = q / 30f; val a = -t * 1.65f * 2f * T2PI; c + (e1 * cos(a) + e2 * sin(a)) * 0.8f + ax * ((t - 0.5f) * 0.5f) }
            g.path(wrap, { 0.12f }, T2_DNA_WRAP, 1f, 6, false)
            if (prev != null) g.path(listOf(prev, (prev + wrap[0]) * 0.5f + t2v(0f, 0.3f, 0f), wrap[0]), { 0.12f }, T2_DNA_WRAP, 1f, 6, false)
            prev = wrap.last()
        }
    }
    // other chromatin fibres of the nucleus around the room (nucleosome strings in 30 nm fibres)
    val rnd = java.util.Random(19L)
    for (f in 0 until 5) {
        val a = f * 1.26f + 1.9f; val r = 5.5f + (f % 2) * 2f
        var prev: T2V? = null
        for (k in 0 until 12) {
            val z = -10f + k * 2.2f
            val c = t2v(cos(a + 0.12f * k) * r, sin(a + 0.12f * k) * r * 0.8f + 0.5f * sin(k * 1.3f), z)
            g.ellAxis(c, t2v(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).unit(), 0.66f, 0.34f, 0.66f, T2_HISTONE, 1f, 6, 9)
            if (prev != null) g.path(listOf(prev, (prev + c) * 0.5f + t2v(0.4f, 0.3f, 0f), c), { 0.12f }, T2_DNA_WRAP, 1f, 5, false)
            prev = c
        }
    }
    t2Bake(g, i, 3f, 9f)
}

/** Scratch for the locus layout: per piece, its start and end in local coords (x, y, z). */
private val t2Lay = FloatArray(64 * 6)

/** Tour II stop 8: V(D)J recombination on a double helix, in two steps. */
internal fun StereoBodyRenderer.drawVdj(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) { _ ->
        t2DrawWorld(t2Chromatin(i))
        val chunk = t2HelixChunk()
        val round = (seconds / 36f).toInt(); val t = seconds % 36f
        val vPick = 1 + (round * 2) % 3; val dPick = 1 + (round + 1) % 3; val jPick = 1 + (round * 3 + 1) % 4
        val germ = t2Germline()
        // step 1: D-J (t 1..15); step 2: V-DJ (t 16..30); glow 30..33; reset 33..36
        val step = if (t < 15.5f) 0 else 1
        val tau = if (step == 0) t - 1f else t - 16f
        var list = germ
        if (step == 1) {   // DJ already joined: drop D(3') RSS .. J(5') RSS, put N nucleotides at the joint
            val a = germ.indexOfFirst { it.kind == T2K_R12 && it.id == 20 + dPick }; val b = germ.indexOfFirst { it.kind == T2K_R23 && it.id == 30 + jPick }
            list = germ.subList(0, a) + listOf(T2Piece(0.2f, T2K_N, 0)) + germ.subList(b + 1, germ.size)
        }
        val la: Int; val lb: Int
        if (step == 0) { la = list.indexOfFirst { it.kind == T2K_R12 && it.id == 20 + dPick }; lb = list.indexOfFirst { it.kind == T2K_R23 && it.id == 30 + jPick } }
        else { la = list.indexOfFirst { it.kind == T2K_R23 && it.id == vPick }; lb = list.indexOfFirst { it.kind == T2K_R12 && it.id == 10 + dPick } }
        val close = t2sm((tau - 1f) / 3f); val cut = tau > 4.5f
        val gap = if (!cut) 0f else 0.35f * (1f - t2sm((tau - 8.5f) / 1.5f))
        var sLoop = 0f; for (k in la..lb) sLoop += list[k].len
        var z0 = -7f; var sBefore = 0f; for (k in 0 until la) sBefore += list[k].len
        val zSyn = z0 + sBefore + sLoop * 0.5f
        val chord = if (!cut) sLoop + (0.35f - sLoop) * close else gap
        val bx = 1.8f; val by = -0.4f; val bdx = 0.75f; val bdy = -0.66f
        // arc through the loop: half-angle th with sin(th)/th = chord / sLoop
        var th = 0.001f
        if (!cut && chord < sLoop * 0.999f) { var lo = 0.001f; var hi = T2PI; repeat(30) { val mid = (lo + hi) * 0.5f; if (sin(mid) / mid > chord / sLoop) lo = mid else hi = mid }; th = (lo + hi) * 0.5f }
        val R = if (th > 0.002f) sLoop / (2f * th) else 1e6f
        val globalAlpha = if (t > 33f) 1f - t2sm((t - 33f) / 1.5f) else if (t < 1f) t2sm(t / 1f) else 1f
        val glow = if (t in 30f..33f) 0.6f * sin((t - 30f) / 3f * T2PI) else 0f
        // lay out every piece: left of the loop, the loop, right of the loop
        fun posOf(s: Float, out: FloatArray) {   // s = arc length along the list
            val sA = sBefore; val sB = sBefore + sLoop
            if (s <= sA) { val z = zSyn - chord * 0.5f - (sA - s); out[0] = bx; out[1] = by; out[2] = z }
            else if (s >= sB) { val z = zSyn + chord * 0.5f + (s - sB); out[0] = bx; out[1] = by; out[2] = z }
            else if (cut) { out[0] = bx; out[1] = by; out[2] = zSyn }
            else if (th < 0.002f) { out[0] = bx; out[1] = by; out[2] = zSyn - sLoop * 0.5f + (s - sA) }
            else { val phi = (s - sA) / R - th; val cth = cos(th)
                val bo = R * (cos(phi) - cth); out[0] = bx + bdx * bo; out[1] = by + bdy * bo; out[2] = zSyn + R * sin(phi) }
        }
        val pA = FloatArray(3); val pB = FloatArray(3)
        var s = 0f
        for ((k, pc) in list.withIndex()) {
            if (cut && k in la..lb) { s += pc.len; continue }
            val nch = max(1, ceil(pc.len / 0.85f).toInt())
            for (c in 0 until nch) {
                posOf(s + pc.len * c / nch, pA); posOf(s + pc.len * (c + 1) / nch, pB)
                t2DrawChunk(i, pA, pB, chunk, t2PieceCol(pc), globalAlpha, glow + (if (pc.kind == T2K_N) 0.4f else 0f))
            }
            s += pc.len
        }
        // the excised loop, closed into a signal-joint circle, drifting off and fading
        if (cut && tau < 9.5f) {
            val drift = t2sm((tau - 4.5f) / 5f) * 3f; val al = globalAlpha * (1f - t2sm((tau - 7.5f) / 2f))
            val rc = sLoop / (2f * T2PI)
            val cx = bx + bdx * (rc + drift); val cy = by + bdy * (rc + drift)
            var ss = 0f
            for (k in la..lb) {
                val pc = list[k]; val nch = max(1, ceil(pc.len / 0.85f).toInt())
                for (c in 0 until nch) {
                    val a0 = (ss + pc.len * c / nch) / rc; val a1 = (ss + pc.len * (c + 1) / nch) / rc
                    pA[0] = cx - bdx * rc * cos(a0); pA[1] = cy - bdy * rc * cos(a0); pA[2] = zSyn + rc * sin(a0)
                    pB[0] = cx - bdx * rc * cos(a1); pB[1] = cy - bdy * rc * cos(a1); pB[2] = zSyn + rc * sin(a1)
                    t2DrawChunk(i, pA, pB, chunk, t2PieceCol(pc), al, 0f)
                }
                ss += pc.len
            }
        }
        // RAG1/2: scans in from the J region, then holds the two RSSs together in the synaptic complex
        val ragZ = if (step == 0) (5.5f + (zSyn - 5.5f) * t2sm(tau / 1.5f)) else zSyn
        val ragOn = globalAlpha * (1f - t2sm((tau - 5.5f) / 1.5f))
        if (ragOn > 0.02f && tau > -0.5f) {
            val fr = t2Frame(i, ragZ)
            val p = t2W(fr, bx + bdx * 0.9f, by + bdy * 0.9f).copyOf()
            // stem toward the loop side, arms reaching back to the two RSSs
            val yx = -(fr[6] * bdx + fr[9] * bdy); val yy = -(fr[7] * bdx + fr[10] * bdy); val yz = -(fr[8] * bdx + fr[11] * bdy)
            val zx = fr[9] * bdx - fr[6] * bdy; val zy = fr[10] * bdx - fr[7] * bdy; val zz = fr[11] * bdx - fr[8] * bdy
            t2Basis(p, zx, zy, zz, yx, yy, yz, 1f, 1f, 1f, t2RagMesh(), T2_RAG1, COL_LAMP, ragOn, if (tau in 4.3f..5.2f) 0.8f else 0.05f)
        }
        // the cut: a flash at both RSS / coding borders
        if (tau in 4.2f..5.6f) {
            val fl = sin((tau - 4.2f) / 1.4f * T2PI)
            for (sd in SIGNS) { val fr = t2Frame(i, zSyn + sd * 0.2f); t2Blob(fr, bx, by, 0.25f, 0.25f, 0.25f, T2_RSS, T2_RSS, 0.9f * fl * globalAlpha, 1.5f) }
        }
        // repair: Ku70/80 rings on the two coding ends, TdT's random nucleotides, ligase IV sealing
        if (cut && tau < 11f) {
            val ku = t2sm((tau - 5f) / 1f) * (1f - t2sm((tau - 9.8f) / 1f)) * globalAlpha
            if (ku > 0.02f) for (sd in SIGNS) {
                val fr = t2Frame(i, zSyn + sd * (gap * 0.5f + 0.18f))
                t2Basis(t2W(fr, bx, by).copyOf(), fr[3], fr[4], fr[5], fr[9], fr[10], fr[11], 1f, 1f, 1f, t2KuMesh(), T2_KU, COL_LAMP, ku, 0.1f)
            }
            val nn = ((tau - 6.5f) / 0.6f).toInt().coerceIn(0, 3)
            val ncol = arrayOf(T2_SEG_D, T2_SIGNAL, T2_SEG_V, T2_SEG_J)
            for (q in 0 until nn) { val fr = t2Frame(i, zSyn + (q - 1f) * 0.1f); t2Blob(fr, bx, by + 0.02f, 0.07f, 0.07f, 0.07f, ncol[(q + round) % 4], COL_LAMP, globalAlpha, 0.5f) }
            if (tau in 6.3f..8.6f) { val fr = t2Frame(i, zSyn); t2Blob(fr, bx + 0.35f, by + 0.45f, 0.36f, 0.3f, 0.3f, T2_TONSIL, COL_LAMP, globalAlpha * 0.95f) }     // TdT
            if (tau in 8.3f..10.8f) { val fr = t2Frame(i, zSyn); t2Blob(fr, bx - 0.4f, by + 0.5f, 0.5f, 0.4f, 0.4f, T2_LIGASE, COL_LAMP, globalAlpha * 0.95f, if (tau > 9.7f) 0.6f else 0.05f) }
        }
    }
}

/** A thin unit ring in the x/y plane (contractile ring, fusion pore). */
private fun StereoBodyRenderer.t2RingMesh(): LitMesh = t2Get("ring") {
    val g = T2Geo(); g.torus(t2v(0f, 0f, 0f), t2v(0f, 0f, 1f), 1f, 0.045f, T2_ACTOMYOSIN, 1f, 48, 8); T2Lit(g.lit())
}

private fun StereoBodyRenderer.t2KuMesh(): LitMesh = t2Get("ku") {
    val g = T2Geo(); g.torus(t2v(0f, 0f, 0f), t2v(0f, 0f, 1f), 0.34f, 0.16f, T2_KU, 1f, 16, 7); T2Lit(g.lit())
}

/** One helix chunk from local point a to b (in the stop's rail coordinates). */
private fun StereoBodyRenderer.t2DrawChunk(i: Int, a: FloatArray, b: FloatArray, chunk: LitMesh, col: FloatArray, alpha: Float, glow: Float) {
    if (alpha < 0.02f) return
    val fa = t2Frame(i, a[2]); val ax = fa[0] + fa[6] * a[0] + fa[9] * a[1]; val ay = fa[1] + fa[7] * a[0] + fa[10] * a[1]; val az = fa[2] + fa[8] * a[0] + fa[11] * a[1]
    val ux = fa[9]; val uy = fa[10]; val uz = fa[11]
    val fb = t2Frame(i, b[2]); val bxw = fb[0] + fb[6] * b[0] + fb[9] * b[1]; val byw = fb[1] + fb[7] * b[0] + fb[10] * b[1]; val bzw = fb[2] + fb[8] * b[0] + fb[11] * b[1]
    val dx = bxw - ax; val dy = byw - ay; val dz = bzw - az; val l = sqrt(dx * dx + dy * dy + dz * dz)
    if (l < 1e-4f) return
    t2Q[0] = (ax + bxw) * 0.5f; t2Q[1] = (ay + byw) * 0.5f; t2Q[2] = (az + bzw) * 0.5f
    t2Basis(t2Q, dx / l, dy / l, dz / l, ux, uy, uz, 1f, 1f, l, chunk, col, COL_LAMP, alpha, 0.08f + glow)
}

// ========================================================================== stop 8: HIGHWAY
// 80 nm a unit (Mote 120 nm). A hepatocyte's cytoplasm: a microtubule (13 protofilaments, 25 nm)
// with its minus end capped by a γ-tubulin ring toward the cell centre (behind) and its plus
// end ahead (GTP cap, growing and shrinking). Kinesin walks hand over hand toward the plus end
// with a vesicle; a dynein dimer (ring-shaped AAA+ motor heads on stalks) hauls a lysosome
// toward the minus end. Around: rough ER sheets studded with 25 nm ribosomes to port, a
// mitochondrion with cristae to starboard, a Golgi stack ahead, lysosomes.

private const val T2_MT_X = 1.9f
private const val T2_MT_Y = -1.5f
private const val T2_MT_Z0 = -9f
private const val T2_MT_Z1 = 10f

private fun StereoBodyRenderer.t2HighwayMeshes(i: Int): Array<ColorVboMesh> = t2Get("highway$i") {
    val g = T2Geo(); val glass = T2Geo(); val rnd = java.util.Random(71L); val lat = ArrayList<Float>()
    val mx = T2_MT_X; val my = T2_MT_Y
    for (k in 0 until 13) {
        val a = k * 2f * T2PI / 13f; val x = mx + cos(a) * 0.125f; val y = my + sin(a) * 0.125f
        g.path(listOf(t2v(x, y, T2_MT_Z0), t2v(x, y, T2_MT_Z1 - 0.3f)), { 0.042f }, T2_TUBULIN, 1f, 5, false)
        g.path(listOf(t2v(x, y, T2_MT_Z1 - 0.3f), t2v(x, y, T2_MT_Z1)), { 0.042f }, T2_GTP_CAP, 1f, 5, false)
    }
    // tubulin dimer boundaries (8 nm) on the lattice, and the γ-tubulin ring complex capping the minus end
    var z = T2_MT_Z0
    while (z < T2_MT_Z1) {
        for (k in 0 until 13) {
            val a0 = k * 2f * T2PI / 13f; val a1 = (k + 1) * 2f * T2PI / 13f; val dz = 0.0092f
            lat.addAll(listOf(mx + cos(a0) * 0.16f, my + sin(a0) * 0.16f, z + k * dz, 0.35f, 0.62f, 0.48f, 0.8f, mx + cos(a1) * 0.16f, my + sin(a1) * 0.16f, z + (k + 1) * dz, 0.35f, 0.62f, 0.48f, 0.8f))
        }
        z += 0.1f
    }
    g.torus(t2v(mx, my, T2_MT_Z0 - 0.04f), t2v(0f, 0f, 1f), 0.13f, 0.05f, T2_GTURC, 1f, 13, 5)
    for (k in 0 until 13) { val a = k * 2f * T2PI / 13f; g.ball(t2v(mx + cos(a) * 0.13f, my + sin(a) * 0.13f, T2_MT_Z0 - 0.1f - 0.02f * k), 0.05f, T2_GTURC, 1f, 3, 5) }
    // more microtubules of the network, further off
    g.path(listOf(t2v(-6.5f, 6.5f, -6f), t2v(-4.5f, 5.8f, 8f), t2v(-2.5f, 5.0f, 22f)), { 0.155f }, T2_TUBULIN, 1f, 8, true)
    g.path(listOf(t2v(5.5f, -1.2f, -6f), t2v(7.5f, 0.8f, 9f), t2v(9.5f, 2.8f, 24f)), { 0.155f }, T2_TUBULIN, 1f, 8, true)
    // mitochondrion (0.64 x 1.6 µm): outer and inner membranes translucent, lamellar cristae inside
    val mc = t2v(8.6f, -3.9f, 4.5f)
    fun capsuleSurf(geo: T2Geo, r: Float, half: Float, col: FloatArray, al: Float) = geo.surf(24, 20, col, al) { u, v ->
        val t = u * 2f - 1f; val a = v * 2f * T2PI
        val zz: Float; val rr: Float
        val cap = r / (half + r)
        if (t < -1f + cap) { val k = (t + 1f) / cap; val an = (1f - k) * T2PI / 2f; zz = -half - sin(an) * r; rr = cos(an) * r }
        else if (t > 1f - cap) { val k = (1f - t) / cap; val an = (1f - k) * T2PI / 2f; zz = half + sin(an) * r; rr = cos(an) * r }
        else { zz = t / (1f - cap) * half; rr = r }
        mc + t2v(cos(a) * rr, sin(a) * rr, zz)
    }
    capsuleSurf(glass, 4.0f, 6.0f, T2_MITO, 0.3f)
    capsuleSurf(glass, 3.72f, 5.75f, T2_MITO, 0.32f)
    for (k in 0 until 9) {
        val zc = mc.z - 7.2f + k * 1.8f; val off = if (k % 2 == 0) 0.55f else -0.55f
        val rmax = min(3.0f, sqrt(max(0.5f, 3.6f * 3.6f - max(0f, abs(zc - mc.z) - 5.4f).pow(2) * 4f)))
        g.ell(mc + t2v(off, (k % 3 - 1) * 0.4f, zc - mc.z), t2v(rmax, 0f, 0f), t2v(0f, rmax * 0.95f, 0f), t2v(0f, 0f, 0.16f), T2_MITO_CRISTA, 1f, 6, 14)
    }
    repeat(8) { g.ball(mc + t2v((rnd.nextFloat() - 0.5f) * 4f, (rnd.nextFloat() - 0.5f) * 4f, (rnd.nextFloat() - 0.5f) * 12f), 0.22f, T2_LYSO_CORE, 1f, 3, 5) }
    // rough ER: three flattened cisternae to port; ribosomes (25 nm, two subunits) crowd the
    // cytosolic face toward the road, many in polysome rows
    for (k in 0 until 3) {
        val xs = -4.2f - 1.8f * k
        for (face in 0..1) {
            val xo = if (face == 0) 0f else -0.6f
            val geo = if (k == 0) g else glass
            geo.surf(18, 24, if (face == 0) T2_ER else T2_HISTONE, if (geo === g) 1f else 0.45f) { u, v ->
                val yy = -6.5f + 13f * u; val zz = -7f + 21f * v
                t2v(xs + xo + 0.3f * sin(zz * 0.35f + k) + 0.15f * sin(yy * 0.6f), yy, zz)
            }
        }
    }
    var yy = -5.6f
    while (yy < 5.6f) {
        var zz = -6f
        while (zz < 13f) {
            if (rnd.nextFloat() < 0.4f) {
                val xsurf = -4.2f + 0.3f * sin(zz * 0.35f) + 0.15f * sin(yy * 0.6f)
                g.ball(t2v(xsurf + 0.2f, yy, zz), 0.2f, T2_RIBO_60S, 1f, 4, 6)
                g.ball(t2v(xsurf + 0.45f, yy + 0.05f, zz), 0.14f, T2_RIBO_40S, 1f, 4, 6)
            }
            zz += 0.62f + rnd.nextFloat() * 0.25f
        }
        yy += 0.75f
    }
    // Golgi stack ahead, cis face toward the ER: six curved cisternae with dilated rims, vesicles budding
    val ag = t2v(-1f, -0.25f, -0.2f).unit(); val ge1 = t2perp(ag); val ge2 = ag cross ge1
    val gc = t2v(5.2f, 5.4f, 13f)
    for (c in 0 until 6) {
        val off = (c - 2.5f) * 0.5f; val cc = gc + ag * off; val rc = 4.4f - 0.25f * abs(c - 2.5f)
        val col = t2mix(T2_GOLGI_TRANS, T2_GOLGI_CIS, c / 5f)
        g.surf(14, 24, col) { u, v ->
            val rho = if (u < 0.5f) u * 2f else (1f - u) * 2f
            val hs = (0.12f * sqrt(max(0f, 1f - rho * rho)) + 0.16f * exp(-((rho - 0.93f) / 0.07f).pow(2))) * (if (u < 0.5f) 1f else -1f)
            val a = v * 2f * T2PI
            cc + (ge1 * cos(a) + ge2 * sin(a)) * (rho * rc) + ag * (hs - 0.9f * rho * rho)
        }
    }
    repeat(14) { val a = rnd.nextFloat() * 2f * T2PI; val c = (rnd.nextFloat() - 0.5f) * 3f
        g.ball(gc + ag * (c - 0.8f) + (ge1 * cos(a) + ge2 * sin(a)) * (4.5f + rnd.nextFloat() * 0.6f), 0.32f + rnd.nextFloat() * 0.14f, T2_GOLGI_CIS, 1f, 5, 7) }
    // lysosomes: single membrane, dense heterogeneous contents
    for (l in arrayOf(floatArrayOf(-2.4f, 5.4f, 9f, 1.6f), floatArrayOf(-0.6f, -5.8f, 13f, 1.5f))) {
        val c = t2v(l[0], l[1], l[2]); glass.ball(c, l[3], T2_LYSOSOME, 0.4f, 10, 14)
        repeat(16) { g.ball(c + t2v(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f) * (l[3] * 1.1f), 0.12f + rnd.nextFloat() * 0.16f, T2_LYSO_CORE, 1f, 3, 5) }
        g.torus(c, t2v(0.3f, 1f, 0.2f), l[3] * 0.45f, 0.06f, T2_LYSOSOME, 1f, 14, 4)
    }
    val bend = t2Bend(i, 3f, 9f)
    val lA = lat.toFloatArray(); for (k in 0 until lA.size / 7) bend(lA, k * 7)
    arrayOf(TriMesh(g.baked(bend)), TriMesh(glass.baked(bend)), LineMesh(lA))
}

/** Dynein's motor: a ring of six AAA+ domains (~13 nm), as one lit mesh in its x/y plane. */
private fun StereoBodyRenderer.t2DyneinRing(): LitMesh = t2Get("dyneinring") {
    val g = T2Geo(); for (k in 0 until 6) { val a = k * T2PI / 3f; g.ball(t2v(cos(a) * 0.11f, sin(a) * 0.11f, 0f), 0.065f, T2_DYNEIN, 1f, 5, 7) }
    g.ball(t2v(0f, 0f, 0.03f), 0.05f, T2_DYNEIN, 1f, 4, 6); T2Lit(g.lit())
}

/** Tour II stop 9: kinesin and dynein on a polarised microtubule through a hepatocyte's organelles. */
internal fun StereoBodyRenderer.drawHighway(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) {
        val m = t2HighwayMeshes(i)
        t2DrawWorld(m[0])
        val mx = T2_MT_X; val my = T2_MT_Y
        // plus-end dynamic instability: growth with a GTP cap, then a catastrophe (protofilaments peel)
        val ph = (seconds % 14f) / 14f
        val ext = if (ph < 0.65f) 0.9f * t2sm(ph / 0.65f) else 0.9f * (1f - t2sm((ph - 0.65f) / 0.25f))
        if (ext > 0.03f) {
            val fr = t2Frame(i, T2_MT_Z1 + ext * 0.5f)
            t2Basis(t2W(fr, mx, my).copyOf(), fr[3], fr[4], fr[5], fr[9], fr[10], fr[11], 0.155f, 0.155f, ext * 0.5f, cylinder, if (ph < 0.65f) T2_GTP_CAP else T2_TUBULIN, COL_LAMP, 1f, 0.2f)
        }
        t2LinesBegin()
        if (ph >= 0.65f && ph < 0.92f) {
            val zt = T2_MT_Z1 + ext
            for (k in 0 until 13) {
                val a = k * 2f * T2PI / 13f; var px = 0f; var py = 0f; var pz = 0f
                for (q in 0..5) {
                    val t = q / 5f; val curl = t * 1.6f
                    val r = 0.125f + 0.25f * sin(curl) * t; val zz = zt + 0.3f * sin(curl) * t
                    val w = t2W(t2Frame(i, zz), mx + cos(a) * r, my + sin(a) * r)
                    if (q > 0) t2Seg(px, py, pz, w[0], w[1], w[2], T2_TUBULIN, 0.9f)
                    px = w[0]; py = w[1]; pz = w[2]
                }
            }
        }
        // ---- kinesin: two heads stepping 16 nm each, hand over hand (8 nm per step of the body),
        //      slowed ~50x; neck to a long stalk; light chains holding a vesicle on a tether
        val stepLen = 0.1f; val walk = (seconds * 2f) % 1f; val stepNo = (seconds * 2f).toInt()
        val head = (stepNo * stepLen) % 9f + 0.5f
        val swing = t2sm(walk); val lift = sin(swing * T2PI) * 0.07f
        val footA = head; val footB = head - stepLen + 2f * stepLen * swing
        val body = (footA + footB) * 0.5f; val top = my + 0.22f
        val fade = t2sm((head - 0.5f) / 0.6f) * (1f - t2sm((head - 8.9f) / 0.6f))
        val a = t2W(t2Frame(i, footA), mx, top).copyOf()
        val b = t2W(t2Frame(i, footB), mx, top + lift).copyOf()
        val hip = t2W(t2Frame(i, body), mx, top + 0.3f).copyOf()
        val sway = 0.04f * sin(seconds * 2f * T2PI)
        val cz = body - 0.55f
        val tether = t2W(t2Frame(i, cz), mx + 0.25f + sway, top + 1.05f).copyOf()
        val cargo = t2W(t2Frame(i, cz), mx + 0.3f + sway, top + 1.75f).copyOf()
        if (fade > 0.02f) {
            t2Basis(a, 0f, 0f, 1f, 0f, 1f, 0f, 0.09f, 0.07f, 0.1f, sphere, COL_KINESIN, COL_KINESIN_LIGHT, fade, 0.25f)
            t2Basis(b, 0f, 0f, 1f, 0f, 1f, 0f, 0.09f, 0.07f, 0.1f, sphere, COL_KINESIN, COL_KINESIN_LIGHT, fade, 0.25f)
            drawStrut(a[0], a[1], a[2], hip[0], hip[1], hip[2], 0.028f, COL_KINESIN, COL_KINESIN_LIGHT)
            drawStrut(b[0], b[1], b[2], hip[0], hip[1], hip[2], 0.028f, COL_KINESIN, COL_KINESIN_LIGHT)
            drawStrut(hip[0], hip[1], hip[2], tether[0], tether[1], tether[2], 0.03f, COL_KINESIN, COL_KINESIN_LIGHT)
            t2Basis(tether, 0f, 0f, 1f, 0f, 1f, 0f, 0.09f, 0.09f, 0.09f, sphere, COL_KINESIN_LIGHT, COL_LAMP, fade, 0.3f)
            for (k in 0 until 5) {
                val aa = k * 1.26f + seconds * 0.3f
                val p = t2W(t2Frame(i, cz + 0.35f * cos(aa)), mx + 0.3f + sway + 0.32f * sin(aa), top + 1.75f + 0.28f * sin(aa * 1.7f)).copyOf()
                t2Basis(p, 0f, 0f, 1f, 0f, 1f, 0f, 0.07f, 0.07f, 0.07f, sphere, COL_PROTEIN, COL_LAMP, fade)
            }
            t2Basis(cargo, 0f, 0f, 1f, 0f, 1f, 0f, 0.72f, 0.72f, 0.72f, sphere, COL_CARGO, COL_LAMP, 0.45f * fade, 0.2f)
        }
        // ---- dynein on the far side of the track, heading for the minus end with a lysosome
        val dz = 9.5f - ((seconds * 0.15f + 0.5f) % 1f) * 9f
        val dfade = t2sm((9.5f - dz) / 0.6f) * t2sm((dz - 0.5f) / 0.6f)
        val ring = t2DyneinRing()
        val jn = t2W(t2Frame(i, dz + 0.05f), mx + 0.62f, my + 0.72f).copyOf()
        for (h in 0..1) {
            val hz = dz + h * 0.12f + 0.05f * sin(seconds * (2.6f + h * 0.7f) + h * 2f)
            val fr = t2Frame(i, hz)
            val mtbd = t2W(fr, mx + 0.12f, my + 0.12f).copyOf()
            val rc = t2W(fr, mx + 0.38f, my + 0.38f + 0.05f * h).copyOf()
            t2Basis(rc, fr[6], fr[7], fr[8], fr[9], fr[10], fr[11], 1f, 1f, 1f, ring, T2_DYNEIN, COL_LAMP, dfade, 0.1f)
            drawStrut(rc[0], rc[1], rc[2], mtbd[0], mtbd[1], mtbd[2], 0.014f, T2_DYNEIN, T2_DYNEIN)
            t2Basis(mtbd, 0f, 0f, 1f, 0f, 1f, 0f, 0.035f, 0.035f, 0.035f, sphere, T2_DYNEIN, COL_LAMP, dfade)
            drawStrut(rc[0], rc[1], rc[2], jn[0], jn[1], jn[2], 0.022f, T2_DYNEIN, T2_DYNEIN)
        }
        val lyso = t2W(t2Frame(i, dz + 0.05f), mx + 0.95f, my + 1.35f).copyOf()
        drawStrut(jn[0], jn[1], jn[2], lyso[0], lyso[1], lyso[2], 0.03f, T2_DYNEIN, T2_DYNEIN)
        t2Basis(lyso, 0f, 0f, 1f, 0f, 1f, 0f, 0.6f, 0.6f, 0.6f, sphere, T2_LYSOSOME, T2_LYSO_CORE, 0.8f * dfade, 0.1f)
        t2LinesEnd(2f)
        t2DrawWorld(m[1], true)
    }
}

// ========================================================================== stop 9: FACTORY
// 8 nm a unit (Mote 12 nm). A pancreatic beta cell making insulin. Behind: the nucleus, the gene
// read by RNA polymerase II on its DNA; the mRNA runs through a nuclear pore complex (120 nm,
// eight-fold: rings, spokes, cytoplasmic filaments, nuclear basket) in the double nuclear
// envelope. Ahead: the rough ER (continuous with the outer nuclear membrane) with ribosomes
// docked on Sec61 translocons, the new chain threading into the lumen signal peptide first,
// cut, folded; far ahead, an insulin granule (~300 nm, crystalline core) fusing with the
// plasma membrane and releasing hexamers that fall apart into monomers.

/** The factory is laid out from the pore; this shifts it all 3 units ahead so the pore is framed from the stop. */
private const val T2_FZ = 3f

private fun StereoBodyRenderer.t2FactoryMeshes(i: Int): Array<ColorVboMesh> = t2Get("factory$i") {
    val g = T2Geo(); val glass = T2Geo(); val heads = ArrayList<Float>(); val fg = ArrayList<Float>(); val rnd = java.util.Random(83L)
    // nuclear envelope: inner (z 0.2) and outer (z 4.4) membranes, 4.8 nm bilayers, joined round the pore
    for (zm in floatArrayOf(0.2f, 4.4f)) {
        glass.surf(8, 56, T2_ENVELOPE, 0.28f) { v, u -> val a = u * 2f * T2PI; val r = 8.35f + v * 20f; t2v(cos(a) * r, sin(a) * r, zm) }
        var r = 8.6f
        while (r < 26f) { val n = (2f * T2PI * r / 0.8f).toInt(); for (k in 0 until n) { val a = 2f * T2PI * k / n + r; for (s in SIGNS) heads.addAll(listOf(cos(a) * r, sin(a) * r, zm + s * 0.3f, 1f, 0.8f, 0.5f, 0.75f)) }; r += 0.8f }
    }
    glass.surf(10, 48, T2_ENVELOPE, 0.3f) { v, u ->
        val a = u * 2f * T2PI; val b = T2PI * 0.5f + v * T2PI; val rr = 8.35f + 2.1f * cos(b)
        t2v(cos(a) * rr, sin(a) * rr, 2.3f + 2.1f * sin(b))
    }
    // the pore complex: cytoplasmic, inner (spoke) and nuclear rings, eight-fold; luminal ring
    for (k in 0 until 8) {
        val a = k * T2PI / 4f + T2PI / 8f; val d = t2v(cos(a), sin(a), 0f); val tg = t2v(-sin(a), cos(a), 0f)
        g.ell(d * 5.4f + t2v(0f, 0f, 4.8f), d * 1.25f, tg * 1.05f, t2v(0f, 0f, 0.8f), T2_NPC, 1f, 7, 10)
        g.ell(d * 5.4f + t2v(0f, 0f, -0.2f), d * 1.25f, tg * 1.05f, t2v(0f, 0f, 0.8f), T2_NPC, 1f, 7, 10)
        g.ell(d * 4.25f + t2v(0f, 0f, 2.3f), d * 0.95f, tg * 0.9f, t2v(0f, 0f, 1.6f), T2_NPC, 1f, 7, 10)
        g.tube(d * 4.6f + t2v(0f, 0f, 0.5f), d * 4.6f + t2v(0f, 0f, 4.1f), 0.35f, 0.35f, T2_NPC, 1f, 6, false)
        g.ball(d * 9.9f + t2v(0f, 0f, 2.3f), 0.7f, T2_NPC, 1f, 6, 8)
        // cytoplasmic filaments reaching into the cytoplasm
        g.path((0..6).map { q -> val t = q / 6f; d * (5.4f + 0.8f * sin(t * 3f + k)) + tg * (0.6f * sin(t * 4f + k * 2f)) + t2v(0f, 0f, 5.4f + t * 5f) }, { t -> 0.17f - 0.06f * t }, T2_NPC, 1f, 5, true)
        // nuclear basket: filaments converging on the distal ring 75 nm into the nucleus
        g.path((0..6).map { q -> val t = q / 6f; val r = 5.3f + (2.6f - 5.3f) * t; d * r + t2v(0f, 0f, -0.6f - 5.6f * t - 0.6f * sin(t * T2PI)) }, { 0.15f }, T2_NPC, 1f, 5, true)
        // FG-repeat filaments filling the channel (the craft slips through them)
        repeat(8) {
            val z0 = 1.0f + rnd.nextFloat() * 2.6f; val a0 = a + (rnd.nextFloat() - 0.5f) * 0.7f
            var px = cos(a0) * 3.3f; var py = sin(a0) * 3.3f; var pz = z0
            for (q in 1..5) {
                val r = 3.3f - q * 0.36f; val aa = a0 + 0.25f * sin(q * 1.9f + it)
                val nx = cos(aa) * r; val ny = sin(aa) * r; val nz = z0 + 0.3f * sin(q * 1.3f + it * 0.7f)
                fg.addAll(listOf(px, py, pz, 0.85f, 0.85f, 1f, 0.35f, nx, ny, nz, 0.85f, 0.85f, 1f, 0.35f)); px = nx; py = ny; pz = nz
            }
        }
    }
    g.torus(t2v(0f, 0f, -6.2f), t2v(0f, 0f, 1f), 2.6f, 0.2f, T2_NPC, 1f, 24, 6)
    // the gene: DNA double helix crossing below, on the nuclear side
    for (st in 0..1) g.path((0..220).map { q -> val x = -9f + q * 18f / 220f; val a = x / 0.425f * 2f * T2PI + st * 2.3f; t2v(x, -2.1f + cos(a) * 0.1f, -1.6f + sin(a) * 0.1f) }, { 0.03f }, T2_DNA, 1f, 4, false)
    for (q in 0 until 420) { val x = -9f + (q + 0.5f) * 18f / 420f; val a = x / 0.425f * 2f * T2PI
        g.tube(t2v(x, -2.1f + cos(a) * 0.1f, -1.6f + sin(a) * 0.1f), t2v(x, -2.1f + cos(a + 2.3f) * 0.1f, -1.6f + sin(a + 2.3f) * 0.1f), 0.016f, 0.016f, T2_DNA, 1f, 3, false) }
    // messenger RNA: out of the polymerase, through the pore, onto the ribosomes (5' cap leading)
    val mr = listOf(t2v(1.2f, -1.4f, -0.6f), t2v(0.8f, -1.35f, 1.0f), t2v(0.3f, -1.3f, 2.6f), t2v(-0.2f, -1.25f, 4.4f), t2v(-1.2f, -1.1f, 6.0f), t2v(-2.9f, -0.9f, 7.0f),
        t2v(-3.1f, -0.9f, 8.0f), t2v(-3.1f, -0.9f, 9.8f), t2v(-3.1f, -0.9f, 11.6f), t2v(-3.0f, -0.8f, 13.4f))
    g.path(mr, { 0.07f }, T2_MRNA, 1f, 5, true)
    g.ball(mr.last() + t2v(0f, 0.05f, 0.1f), 0.14f, T2_MRNA, 1f, 4, 6)
    // rough ER membrane, continuous with the outer nuclear membrane, to port
    for (xm in floatArrayOf(-5.6f, -6.2f)) {
        glass.surf(10, 12, T2_ER, 0.3f) { v, u -> t2v(xm, -12f + 24f * u, 4.4f + 18f * v) }
        var yy = -11.6f; while (yy < 12f) { var zz = 4.8f; while (zz < 22f) { heads.addAll(listOf(xm + (if (xm < -6f) -0.02f else 0.02f), yy, zz, 1f, 0.8f, 0.5f, 0.7f)); zz += 0.8f }; yy += 0.8f }
    }
    // ribosomes on Sec61: the large (60S) subunit against the translocon, the small (40S) outward
    for (rz in floatArrayOf(8f, 11.6f)) {
        g.ell(t2v(-4.3f, -0.9f, rz), t2v(1.2f, 0f, 0f), t2v(0f, 1.45f, 0f), t2v(0f, 0f, 1.35f), T2_RIBO_60S, 1f, 9, 12)
        g.ball(t2v(-3.7f, 0.5f, rz - 0.2f), 0.5f, T2_RIBO_60S, 1f, 5, 7)
        g.ball(t2v(-4.1f, -1.0f, rz + 1.35f), 0.42f, T2_RIBO_60S, 1f, 5, 7)
        g.ell(t2v(-2.3f, -0.8f, rz), t2v(0.75f, 0f, 0f), t2v(0f, 1.15f, 0f), t2v(0f, 0f, 1.2f), T2_RIBO_40S, 1f, 8, 11)
        g.ball(t2v(-2.2f, 0.5f, rz + 0.2f), 0.55f, T2_RIBO_40S, 1f, 5, 7)
        g.tube(t2v(-5.5f, -0.9f, rz), t2v(-6.3f, -0.9f, rz), 0.6f, 0.6f, T2_SEC61, 1f, 10, true)
    }
    // the second ribosome's product, already folded in the lumen, with a chaperone
    g.ball(t2v(-7.4f, -0.8f, 11.9f), 0.42f, T2_CHAIN_B, 1f, 6, 8); g.ball(t2v(-7.3f, 0.05f, 12.4f), 0.45f, T2_BIP, 1f, 6, 8)
    for (q in 0 until 6) g.ball(t2v(-6.3f - q * 0.18f, -0.9f + 0.05f * q, 11.6f + 0.04f * q), 0.1f, if (q % 2 == 0) T2_CHAIN_A else T2_CHAIN_B, 1f, 3, 4)
    val bend0 = t2Bend(i, 5f, 12f); val bend = { o: FloatArray, k: Int -> o[k + 2] += T2_FZ; bend0(o, k) }
    val hA = heads.toFloatArray(); for (k in 0 until hA.size / 7) bend(hA, k * 7)
    val fA = fg.toFloatArray(); for (k in 0 until fA.size / 7) bend(fA, k * 7)
    arrayOf(TriMesh(g.baked(bend)), TriMesh(glass.baked(bend)), PointMesh(hA), LineMesh(fA))
}

/** The insulin granule and the plasma membrane it fuses with (far ahead-right; rigid frame). */
private fun StereoBodyRenderer.t2GranuleMeshes(i: Int): Array<ColorVboMesh> = t2Get("granule$i") {
    val glass = T2Geo(); val core = ArrayList<Float>(); val rnd = java.util.Random(89L)
    val gcx = 6f; val gcy = 3f; val gcz = 46f
    glass.surf(18, 28, T2_GRANULE, 0.26f) { u, v -> val th = u * T2PI; val ph = v * 2f * T2PI; t2v(gcx + sin(th) * cos(ph) * 15f, gcy + cos(th) * 15f, gcz + sin(th) * sin(ph) * 15f) }
    repeat(2200) {   // the dense core: a crystal of zinc-insulin hexamers
        var x: Float; var y: Float; var z: Float
        do { x = rnd.nextFloat() * 2f - 1f; y = rnd.nextFloat() * 2f - 1f; z = rnd.nextFloat() * 2f - 1f } while (x * x + y * y + z * z > 1f)
        core.addAll(listOf(gcx + 0.5f + x * 9f, gcy + y * 9f, gcz + z * 9f, 0.98f, 0.9f, 0.5f, 0.9f))
    }
    glass.surf(10, 14, T2_PM, 0.3f) { u, v -> t2v(21.3f, -16f + 38f * u, 20f + 54f * v) }
    val rigid0 = t2Bend(i, 0f, 0f); val rigid = { o: FloatArray, k: Int -> o[k + 2] += T2_FZ; rigid0(o, k) }
    val cA = core.toFloatArray(); for (k in 0 until cA.size / 7) rigid(cA, k * 7)
    arrayOf(TriMesh(glass.baked(rigid)), PointMesh(cA))
}

private fun StereoBodyRenderer.t2Octa(x: Float, y: Float, z: Float, r: Float, c: FloatArray, a: Float, n: Int): Int {
    // an octahedron with baked shading into t2Tris at vertex index n; returns the new index
    val d = t2Tris.data
    val px = floatArrayOf(r, -r, 0f, 0f, 0f, 0f); val py = floatArrayOf(0f, 0f, r, -r, 0f, 0f); val pz = floatArrayOf(0f, 0f, 0f, 0f, r, -r)
    val faces = intArrayOf(0, 2, 4, 2, 1, 4, 1, 3, 4, 3, 0, 4, 2, 0, 5, 1, 2, 5, 3, 1, 5, 0, 3, 5)
    var v = n
    for (f in 0 until 8) {
        val i0 = faces[f * 3]; val i1 = faces[f * 3 + 1]; val i2 = faces[f * 3 + 2]
        val nx = (px[i0] + px[i1] + px[i2]); val ny = (py[i0] + py[i1] + py[i2]); val nz = (pz[i0] + pz[i1] + pz[i2])
        val sh = 0.5f + 0.5f * abs(nx * 0.3f + ny * 0.83f - nz * 0.47f) / (r * 1.0f)
        for (q in intArrayOf(i0, i1, i2)) {
            if (v >= 4000) return v
            val o = v * 7
            d[o] = x + px[q]; d[o + 1] = y + py[q]; d[o + 2] = z + pz[q]
            d[o + 3] = min(1f, c[0] * sh); d[o + 4] = min(1f, c[1] * sh); d[o + 5] = min(1f, c[2] * sh); d[o + 6] = a
            v++
        }
    }
    return v
}

/** Tour II stop 10: gene to granule — polymerase, pore, ribosome on the ER, signal peptide, insulin exocytosis. */
internal fun StereoBodyRenderer.drawFactory(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) { own ->
        val m = t2FactoryMeshes(i)
        t2DrawWorld(m[0])
        // RNA polymerase II crawling along the gene (a 30 s pass), its transcript joining the strand
        run {
            val t = (seconds % 30f) / 30f
            val px = 0.4f + 1.4f * t; val al = t2sm(t / 0.06f) * (1f - t2sm((t - 0.94f) / 0.06f))
            val fr = t2Frame(i, T2_FZ + -1.6f)
            val p = t2W(fr, px, -1.85f).copyOf()
            t2Basis(p, fr[3], fr[4], fr[5], fr[9], fr[10], fr[11], 1.05f, 0.85f, 0.95f, sphere, T2_POL2, COL_LAMP, al, 0.05f)
            val q = t2W(t2Frame(i, T2_FZ + -0.6f), 1.2f, -1.4f).copyOf()
            val e = t2W(t2Frame(i, T2_FZ + -1.3f), px, -1.2f).copyOf()
            drawStrut(e[0], e[1], e[2], q[0], q[1], q[2], 0.07f, T2_MRNA, T2_MRNA)
        }
        // the nascent chain (24 s): out of the large subunit's tunnel, through Sec61, signal peptide
        // first; signal peptidase cuts it off; BiP holds the chain while it folds.
        run {
            val t = seconds % 24f
            val nb = min(34, (t / 0.42f).toInt())
            val cut = t2sm((t - 15f) / 1.5f); val fold = t2sm((t - 16f) / 4f)
            val al = 1f - t2sm((t - 22.5f) / 1.5f)
            var v = 0
            val rnd = java.util.Random(3L)
            for (j in 0 until nb) {
                val s = (nb - 1 - j) * 0.28f          // j = 0 is the N-terminus, farthest along
                var x: Float; var y: Float; var z: Float
                if (s < 1.8f) { x = -5.2f - s; y = -0.9f; z = 8f }
                else { val u = s - 1.8f; x = -7.0f - 0.35f * sin(u * 1.3f); y = -0.9f + 0.8f * sin(u * 0.9f); z = 8f + 0.9f * cos(u * 0.8f) - 0.9f }
                val sig = j < 8
                if (sig) { x += cut * 0.4f; z -= cut * 1.2f }
                else if (j >= 8 && fold > 0f) { val fx = -7.5f + (rnd.nextFloat() - 0.5f) * 0.9f; val fy = -1.0f + (rnd.nextFloat() - 0.5f) * 0.9f; val fz = 8.4f + (rnd.nextFloat() - 0.5f) * 0.9f
                    x += (fx - x) * fold; y += (fy - y) * fold; z += (fz - z) * fold }
                val w = t2W(t2Frame(i, T2_FZ + z), x, y)
                val c = if (sig) T2_SIGNAL else if (j % 2 == 0) T2_CHAIN_A else T2_CHAIN_B
                v = t2Octa(w[0], w[1], w[2], 0.12f, c, al * (if (sig) 1f - cut * 0.7f else 1f), v)
            }
            if (v > 0) { Matrix.setIdentityM(model, 0); Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
                colorShader.use(mvp, 1f); GLES20.glDisable(GLES20.GL_CULL_FACE); t2Tris.draw(colorShader.positionHandle, colorShader.colorHandle, GLES20.GL_TRIANGLES, v); GLES20.glEnable(GLES20.GL_CULL_FACE) }
            val fr = t2Frame(i, T2_FZ + 8.6f)
            t2Blob(fr, -6.45f, 0.1f, 0.34f, 0.3f, 0.34f, T2_TONSIL, COL_LAMP, 1f, if (t in 14.8f..16.4f) 1.2f else 0.05f)   // signal peptidase
            if (t in 9f..22f) t2Blob(t2Frame(i, T2_FZ + 8.9f), -7.6f, -0.2f, 0.45f, 0.45f, 0.45f, T2_BIP, COL_LAMP, t2sm((t - 9f) / 1f) * al, 0.05f)
            if (t in 19f..22f) for (q in 0 until 3) t2Blob(t2Frame(i, T2_FZ + 8.2f + q * 0.25f), -7.4f + q * 0.1f, -1.1f + q * 0.2f, 0.08f, 0.08f, 0.08f, T2_SIGNAL, T2_SIGNAL, al, 1.5f)   // disulfide bonds forming
        }
        t2DrawWorld(m[3], true)
        t2DrawWorld(m[1], true)
        t2DrawWorld(m[2], true, 3f, true)
        if (own) {
            // exocytosis far ahead: the granule's membrane fuses with the plasma membrane, the pore
            // widens, hexamers stream out and fall apart into monomers (a 20 s cycle)
            val gm = t2GranuleMeshes(i)
            t2DrawWorld(gm[1], true, 3f, true)
            t2DrawWorld(gm[0], true)
            val f0 = t2Rigid(i, 0f, FloatArray(13))
            val t = (seconds % 20f) / 20f
            val rho = 0.4f + 3.6f * t2sm(t / 0.35f)
            val fp = t2W(f0, 21.3f, 3f, 46f + T2_FZ).copyOf()
            t2Basis(fp, f0[6], f0[7], f0[8], f0[9], f0[10], f0[11], rho, rho, 6f, t2RingMesh(), T2_PM, COL_LAMP, 0.8f, 0.3f)
            t2LinesBegin()
            for (k in 0 until 40) {
                val tk = (t * 1.6f - k * 0.02f); if (tk < 0.15f || tk > 1.3f) continue
                val u = (tk - 0.15f) / 1.15f
                val a = k * 2.4f; val spread = u * 7f
                val x = 21.3f + u * 9f; val y = 3f + cos(a) * min(rho * 0.8f, 3f) + sin(a) * spread * 0.6f; val z = 46f + sin(a) * min(rho * 0.8f, 3f) + cos(a) * spread * 0.6f
                val split = t2sm((u - 0.4f) / 0.2f)
                for (sd in SIGNS) {
                    val w = t2W(f0, x + sd * split * 0.6f, y + sd * split * 0.4f, z + T2_FZ)
                    t2Vert(w[0], w[1], w[2], T2_INSULIN, 1f - t2sm((u - 0.8f) / 0.2f))
                }
            }
            t2LinesEnd(1f, true, 4f)
        }
    }
}

// ============================================================================ stop 10: MOTOR
// 0.8 nm a unit (Mote 1.2 nm). One ATP synthase in the inner membrane of a heart-muscle
// mitochondrion, built to scale around the craft: a 4-nm bilayer below (matrix side up), the
// c8 ring (~5 nm) in it, the central stalk (γδε) rising through the 4.5 nm gap into the α3β3
// head (~10 nm) overhead, the peripheral stalk from subunit a up the side to OSCP on top.
// Protons pass from the intermembrane space (below) through subunit a's entry half-channel
// onto a c subunit, ride the ring almost a full turn, and leave through the exit half-channel
// into the matrix: the rotor turns counter-clockwise seen from the matrix (synthesis), one ATP
// released per 120 degrees — 8 protons, 3 ATP per turn (slowed ~800x).

private const val T2_MEM_TOP = -3.0f
private const val T2_MEM_BOT = -8.0f
private const val T2_AXX = -3.2f
private const val T2_AXZ = 9.0f
private const val T2_SUBA = -2.356f     // subunit a's angle round the ring (+x toward +z): front, away from the lane

private fun StereoBodyRenderer.t2MotorMeshes(i: Int): Array<ColorVboMesh> = t2Get("motor$i") {
    val mem = T2Geo(); val win = T2Geo(); val stat = T2Geo(); val rot = T2Geo(); val glass = T2Geo(); val tails = ArrayList<Float>(); val far = ArrayList<Float>(); val rnd = java.util.Random(97L)
    // ---- the lipid bilayer: headgroups 0.8 nm apart on both faces, tails into the middle
    val ax = T2_AXX; val az = T2_AXZ
    val sax = ax + cos(T2_SUBA) * 3.7f; val saz = az + sin(T2_SUBA) * 3.7f
    var x = -14f
    while (x <= 16f) {
        var z = -8f
        while (z <= 24f) {
            val jx = x + (rnd.nextFloat() - 0.5f) * 0.3f; val jz = z + (rnd.nextFloat() - 0.5f) * 0.3f
            val dr = sqrt((jx - ax) * (jx - ax) + (jz - az) * (jz - az)); val da = sqrt((jx - sax) * (jx - sax) + (jz - saz) * (jz - saz))
            if (dr > 3.3f && da > 2.0f) for (yh in floatArrayOf(T2_MEM_TOP, T2_MEM_BOT)) {
                // round the enzyme the matrix-side leaflet is drawn see-through, so the c ring,
                // subunit a and the protons in the membrane can be seen
                if (yh == T2_MEM_TOP && dr < 7.5f) win.ball(t2v(jx, yh, jz), 0.3f, T2_LIPID_HEAD, 0.3f, 3, 5)
                else mem.ball(t2v(jx, yh, jz), 0.3f, T2_LIPID_HEAD, 1f, 3, 5)
                val dir = if (yh > -5f) -1f else 1f
                for (tl in 0..1) { val ox = (tl - 0.5f) * 0.3f; var py = yh
                    for (q in 1..4) { val ny = yh + dir * q * 0.55f; val wx = jx + ox + 0.08f * sin(q * 2f + tl)
                        tails.addAll(listOf(jx + ox + 0.08f * sin((q - 1) * 2f + tl), py, jz, 0.5f, 0.86f, 0.8f, 0.7f, wx, ny, jz, 0.5f, 0.86f, 0.8f, 0.7f)); py = ny } }
            }
            z += 1.0f
        }
        x += 1.0f
    }
    x = -34f
    while (x <= 36f) { var z = -26f; while (z <= 44f) { if (x < -14.5f || x > 16.5f || z < -8.5f || z > 24.5f) for (yh in floatArrayOf(T2_MEM_TOP, T2_MEM_BOT)) far.addAll(listOf(x, yh, z, 1f, 0.8f, 0.5f, 0.85f)); z += 1f }; x += 1f }
    // ---- rotor (local, axis at the origin, +y up to the matrix): c8 ring, δε foot, γ
    for (k in 0 until 8) {
        val a = k * T2PI / 4f; val c = if (k == 0) T2_C_MARK else T2_C_RING
        val ci = t2v(cos(a) * 1.55f, 0f, sin(a) * 1.55f); val co = t2v(cos(a + 0.18f) * 2.55f, 0f, sin(a + 0.18f) * 2.55f)
        rot.tube(ci + t2v(0f, -8.5f, 0f), ci + t2v(0f, -2.5f, 0f), 0.45f, 0.45f, c, 1f, 7, true)
        rot.tube(co + t2v(0f, -8.6f, 0f), co + t2v(0f, -2.4f, 0f), 0.5f, 0.5f, c, 1f, 7, true)
        rot.tube(ci + t2v(0f, -2.4f, 0f), co + t2v(0f, -2.3f, 0f), 0.36f, 0.36f, c, 1f, 6, true)
        rot.ball(t2v(cos(a + 0.18f) * 3.02f, -5.5f, sin(a + 0.18f) * 3.02f), 0.2f, floatArrayOf(0.95f, 0.3f, 0.3f, 1f), 1f, 4, 6)   // the carrier glutamate
    }
    rot.ball(t2v(0f, -1.6f, 0f), 1.5f, T2_GAMMA, 1f, 8, 12)
    rot.path(listOf(t2v(0f, -1.2f, 0f), t2v(0.3f, 1.5f, 0f), t2v(0.55f, 4.0f, 0.1f), t2v(0.25f, 7.5f, 0f), t2v(0f, 10.8f, 0f)), { t -> 0.62f - 0.12f * t }, T2_GAMMA, 1f, 8, true)
    rot.ball(t2v(0.7f, 1.6f, 0.2f), 0.85f, T2_GAMMA, 1f, 6, 9)
    // ---- stator (static): α subunits of the head, subunit a in the membrane, peripheral stalk, OSCP
    for (k in 0 until 3) {
        val a = k * 2f * T2PI / 3f + T2PI / 3f
        stat.ell(t2v(ax + cos(a) * 3.4f, 8.0f, az + sin(a) * 3.4f), t2v(2.6f, 0f, 0f), t2v(0f, 4.6f, 0f), t2v(0f, 0f, 2.6f), T2_ALPHA, 1f, 10, 14)
    }
    stat.ellAxis(t2v(sax, -5.5f, saz), t2v(0f, 1f, 0f), 1.3f, 3.5f, 2.3f, T2_SUB_A, 1f, 9, 12, t2v(cos(T2_SUBA), 0f, sin(T2_SUBA)))
    val dA = t2v(cos(T2_SUBA), 0f, sin(T2_SUBA))
    val stalk = listOf(t2v(ax, -2f, az) + dA * 4.4f, t2v(ax, 3f, az) + dA * 6.3f, t2v(ax, 8f, az) + dA * 6.7f, t2v(ax, 12.6f, az) + dA * 5.4f, t2v(ax, 14.2f, az) + dA * 2.4f)
    stat.path(stalk, { 0.45f }, T2_STATOR, 1f, 8, true)
    stat.ball(t2v(ax, 6.2f, az) + dA * 6.6f, 0.95f, T2_STATOR, 1f, 6, 9)
    stat.ball(t2v(ax, 14.4f, az), 1.6f, T2_STATOR, 1f, 8, 12)
    // the two half-channels in subunit a, at the a/c interface: entry from below, exit to above
    for ((sd, y0, y1) in listOf(Triple(0.3f, -9f, -5.5f), Triple(-0.3f, -5.5f, -2.2f))) {
        val a = T2_SUBA + sd; val p = t2v(ax + cos(a) * 3.45f, 0f, az + sin(a) * 3.45f)
        glass.tube(p + t2v(0f, y0, 0f), p + t2v(0f, y1, 0f), 0.36f, 0.36f, T2_PROTON, 0.35f, 8, true)
    }
    val rigid = t2Bend(i, 0f, 0f)
    val tA = tails.toFloatArray(); for (k in 0 until tA.size / 7) rigid(tA, k * 7)
    val fA = far.toFloatArray(); for (k in 0 until fA.size / 7) rigid(fA, k * 7)
    arrayOf(TriMesh(mem.baked(rigid)), TriMesh(stat.baked(rigid)), TriMesh(rot.baked()), LineMesh(tA), PointMesh(fA), TriMesh(glass.baked(rigid)), TriMesh(win.baked(rigid)))
}

private fun StereoBodyRenderer.t2AtpMesh(): TriMesh = t2Get("atp") {
    // ATP, ~1.4 nm: adenine (two fused rings), ribose, and a chain of three phosphates
    val g = T2Geo()
    g.ball(t2v(-0.75f, 0.1f, 0f), 0.3f, T2_ADENINE, 1f, 5, 7); g.ball(t2v(-0.35f, 0.25f, 0f), 0.26f, T2_ADENINE, 1f, 5, 7)
    g.ball(t2v(0.05f, -0.1f, 0f), 0.26f, T2_LIPID_TAIL, 1f, 5, 7)
    for (k in 0 until 3) g.ball(t2v(0.4f + k * 0.34f, -0.15f + 0.08f * (k % 2), 0f), 0.2f, T2_PHOSPHATE, 1f, 5, 7)
    TriMesh(g.baked())
}

/** Tour II stop 11: ATP synthase running forward: protons down their gradient turn the rotor, the head makes ATP. */
internal fun StereoBodyRenderer.drawMotor(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) {
        val m = t2MotorMeshes(i)
        val f0 = t2Rigid(i, 0f, FloatArray(13))
        t2DrawWorld(m[0]); t2DrawWorld(m[1]); t2DrawWorld(m[3], true); t2DrawWorld(m[4], true, 3f, true)
        val omega = 2f * T2PI / 8f             // one turn in 8 s
        val th = seconds * omega               // counter-clockwise seen from the matrix (+y)
        t2Model(f0, T2_AXX, 0f, T2_AXZ, th); t2Draw(m[2])
        // β subunits: each cycles open (empty) -> loose (ADP + Pi) -> tight (ATP) as γ turns;
        // the one opening releases its ATP, glowing.
        val third = 2f * T2PI / 3f
        for (k in 0 until 3) {
            val a = k * third
            val rel = ((th - a) % third + third) % third / third            // progress through this 120° step
            val state = (floor((th - a) / third).toInt() % 3 + 3) % 3
            val open = if (state == 0) 1f else 0f
            val sc = if (state == 2) 0.93f else 1f
            val p = t2W(f0, T2_AXX + cos(a) * (3.4f + 0.3f * open), 8.0f - 0.2f * open, T2_AXZ + sin(a) * (3.4f + 0.3f * open)).copyOf()
            val glow = if (state == 0) 0.6f * (1f - t2sm(rel / 0.35f)) else 0.05f
            t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 2.6f * sc, 4.6f * sc, 2.6f * sc, sphere, T2_BETA, T2_ALPHA, 1f, glow)
            if (state == 0 && rel < 0.95f) {    // the ATP just released drifts out into the matrix
                val d = 3.4f + 3.0f + rel * 6f
                t2Model(f0, T2_AXX + cos(a) * d, 5.5f + rel * 3f, T2_AXZ + sin(a) * d, rel * 4f)
                val keep = colorShader.globalFade; colorShader.globalFade = keep * (1f - t2sm((rel - 0.7f) / 0.25f)); t2Draw(m[2].let { t2AtpMesh() }); colorShader.globalFade = keep
            }
        }
        // protons (hydronium, ~0.3 nm): arriving from the intermembrane space, riding the c ring,
        // leaving into the matrix
        val entry = T2_SUBA + 0.3f; val exit = T2_SUBA - 0.3f
        val q = FloatArray(3)
        for (k in 0 until 8) {
            val phi = th + k * T2PI / 4f + 0.18f
            val sinceEntry = ((phi - entry) % (2f * T2PI) + 2f * T2PI) % (2f * T2PI)
            val sinceExit = ((phi - exit) % (2f * T2PI) + 2f * T2PI) % (2f * T2PI)
            val toEntry = 2f * T2PI - sinceEntry
            if (sinceEntry < 2f * T2PI - 0.6f) {   // bound to this subunit's glutamate
                t2W(f0, T2_AXX + cos(phi) * 3.1f, -5.5f, T2_AXZ + sin(phi) * 3.1f).copyInto(q)
                t2Basis(q, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.28f, 0.28f, 0.28f, sphere, T2_PROTON, T2_PROTON, 1f, 1.0f)
            }
            val tIn = toEntry / omega
            if (tIn < 3f) {   // arriving: up from the IMS into the entry half-channel
                val u = tIn / 3f
                val cx = T2_AXX + cos(entry) * 3.45f; val cz = T2_AXZ + sin(entry) * 3.45f
                val px: Float; val py: Float; val pz: Float
                if (u < 0.35f) { val s = u / 0.35f; px = cx; py = -5.5f - 3.5f * s; pz = cz }
                else { val s = (u - 0.35f) / 0.65f; px = cx + cos(entry + k) * 5f * s; py = -9f - 3f * s; pz = cz + sin(entry + k) * 5f * s }
                t2W(f0, px, py, pz).copyInto(q)
                t2Basis(q, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.28f, 0.28f, 0.28f, sphere, T2_PROTON, T2_PROTON, 1f - t2sm((u - 0.85f) / 0.15f), 1.0f)
            }
            val tOut = sinceExit / omega
            if (tOut < 3f) {  // leaving: up the exit half-channel into the matrix
                val u = tOut / 3f
                val cx = T2_AXX + cos(exit) * 3.45f; val cz = T2_AXZ + sin(exit) * 3.45f
                val px: Float; val py: Float; val pz: Float
                if (u < 0.35f) { val s = u / 0.35f; px = cx; py = -5.5f + 3.3f * s; pz = cz }
                else { val s = (u - 0.35f) / 0.65f; px = cx + cos(exit - k) * 4f * s; py = -2.2f + 2.4f * s; pz = cz + sin(exit - k) * 4f * s }
                t2W(f0, px, py, pz).copyInto(q)
                t2Basis(q, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.28f, 0.28f, 0.28f, sphere, T2_PROTON, T2_PROTON, 1f - t2sm((u - 0.8f) / 0.2f), 1.0f)
            }
        }
        t2DrawWorld(m[5], true)
        t2DrawWorld(m[6], true)
    }
}

// ========================================================================= stop 11: DIVISION
// 0.8 µm a unit (Mote 1.2 µm). An intestinal crypt cell dividing in its epithelium: rounded up
// (~14 µm) toward the lumen among tall columnar neighbours (brush border up, nuclei low), its
// spindle parallel to the sheet. 46 chromosomes, each two sister chromatids joined at the
// centromere, congress to a ring-shaped metaphase plate (the craft passes through its hollow
// centre), then the sisters part and move poleward as V shapes (anaphase A) while the poles
// separate (anaphase B); nuclear envelopes re-form round the decondensing sets and an actomyosin
// ring pinches the cell in two, leaving a midbody (a 36 s cycle).

private const val T2_SPX = 0f
private const val T2_SPY = -1.8f       // spindle axis (parallel to the rail, below it)
private const val T2_PLATE_Z = 2.5f

private class T2Chromo(val cx: Float, val cy: Float, val wx: Float, val wy: Float, val lp: Float, val lq: Float, val sx: Float, val sy: Float, val sz: Float)

private val t2Chromos: List<T2Chromo> by lazy {
    val rnd = java.util.Random(131L); val out = ArrayList<T2Chromo>()
    for (k in 0 until 46) {
        val big = k < 8; val mid = k in 8 until 22
        val lp = if (big) 1.8f + rnd.nextFloat() * 0.4f else if (mid) 1.2f + rnd.nextFloat() * 0.3f else 0.55f + rnd.nextFloat() * 0.35f
        val lq = if (big) 2.6f + rnd.nextFloat() * 0.4f else if (mid) 1.8f + rnd.nextFloat() * 0.5f else 1.0f + rnd.nextFloat() * 0.5f
        val ang = (k * 2.39996f) % (2f * T2PI)
        var rad = 3.0f + ((k * 0.61803f) % 1f) * 2.4f
        val q = (rnd.nextFloat() - 0.5f) * 1.1f
        val tx = -sin(ang); val ty = cos(ang); val rx = cos(ang); val ry = sin(ang)
        val wx = tx * cos(q) + rx * sin(q); val wy = ty * cos(q) + ry * sin(q)
        // keep the arm tips inside the cell
        val cx0 = cos(ang) * rad; val cy0 = sin(ang) * rad
        val tip = max(sqrt((cx0 + wx * lq).pow(2) + (cy0 + wy * lq).pow(2)), sqrt((cx0 - wx * lp).pow(2) + (cy0 - wy * lp).pow(2)))
        if (tip > 7.2f) rad -= (tip - 7.2f)
        out.add(T2Chromo(cos(ang) * max(2.9f, rad), sin(ang) * max(2.9f, rad), wx, wy, lp, lq, (rnd.nextFloat() - 0.5f) * 4f, (rnd.nextFloat() - 0.5f) * 4f, (rnd.nextFloat() - 0.5f) * 6f))
    }
    out
}

// A chromatid arm as a tapered, round-tipped tube: rings along its length (0 = centromere).
private val T2_ARM_T = floatArrayOf(0f, 0.12f, 0.4f, 0.8f, 0.94f, 1f)
private val T2_ARM_R = floatArrayOf(0.6f, 0.95f, 1f, 0.98f, 0.72f, 0f)

private fun t2Arm(n0: Int, cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float, len: Float, r: Float, col: FloatArray, al: Float): Int {
    val d = t2ChromTris.data; var v = n0
    // perpendicular basis
    var px = -dy; var py = dx; var pz = 0f
    var pl = sqrt(px * px + py * py)
    if (pl < 1e-4f) { px = 1f; py = 0f; pz = 0f; pl = 1f }
    px /= pl; py /= pl; pz /= pl
    val qx = dy * pz - dz * py; val qy = dz * px - dx * pz; val qz = dx * py - dy * px
    val sides = 5
    for (ri in 0 until 5) for (s in 0 until sides) {
        if (v + 6 > 30000) return v
        val a0 = s * 2f * T2PI / sides; val a1 = (s + 1) * 2f * T2PI / sides
        for ((rk, aa) in arrayOf(ri to a0, ri + 1 to a0, ri + 1 to a1, ri to a0, ri + 1 to a1, ri to a1)) {
            val ca = cos(aa); val sa = sin(aa); val rr = T2_ARM_R[rk] * r; val t = T2_ARM_T[rk] * len
            val nx = px * ca + qx * sa; val ny = py * ca + qy * sa; val nz = pz * ca + qz * sa
            val sh = 0.5f + 0.55f * abs(nx * 0.3f + ny * 0.83f - nz * 0.47f)
            val o = v * 7
            d[o] = cx + dx * t + nx * rr; d[o + 1] = cy + dy * t + ny * rr; d[o + 2] = cz + dz * t + nz * rr
            d[o + 3] = min(1f, col[0] * sh); d[o + 4] = min(1f, col[1] * sh); d[o + 5] = min(1f, col[2] * sh); d[o + 6] = al
            v++
        }
    }
    return v
}

private fun StereoBodyRenderer.t2Epithelium(i: Int): Array<ColorVboMesh> = t2Get("epith$i") {
    val g = T2Geo(); val brush = ArrayList<Float>(); val rnd = java.util.Random(151L)
    val rh = 3.6f; val apical = 5.4f; val basal = -24f
    for (r in -5..6) for (c in -5..5) {
        val x = c * 1.5f * rh; val z = T2_PLATE_Z + r * sqrt(3f) * rh + (if (c % 2 != 0) sqrt(3f) * rh * 0.5f else 0f)
        val d = sqrt(x * x + (z - T2_PLATE_Z) * (z - T2_PLATE_Z))
        if (d < 10.6f || d > 30f) continue
        val corners = (0 until 6).map { k -> val a = k * T2PI / 3f; t2v(x + cos(a) * rh * 0.96f, 0f, z + sin(a) * rh * 0.96f) }
        for (k in 0 until 6) {
            val a = corners[k]; val b = corners[(k + 1) % 6]
            g.quad(t2v(a.x, basal, a.z), t2v(b.x, basal, b.z), t2v(b.x, apical, b.z), t2v(a.x, apical, a.z), T2_EPITHELIUM, 0.55f)
            g.tri(t2v(x, apical, z), t2v(a.x, apical, a.z), t2v(b.x, apical, b.z), T2_EPITHELIUM, 0.55f)
        }
        g.ellAxis(t2v(x, -13f + rnd.nextFloat() * 2f, z), t2v(0f, 1f, 0f), 1.3f, 3.2f, 1.3f, T2_NUCLEUS, 1f, 6, 9)
        repeat(22) {   // brush border (microvilli, 1-2 µm) on the apical surface
            val u = (rnd.nextFloat() - 0.5f) * rh * 1.5f; val w = (rnd.nextFloat() - 0.5f) * rh * 1.5f
            brush.addAll(listOf(x + u, apical, z + w, 0.99f, 0.9f, 0.86f, 0.5f, x + u, apical + 1.2f, z + w, 0.99f, 0.9f, 0.86f, 0.5f))
        }
    }
    val rigid = t2Bend(i, 0f, 0f)
    val bA = brush.toFloatArray(); for (k in 0 until bA.size / 7) rigid(bA, k * 7)
    arrayOf(TriMesh(g.baked(rigid)), LineMesh(bA))
}

private var t2DivBuilt = -1f
private var t2DivT = -1f
private var t2DivVerts = 0

/** Tour II stop 12: mitosis in a gut-lining cell — prometaphase to cytokinesis, 46 chromosomes. */
internal fun StereoBodyRenderer.drawDivision(n: TourNode, i: Int, seconds: Float) {
    if (!t2Near(i)) return
    t2Open(i, seconds) { own ->
        val f0 = t2Rigid(i, 0f, FloatArray(13))
        val epi = t2Epithelium(i)
        if (own) { t2DrawWorld(epi[0], true); t2DrawWorld(epi[1], true) }
        val t = seconds % 36f
        val cong = t2sm((t - 1f) / 6f); val anaA = t2sm((t - 16f) / 7f); val anaB = t2sm((t - 18f) / 6f)
        val telo = t2sm((t - 23f) / 4f); val cyto = t2sm((t - 23f) / 8f)
        val cycleA = t2sm(t / 1.2f) * (1f - t2sm((t - 34.5f) / 1.5f))
        val poleD = 6.0f + 1.3f * anaB
        // chromosomes, rebuilt at ~20 Hz (both eyes share one build)
        val sig = cong + anaA * 3f + anaB * 7f + telo * 13f + cycleA * 29f
        if (t2DivVerts == 0 || (abs(sig - t2DivBuilt) > 0.002f && abs(seconds - t2DivT) > 0.066f)) {
            t2DivBuilt = sig; t2DivT = seconds
            var v = 0
            val ch = t2Chromos
            val col = t2mix(T2_CHROMATID, T2_CHROMOSOME_LIGHT_T2, telo * 0.6f)
            val al = (1f - 0.55f * telo) * cycleA
            val r = 0.28f * (1f + 0.3f * telo); val shrink = 1f - 0.25f * telo
            val tw = FloatArray(3)
            for (c in ch) {
                for (sgn in SIGNS) {
                    val sc = 1f - cong
                    var x = T2_SPX + c.cx + c.sx * sc; var y = T2_SPY + c.cy + c.sy * sc
                    var z = T2_PLATE_Z + c.sz * sc + sgn * 0.3f + sgn * anaA * (poleD - 1.6f)
                    val conv = 1f - 0.45f * anaA - 0.2f * telo
                    x = T2_SPX + (x - T2_SPX) * conv; y = T2_SPY + (y - T2_SPY) * conv
                    val gam = T2PI * 0.5f * (1f - anaA) + 0.5f * anaA
                    val bk = -sgn
                    for (arm in 0..1) {
                        val s = if (arm == 0) -1f else 1f
                        val dx = c.wx * s * sin(gam); val dy = c.wy * s * sin(gam); val dz = bk * cos(gam)
                        val len = (if (arm == 0) c.lp else c.lq) * shrink
                        val w = t2W(f0, x, y, z); tw[0] = w[0]; tw[1] = w[1]; tw[2] = w[2]
                        // arm direction in world
                        val wx = f0[6] * dx + f0[9] * dy + f0[3] * dz; val wy = f0[7] * dx + f0[10] * dy + f0[4] * dz; val wz = f0[8] * dx + f0[11] * dy + f0[5] * dz
                        v = t2Arm(v, tw[0], tw[1], tw[2], wx, wy, wz, len, r, col, al)
                    }
                }
            }
            t2DivVerts = v
        }
        if (t2DivVerts > 0) {
            Matrix.setIdentityM(model, 0); Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
            colorShader.use(mvp, 1f); GLES20.glDisable(GLES20.GL_CULL_FACE)
            if (telo > 0.02f) GLES20.glDepthMask(false)
            t2ChromTris.draw(colorShader.positionHandle, colorShader.colorHandle, GLES20.GL_TRIANGLES, t2DivVerts)
            GLES20.glDepthMask(true); GLES20.glEnable(GLES20.GL_CULL_FACE)
        }
        // spindle: kinetochore fibres to each sister's own pole, interpolar fibres overlapping at the
        // equator, astral fibres radiating from the poles to the cortex
        t2LinesBegin()
        val sp = 1f - t2sm((t - 26f) / 4f)
        if (sp > 0.02f) {
            for (sgn in SIGNS) {
                val pz = T2_PLATE_Z + sgn * poleD
                val pw = t2W(f0, T2_SPX, T2_SPY, pz); val px = pw[0]; val py = pw[1]; val pzz = pw[2]
                if (cong > 0.2f) for (c in t2Chromos) {
                    val sc = 1f - cong; val conv = 1f - 0.45f * anaA
                    val x = T2_SPX + (c.cx + c.sx * sc) * conv; val y = T2_SPY + (c.cy + c.sy * sc) * conv
                    val z = T2_PLATE_Z + c.sz * sc + sgn * 0.3f + sgn * anaA * (poleD - 1.6f)
                    val k = t2W(f0, x, y, z)
                    t2Seg(k[0], k[1], k[2], px, py, pzz, T2_SPINDLE, 0.4f * cong * sp, 0.15f * sp)
                }
                for (q in 0 until 20) {
                    val a = q * 2.4f; val rr = 0.8f + (q % 5) * 0.9f
                    val e = t2W(f0, T2_SPX + cos(a) * rr, T2_SPY + sin(a) * rr, T2_PLATE_Z - sgn * 1.0f)
                    t2Seg(px, py, pzz, e[0], e[1], e[2], T2_SPINDLE, 0.3f * sp, 0.12f * sp)
                }
                for (q in 0 until 16) {
                    val a = q * 2.4f; val el = 0.4f + (q % 4) * 0.25f
                    val e = t2W(f0, T2_SPX + cos(a) * 5f * el, T2_SPY + sin(a) * 5f * el, pz + sgn * 4.5f * (1f - el * 0.5f))
                    t2Seg(px, py, pzz, e[0], e[1], e[2], T2_SPINDLE, 0.3f * sp, 0.05f)
                }
            }
        }
        t2LinesEnd(1.5f)
        // centrosomes: a pair of centrioles at right angles in pericentriolar material
        for (sgn in SIGNS) {
            val pz = T2_PLATE_Z + sgn * poleD
            val p = t2W(f0, T2_SPX, T2_SPY, pz).copyOf()
            t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.4f, 0.4f, 0.4f, sphere, T2_CENTROSOME, COL_LAMP, 0.55f * cycleA, 0.4f)
            t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], 0.1f, 0.1f, 0.25f, cylinder, T2_GTP_CAP, COL_LAMP, cycleA, 0.3f)
            t2Basis(p, f0[6], f0[7], f0[8], f0[9], f0[10], f0[11], 0.1f, 0.1f, 0.25f, cylinder, T2_GTP_CAP, COL_LAMP, cycleA, 0.3f)
        }
        // nuclear envelopes re-forming round each set (telophase)
        if (telo > 0.02f) for (sgn in SIGNS) {
            val p = t2W(f0, T2_SPX, T2_SPY, T2_PLATE_Z + sgn * (poleD - 1.9f)).copyOf()
            val rr = 3.4f * telo
            t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], rr, rr, rr * 0.9f, t2BallMesh(), T2_NUCLEUS, COL_LAMP, 0.3f * telo * cycleA, 0.1f)
        }
        // the cell: rounded, elongating in anaphase, then cleaved by the contractile ring
        val d = 1.2f * anaB + 5.4f * cyto; val R = 8.5f - 1.3f * cyto
        val waist = sqrt(max(0.3f, R * R - d * d))
        for (sgn in SIGNS) {
            val p = t2W(f0, T2_SPX, T2_SPY, T2_PLATE_Z + sgn * d).copyOf()
            t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], R, R, R, t2BallMesh(), T2_CELL, COL_CELL_EDGE, 0.13f * cycleA, 0.1f)
            if (d < 0.01f) break
        }
        if (cyto > 0.02f) {
            val p = t2W(f0, T2_SPX, T2_SPY, T2_PLATE_Z).copyOf()
            t2Basis(p, f0[3], f0[4], f0[5], f0[9], f0[10], f0[11], waist, waist, 4f, t2RingMesh(), T2_ACTOMYOSIN, COL_LAMP, cyto * cycleA, 0.3f)
            if (cyto > 0.85f) {
                val a = t2W(f0, T2_SPX, T2_SPY, T2_PLATE_Z - 0.5f).copyOf(); val b = t2W(f0, T2_SPX, T2_SPY, T2_PLATE_Z + 0.5f)
                drawStrut(a[0], a[1], a[2], b[0], b[1], b[2], 0.25f, T2_GTP_CAP, T2_GTP_CAP, 0.6f)
            }
        }
    }
}

internal val T2_CHROMOSOME_LIGHT_T2 = floatArrayOf(0.86f, 0.78f, 1f, 1f)
