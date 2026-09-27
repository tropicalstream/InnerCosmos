package com.rayneo.innercosmos

import android.opengl.GLES20
import android.opengl.Matrix
import kotlin.math.*

// Chapter III — Bethune: a life told through the tissues it touched.
// Landmark scenes, drawn by StereoBodyRenderer.drawLandmarks via the stop's Scene.
//
// Every structure here is sized from its real dimension at the stop's stated scale (see [umu]):
// the Mote is 1.5 units long, so at a 12 µm Mote one unit is 8 µm and a red cell is 0.94 units
// across. Most of the anatomy is built once into static world-space meshes (one draw call each,
// however many cells they hold) that follow the curving rail; only moving things are drawn per
// frame. Several stops look through the passage wall at the tissue beyond it: [openWall] clears
// the wall's depth (and optionally repaints it) over the stop's stretch of rail, so structures
// outside the passage radius — muscle fibres behind a vessel, a megakaryocyte behind a sinus
// wall, a lung cavity opening off an airway — can be drawn where they really are.

/** What drifts past at each stop of this tour, by stop index; stops not listed use DriftSpec.forAmb. */
internal val BETHUNE_DRIFT: Map<Int, DriftSpec> = mapOf(
    // Alveolar air: only fine dust reaches this deep (pollen is filtered out in the nose).
    // (no dust bodies: a mote near the lens reads as a pebble; the faint fine drift carries the air)
    0 to DriftSpec.NONE,
    // A 1.2 mm Mote in a donor's vein: every cell is below the resolution of the scene.
    1 to DriftSpec.NONE,
    // Stored blood: settled, still. The cells are drawn lying on the floor, not drifting.
    2 to DriftSpec.NONE,
    // The wound: blood seeping slowly through the torn tissue.
    3 to DriftSpec.of(BodyField.RED_CELL to 0.93f, BodyField.PLATELET to 0.07f, density = 0.1f, flow = 0.35f),
    // The transfusion: a vessel refilling from 15% to full over forty seconds, flowing with the craft.
    // (its red cells are drawn by the scene: a filling front with a cell-free layer at the wall)
    4 to DriftSpec.of(BodyField.PLATELET to 1f, density = 0.06f, flow = 1.1f),
    // The table, at 12 mm: cells are far below the resolution of the scene.
    5 to DriftSpec.NONE,
    // A marrow sinusoid: slow flow, new red cells and platelets.
    6 to DriftSpec.of(BodyField.RED_CELL to 0.84f, BodyField.PLATELET to 0.16f, density = 0.4f, flow = 0.35f),
    // The cut: tissue, not a vessel; the few red cells in the cleft are part of the scene.
    7 to DriftSpec.NONE,
    // Septicaemia: sluggish venous blood (the neutrophils are drawn by the scene).
    // (its red cells are drawn by the scene, packed and sluggish; the drift carries a few platelets)
    8 to DriftSpec.of(BodyField.PLATELET to 1f, density = 0.06f, flow = 0.3f),
)

// ------------------------------------------------------------------ palette
internal val T3_ALVEOLUS = floatArrayOf(0.93f, 0.70f, 0.72f, 1f)
internal val T3_SEPTUM = floatArrayOf(0.94f, 0.80f, 0.80f, 1f)
internal val T3_CAPILLARY = floatArrayOf(0.88f, 0.20f, 0.24f, 1f)
internal val T3_CAPILLARY_NET = floatArrayOf(0.85f, 0.22f, 0.26f, 1f)
internal val T3_PARENCHYMA = floatArrayOf(0.62f, 0.44f, 0.46f, 1f)
internal val T3_CASEUM = floatArrayOf(0.94f, 0.88f, 0.62f, 1f)
internal val T3_CASEUM_DEEP = floatArrayOf(0.80f, 0.74f, 0.52f, 1f)
internal val T3_CASEUM_LIP = floatArrayOf(0.90f, 0.84f, 0.60f, 1f)
internal val T3_CASEUM_MID = floatArrayOf(0.55f, 0.48f, 0.32f, 1f)
internal val T3_CASEUM_DEPTH = floatArrayOf(0.30f, 0.24f, 0.18f, 1f)
private val CAV_BANDS = arrayOf(floatArrayOf(0.72f, 0.64f, 0.44f, 1f), floatArrayOf(0.62f, 0.54f, 0.36f, 1f), floatArrayOf(0.52f, 0.44f, 0.30f, 1f),
    floatArrayOf(0.42f, 0.34f, 0.24f, 1f), floatArrayOf(0.34f, 0.27f, 0.20f, 1f))
private val CAV_GLOW = floatArrayOf(0.3f, 0.3f, 0.35f, 0.4f, 0.4f)
internal val T3_MACRO_PALE = floatArrayOf(0.88f, 0.80f, 0.80f, 1f)
internal val T3_BRONCHIOLE = floatArrayOf(0.90f, 0.75f, 0.78f, 1f)
internal val T3_BRONCHIOLE_DARK = floatArrayOf(0.78f, 0.60f, 0.64f, 1f)
internal val T3_SCAR = floatArrayOf(0.84f, 0.80f, 0.80f, 1f)
internal val T3_SCAR_SEPTUM = floatArrayOf(0.92f, 0.90f, 0.86f, 1f)
internal val T3_ANTHRACOTIC = floatArrayOf(0.36f, 0.33f, 0.40f, 1f)
internal val T3_ANTHRACOTIC_RIM = floatArrayOf(0.60f, 0.58f, 0.70f, 1f)
internal val T3_FOLD = floatArrayOf(0.93f, 0.80f, 0.82f, 1f)
internal val T3_GRANULOMA = floatArrayOf(0.84f, 0.68f, 0.72f, 1f)
internal val T3_EPITHELIOID = floatArrayOf(0.93f, 0.80f, 0.78f, 1f)
internal val T3_FIBROUS = floatArrayOf(0.96f, 0.94f, 0.89f, 1f)
internal val T3_LANGHANS = floatArrayOf(0.94f, 0.80f, 0.88f, 1f)
internal val T3_NUCLEUS = floatArrayOf(0.44f, 0.30f, 0.68f, 1f)
internal val T3_LYMPHOCYTE = floatArrayOf(0.42f, 0.40f, 0.78f, 1f)
internal val T3_INTIMA = floatArrayOf(0.80f, 0.78f, 0.82f, 1f)
internal val T3_MEDIA = floatArrayOf(0.70f, 0.52f, 0.56f, 1f)
internal val T3_ADVENTITIA = floatArrayOf(0.86f, 0.80f, 0.74f, 1f)
internal val T3_FLOW_WALL = floatArrayOf(0.70f, 0.15f, 0.18f, 1f)
internal val T3_AGGER = floatArrayOf(0.84f, 0.72f, 0.76f, 1f)
internal val T3_INTIMA_RIM = floatArrayOf(0.66f, 0.62f, 0.68f, 1f)
internal val T3_INTIMA_FOLD = floatArrayOf(0.80f, 0.70f, 0.74f, 1f)
internal val T3_VENOUS = floatArrayOf(0.42f, 0.05f, 0.09f, 1f)
internal val T3_BLOOD_IN = floatArrayOf(0.62f, 0.06f, 0.10f, 1f)
internal val T3_FLOW_BRIGHT = floatArrayOf(0.95f, 0.30f, 0.30f, 1f)
internal val T3_STEEL = floatArrayOf(0.70f, 0.74f, 0.80f, 1f)
internal val T3_STEEL_EDGE = floatArrayOf(0.96f, 0.98f, 1f, 1f)
internal val T3_BORE = floatArrayOf(0.36f, 0.38f, 0.44f, 1f)
internal val T3_VALVE = floatArrayOf(0.93f, 0.82f, 0.86f, 1f)
internal val T3_FLOW = floatArrayOf(0.86f, 0.16f, 0.20f, 1f)
internal val T3_COLD = floatArrayOf(0.24f, 0.33f, 0.52f, 1f)
internal val T3_STORED_RBC = floatArrayOf(0.40f, 0.04f, 0.09f, 1f)
internal val T3_PACKED = floatArrayOf(0.28f, 0.03f, 0.07f, 1f)
internal val T3_COLD_RIM_DIM = floatArrayOf(0.135f, 0.165f, 0.24f, 1f)
internal val T3_BUFFY = floatArrayOf(0.94f, 0.92f, 0.86f, 1f)
internal val T3_PLASMA_PALE = floatArrayOf(0.96f, 0.86f, 0.52f, 1f)
internal val T3_PLASMA = floatArrayOf(0.96f, 0.86f, 0.50f, 1f)
internal val T3_PLASMA_BG = floatArrayOf(0.72f, 0.74f, 0.78f, 1f)
internal val T3_COLD_RIM = floatArrayOf(0.60f, 0.75f, 1f, 1f)
internal val T3_GLASS_COLDER = floatArrayOf(0.55f, 0.72f, 0.98f, 1f)
internal val T3_FROST = floatArrayOf(0.85f, 0.92f, 1f, 1f)
internal val T3_PLATELET_LILAC = floatArrayOf(0.82f, 0.76f, 0.90f, 1f)
internal val T3_GLASS_COLD = floatArrayOf(0.60f, 0.78f, 0.98f, 1f)
internal val T3_GLASS_EDGE = floatArrayOf(0.90f, 0.95f, 1f, 1f)
internal val T3_LEVEL = floatArrayOf(1f, 0.95f, 0.80f, 1f)
internal val T3_GLASS = floatArrayOf(0.72f, 0.86f, 0.98f, 1f)
internal val T3_PLATELET = floatArrayOf(0.82f, 0.76f, 0.90f, 1f)
internal val T3_COTTON_LUMEN = floatArrayOf(0.76f, 0.72f, 0.62f, 1f)
internal val T3_LEUKOCYTE = floatArrayOf(0.92f, 0.93f, 0.88f, 1f)
internal val T3_WOUND_DARK = floatArrayOf(0.34f, 0.08f, 0.10f, 1f)
internal val T3_WOUND_FLOOR = floatArrayOf(0.56f, 0.16f, 0.18f, 1f)
internal val T3_I_BAND = floatArrayOf(0.86f, 0.54f, 0.55f, 1f)
internal val T3_A_BAND = floatArrayOf(0.74f, 0.36f, 0.40f, 1f)
internal val T3_MYOFIBRIL = floatArrayOf(0.88f, 0.48f, 0.48f, 1f)
internal val T3_CONTRACTION = floatArrayOf(0.46f, 0.12f, 0.16f, 1f)
internal val T3_MUSCLE_NUCLEUS = floatArrayOf(0.42f, 0.32f, 0.66f, 1f)
internal val T3_FIBRIN = floatArrayOf(0.97f, 0.94f, 0.82f, 1f)
internal val T3_GRIT = floatArrayOf(0.54f, 0.47f, 0.38f, 1f)
internal val T3_COTTON = floatArrayOf(0.94f, 0.92f, 0.82f, 1f)
internal val T3_GRAM_POS = floatArrayOf(0.60f, 0.38f, 0.88f, 1f)
internal val T3_SPORE = floatArrayOf(0.92f, 0.90f, 0.96f, 1f)
internal val T3_CAP_MUSCLE = floatArrayOf(0.75f, 0.15f, 0.18f, 1f)
internal val T3_ENDOMYSIUM = floatArrayOf(0.97f, 0.94f, 0.90f, 1f)
internal val T3_RBC = floatArrayOf(0.86f, 0.12f, 0.14f, 1f)
internal val T3_RBC_DEOXY = floatArrayOf(0.56f, 0.07f, 0.13f, 1f)
internal val T3_RBC_RIM3 = floatArrayOf(0.98f, 0.35f, 0.32f, 1f)
internal val T3_ENDOTHELIUM = floatArrayOf(0.94f, 0.82f, 0.85f, 1f)
internal val T3_JUNCTION = floatArrayOf(0.94f, 0.80f, 0.84f, 1f)
internal val T3_ENDO_NUCLEUS = floatArrayOf(0.80f, 0.64f, 0.82f, 1f)
internal val T3_ENDO_NUC_FLAT = floatArrayOf(0.70f, 0.58f, 0.78f, 1f)
internal val T3_ENDO_NUC_PALE = floatArrayOf(0.78f, 0.70f, 0.84f, 1f)
internal val T3_MUSCLE_NUC_LOW = floatArrayOf(0.52f, 0.40f, 0.66f, 1f)
internal val T3_TISSUE_DARK = floatArrayOf(0.20f, 0.11f, 0.15f, 1f)
internal val T3_ISCHAEMIC = floatArrayOf(0.52f, 0.50f, 0.60f, 1f)
internal val T3_ISCHAEMIC_A = floatArrayOf(0.40f, 0.38f, 0.50f, 1f)
internal val T3_PERFUSED = floatArrayOf(0.70f, 0.18f, 0.20f, 1f)
internal val T3_PERFUSED_A = floatArrayOf(0.48f, 0.08f, 0.12f, 1f)
internal val T3_SMC = floatArrayOf(0.82f, 0.52f, 0.54f, 1f)
internal val T3_CAP_EMPTY = floatArrayOf(0.40f, 0.30f, 0.40f, 1f)
internal val T3_SKIN_EDGE = floatArrayOf(0.55f, 0.38f, 0.30f, 1f)
internal val T3_SKIN_TOP = floatArrayOf(0.95f, 0.76f, 0.66f, 1f)
internal val T3_DERMIS = floatArrayOf(0.96f, 0.80f, 0.78f, 1f)
internal val T3_FAT = floatArrayOf(0.99f, 0.88f, 0.52f, 1f)
internal val T3_FAT_FACE = floatArrayOf(0.96f, 0.82f, 0.44f, 1f)
internal val T3_SEPTA = floatArrayOf(0.98f, 0.92f, 0.88f, 1f)
internal val T3_CUT_VESSEL = floatArrayOf(0.55f, 0.08f, 0.12f, 1f)
internal val T3_FASCIA = floatArrayOf(0.97f, 0.96f, 0.92f, 1f)
internal val T3_MUSCLE = floatArrayOf(0.76f, 0.22f, 0.24f, 1f)
internal val T3_MUSCLE_VIABLE = floatArrayOf(0.78f, 0.20f, 0.22f, 1f)
internal val T3_MUSCLE_LINE = floatArrayOf(0.56f, 0.14f, 0.18f, 1f)
internal val T3_GAUZE = floatArrayOf(0.97f, 0.97f, 0.94f, 1f)
internal val T3_GAUZE_WET = floatArrayOf(0.70f, 0.22f, 0.20f, 1f)
internal val T3_BLEED = floatArrayOf(0.90f, 0.10f, 0.12f, 1f)
internal val T3_CORD = floatArrayOf(0.50f, 0.32f, 0.48f, 1f)
internal val T3_MEGA = floatArrayOf(0.88f, 0.74f, 0.92f, 1f)
internal val T3_MEGA_BODY = floatArrayOf(0.80f, 0.64f, 0.86f, 1f)
internal val T3_MEGA_RIM = floatArrayOf(0.66f, 0.56f, 0.80f, 1f)
private const val MK_CUT = 0.6f
internal val T3_MEGA_NUCLEUS = floatArrayOf(0.40f, 0.20f, 0.60f, 1f)
internal val T3_MEGA_GRANULE = floatArrayOf(0.96f, 0.72f, 0.90f, 1f)
internal val T3_MACROPHAGE = floatArrayOf(0.82f, 0.86f, 0.74f, 1f)
internal val T3_EB_EARLY = floatArrayOf(0.40f, 0.38f, 0.80f, 1f)
internal val T3_EB_MID = floatArrayOf(0.66f, 0.46f, 0.72f, 1f)
internal val T3_EB_LATE = floatArrayOf(0.90f, 0.46f, 0.52f, 1f)
internal val T3_EB_NUCLEUS = floatArrayOf(0.30f, 0.20f, 0.46f, 1f)
internal val T3_RETICULOCYTE = floatArrayOf(0.88f, 0.44f, 0.58f, 1f)
internal val T3_ADIPOCYTE = floatArrayOf(0.99f, 0.95f, 0.78f, 1f)
internal val T3_MYELOCYTE = floatArrayOf(0.92f, 0.86f, 0.82f, 1f)
internal val T3_BONE = floatArrayOf(0.96f, 0.91f, 0.78f, 1f)
internal val T3_OSTEOBLAST = floatArrayOf(0.74f, 0.64f, 0.88f, 1f)
internal val T3_LINING = floatArrayOf(0.90f, 0.84f, 0.72f, 1f)
internal val T3_PORE = floatArrayOf(0.94f, 0.80f, 0.84f, 1f)
internal val T3_VACUOLE = floatArrayOf(0.95f, 0.90f, 0.95f, 1f)
internal val T3_MACRO_PINK = floatArrayOf(0.88f, 0.78f, 0.80f, 1f)
internal val T3_MYELO_GRANULE = floatArrayOf(0.95f, 0.75f, 0.80f, 1f)
internal val T3_EB_PRO = floatArrayOf(0.35f, 0.35f, 0.80f, 1f)
internal val T3_OSTEOID = floatArrayOf(0.96f, 0.80f, 0.84f, 1f)
internal val T3_GRAN_NUCLEUS = floatArrayOf(0.40f, 0.30f, 0.66f, 1f)
internal val T3_STEM = floatArrayOf(0.82f, 0.87f, 0.98f, 1f)
internal val T3_CORNEUM = floatArrayOf(0.96f, 0.84f, 0.86f, 1f)
internal val T3_CORN_BACK = floatArrayOf(0.92f, 0.80f, 0.82f, 1f)
internal val T3_GRAN_BACK = floatArrayOf(0.88f, 0.70f, 0.72f, 1f)
internal val T3_EPI_BACK = floatArrayOf(0.90f, 0.68f, 0.70f, 1f)
internal val T3_LUCIDUM = floatArrayOf(0.97f, 0.94f, 0.88f, 1f)
internal val T3_INTERCELL = floatArrayOf(0.90f, 0.74f, 0.78f, 1f)
internal val T3_CRUSHED = floatArrayOf(0.97f, 0.86f, 0.88f, 1f)
internal val T3_DESMOSOME = floatArrayOf(0.62f, 0.45f, 0.66f, 1f)
internal val T3_PYKNOTIC = floatArrayOf(0.30f, 0.18f, 0.40f, 1f)
internal val T3_STREP = floatArrayOf(0.62f, 0.35f, 0.95f, 1f)
internal val T3_GRANULAR = floatArrayOf(0.90f, 0.72f, 0.72f, 1f)
internal val T3_KERATOHYALIN = floatArrayOf(0.36f, 0.22f, 0.44f, 1f)
internal val T3_SPINOUS = floatArrayOf(0.94f, 0.70f, 0.72f, 1f)
internal val T3_BASAL = floatArrayOf(0.80f, 0.48f, 0.60f, 1f)
internal val T3_BASEMENT = floatArrayOf(0.99f, 0.95f, 0.95f, 1f)
internal val T3_COLLAGEN = floatArrayOf(0.99f, 0.86f, 0.86f, 1f)
internal val T3_INTERSTITIUM = floatArrayOf(0.44f, 0.28f, 0.30f, 1f)
internal val T3_OEDEMA = floatArrayOf(0.98f, 0.88f, 0.56f, 1f)
internal val T3_GAP_RIM = floatArrayOf(1f, 0.95f, 0.75f, 1f)
internal val T3_GAP_LINE = floatArrayOf(0.90f, 0.80f, 0.70f, 1f)
internal val T3_PERICYTE = floatArrayOf(0.72f, 0.55f, 0.60f, 1f)
internal val T3_PHAGOSOME = floatArrayOf(0.95f, 0.95f, 1f, 1f)
internal val T3_NEUTRO_GRANULE = floatArrayOf(0.98f, 0.86f, 0.92f, 1f)
internal val T3_LAMP_SOFT = floatArrayOf(1f, 0.9f, 0.7f, 1f)
internal val T3_BLACK = floatArrayOf(0f, 0f, 0f, 1f)

// ============================================================== toolkit

/** Rail units per stop (the stops are ~16 world units apart). */
private const val NODE_UNITS = 16f

/** µm per scene unit at a stop's stated scale (the Mote, 1.5 units, is shipLenM long). */
private fun StereoBodyRenderer.umu(i: Int): Float = (nodes[i].shipLenM * 1e6 / 1.5).toFloat()

/** A small 3-vector for building meshes (build time, and a few per-frame positions). */
private class V3(@JvmField val x: Float, @JvmField val y: Float, @JvmField val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    operator fun unaryMinus() = V3(-x, -y, -z)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(dot(this))
    fun unit(): V3 { val l = len(); return if (l < 1e-9f) V3(0f, 1f, 0f) else this * (1f / l) }
}

private fun lerpV(a: V3, b: V3, t: Float) = a + (b - a) * t
private fun perp(v: V3): V3 = (if (abs(v.y) < 0.9f) v.cross(V3(0f, 1f, 0f)) else v.cross(V3(1f, 0f, 0f))).unit()
private fun angDiff(a: Float, b: Float) = abs(atan2(sin(a - b), cos(a - b)))

/** A rail frame as vectors: centre, along (deeper), side, up. */
private class RF(val c: V3, val d: V3, val s: V3, val u: V3) {
    fun at(side: Float, up: Float) = c + s * side + u * up
    fun radial(th: Float) = s * cos(th) + u * sin(th)
    fun pol(th: Float, r: Float) = c + radial(th) * r
}

/** The rail frame [a] units along from stop [b] (rail-following: it curves with the passage). */
private fun StereoBodyRenderer.rfv(b: Float, a: Float): RF {
    val f = frameAt(b + a / NODE_UNITS)
    return RF(V3(f.cx, f.cy, f.cz), V3(f.dx, f.dy, f.dz), V3(f.sx, f.sy, f.sz), V3(f.ux, f.uy, f.uz))
}

private fun StereoBodyRenderer.wAt(b: Float, a: Float, side: Float, up: Float) = rfv(b, a).at(side, up)
private fun StereoBodyRenderer.radiusAt(b: Float, a: Float) = tunnelRadius(b + a / NODE_UNITS)

/**
 * Triangle soup with explicit normals, built once. Every triangle is wound counter-clockwise as
 * seen from the side its normals face, so a closed shape can be back-face culled like any sphere.
 */
private class Mb {
    private var d = FloatArray(6 * 3 * 512)
    private var n = 0
    private fun put(p: V3, q: V3) {
        if (n + 6 > d.size) d = d.copyOf(d.size * 2)
        val u = q.unit()
        d[n++] = p.x; d[n++] = p.y; d[n++] = p.z; d[n++] = u.x; d[n++] = u.y; d[n++] = u.z
    }

    fun tri(a: V3, na: V3, b: V3, nb: V3, c: V3, nc: V3) {
        val g = (b - a).cross(c - a)
        if (g.dot(na + nb + nc) >= 0f) { put(a, na); put(b, nb); put(c, nc) } else { put(a, na); put(c, nc); put(b, nb) }
    }

    /** A grid surface: positions p(u,v) and normals nf(u,v,p), u over [st] rows and v over [sl] columns. */
    fun grid(st: Int, sl: Int, p: (Float, Float) -> V3, nf: (Float, Float, V3) -> V3) {
        val P = Array(st + 1) { i -> Array(sl + 1) { j -> p(i.toFloat() / st, j.toFloat() / sl) } }
        val N = Array(st + 1) { i -> Array(sl + 1) { j -> nf(i.toFloat() / st, j.toFloat() / sl, P[i][j]) } }
        for (i in 0 until st) for (j in 0 until sl) {
            tri(P[i][j], N[i][j], P[i + 1][j], N[i + 1][j], P[i + 1][j + 1], N[i + 1][j + 1])
            tri(P[i][j], N[i][j], P[i + 1][j + 1], N[i + 1][j + 1], P[i][j + 1], N[i][j + 1])
        }
    }

    /** A grid surface with normals from finite differences, turned to agree with [hint](u, v, p). */
    fun gridFd(st: Int, sl: Int, p: (Float, Float) -> V3, hint: (Float, Float, V3) -> V3) {
        val e = 1e-3f
        grid(st, sl, p) { u, v, q ->
            val du = p((u + e).coerceAtMost(1f), v) - p((u - e).coerceAtLeast(0f), v)
            val dv = p(u, (v + e).coerceAtMost(1f)) - p(u, (v - e).coerceAtLeast(0f))
            val c = du.cross(dv); val h = hint(u, v, q)
            if (c.len() < 1e-10f) h else if (c.dot(h) < 0f) -c else c
        }
    }

    /** Ellipsoid centred at [c]: [a] is the polar semi-axis vector, [b] and [e] the equatorial ones. */
    fun ellipsoid(c: V3, a: V3, b: V3, e: V3, st: Int = 6, sl: Int = 10, inward: Boolean = false) {
        val ia = a * (1f / a.dot(a)); val ib = b * (1f / b.dot(b)); val ie = e * (1f / e.dot(e))
        val sg = if (inward) -1f else 1f
        grid(st, sl, { u, v ->
            val ph = u * PI.toFloat(); val th = v * 2f * PI.toFloat()
            c + a * cos(ph) + b * (sin(ph) * cos(th)) + e * (sin(ph) * sin(th))
        }) { u, v, _ ->
            val ph = u * PI.toFloat(); val th = v * 2f * PI.toFloat()
            (ia * cos(ph) + ib * (sin(ph) * cos(th)) + ie * (sin(ph) * sin(th))) * sg
        }
    }

    fun sphere(c: V3, r: Float, st: Int = 6, sl: Int = 10) = ellipsoid(c, V3(0f, r, 0f), V3(r, 0f, 0f), V3(0f, 0f, r), st, sl)

    /** An ellipsoid given its long axis direction [ax] (half-length [la]) and a second direction [bx]. */
    fun ellipsoidAxes(c: V3, ax: V3, la: Float, bx: V3, lb: Float, le: Float, st: Int = 6, sl: Int = 10) {
        val a = ax.unit(); val e = a.cross(bx).unit(); val b = e.cross(a).unit()
        ellipsoid(c, a * la, b * lb, e * le, st, sl)
    }

    /** A tube through [pts] (radius per point), rotation-minimising frames, rounded caps. */
    fun tube(pts: List<V3>, radii: FloatArray, sides: Int = 8, caps: Boolean = true) {
        val m = pts.size
        if (m < 2) return
        val T = Array(m) { k -> (pts[min(k + 1, m - 1)] - pts[max(k - 1, 0)]).unit() }
        val N = arrayOfNulls<V3>(m); val B = arrayOfNulls<V3>(m)
        N[0] = perp(T[0])
        for (k in 1 until m) { val p = N[k - 1]!!; N[k] = (p - T[k] * p.dot(T[k])).unit() }
        for (k in 0 until m) B[k] = T[k].cross(N[k]!!).unit()
        class Ring(val c: V3, val n: V3, val b: V3, val t: V3, val r: Float, val tilt: Float)
        val rings = ArrayList<Ring>()
        val capSteps = 3
        if (caps) for (q in capSteps downTo 1) {
            val ang = q.toFloat() / capSteps * PI.toFloat() / 2f
            rings.add(Ring(pts[0] - T[0] * (radii[0] * sin(ang)), N[0]!!, B[0]!!, T[0], radii[0] * cos(ang), -sin(ang)))
        }
        for (k in 0 until m) rings.add(Ring(pts[k], N[k]!!, B[k]!!, T[k], radii[k], 0f))
        if (caps) for (q in 1..capSteps) {
            val ang = q.toFloat() / capSteps * PI.toFloat() / 2f
            rings.add(Ring(pts[m - 1] + T[m - 1] * (radii[m - 1] * sin(ang)), N[m - 1]!!, B[m - 1]!!, T[m - 1], radii[m - 1] * cos(ang), sin(ang)))
        }
        fun pos(r: Ring, j: Int): V3 { val th = j * 2f * PI.toFloat() / sides; return r.c + (r.n * cos(th) + r.b * sin(th)) * r.r }
        fun nrm(r: Ring, j: Int): V3 {
            val th = j * 2f * PI.toFloat() / sides
            val rad = r.n * cos(th) + r.b * sin(th)
            return rad * sqrt(1f - r.tilt * r.tilt) + r.t * r.tilt
        }
        for (k in 0 until rings.size - 1) for (j in 0 until sides) {
            val r0 = rings[k]; val r1 = rings[k + 1]
            tri(pos(r0, j), nrm(r0, j), pos(r1, j), nrm(r1, j), pos(r1, j + 1), nrm(r1, j + 1))
            tri(pos(r0, j), nrm(r0, j), pos(r1, j + 1), nrm(r1, j + 1), pos(r0, j + 1), nrm(r0, j + 1))
        }
    }

    fun rod(p0: V3, p1: V3, r: Float, sides: Int = 8) = tube(listOf(p0, p1), floatArrayOf(r, r), sides, true)

    /** A plain open cylinder (no caps) between two points. */
    fun cylinder(p0: V3, p1: V3, r: Float, sides: Int = 14) {
        val t = (p1 - p0).unit(); val nn = perp(t); val bb = t.cross(nn).unit()
        grid(1, sides, { u, v -> val th = v * 2f * PI.toFloat(); lerpV(p0, p1, u) + (nn * cos(th) + bb * sin(th)) * r }) { _, v, _ ->
            val th = v * 2f * PI.toFloat(); nn * cos(th) + bb * sin(th)
        }
    }

    /** A torus (or arc of one): centre [c], axis [n], major radius [R], minor radius [r]. */
    fun torus(c: V3, n: V3, R: Float, r: Float, seg: Int = 24, sides: Int = 6, arc: Float = 1f, start: Float = 0f) {
        val a = n.unit(); val e1 = perp(a); val e2 = a.cross(e1).unit()
        grid(seg, sides, { u, v ->
            val ph = (start + u * arc) * 2f * PI.toFloat(); val ps = v * 2f * PI.toFloat()
            val rh = e1 * cos(ph) + e2 * sin(ph)
            c + rh * R + (rh * cos(ps) + a * sin(ps)) * r
        }) { u, v, _ ->
            val ph = (start + u * arc) * 2f * PI.toFloat(); val ps = v * 2f * PI.toFloat()
            val rh = e1 * cos(ph) + e2 * sin(ph)
            rh * cos(ps) + a * sin(ps)
        }
    }

    /** A flat annulus (r0..r1) in the plane through [c] normal to [n] (normals along n). */
    fun annulus(c: V3, n: V3, r0: Float, r1: Float, seg: Int = 32, wobble: Float = 0f) {
        val a = n.unit(); val e1 = perp(a); val e2 = a.cross(e1).unit()
        grid(1, seg, { u, v ->
            val th = v * 2f * PI.toFloat(); val w = 1f + wobble * sin(th * 7f) * (u * 2f - 1f)
            c + (e1 * cos(th) + e2 * sin(th)) * ((r0 + (r1 - r0) * u) * w)
        }) { _, _, _ -> a }
    }

    /** A biconcave red cell (Evans-Fung profile) of radius [r] with its disc axis along [ax]. */
    fun redCell(c: V3, ax: V3, r: Float, rings: Int = 5, sl: Int = 12) {
        // Top and bottom faces built as separate grids, each running from the rim (u = 0) to its own
        // pole (u = 1), so the dimple is a real surface ~0.8 µm thick at the centre, never a hole.
        val a = ax.unit(); val e1 = perp(a); val e2 = a.cross(e1).unit()
        for (sg in floatArrayOf(1f, -1f)) {
            fun p(u: Float, v: Float): V3 {
                val rr = (1f - u).coerceIn(0f, 1f)
                val q = rr * rr
                val h = 0.5f * sqrt((1f - q).coerceAtLeast(0f)) * (0.81f + 7.83f * q - 4.39f * q * q) / 3.91f
                val th = v * 2f * PI.toFloat()
                return c + (e1 * cos(th) + e2 * sin(th)) * (rr * r) + a * (sg * h * r)
            }
            gridFd(rings + 1, sl, ::p) { _, _, q -> val rel = q - c; val radial = rel - a * rel.dot(a); a * sg + radial * 0.3f }
        }
    }

    fun build(twoSided: Boolean = false): T3Mesh = T3Mesh(d.copyOf(n), twoSided)
}

/** A static triangle mesh of this file's own (position + normal), optionally drawn two-sided. */
private class T3Mesh(data: FloatArray, private val twoSided: Boolean) : LitMesh() {
    private val vbo = makeVbo(data)
    private val count = data.size / 6
    override fun draw(positionHandle: Int, normalHandle: Int) {
        if (count == 0) return
        if (twoSided) GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        if (twoSided) GLES20.glEnable(GLES20.GL_CULL_FACE)
    }
}

/** Meshes (and a few precomputed points) built on first use, dropped when the GL context is recreated. */
private object T3Cache {
    var owner: Any? = null
    val meshes = HashMap<String, LitMesh>()
    val points = HashMap<String, Any>()
}

private fun StereoBodyRenderer.checkCache() {
    if (T3Cache.owner !== sphere) { T3Cache.meshes.clear(); T3Cache.points.clear(); T3Cache.owner = sphere }
}

private fun StereoBodyRenderer.cached(key: String, build: () -> LitMesh): LitMesh {
    checkCache()
    return T3Cache.meshes.getOrPut(key, build)
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> StereoBodyRenderer.cachedValue(key: String, build: () -> T): T {
    checkCache()
    return T3Cache.points.getOrPut(key, build) as T
}

/** Draw a static world-space mesh. */
private fun StereoBodyRenderer.drawMesh(mesh: LitMesh, base: FloatArray, accent: FloatArray, alpha: Float = 1f, pattern: Float = 0f, glow: Float = 0f) {
    Matrix.setIdentityM(model, 0)
    drawLitModel(mesh, base, accent, alpha * landmarkFade, pattern, glow)
}

/** Draw a static world-space mesh scaled by [k] across the rail (side/up) about the rail axis at [b]: breathing, dilating. */
private fun StereoBodyRenderer.drawMeshRadial(mesh: LitMesh, b: Float, k: Float, base: FloatArray, accent: FloatArray, alpha: Float = 1f, pattern: Float = 0f, glow: Float = 0f) {
    val f = frameAt(b)
    val q = k - 1f
    val m = model
    m[0] = 1f + q * (f.sx * f.sx + f.ux * f.ux); m[4] = q * (f.sx * f.sy + f.ux * f.uy); m[8] = q * (f.sx * f.sz + f.ux * f.uz)
    m[1] = m[4]; m[5] = 1f + q * (f.sy * f.sy + f.uy * f.uy); m[9] = q * (f.sy * f.sz + f.uy * f.uz)
    m[2] = m[8]; m[6] = m[9]; m[10] = 1f + q * (f.sz * f.sz + f.uz * f.uz)
    m[3] = 0f; m[7] = 0f; m[11] = 0f; m[15] = 1f
    m[12] = f.cx - (m[0] * f.cx + m[4] * f.cy + m[8] * f.cz)
    m[13] = f.cy - (m[1] * f.cx + m[5] * f.cy + m[9] * f.cz)
    m[14] = f.cz - (m[2] * f.cx + m[6] * f.cy + m[10] * f.cz)
    drawLitModel(mesh, base, accent, alpha * landmarkFade, pattern, glow)
}

/** Draw [mesh] (built in its own local frame) at [p] with local z along [z] and local y along [y]. */
private fun StereoBodyRenderer.drawLocal(mesh: LitMesh, p: V3, z: V3, y: V3, base: FloatArray, accent: FloatArray, alpha: Float = 1f, glow: Float = 0f) =
    drawBasis(p.x, p.y, p.z, z.x, z.y, z.z, y.x, y.y, y.z, 1f, 1f, 1f, mesh, base, accent, alpha, 0f, glow)

/** Draw [mesh] at [p], local z along [z], local y along [y], scaled per axis (x, y, z). */
private fun StereoBodyRenderer.drawScaled(mesh: LitMesh, p: V3, z: V3, y: V3, sx: Float, sy: Float, sz: Float, base: FloatArray, accent: FloatArray, alpha: Float = 1f, glow: Float = 0f) =
    drawBasis(p.x, p.y, p.z, z.x, z.y, z.z, y.x, y.y, y.z, sx, sy, sz, mesh, base, accent, alpha, 0f, glow)

/** A tube following the rail from [a0] to [a1] units past stop [b], at [frac] of the passage radius. */
private fun StereoBodyRenderer.railTube(b: Float, a0: Float, a1: Float, frac: Float, inward: Boolean): Mb {
    val mb = Mb()
    val st = ((a1 - a0) / 0.5f).toInt().coerceAtLeast(2)
    mb.grid(st, 18, { u, v ->
        val a = a0 + (a1 - a0) * u
        rfv(b, a).pol(v * 2f * PI.toFloat(), radiusAt(b, a) * frac)
    }) { u, _, p ->
        val c = rfv(b, a0 + (a1 - a0) * u).c
        if (inward) c - p else p - c
    }
    return mb
}

/** Close a rail tube at [a] with a disc facing back down the passage (so the far end is never a hole). */
private fun Mb.railCap(r: StereoBodyRenderer, b: Float, a: Float, frac: Float) {
    val f = r.rfv(b, a); val R = r.tunnelRadius(b + a / NODE_UNITS) * frac
    grid(1, 18, { u, v -> f.pol(v * 2f * PI.toFloat(), R * u) }) { _, _, _ -> -f.d }
}

/**
 * The lumen of a vessel filled with blood (or plasma): a translucent tube with a far cap, seen
 * from inside, tinting everything beyond it. Draw after the opaque scene, depth writes off.
 */
private fun StereoBodyRenderer.fillLumen(key: String, b: Float, a0: Float, a1: Float, frac: Float, col: FloatArray, alpha: Float, glow: Float) {
    val end = min(a1, (nodes.lastIndex - b) * NODE_UNITS)
    val m = cached(key) { railTube(b, a0, end, frac, true).also { it.railCap(this, b, end, frac) }.build(twoSided = true) }
    GLES20.glDepthMask(false)
    drawMesh(m, col, col, alpha, 0f, glow)
    GLES20.glDepthMask(true)
}

/**
 * Look through the passage wall at this stop: over the stop's stretch of rail, the wall's depth is
 * pushed to the far plane (and its colour replaced by [paint], if given), so tissue drawn beyond the
 * wall radius shows where it really lies. Only while the craft is nearer this stop than any other
 * (the depth clear would otherwise cut into the neighbouring stop's scene).
 */
private fun StereoBodyRenderer.openWall(i: Int, a0: Float, a1: Float, paint: FloatArray?, glow: Float = 0f): Boolean {
    // Seen from the neighbouring stop these scenes would hang half-built in the passage (their
    // tissue lies beyond the wall), so each fades in over the last stretch of the approach instead.
    val near = smooth01((0.45f - abs(routeProgress - i)) / 0.12f)
    if (near <= 0f) return false
    landmarkFade *= near
    colorShader.globalFade = landmarkFade
    // (the far end runs well past the scene, so the passage beyond is only a speck in the distance)
    val end = min(a1, (nodes.lastIndex - i) * NODE_UNITS)
    val tube = cached("wall$i") { railTube(i.toFloat(), a0, end, 0.84f, true).also { it.railCap(this, i.toFloat(), end, 0.84f) }.build(twoSided = true) }
    GLES20.glDepthFunc(GLES20.GL_ALWAYS)
    GLES20.glDepthRangef(1f, 1f)
    if (paint == null) GLES20.glColorMask(false, false, false, false)
    Matrix.setIdentityM(model, 0)
    drawLitModel(tube, paint ?: T3_BLACK, T3_BLACK, landmarkFade, 0f, glow)
    GLES20.glColorMask(true, true, true, true)
    GLES20.glDepthRangef(0f, 1f)
    GLES20.glDepthFunc(GLES20.GL_LESS)
    return true
}

/** Seconds since the craft arrived at stop [i] (0 while away); restarts after any absence. */
private val t3Arrive = FloatArray(16) { -1f }
private val t3Seen = FloatArray(16) { -1f }
private fun StereoBodyRenderer.sinceArrival(i: Int, seconds: Float): Float {
    val far = abs(routeProgress - i) > 0.5f
    val gap = seconds - t3Seen[i] > 0.6f
    t3Seen[i] = seconds
    if (far) { t3Arrive[i] = -1f; return 0f }
    if (gap || t3Arrive[i] < 0f) t3Arrive[i] = seconds
    return seconds - t3Arrive[i]
}

/** The translucent lining of a vessel: endothelial cells as a sheet, their junctions, their nuclei. */
private fun StereoBodyRenderer.endotheliumMeshes(key: String, b: Float, a0: Float, a1: Float, rAt: (Float) -> Float,
                                                  cellLen: Float, around: Int, holes: List<Pair<Float, Float>> = emptyList(),
                                                  holeA: Float = 0.55f, holeTh: Float = 0.16f): Triple<LitMesh, LitMesh, LitMesh> {
    val tube = cached("${key}_tube") {
        val mb = Mb()
        val fine = holes.isNotEmpty() && holeA < 0.3f
        val st = ((a1 - a0) / (if (fine) 0.1f else 0.4f)).toInt()
        val sl = around * (if (fine) 10 else 3)
        // Holes (gaps opened between cells) are left out of the sheet: (along, angle) centres.
        val P = Array(st + 1) { i -> Array(sl + 1) { j -> val a = a0 + (a1 - a0) * i / st; rfv(b, a).pol(j * 2f * PI.toFloat() / sl, rAt(a)) } }
        val C = Array(st + 1) { i -> rfv(b, a0 + (a1 - a0) * i / st).c }
        for (i in 0 until st) for (j in 0 until sl) {
            val a = a0 + (a1 - a0) * (i + 0.5f) / st; val th = (j + 0.5f) * 2f * PI.toFloat() / sl
            if (holes.any { (ha, hth) -> abs(a - ha) < holeA && angDiff(th, hth) < holeTh }) continue
            val n00 = C[i] - P[i][j]; val n10 = C[i + 1] - P[i + 1][j]; val n11 = C[i + 1] - P[i + 1][j + 1]; val n01 = C[i] - P[i][j + 1]
            mb.tri(P[i][j], n00, P[i + 1][j], n10, P[i + 1][j + 1], n11)
            mb.tri(P[i][j], n00, P[i + 1][j + 1], n11, P[i][j + 1], n01)
        }
        mb.build(twoSided = true)
    }
    val junctions = cached("${key}_junc") {
        val mb = Mb()
        val dth = 2f * PI.toFloat() / around
        for (col in 0 until around) {
            val th = col * dth + dth / 2f
            // The long border between two columns of cells, gently wavy.
            val pts = ArrayList<V3>(); var a = a0
            while (a <= a1) { pts.add(rfv(b, a).pol(th + 0.05f * sin(a * 2.3f + col), rAt(a) - 0.02f)); a += 0.5f }
            mb.tube(pts, FloatArray(pts.size) { 0.012f }, 4, false)
            // The cells' ends, staggered column to column.
            var e = a0 + (if (col % 2 == 0) 0f else cellLen * 0.5f)
            while (e <= a1) {
                val arc = ArrayList<V3>()
                for (k in 0..5) arc.add(rfv(b, e + 0.2f * sin(k * 1.1f)).pol(th - dth + k * dth / 5f, rAt(e) - 0.02f))
                mb.tube(arc, FloatArray(arc.size) { 0.012f }, 4, false)
                e += cellLen
            }
        }
        mb.build()
    }
    val nuclei = cached("${key}_nuc") {
        val mb = Mb()
        val dth = 2f * PI.toFloat() / around
        for (col in 0 until around) {
            var a = a0 + (if (col % 2 == 0) cellLen * 0.5f else cellLen)
            while (a < a1) {
                val f = rfv(b, a); val th = col * dth
                // Flat (~1 µm), elongated along the vessel, lying in the wall and bulging only slightly.
                mb.ellipsoid(f.pol(th, rAt(a) - 0.03f), f.radial(th) * 0.04f, f.d * 0.6f, f.d.cross(f.radial(th)).unit() * 0.25f, 5, 14)
                a += cellLen
            }
        }
        mb.build()
    }
    return Triple(tube, junctions, nuclei)
}

/** Streptococcus: a gently curved chain of [n] cocci of radius [r] along local z, the last one dividing. */
private fun chainMesh(n: Int, r: Float, seed: Int): T3Mesh {
    val mb = Mb()
    val rnd = java.util.Random(seed.toLong())
    val gap = r * 1.9f
    var x = 0f; var y = 0f; var z = -gap * (n - 1) / 2f
    var hx = 0f; var hy = 0f
    for (k in 0 until n) {
        if (k == n - 1) {
            // Dividing: elongated, the new septum pinching it into two halves.
            mb.sphere(V3(x, y, z - r * 0.22f), r * 0.92f, 6, 10)
            mb.sphere(V3(x, y, z + r * 0.62f), r * 0.86f, 6, 10)
        } else mb.sphere(V3(x, y, z), r, 6, 10)
        hx = (hx + (rnd.nextFloat() - 0.5f) * 0.35f).coerceIn(-0.5f, 0.5f)
        hy = (hy + (rnd.nextFloat() - 0.5f) * 0.35f).coerceIn(-0.5f, 0.5f)
        x += hx * gap; y += hy * gap; z += gap
    }
    return mb.build()
}

/** A neutrophil's nucleus: [lobes] lobes joined by thin chromatin strands, in a cell of radius [R] (local, centred). */
private fun neutrophilNucleus(R: Float, lobes: Int, seed: Int): T3Mesh {
    val mb = Mb()
    val rnd = java.util.Random(seed.toLong())
    val pts = ArrayList<V3>()
    for (k in 0 until lobes) {
        val a = -1.1f + 2.2f * k / (lobes - 1).coerceAtLeast(1)
        pts.add(V3(cos(a) * R * 0.42f, (rnd.nextFloat() - 0.5f) * R * 0.25f, sin(a) * R * 0.42f))
    }
    for (p in pts) mb.ellipsoid(p, V3(0f, R * 0.22f, 0f), V3(R * 0.27f, 0f, 0f), V3(0f, 0f, R * 0.24f), 6, 10)
    for (k in 0 until pts.size - 1) mb.rod(pts[k], pts[k + 1], R * 0.06f, 5)
    return mb.build()
}

/** Cytoplasmic granules: fine specks scattered through a cell of radius [R]. */
private fun granuleMesh(R: Float, count: Int, seed: Int): T3Mesh {
    val mb = Mb()
    val rnd = java.util.Random(seed.toLong())
    for (k in 0 until count) {
        val d = V3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).unit()
        mb.sphere(d * (R * (0.55f + 0.35f * rnd.nextFloat())), R * 0.045f, 3, 5)
    }
    return mb.build()
}

/** Cross-striated muscle: pale I-band cylinder, or the dark A-band rings (2.5 µm sarcomeres), between two points. */
private fun Mb.striatedFibre(p0: V3, p1: V3, r: Float, sarcomere: Float, aBandsOnly: Boolean) {
    if (!aBandsOnly) { cylinder(p0, p1, r, 18); return }
    val len = (p1 - p0).len(); val t = (p1 - p0).unit()
    var z = sarcomere * 0.3f
    while (z + sarcomere * 0.64f < len) {
        cylinder(p0 + t * z, p0 + t * (z + sarcomere * 0.64f), r * 1.012f, 18)
        z += sarcomere
    }
}

/**
 * A cell cut open on the side facing [n], as a section is: the rounded body with its cap sliced
 * off [cut]·r in front of the centre, and the flat cut face. One opaque surface; the nucleus and
 * granules are then laid on the face as flat inclusions ([inlay]), the way a slide shows them.
 * [flat] squashes the cell along [n] (a cell lying on a surface).
 */
private fun Mb.sectionedCell(c: V3, n: V3, r: Float, flat: Float = 1f, cut: Float = 0.35f, st: Int = 7, sl: Int = 14) {
    val a = n.unit(); val e1 = perp(a); val e2 = a.cross(e1).unit()
    val ph0 = acos(cut)
    grid(st, sl, { u, v ->
        val ph = ph0 + (PI.toFloat() - ph0) * u; val th = v * 2f * PI.toFloat()
        c + a * (r * flat * cos(ph)) + (e1 * cos(th) + e2 * sin(th)) * (r * sin(ph))
    }) { u, v, _ ->
        val ph = ph0 + (PI.toFloat() - ph0) * u; val th = v * 2f * PI.toFloat()
        a * (cos(ph) / flat) + (e1 * cos(th) + e2 * sin(th)) * sin(ph)
    }
    val fc = c + a * (r * flat * cut); val fr = r * sin(ph0)
    grid(1, sl, { u, v -> val th = v * 2f * PI.toFloat(); fc + (e1 * cos(th) + e2 * sin(th)) * (fr * u) }) { _, _, _ -> a }
}

/** Centre and radius of a sectioned cell's cut face. */
private fun faceOf(c: V3, n: V3, r: Float, flat: Float = 1f, cut: Float = 0.35f): Pair<V3, Float> =
    (c + n.unit() * (r * flat * cut)) to (r * sin(acos(cut)))

/** A flat inclusion lying on a cut face at [p] (normal [n]): an ellipse, semi-axes [ra] along [ax] and [rb] across. */
private fun Mb.inlay(p: V3, n: V3, ax: V3, ra: Float, rb: Float, lift: Float = 0.012f) {
    val a = n.unit(); val x = (ax - a * ax.dot(a)).unit(); val y = a.cross(x).unit()
    ellipsoid(p + a * lift, a * (lift * 0.9f), x * ra, y * rb, 4, 18)
}

/**
 * A nucleus in section on a cut face of radius [fr] centred at [fc]: round (0, a lymphocyte or blast),
 * segmented (1, 3-4 lobes joined by chromatin strands), band (2, a C), kidney (3, indented).
 */
private fun Mb.nucleusOnFace(fc: V3, n: V3, fr: Float, kind: Int, seed: Int, scale: Float = 1f) {
    val a = n.unit(); val e1 = perp(a); val e2 = a.cross(e1).unit()
    val rot = seed * 1.3f
    val x = e1 * cos(rot) + e2 * sin(rot); val y = a.cross(x).unit()
    when (kind) {
        0 -> inlay(fc + x * (fr * 0.08f), a, x, fr * 0.7f * scale, fr * 0.66f * scale)
        1 -> {
            val lobes = 3 + seed % 2
            val pts = (0 until lobes).map { k -> val t = -1.1f + 2.2f * k / (lobes - 1); fc + (x * cos(t) + y * sin(t)) * (fr * 0.45f) }
            for (p in pts) inlay(p, a, (p - fc), fr * 0.22f, fr * 0.26f)
            for (k in 0 until lobes - 1) { val m = lerpV(pts[k], pts[k + 1], 0.5f); inlay(m, a, pts[k + 1] - pts[k], fr * 0.2f, fr * 0.05f, 0.011f) }
        }
        2 -> for (k in 0 until 9) {
            val t = -1.3f + 2.6f * k / 8f
            val p = fc + (x * cos(t) + y * sin(t)) * (fr * 0.42f)
            inlay(p, a, x * (-sin(t)) + y * cos(t), fr * 0.16f, fr * 0.13f)
        }
        else -> {
            inlay(fc + x * (fr * 0.2f) + y * (fr * 0.2f), a, x + y, fr * 0.38f, fr * 0.3f)
            inlay(fc + x * (fr * 0.2f) - y * (fr * 0.2f), a, x - y, fr * 0.38f, fr * 0.3f)
        }
    }
}

/** Fine granules scattered over a cut face. */
private fun Mb.granulesOnFace(fc: V3, n: V3, fr: Float, count: Int, rg: Float, seed: Int) {
    val rnd = java.util.Random(seed.toLong())
    val a = n.unit(); val e1 = perp(a); val e2 = a.cross(e1).unit()
    for (k in 0 until count) {
        val t = rnd.nextFloat() * 2f * PI.toFloat(); val rr = fr * 0.9f * sqrt(rnd.nextFloat())
        inlay(fc + (e1 * cos(t) + e2 * sin(t)) * rr, a, e1, rg, rg, 0.02f)
    }
}

// ============================================================== the stops

/**
 * Stop 1 (THE CAVITY, 120 µm Mote: 1 unit = 80 µm). Round the craft, an alveolar duct: its wall is
 * a honeycomb of alveoli ~180 µm across whose neighbours share one septum, the septal rims knobbed
 * with smooth muscle at the corners, a dense capillary net in every alveolar wall, all breathing
 * together. Ahead the duct becomes a bronchiole (a conducting airway: smooth epithelium and bands of
 * smooth muscle, no alveoli), and in its wall to port, a tuberculous cavity ~2 mm across has eroded
 * through and drained into it. The lung round it is scarred grey with anthracotic pigment, the
 * nearest alveoli collapsed. Through the eroded mouth the crew look into the dark hollow: the neck
 * shows the cavity wall in section — white fibrous capsule, a granulomatous band of epithelioid
 * cells and Langhans giant cells with a lymphocyte cuff, then the pale caseous lining, which runs
 * off into the dark depth of the hollow. Crumbs of caseum drift out and up the airway (coughed
 * away). Two satellite tubercles (~170 µm) sit in the nearby alveolar wall; alveolar macrophages
 * with swallowed soot lie in the alveoli beside the scar.
 */
internal fun StereoBodyRenderer.drawCavity(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    val um = umu(i)
    if (!openWall(i, -8f, 34f, T3_PARENCHYMA, 0.45f)) return
    val g = cavityGeometry(b)
    // Every alveolus inflates together, ~13 breaths a minute.
    val breath = 1f + 0.03f * sin(seconds * 2f * PI.toFloat() / 4.6f)
    drawMeshRadial(cached("cav_cups") { honeycomb(b, g, 0) }, b, breath, T3_ALVEOLUS, T3_SEPTUM, 1f, 0f, 0.05f)
    drawMeshRadial(cached("cav_septa") { honeycomb(b, g, 1) }, b, breath, T3_SEPTUM, T3_SEPTUM, 1f, 0f, 0.15f)
    drawMeshRadial(cached("cav_caps") { honeycomb(b, g, 2) }, b, breath, T3_CAPILLARY_NET, T3_CAPILLARY_NET, 1f, 0f, 0f)
    drawMeshRadial(cached("cav_knobs") { honeycomb(b, g, 3) }, b, breath, T3_SMC, T3_SMC, 1f, 0f, 0.05f)
    drawMeshRadial(cached("cav_mac") { honeycomb(b, g, 6) }, b, breath, T3_MACRO_PALE, T3_MACRO_PALE, 1f, 0f, 0.1f)
    drawMeshRadial(cached("cav_macsoot") { honeycomb(b, g, 7) }, b, breath, T3_ANTHRACOTIC, T3_ANTHRACOTIC_RIM, 1f, 0f, 0f)
    // Next to the cavity the lung is scarred and collapsed: shrunken alveoli that no longer inflate.
    drawMesh(cached("cav_dead") { honeycomb(b, g, 4) }, T3_SCAR, T3_SCAR, 1f, 0f, 0.1f)
    drawMesh(cached("cav_deadsep") { honeycomb(b, g, 5) }, T3_SCAR_SEPTUM, T3_FIBROUS, 1f, 0f, 0.12f)
    // The bronchiole ahead, its folded mucosa narrowing into the eroded mouth of the cavity, the
    // wall round the mouth grey with scar and speckled with anthracotic pigment.
    drawMesh(cached("cav_bronch") { bronchiole(b, g, 0) }, T3_BRONCHIOLE, T3_FOLD, 1f, 0f, 0.1f)
    drawMesh(cached("cav_scar") { bronchiole(b, g, 1) }, T3_SCAR, T3_FIBROUS, 1f, 0f, 0.1f)
    drawMesh(cached("cav_soot") { bronchiole(b, g, 2) }, T3_ANTHRACOTIC, T3_ANTHRACOTIC_RIM, 1f, 0f, 0f)
    // The eroded mouth: its neck cut through the cavity wall, layer by layer.
    drawMesh(cached("cav_neck0") { cavityNeck(g, 0) }, T3_FIBROUS, T3_FIBROUS, 1f, 0f, 0.15f)
    drawMesh(cached("cav_neck1") { cavityNeck(g, 1) }, T3_GRANULOMA, T3_GRANULOMA, 1f, 0f, 0.12f)
    drawMesh(cached("cav_neck2") { cavityNeck(g, 2) }, T3_CASEUM, T3_CASEUM, 1f, 0f, 0.2f)
    drawMesh(cached("cav_epi") { cavityNeck(g, 3) }, T3_EPITHELIOID, T3_FIBROUS, 1f, 0f, 0.1f)
    drawMesh(cached("cav_giant") { cavityNeck(g, 4) }, T3_LANGHANS, T3_FIBROUS, 1f, 0f, 0.12f)
    drawMesh(cached("cav_gnuc") { cavityNeck(g, 5) }, T3_NUCLEUS, T3_NUCLEUS, 1f, 0f, 0.25f)
    drawMesh(cached("cav_lymph") { cavityNeck(g, 6) }, T3_LYMPHOCYTE, T3_LYMPHOCYTE, 1f, 0f, 0.3f)
    drawMesh(cached("cav_raglip") { cavityNeck(g, 8) }, T3_CASEUM, T3_CASEUM, 1f, 0f, 0.15f)
    // The hollow: pale caseous lining at the lip, running into darkness in the depth.
    // (far from the lamp it is self-lit just enough to read on the see-through display: a dim tan
    // wall converging, band by band, to a dark-brown floor, never to transparent black)
    for (k in 0 until 5) drawMesh(cached("cav_band$k") { cavityLining(g, k) }, CAV_BANDS[k], CAV_BANDS[k], 1f, 0f, CAV_GLOW[k])
    drawMesh(cached("cav_plaques") { cavityLining(g, 5) }, T3_CASEUM_LIP, T3_CASEUM_MID, 1f, 0f, 0.35f)
    // Satellite tubercles: a caseous core in a grey-white granuloma, sectioned where they meet the duct.
    drawMesh(cached("cav_tub") { cavityTubercles(b, 0) }, T3_GRANULOMA, T3_FIBROUS, 1f, 0f, 0.1f)
    drawMesh(cached("cav_tubcore") { cavityTubercles(b, 1) }, T3_CASEUM, T3_CASEUM, 1f, 0f, 0.2f)

    // Caseous crumbs: liquefied debris drifting out through the mouth and up the airway, coughed away.
    for (k in 0 until (if (quality == 0) 5 else 2)) {
        val t = ((seconds / 16f + k * 0.2f) % 1f)
        val r = min(0.2f, (14f + 6f * (k % 3)) / um)
        val p: V3 = if (t < 0.35f) {
            val q = smooth01(t / 0.35f)
            val start = g.centre + g.inward * (g.rc * 0.5f) + g.e1 * (1.5f * cos(k * 2.1f)) + g.e2 * (1.5f * sin(k * 2.1f))
            lerpV(start, g.mouth + g.e1 * (0.5f * cos(k * 1.3f)) + g.e2 * (0.5f * sin(k * 1.3f)), q)
        } else {
            // out of the mouth and back up the airway toward the craft (and the trachea beyond)
            val q = (t - 0.35f) / 0.65f
            val a = g.mouthAlong - q * 15f
            rfv(b, a).pol(k * 1.26f + 0.4f * sin(k * 1.7f + q * 3f), lerp(0.6f, 1.9f, smooth01(q * 2f)))
        }
        if ((p.x - camNowX).pow(2) + (p.y - camNowY).pow(2) + (p.z - camNowZ).pow(2) < 4f) continue
        drawSphereAt(p.x, p.y, p.z, r, r * 0.8f, r * 1.1f, T3_CASEUM, T3_CASEUM, 1f - smooth01((t - 0.85f) / 0.15f), k * 50f + seconds * 20f, 0.3f, 1f, 0.2f, blob, 0f, 0.2f)
    }
}

/** The cavity: its eroded mouth on the bronchiole's port wall ahead, neck and hollow beyond. */
private class CavityGeom(
    val mouth: V3, val inward: V3, val e1: V3, val e2: V3, val centre: V3, val neckEnd: V3,
    val rm: Float, val rc: Float, val neck: Float, val rimR: Float, val mouthAlong: Float, val mouthAngle: Float
)

/** The honeycomb duct runs up to here; beyond it the wall is bronchiole. */
private const val DUCT_END = 6.0f

private fun StereoBodyRenderer.cavityGeometry(b: Float): CavityGeom = cachedValue("cav_geom") {
    val um = umu(0)
    // The draining bronchiole ends straight ahead in the eroded mouth of the cavity: the pilot looks
    // down the airway, through the layered neck, into the dark hollow.
    val along = BRONCH_END
    val f = rfv(b, along)
    val rimR = radiusAt(b, along) - 1.45f
    val mouth = f.c
    val inward = -f.d
    val e1 = f.s; val e2 = f.u
    val rm = 150f / um          // eroded mouth ~300 µm across
    val rc = 2500f / um         // a cavity ~5 mm across
    val neck = 3.2f             // the cavity wall's thickness, ~0.25 mm
    val neckEnd = mouth - inward * neck
    val centre = neckEnd - inward * sqrt(rc * rc - rm * rm)
    CavityGeom(mouth, inward, e1, e2, centre, neckEnd, rm, rc, neck, rimR, along, 0f)
}

/** The bronchiole narrows from along 11 into the mouth at BRONCH_END. */
private const val BRONCH_END = 13f
private const val TAPER_START = 11f

/** Hex lattice of alveoli on the duct: (along, angle) of each cell centre. */
private fun StereoBodyRenderer.alveolusCells(b: Float): List<FloatArray> = cachedValue("cav_cells") {
    val count = 7
    val r0 = radiusAt(b, 0f) - 1.45f
    val w = 2f * PI.toFloat() * r0 / count
    val p = w * sqrt(3f) / 2f
    val out = ArrayList<FloatArray>()
    var row = 0
    var a = -8f
    val rnd = java.util.Random(71)
    while (a <= 26f) {
        for (k in 0 until count) {
            // past the duct the wall is bronchiolar, with alveoli opening from it here and there
            val keep = a <= DUCT_END || rnd.nextFloat() < 0.4f
            if (keep) out.add(floatArrayOf(a, (k + (if (row % 2 == 0) 0f else 0.5f)) * 2f * PI.toFloat() / count + 0.3f))
        }
        a += p; row++
    }
    out
}

/**
 * The alveolar duct wall: cups with hexagonal mouths (0), the shared septal sheet between them (1),
 * the capillary net in each alveolar wall (2), smooth-muscle knobs at the septal corners (3),
 * collapsed alveoli (4) and their septa (5) beside the cavity, alveolar macrophages (6) and the
 * soot they have swallowed (7).
 */
private fun StereoBodyRenderer.honeycomb(b: Float, g: CavityGeom, part: Int): T3Mesh {
    val mb = Mb()
    val rnd = java.util.Random(37L + part)
    val count = 7
    val R0 = radiusAt(b, 0f) - 1.45f
    val w = 2f * PI.toFloat() * R0 / count
    val half = w / 2f; val sept = 0.06f
    val tub = tubercleSpots(b)
    // Local cell coordinates (x round the duct, y along it, depth outward) mapped onto the duct wall.
    fun at(c: FloatArray, x: Float, y: Float, depth: Float): V3 { val a = c[0] + y; return rfv(b, a).pol(c[1] + x / R0, radiusAt(b, a) - 1.45f + depth) }
    fun hexR(t: Float, rin: Float): Float { val tm = ((t / DEG + 30f) % 60f + 60f) % 60f - 30f; return rin / cos(tm * DEG) }
    val macCells = HashSet<Int>()
    for ((idx, c) in alveolusCells(b).withIndex()) {
        val centre = at(c, 0f, 0f, 0f)
        if (tub.any { (centre - it).len() < 1.3f }) continue
        val dMouth = (centre - g.mouth).len()
        if (c[0] > TAPER_START - half) continue                                  // the taper into the mouth
        val collapsed = dMouth < g.rm + 4.5f
        if (part == 4 || part == 5) { if (!collapsed) continue } else if (collapsed && part != 6 && part != 7) continue
        val depthK = if (collapsed) 0.25f else 1f
        val rin = if (collapsed) (half - sept) * 0.6f else half - sept
        val D = rin * 1.1f * depthK * (if (collapsed) 1.6f else 1f)
        // collapsed alveoli: shrunken, puckered, their rims irregular
        val jit = FloatArray(7) { k -> if (collapsed) 1f + 0.25f * sin(idx * 3.7f + (k % 6) * 2.1f) else 1f }
        fun rimJ(t: Float): Float { val x = ((t / (PI.toFloat() / 3f)) % 6f + 6f) % 6f; val k = x.toInt(); val fr = x - k; return jit[k] * (1f - fr) + jit[k + 1] * fr }
        fun bowl(ph: Float, t: Float): V3 { val rr = hexR(t, rin) * rimJ(t) * sin(ph); return at(c, rr * cos(t), rr * sin(t), D * cos(ph)) }
        when (part) {
            0, 4 -> mb.gridFd(6, 24, { u, v -> bowl((1f - u) * PI.toFloat() / 2f, v * 2f * PI.toFloat()) }) { _, _, p -> at(c, 0f, 0f, D * 0.3f) - p + (centre - at(c, 0f, 0f, 1f)) }
            1 -> mb.gridFd(1, 24, { u, v ->
                val t = v * 2f * PI.toFloat(); val rr = hexR(t, rin + (half + 0.08f - rin) * u)
                at(c, rr * cos(t), rr * sin(t), -0.012f)
            }) { _, _, _ -> centre - at(c, 0f, 0f, 1f) }
            5 -> mb.gridFd(2, 24, { u, v ->
                // the collapsed cup's thick fibrous rim, then scar filling out to the old hexagon
                // (its inner edge exactly the cup's own rim, overlapping it slightly, so no gap opens)
                val t = v * 2f * PI.toFloat(); val r0 = hexR(t, rin) * rimJ(t) * 0.98f
                val rr = r0 + (hexR(t, half + 0.08f) - r0) * u
                at(c, rr * cos(t), rr * sin(t), -0.012f * u)
            }) { _, _, _ -> centre - at(c, 0f, 0f, 1f) }
            2 -> {
                // a dense capillary net over the alveolar wall, half embedded in it (sheet flow)
                val lats = 10; val mers = 20
                val P = Array(lats) { li -> Array(mers) { mi ->
                    val ph = (10f + (82f - 10f) * li / (lats - 1)) * DEG + (rnd.nextFloat() - 0.5f) * 0.08f
                    val t = mi * 2f * PI.toFloat() / mers + (rnd.nextFloat() - 0.5f) * 0.1f
                    bowl(ph, t)
                } }
                for (li in 0 until lats) for (mi in 0 until mers) {
                    if (rnd.nextFloat() < 0.85f) mb.tube(listOf(P[li][mi], P[li][(mi + 1) % mers]), floatArrayOf(0.04f, 0.04f), 3, false)
                    if (li + 1 < lats && rnd.nextFloat() < 0.85f) mb.tube(listOf(P[li][mi], P[li + 1][mi]), floatArrayOf(0.04f, 0.04f), 3, false)
                }
            }
            3 -> for (t in floatArrayOf(30f, 90f)) {
                val p = at(c, hexR(t * DEG, half) * cos(t * DEG), hexR(t * DEG, half) * sin(t * DEG), -0.05f)
                val f = rfv(b, c[0]); val rad = f.radial(c[1])
                mb.ellipsoid(p, rad * 0.1f, f.d * 0.1f, rad.cross(f.d).unit() * 0.14f, 4, 8)
            }
            6, 7 -> {
                // two dust-laden macrophages in each of a few alveoli on the scarred side
                if (!(dMouth < g.rm + 8f && dMouth > g.rm + 4.5f) || macCells.size >= 4 && idx !in macCells) continue
                macCells.add(idx)
                for (m in 0 until 2) {
                    val p = bowl(0.5f + 0.35f * m, 1.2f + 2.5f * m) + (at(c, 0f, 0f, D * 0.3f) - bowl(0.5f + 0.35f * m, 1.2f + 2.5f * m)).unit() * 0.2f
                    val f = rfv(b, c[0]); val rad = f.radial(c[1])
                    if (part == 6) mb.ellipsoid(p, rad * 0.22f, f.d * 0.3f, rad.cross(f.d).unit() * 0.22f, 5, 9)
                    else for (q in 0 until 5) mb.sphere(p - rad * 0.2f + f.d * ((rnd.nextFloat() - 0.5f) * 0.36f) + rad.cross(f.d).unit() * ((rnd.nextFloat() - 0.5f) * 0.25f), 0.045f, 3, 5)
                }
            }
        }
    }
    return mb.build(twoSided = part == 0 || part == 1 || part == 4 || part == 5)
}

/**
 * The bronchiole ahead: its folded mucosa (0), narrowing into the eroded mouth of the cavity at
 * BRONCH_END; the grey scar round the mouth (1), speckled with anthracotic pigment (2).
 */
private fun StereoBodyRenderer.bronchiole(b: Float, g: CavityGeom, part: Int): T3Mesh {
    val mb = Mb()
    val R0 = radiusAt(b, 0f) - 1.45f
    val half = PI.toFloat() * R0 / 7f
    val a0 = alveolusCells(b).filter { it[0] <= DUCT_END }.maxOf { it[0] } + half; val a1 = BRONCH_END
    val scarFrom = BRONCH_END - 1.8f
    val cells = alveolusCells(b).filter { it[0] > a0 - 2f && it[0] < TAPER_START - half }
    fun collapsedCell(c: FloatArray) = (rfv(b, c[0]).pol(c[1], R0) - g.mouth).len() < g.rm + 4.5f
    fun inAlveolus(a: Float, th: Float): Boolean = cells.any { c ->
        val y = a - c[0]
        if (abs(y) > 1.6f) false else {
            val x = atan2(sin(th - c[1]), cos(th - c[1])) * R0
            val d = sqrt(x * x + y * y); val t = atan2(y, x)
            val tm = ((t / DEG + 30f) % 60f + 60f) % 60f - 30f
            d * cos(tm * DEG) < half * 0.9f
        }
    }
    // The airway wall: low longitudinal mucosal folds every 45°, the lumen narrowing smoothly into
    // the mouth over the last two units.
    fun baseR(a: Float): Float { val t = smooth01((a - TAPER_START) / (BRONCH_END - TAPER_START)); return (radiusAt(b, a) - 1.45f) * (1f - t) + g.rm * 1.2f * t }
    fun wall(a: Float, th: Float, lift: Float = 0f): V3 {
        val fold = 0.10f * max(0f, cos(8f * (th - 0.2f))).pow(3) * (1f - smooth01((a - TAPER_START) / 1.5f))
        val wr = if (a > scarFrom) 0.05f * sin(a * 5.3f + th * 7.1f) * sin(a * 2.1f - th * 4.3f) else 0f
        return rfv(b, a).pol(th, baseR(a) - fold + wr - lift)
    }
    when (part) {
        0, 1 -> {
            val na = ((a1 - a0) / 0.15f).toInt(); val nt = 120
            val P = Array(na + 1) { i -> Array(nt + 1) { j -> wall(a0 + (a1 - a0) * i / na, j * 2f * PI.toFloat() / nt) } }
            val C = Array(na + 1) { i -> rfv(b, a0 + (a1 - a0) * i / na).c }
            for (i in 0 until na) for (j in 0 until nt) {
                val am = a0 + (a1 - a0) * (i + 0.5f) / na
                if ((am > scarFrom) != (part == 1)) continue
                if (inAlveolus(am, (j + 0.5f) * 2f * PI.toFloat() / nt)) continue
                val q = arrayOf(P[i][j], P[i + 1][j], P[i + 1][j + 1], P[i][j + 1])
                val n0 = C[i] - P[i][j]; val n1 = C[i + 1] - P[i + 1][j]
                mb.tri(q[0], n0, q[1], n1, q[2], n1); mb.tri(q[0], n0, q[2], n1, q[3], n0)
            }
        }
        else -> {
            // anthracotic pigment: speckled patches in the scar, never filaments
            val rnd = java.util.Random(19)
            for (k in 0 until 6) {
                val pa = scarFrom + 0.3f + rnd.nextFloat() * 1.2f; val pth = k * 1.05f + rnd.nextFloat() * 0.5f
                val n = 12 + rnd.nextInt(9)
                for (q in 0 until n) {
                    val rr = 0.6f * sqrt(rnd.nextFloat()); val t = rnd.nextFloat() * 2f * PI.toFloat()
                    val a = (pa + cos(t) * rr).coerceAtMost(BRONCH_END - 0.1f); val th = pth + sin(t) * rr / baseR(a)
                    val f = rfv(b, a); val rad = f.radial(th)
                    mb.ellipsoid(wall(a, th, 0.012f), rad * 0.02f, f.d * 0.06f, rad.cross(f.d).unit() * 0.06f, 4, 8)
                }
            }
        }
    }
    return mb.build(twoSided = part <= 1)
}

private fun StereoBodyRenderer.tubercleSpots(b: Float): List<V3> = listOf(
    rfv(b, -1.9f).pol(-40f * DEG, radiusAt(b, -1.9f) - 1.45f + 0.3f),
    rfv(b, 2.0f).pol(250f * DEG, radiusAt(b, 2f) - 1.45f + 0.3f),
)

/** Satellite tubercles, sectioned where they bulge from the duct wall: granuloma (0), caseous core on the cut face (1). */
private fun StereoBodyRenderer.cavityTubercles(b: Float, part: Int): T3Mesh {
    val mb = Mb()
    for ((k, p) in tubercleSpots(b).withIndex()) {
        val f = rfv(b, if (k == 0) -1.9f else 2f)
        val toAxis = (f.c - p).unit()
        val r = 1.05f
        if (part == 0) mb.sectionedCell(p, toAxis, r, 1f, 0.45f, 9, 18)
        else {
            val (fc, fr) = faceOf(p, toAxis, r, 1f, 0.45f)
            mb.inlay(fc, toAxis, f.d, fr * 0.5f, fr * 0.46f, 0.015f)
        }
    }
    return mb.build()
}

/**
 * The neck of the eroded mouth, cut through the cavity wall: seen from the airway, first the white
 * fibrous capsule (0), then the granulomatous band (1), then the caseous lining (2); in the band,
 * epithelioid cells (3), Langhans giant cells (4) with their horseshoe of nuclei (5), and a cuff of
 * lymphocytes (6, ~7 µm) at its outer edge; a rim of scar round the mouth (7).
 */
private fun cavityNeck(g: CavityGeom, part: Int): T3Mesh {
    val mb = Mb()
    val out = -g.inward
    val bands = floatArrayOf(0f, 1.2f, 2.4f, g.neck)
    // the tunnel flares into a funnel over its first 0.4 units, eroded into the airway wall
    fun rAt(d: Float) = g.rm * (1f + 0.2f * max(0f, 1f - d / 0.4f))
    fun at(depth: Float, th: Float, inset: Float = 0f) = g.mouth + out * depth + (g.e1 * cos(th) + g.e2 * sin(th)) * (rAt(depth) - inset)
    fun toAxis(th: Float) = -(g.e1 * cos(th) + g.e2 * sin(th))
    when (part) {
        0, 1, 2 -> mb.grid(6, 36, { u, v ->
            val d = bands[part] + (bands[part + 1] - bands[part]) * u
            val th = v * 2f * PI.toFloat()
            at(d, th, -0.04f * g.rm * sin(th * 5f + d * 3f))
        }) { _, v, _ -> toAxis(v * 2f * PI.toFloat()) }
        3 -> for (k in 0 until 60) {
            // epithelioid cells packed through the granulomatous band
            val th = k * 2f * PI.toFloat() / 20f + (k / 20) * 0.15f
            if (angDiff(th, 0.9f) < 0.45f || angDiff(th, 4.0f) < 0.45f) continue
            val d = 1.55f + 0.32f * (k / 20)
            val tg = g.e2 * cos(th) - g.e1 * sin(th)
            mb.ellipsoid(at(d, th, 0.03f), toAxis(th) * 0.05f, tg * 0.18f, out * 0.11f, 5, 10)
        }
        4 -> for (th in floatArrayOf(0.9f, 4.0f)) {
            val tg = g.e2 * cos(th) - g.e1 * sin(th)
            mb.ellipsoid(at(1.85f, th, 0.05f), toAxis(th) * 0.08f, tg * 0.45f, out * 0.24f, 8, 16)
        }
        5 -> for (th in floatArrayOf(0.9f, 4.0f)) {
            // nuclei ranged round the cell margin in a horseshoe
            val tg = g.e2 * cos(th) - g.e1 * sin(th)
            val cc = at(1.85f, th, 0.13f)
            for (k in 0 until 11) {
                val a = (-0.75f + 1.5f * k / 10f) * PI.toFloat()
                mb.ellipsoid(cc + tg * (0.34f * cos(a)) + out * (0.17f * sin(a)), toAxis(th) * 0.02f, tg * 0.05f, out * 0.035f, 4, 8)
            }
        }
        6 -> for (k in 0 until 120) {
            // the lymphocyte cuff at the granuloma's outer edge
            val th = k * 2f * PI.toFloat() / 60f + (k / 60) * 0.05f
            mb.sphere(at(1.15f + 0.15f * (k / 60) + 0.04f * sin(k * 1.9f), th, 0.03f), 0.045f, 4, 7)
        }
        else -> for (k in 0 until 18) {
            // the ragged caseous lip where the neck opens into the hollow
            val th = k * 2f * PI.toFloat() / 18f + 0.1f * sin(k * 2.7f)
            val tg = g.e2 * cos(th) - g.e1 * sin(th)
            mb.ellipsoid(at(g.neck - 0.05f, th, 0.08f), out * (0.15f + 0.05f * sin(k * 1.3f)), tg * 0.25f, toAxis(th) * 0.15f, 5, 10)
        }
    }
    return mb.build(twoSided = part <= 2)
}

/** The cavity's lining seen from inside: lip band (0), middle (1), depth (2), and caseous plaques in the middle band (3). */
private fun cavityLining(g: CavityGeom, part: Int): T3Mesh {
    val mb = Mb()
    val c = g.centre; val ax = g.inward
    val hole = asin(g.rm / g.rc)
    if (part == 5) {
        // soft caseous plaques on the wall, in the part of the hollow seen through the mouth
        val rnd = java.util.Random(31)
        for (k in 0 until 16) {
            val ph = (150f + 27f * rnd.nextFloat()) * DEG; val th = rnd.nextFloat() * 2f * PI.toFloat()
            val dir = ax * cos(ph) + (g.e1 * cos(th) + g.e2 * sin(th)) * sin(ph)
            val t1 = perp(dir); val t2 = dir.cross(t1).unit()
            val s = 1.0f + 0.9f * rnd.nextFloat()
            mb.ellipsoid(c + dir * (g.rc - 0.1f), dir * (0.3f + 0.2f * rnd.nextFloat()), t1 * s, t2 * (s * 0.8f), 5, 9)
        }
        return mb.build()
    }
    val edges = floatArrayOf(hole / DEG, 100f, 140f, 160f, 172f, 180f)
    val p0 = edges[part] * DEG; val p1 = edges[part + 1] * DEG
    mb.grid(10, 36, { u, v ->
        val ph = p0 + (p1 - p0) * u; val th = v * 2f * PI.toFloat()
        c + ax * (g.rc * cos(ph)) + (g.e1 * cos(th) + g.e2 * sin(th)) * (g.rc * sin(ph))
    }) { _, _, p -> c - p }
    return mb.build()
}

/**
 * Stop 2 (THE VEIN, 1.2 mm Mote: 1 unit = 0.8 mm): inside the donor's median cubital vein, ~5 mm
 * across, its smooth pale intima (the endothelium) sliding past. An 18-gauge steel needle (1.3 mm
 * across, 0.8 mm bore) has come through the wall from the skin side at ~18°, pointing downstream
 * toward the heart with its bevel up (facing the skin side); its polished bevel exposes the bore.
 * Streamlines show the blood running with the craft, some turning into the bevel. Downstream a
 * bicuspid venous valve stands open: two cusps hinged on the wall along U-shaped attachments, free
 * edges pointing downstream, sinuses behind them, fluttering with the flow.
 */
internal fun StereoBodyRenderer.drawDonor(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    val um = umu(i)
    if (!openWall(i, -8f, 34f, T3_INTIMA)) return
    val nd = needleFrame(b, um)
    // The vein's own wall: smooth grey intima (distended, no folds), bulging out into a sinus
    // behind each valve cusp.
    drawMesh(cached("dn_wall") { veinWall(b, 0) }, T3_INTIMA, T3_INTIMA_RIM, 1f, 0f, 0.1f)
    // The needle, 18 G, seen side-on: the steel shaft running up through the wall, the long bevel
    // with its open bore full of blood, the polished edge and lancet point.
    drawMesh(cached("dn_outer2") { needleMesh(nd, 0) }, T3_STEEL, T3_STEEL_EDGE, 1f, 0f, 0.1f)
    drawMesh(cached("dn_bore2") { needleMesh(nd, 1) }, T3_BORE, T3_BORE, 1f, 0f, 0f)
    drawMesh(cached("dn_window") { needleMesh(nd, 6) }, T3_STEEL_EDGE, T3_STEEL_EDGE, 1f, 0f, 0.4f)
    drawMesh(cached("dn_bevel") { needleMesh(nd, 2) }, T3_STEEL_EDGE, T3_STEEL_EDGE, 1f, 0f, 0.5f)
    drawMesh(cached("dn_column") { needleMesh(nd, 4) }, T3_BLOOD_IN, T3_BLOOD_IN, 1f, 0f, 0.2f)
    drawMesh(cached("dn_opening") { needleMesh(nd, 5) }, T3_BLOOD_IN, T3_BLOOD_IN, 1f, 0f, 0.1f)
    // the puncture: the wall in section round the shaft, tented in where it is pushed
    drawMesh(cached("dn_ring0") { punctureRing(b, nd, 0) }, T3_INTIMA, T3_INTIMA, 1f, 0f, 0.1f)
    drawMesh(cached("dn_ring1") { punctureRing(b, nd, 1) }, T3_MEDIA, T3_MEDIA, 1f, 0f, 0.1f)
    drawMesh(cached("dn_ring2") { punctureRing(b, nd, 2) }, T3_ADVENTITIA, T3_ADVENTITIA, 1f, 0f, 0.1f)
    // The valve: cusps flutter a little with each surge of flow, never closing while blood runs forward.
    val flutter = 1f + 0.035f * sin(seconds * 2f * PI.toFloat() / 1.6f)
    drawMeshRadial(cached("dn_cusps") { valveMesh(b, 0) }, b + VALVE_A / NODE_UNITS, flutter, T3_VALVE, T3_SEPTUM, 0.92f, 0f, 0.12f)
    drawMeshRadial(cached("dn_edges") { valveMesh(b, 1) }, b + VALVE_A / NODE_UNITS, flutter, T3_SEPTUM, T3_SEPTUM, 1f, 0f, 0.35f)
    // The agger: the thickened attachment of each cusp to the wall, and the two commissural ridges.
    drawMesh(cached("dn_agger") { valveMesh(b, 2) }, T3_AGGER, T3_AGGER, 1f, 0f, 0.15f)
    // The vein is full of dark venous blood (at 0.8 mm per unit a continuous fluid, not cells).
    // (a core of blood, leaving the grey intima clear next to the wall)
    fillLumen("dn_blood3", b, -8f, 34f, 0.55f, T3_VENOUS, 0.15f, 0.1f)
    // Blood flow as streamlines: with the craft, through the valve, and into the bevel.
    linesBegin(3f)
    // Parabolic profile: fast, long dashes on the axis, short slow ones near the wall.
    val lines = if (quality == 0) 20 else 12
    val Rw = radiusAt(b, 0f) * VEIN_WALL
    for (k in 0 until lines) {
        val th = k * 2.399f
        val rho = Rw * 0.92f * sqrt(((k * 37 % 100) + 5) / 105f)
        val prof = 1f - (rho / Rw).pow(2)
        val v = 0.6f * prof + 0.05f
        val span = 26f
        val a = -6f + ((seconds * v * 2.5f + k * 4.13f) % span)
        val squeeze = if (a > VALVE_A - 2.5f && a < VALVE_A + 1.5f) 0.75f + 0.25f * abs(a - VALVE_A) / 2.5f else 1f   // narrowing through the valve
        val f0 = rfv(b, a)
        val p0 = f0.pol(th, rho * squeeze); val p1 = p0 + f0.d * (0.4f + 1.0f * prof)
        val fade = smooth01((a + 6f) / 2f) * (1f - smooth01((a - 17f) / 3f))
        line(p0, p1, mixCol(T3_FLOW_WALL, T3_FLOW_BRIGHT, prof, tmpCol3), 0.85f * fade)
    }
    for (k in 0 until (if (quality == 0) 6 else 3)) {
        // drawn into the needle: from beside the shaft, curving into the bevel's opening
        val t = ((seconds * 0.35f + k / 6f) % 1f)
        val side = if (k % 2 == 0) 1f else -1f
        val start = nd.o + nd.x * (side * 1.2f) - nd.y * 0.8f - nd.z * 4.5f
        val ctrl = nd.o + nd.x * (side * 0.8f) - nd.y * 0.2f - nd.z * 2.0f
        val end = nd.o - nd.z * (nd.bevelLen * 0.5f)
        val p0 = bez(start, ctrl, end, t); val p1 = bez(start, ctrl, end, (t + 0.08f).coerceAtMost(1f))
        line(p0, p1, T3_FLOW_BRIGHT, 0.9f * (1f - t * 0.5f))
    }
    // In each valve sinus the flow curls back on itself: a vortex that washes the pocket and keeps
    // the cusp ready to close (downstream along the cusp's face, back upstream along the wall).
    for (cusp in 0 until 2) for (l in 0 until 3) {
        val th = (if (cusp == 0) 90f else 270f) * DEG + (l - 1) * 0.5f
        val f = rfv(b, VALVE_A - 0.8f); val R = radiusAt(b, VALVE_A - 0.8f) * VEIN_WALL
        val centre = f.pol(th, R - 0.3f); val rad = f.radial(th)
        for (q in 0 until 4) {
            val ang = seconds * 0.5f * 2f * PI.toFloat() + q * PI.toFloat() / 2f + l
            fun pt(a2: Float) = centre + f.d * (0.35f * cos(a2)) + rad * (0.35f * sin(a2))
            line(pt(ang), pt(ang + 0.5f), T3_FLOW_BRIGHT, 0.6f)
        }
    }
    linesEnd()
}

private fun bez(a: V3, c: V3, e: V3, t: Float): V3 { val s = 1f - t; return a * (s * s) + c * (2f * s * t) + e * (t * t) }

private fun putLine(arr: FloatArray, v0: Int, p0: V3, p1: V3, col: FloatArray, alpha: Float): Int {
    var v = v0
    if (v + 14 > arr.size) return v
    for (p in arrayOf(p0, p1)) { arr[v++] = p.x; arr[v++] = p.y; arr[v++] = p.z; arr[v++] = col[0]; arr[v++] = col[1]; arr[v++] = col[2]; arr[v++] = alpha }
    return v
}

private fun StereoBodyRenderer.drawDyn(floats: Int, width: Float) {
    if (floats <= 0) return
    GLES20.glDepthMask(false)
    Matrix.setIdentityM(model, 0)
    Matrix.multiplyMM(mv, 0, view, 0, model, 0)
    Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    colorShader.use(mvp, 1f)
    lineWidth(width)
    dynLines.draw(colorShader.positionHandle, colorShader.colorHandle, GLES20.GL_LINES, floats / 7)
    lineWidth(1f)
    GLES20.glDepthMask(true)
}

/** Per-frame line segments, flushed in batches as the small dynamic buffer fills. */
private var lbV = 0
private var lbW = 2f
private fun StereoBodyRenderer.linesBegin(width: Float) { lbV = 0; lbW = width }
private fun StereoBodyRenderer.line(p0: V3, p1: V3, col: FloatArray, alpha: Float) {
    if (lbV + 14 > dynLines.data.size) { drawDyn(lbV, lbW); lbV = 0 }
    lbV = putLine(dynLines.data, lbV, p0, p1, col, alpha)
}
private fun StereoBodyRenderer.linesEnd() { drawDyn(lbV, lbW); lbV = 0 }

/**
 * The needle's own frame: origin on its axis at the tip, z forward along the shaft, y toward the
 * bevel (skin side); [win] is the angle (in x/y) of the cutaway window, facing the passage axis;
 * [zWall] where the shaft passes through the vein wall.
 */
private class NeedleFrame(val o: V3, val x: V3, val y: V3, val z: V3, val ro: Float, val ri: Float, val bevelLen: Float, val win: Float, val zWall: Float)

private fun StereoBodyRenderer.needleFrame(b: Float, um: Float): NeedleFrame = cachedValue("needle") {
    val ro = 1270f / 2f / um; val ri = 840f / 2f / um          // 18 G
    // In through the starboard-upper wall (35° above starboard) at 25° to the vein, pointing
    // downstream, the tip at along 9: seen from the craft at ~55°, the bevel shows in profile.
    val tipA = 9f
    val f = rfv(b, tipA)
    val nEntry = f.radial(35f * DEG)
    val ang = 15f * DEG
    val z = (f.d * cos(ang) - nEntry * sin(ang)).unit()
    // Bevel up: facing the skin side the needle came in from.
    val y = (nEntry - z * nEntry.dot(z)).unit()
    val x = y.cross(z).unit()
    val o = f.pol(35f * DEG, 0.35f)                                // the whole bevel inside the lumen
    var zw = 0f
    while (zw > -12f) {
        val p = o + z * zw; val a = tipA + (p - o).dot(f.d); val fr = rfv(b, a)
        val rad = p - fr.c; val r = (rad - fr.d * rad.dot(fr.d)).len()
        if (r > radiusAt(b, a) * VEIN_WALL) break
        zw -= 0.05f
    }
    val toAxis = f.c - o
    NeedleFrame(o, x, y, z, ro, ri, 2f * ro / tan(18f * DEG), atan2(toAxis.dot(y), toAxis.dot(x)).let { if (toAxis.len() < 0.5f) atan2(-1f, 0f) else it }, zw)
}

private const val VEIN_WALL = 0.95f

/**
 * Needle parts: outer shaft (0) and bore (1), both cut away over a window facing the crew; the
 * polished bevel face and cutting edge (2); the vein wall dimpled round the entry (3); the column
 * of blood in the bore (4); the open bore in the bevel plane (5); the bright cut edges of the
 * window (6).
 */
private fun needleMesh(nd: NeedleFrame, part: Int): T3Mesh {
    val mb = Mb()
    val back = -30f                                                    // the shaft runs on out through wall and skin
    // The bevel plane: the point at the bottom (y = -ro, z = 0), rising back to the heel at the top.
    fun zCut(y: Float) = -(y + nd.ro) / (2f * nd.ro) * nd.bevelLen
    fun w(lx: Float, ly: Float, lz: Float) = nd.o + nd.x * lx + nd.y * ly + nd.z * lz
    when (part) {
        0, 1 -> {
            // the steel tube (0) and its bore (1), with a cutaway window on the side facing the axis
            val r = if (part == 0) nd.ro else nd.ri
            val sl = 36
            val zs = ArrayList<Float>(); var z = back; while (z < -8f) { zs.add(z); z += 1f }; while (z < 0.001f) { zs.add(z); z += 0.2f }
            fun pt(zz: Float, j: Int): V3 { val th = j * 2f * PI.toFloat() / sl; val ly = r * sin(th); return w(r * cos(th), ly, min(zz, zCut(ly))) }
            fun nr(j: Int): V3 { val th = j * 2f * PI.toFloat() / sl; val rad = nd.x * cos(th) + nd.y * sin(th); return if (part == 0) rad else -rad }
            for (i in 0 until zs.size - 1) for (j in 0 until sl) {
                val th = (j + 0.5f) * 2f * PI.toFloat() / sl; val zm = (zs[i] + zs[i + 1]) / 2f
                if (angDiff(th, nd.win) < 55f * DEG && zm < -5.4f && zm > -7.0f) continue
                mb.tri(pt(zs[i], j), nr(j), pt(zs[i + 1], j), nr(j), pt(zs[i + 1], j + 1), nr(j + 1))
                mb.tri(pt(zs[i], j), nr(j), pt(zs[i + 1], j + 1), nr(j + 1), pt(zs[i], j + 1), nr(j + 1))
            }
        }
        6 -> {
            // the bright cut edge of the window
            val a0 = nd.win - 55f * DEG; val a1 = nd.win + 55f * DEG
            val loop = ArrayList<V3>()
            // (behind the bevel's heel, so the bevel reads cleanly as one bright ellipse ending in a point)
            for (k in 0..10) { val th = a0 + (a1 - a0) * k / 10f; loop.add(w(nd.ro * cos(th), nd.ro * sin(th), -5.4f)) }
            for (k in 0..6) loop.add(w(nd.ro * cos(a1), nd.ro * sin(a1), -5.4f - 1.6f * k / 6f))
            for (k in 0..10) { val th = a1 - (a1 - a0) * k / 10f; loop.add(w(nd.ro * cos(th), nd.ro * sin(th), -7.0f)) }
            for (k in 0..6) loop.add(w(nd.ro * cos(a0), nd.ro * sin(a0), -7.0f + 1.6f * k / 6f))
            mb.tube(loop, FloatArray(loop.size) { 0.03f }, 5, false)
        }
        2 -> {
            val nb = (nd.y * nd.bevelLen + nd.z * (2f * nd.ro)).unit()
            mb.grid(2, 40, { u, v ->
                val th = v * 2f * PI.toFloat(); val r = nd.ri + (nd.ro - nd.ri) * u
                val ly = r * sin(th); w(r * cos(th), ly, zCut(ly))
            }) { _, _, _ -> nb }
            // the polished cutting edge round the bevel, and the bright lancet point
            mb.tube((0..48).map { k -> val th = k * 2f * PI.toFloat() / 48f; val ly = nd.ro * sin(th); w(nd.ro * cos(th), ly, zCut(ly)) }, FloatArray(49) { 0.05f }, 6, false)
            mb.tube((0..6).map { k -> val th = -PI.toFloat() / 2f + (k - 3) * 0.12f; val ly = nd.ro * sin(th); w(nd.ro * cos(th), ly, zCut(ly)) }, FloatArray(7) { 0.08f }, 6, true)
        }
        3 -> {}
        4 -> {
            // blood filling the bore, back from the bevel
            val r = nd.ri * 0.96f
            mb.grid(20, 20, { u, v ->
                val th = v * 2f * PI.toFloat(); val ly = r * sin(th)
                w(r * cos(th), ly, -12f + (zCut(ly) - 0.02f + 12f) * u)
            }) { _, v, _ -> val th = v * 2f * PI.toFloat(); nd.x * cos(th) + nd.y * sin(th) }
        }
        else -> {
            // the open end of the bore in the bevel plane: a dark oval inside the bright steel rim
            val nb = (nd.y * nd.bevelLen + nd.z * (2f * nd.ro)).unit()
            mb.grid(1, 40, { u, v ->
                val th = v * 2f * PI.toFloat(); val r = nd.ri * u
                val ly = r * sin(th); w(r * cos(th), ly, zCut(ly) - 0.01f)
            }) { _, _, _ -> nb }
        }
    }
    return mb.build(twoSided = part <= 2 || part == 4 || part == 5)
}

/**
 * The puncture in the vein wall, lying in the wall surface round the shaft's elliptical footprint:
 * intima (0), media (1), adventitia (2) in section, each a ring.
 */
private fun StereoBodyRenderer.punctureRing(b: Float, nd: NeedleFrame, ring: Int): T3Mesh {
    val mb = Mb()
    val P = nd.o + nd.z * nd.zWall
    val f0 = rfv(b, 0f)
    val aP = (P - f0.c).dot(f0.d)
    val fP = rfv(b, aP); val relP = P - fP.c
    val thP = atan2(relP.dot(fP.u), relP.dot(fP.s))
    val n = fP.radial(thP)
    val along = (nd.z - n * nd.z.dot(n)).unit(); val across = n.cross(along).unit()
    val sinA = sin(15f * DEG)
    val w0 = floatArrayOf(0f, 0.05f, 0.17f)[ring]; val w1 = floatArrayOf(0.05f, 0.17f, 0.25f)[ring]
    mb.grid(1, 40, { u, v ->
        val t = v * 2f * PI.toFloat(); val wd = w0 + (w1 - w0) * u
        val q = P + across * ((nd.ro + wd) * cos(t)) + along * ((nd.ro / sinA + wd) * sin(t))
        val a = (q - f0.c).dot(f0.d); val fq = rfv(b, a); val rel = q - fq.c
        val th = atan2(rel.dot(fq.u), rel.dot(fq.s))
        fq.pol(th, veinR(b, nd, a, th) - 0.01f)
    }) { _, _, _ -> -n }
    return mb.build(twoSided = true)
}

/** The vein's inner radius: a sinus bulge behind each valve cusp, and the wall tented in round the needle. */
private fun StereoBodyRenderer.veinR(b: Float, nd: NeedleFrame, a: Float, th: Float): Float {
    var bulge = 0f
    for (thc in floatArrayOf(90f * DEG, 270f * DEG)) {
        val da = angDiff(th, thc)
        if (da < 80f * DEG && a > VALVE_A - 2.6f && a < VALVE_A + 0.9f) bulge = max(bulge, 0.45f * cos(da / (80f * DEG) * PI.toFloat() / 2f) * sin((a - VALVE_A + 2.6f) / 3.5f * PI.toFloat()))
    }
    val R = radiusAt(b, a) * VEIN_WALL
    val P = nd.o + nd.z * nd.zWall
    val q = rfv(b, a).pol(th, R)
    val d = (q - P).len()
    val dent = if (d < 0.8f) 0.15f * (1f - d / 0.8f) else 0f
    return R + bulge - dent
}

/** The vein wall (0) with a sinus bulging out behind each valve cusp, and its intimal folds (1). */
private fun StereoBodyRenderer.veinWall(b: Float, part: Int): T3Mesh {
    val mb = Mb()
    val a0 = -8f; val a1 = min(34f, (nodes.lastIndex - b) * NODE_UNITS)
    fun r(a: Float, th: Float): Float {
        var bulge = 0f
        for (thc in floatArrayOf(90f * DEG, 270f * DEG)) {
            val da = angDiff(th, thc)
            if (da < 80f * DEG && a > VALVE_A - 2.6f && a < VALVE_A + 0.9f) bulge = max(bulge, 0.45f * cos(da / (80f * DEG) * PI.toFloat() / 2f) * sin((a - VALVE_A + 2.6f) / 3.5f * PI.toFloat()))
        }
        return radiusAt(b, a) * VEIN_WALL + bulge
    }
    val nd = needleFrame(b, umu(b.toInt()))
    if (part == 0) mb.gridFd(((a1 - a0) / 0.2f).toInt(), 72, { u, v ->
        val a = a0 + (a1 - a0) * u; val th = v * 2f * PI.toFloat(); rfv(b, a).pol(th, veinR(b, nd, a, th))
    }) { u, _, p -> rfv(b, a0 + (a1 - a0) * u).c - p }
    else for (k in 0 until 4) {
        val th = (45f + 90f * k) * DEG
        val pts = ArrayList<V3>(); var a = a0
        while (a <= a1) { val t2 = th + 0.04f * sin(a * 0.7f + k); pts.add(rfv(b, a).pol(t2, r(a, t2) - 0.025f)); a += 0.5f }
        mb.tube(pts, FloatArray(pts.size) { 0.03f }, 5, true)
    }
    return mb.build(twoSided = part == 0)
}

/** Where the valve sits: well downstream of the needle tip, which must not lie in a valve sinus. */
private const val VALVE_A = 17f

/** A bicuspid venous valve: cusps (0), their free edges (1), the agger and commissural ridges (2). */
private fun StereoBodyRenderer.valveMesh(b: Float, part: Int): T3Mesh {
    val mb = Mb()
    val av = VALVE_A
    val axisC = rfv(b, av).c
    for (cusp in 0 until 2) {
        val thc = if (cusp == 0) 90f * DEG else 270f * DEG
        fun p(s0: Float, t: Float): V3 {
            val s = s0 * 2f - 1f                                   // -1..1 across the cusp, commissure to commissure
            val th = thc + s * 86f * DEG
            val belly = 1f - s * s
            val aAtt = av - 2.4f * belly                          // U-shaped attachment, deepest at the middle
            val aFree = av + 0.5f
            val a = aAtt + (aFree - aAtt) * t
            val R = radiusAt(b, a) * VEIN_WALL * 0.99f
            val rFree = R - R * 0.46f * belly.pow(0.6f)            // open: free edge well out from the axis
            val r = R + (rFree - R) * t.pow(0.8f) - 0.25f * belly * sin(t * PI.toFloat())   // the cusp bows, leaving the sinus behind it
            return rfv(b, a).pol(th, r)
        }
        if (part == 0) mb.gridFd(8, 18, { u, v -> p(v, u) }) { _, _, q -> axisC - q }
        else if (part == 1) mb.tube((0..20).map { k -> p(k / 20f, 1f) }, FloatArray(21) { 0.06f }, 5, true)
        else {
            mb.tube((0..24).map { k -> p(k / 24f, 0f) }, FloatArray(25) { 0.07f }, 5, true)
            val thm = thc + 90f * DEG      // the commissure between this cusp and the next
            mb.tube((0..6).map { k -> val a = av - 0.5f + k / 6f; rfv(b, a).pol(thm, radiusAt(b, a) * VEIN_WALL - 0.03f) }, FloatArray(7) { 0.06f }, 5, true)
        }
    }
    return mb.build(twoSided = part == 0)
}

/**
 * Stop 3 (THE BOTTLE, 12 µm Mote: 1 unit = 8 µm): citrated blood that has stood in the cold. The
 * red cells have settled into a dark packed floor — many stacked face to face in rouleaux, others
 * lying flat — with a thin buffy coat of white cells on top (neutrophils, lymphocytes, monocytes),
 * pale straw plasma standing above with the platelets still suspended in it, too small to settle.
 * To starboard the cold glass of the bottle.
 */
internal fun StereoBodyRenderer.drawStored(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    val um = umu(i)
    if (!openWall(i, -8f, 34f, T3_PLASMA_BG, 0.4f)) return
    // The cold is carried by a blue light on everything: the rim light of every surface is cold blue.
    // The dark floor of settled, deoxygenated cells, a faint cold rim light on them.
    drawMesh(cached("st_packed") { storedMesh(b, um, 0) }, T3_PACKED, T3_COLD_RIM_DIM, 1f, 0f, 0f)
    drawMesh(cached("st_cells") { storedMesh(b, um, 1) }, T3_STORED_RBC, T3_COLD_RIM_DIM, 1f, 0f, 0f)
    drawMesh(cached("st_echino") { storedMesh(b, um, 10) }, T3_STORED_RBC, T3_COLD_RIM_DIM, 1f, 0f, 0f)
    drawMesh(cached("st_plt") { storedMesh(b, um, 2) }, T3_PLATELET_LILAC, T3_COLD_RIM, 1f, 0f, 0.1f)
    // The buffy coat: a row of white cells seated in a thin pale layer on the red cells, cut
    // through so their nuclei and granules show on the section.
    drawMesh(cached("st_crust2") { storedMesh(b, um, 13) }, T3_BUFFY, T3_BUFFY, 1f, 0f, 0.1f)
    drawMesh(cached("st_wbc2") { storedMesh(b, um, 4) }, T3_LEUKOCYTE, T3_COLD_RIM, 1f, 0f, 0.08f)
    drawMesh(cached("st_bplt") { storedMesh(b, um, 15) }, T3_PLATELET_LILAC, T3_PLATELET_LILAC, 1f, 0f, 0.1f)
    drawMesh(cached("st_nuc2") { storedMesh(b, um, 3) }, T3_NUCLEUS, T3_NUCLEUS, 1f, 0f, 0.15f)
    drawMesh(cached("st_gran2") { storedMesh(b, um, 12) }, T3_NEUTRO_GRANULE, T3_NEUTRO_GRANULE, 1f, 0f, 0.15f)
    drawMesh(cached("st_edge") { storedMesh(b, um, 8) }, T3_GLASS_EDGE, T3_GLASS_EDGE, 1f, 0f, 0.6f)
    // Platelets are too small to settle in days: they stay suspended in the plasma, sinking very slowly.
    val pr = 2.5f / 2f / um
    val drops = cachedValue("st_float") {
        val rnd = java.util.Random(5)
        val out = ArrayList<FloatArray>()
        while (out.size < 20) {
            val a = -4f + rnd.nextFloat() * 16f; val sd = -2.8f + rnd.nextFloat() * 5f; val up = BUFFY_TOP + 0.5f + rnd.nextFloat() * 2f
            if (sd * sd + up * up < 3.4f) continue                       // not in the craft's lane
            out.add(floatArrayOf(a, sd, up, rnd.nextFloat() * 6.28f))
        }
        out
    }
    for (d in drops) {
        val up = BUFFY_TOP + 0.5f + ((d[2] - BUFFY_TOP - 0.5f - seconds * 0.01f) % 2f + 2f) % 2f
        val f = rfv(b, d[0])
        val pp = f.at(d[1], up)
        if ((pp.x - camNowX).pow(2) + (pp.y - camNowY).pow(2) + (pp.z - camNowZ).pow(2) < 4f) continue
        val tilt = f.u * cos(d[3] + seconds * 0.1f) + f.s * sin(d[3] + seconds * 0.1f)
        drawScaled(sphere, pp, f.d, tilt, 0.16f, 0.05f, 0.16f, T3_PLATELET_LILAC, T3_COLD_RIM, 1f, 0.1f)
    }
    GLES20.glDepthMask(false)
    // near the craft the coat is thin and sparse, the dark red floor showing through it
    drawMesh(cached("st_thin") { storedMesh(b, um, 16) }, T3_BUFFY, T3_BUFFY, 0.4f, 0f, 0.1f)
    // Plasma: a pale straw body filling everything above the cells (seen from within it).
    drawMesh(cached("st_plasma") { storedMesh(b, um, 5) }, T3_PLASMA_PALE, T3_PLASMA_PALE, 0.35f, 0f, 0.5f)
    // The glass: cold, blue, beaded outside with condensation from the cold room (never frost
    // inside: blood that freezes is lost).
    drawMesh(cached("st_glass") { storedMesh(b, um, 6) }, T3_GLASS_COLDER, T3_COLD_RIM, 0.5f, 0f, 0.4f)
    drawMesh(cached("st_glass2") { storedMesh(b, um, 14) }, T3_GLASS_COLDER, T3_COLD_RIM, 0.35f, 0f, 0.4f)
    drawMesh(cached("st_beads") { storedMesh(b, um, 11) }, T3_FROST, T3_FROST, 0.6f, 0f, 0.5f)
    drawMesh(cached("st_glint") { storedMesh(b, um, 7) }, T3_STEEL_EDGE, T3_STEEL_EDGE, 0.45f + 0.1f * sin(seconds * 0.4f), 0f, 0.8f)
    GLES20.glDepthMask(true)
}

private const val BED_TOP = -2.5f
/** The top of the thin buffy-coat layer (a little over one red cell above the bed). */
private const val BUFFY_TOP = BED_TOP + 1.25f
/** Where the buffy coat's crust begins: the red floor stays bare in front of the craft. */
private const val BUFFY_A0 = 4.0f

/**
 * An echinocyte (the storage lesion): a crenated cell that has lost its dimple, ~16 spicules
 * evenly over its whole surface; the body (spikes = false) or the spicules (spikes = true).
 */
private fun echinocyte(mb: Mb, c: V3, ax: V3, r: Float, spikes: Boolean) {
    val a = ax.unit(); val e1 = perp(a); val e2 = a.cross(e1).unit()
    val h = r * 0.42f
    if (!spikes) { mb.ellipsoid(c, a * h, e1 * r, e2 * r, 8, 14); return }
    val n = 16
    for (q in 0 until n) {
        val zq = 1f - 2f * (q + 0.5f) / n; val rq = sqrt(1f - zq * zq); val t = q * 2.39996f
        val dirL = e1 * (rq * cos(t)) + e2 * (rq * sin(t)) + a * zq
        val base = c + e1 * (r * rq * cos(t)) + e2 * (r * rq * sin(t)) + a * (h * zq)
        val nrm = (e1 * (rq * cos(t) / r) + e2 * (rq * sin(t) / r) + a * (zq / h)).unit()
        mb.tube(listOf(base - nrm * 0.02f, base + nrm * 0.14f), floatArrayOf(0.05f, 0.01f), 6, false)
        if (dirL.len() < 0f) break
    }
}

/** Stored blood: packed mass (0), rouleaux and single cells (1), platelets (2), leukocyte nuclei (3), leukocytes (4), plasma (5), glass (6), glint (7). */
private fun StereoBodyRenderer.storedMesh(b: Float, um: Float, part: Int): T3Mesh {
    val mb = Mb()
    val rbcR = 7.5f / 2f / um
    val rim = 2.5f / um                                            // cell thickness at the rim: the rouleau spacing
    val rnd = java.util.Random(47)
    val glassSide = 2.45f
    when (part) {
        0 -> {
            // the packed mass beneath the top layer (a bed thousands of cells deep)
            var a = -6.5f
            while (a < 14f) {
                val f = rfv(b, a)
                mb.ellipsoid(f.at(-0.8f, BED_TOP - 1.1f), f.u * 1.2f, f.d * 1.9f, f.s * 3.9f, 5, 12)
                a += 2.4f
            }
        }
        1, 10 -> {
            // (10: the spicules of the echinocytes among the single cells — the storage lesion)
            val er = java.util.Random(53)
            var a = -5.5f
            while (a < 12.5f) {
                var s = -3.6f
                while (s < glassSide - 0.6f) {
                    val f = rfv(b, a + rnd.nextFloat() * 0.6f)
                    if (rnd.nextFloat() < 0.72f) {
                        // a rouleau lying on the bed: 4-9 discs face to face
                        val nCells = 4 + rnd.nextInt(6)
                        val az = rnd.nextFloat() * PI.toFloat()
                        val tilt = (rnd.nextFloat() - 0.5f) * 0.5f
                        val ax = (f.d * cos(az) + f.s * sin(az) + f.u * tilt).unit()
                        val c = f.at(s + rnd.nextFloat() * 0.4f, BED_TOP + rbcR * 0.95f)
                        if (part == 1) for (k in 0 until nCells) mb.redCell(c + ax * ((k - (nCells - 1) / 2f) * rim * 1.02f), ax, rbcR, 4, 12)
                    } else {
                        val tip = V3(rnd.nextFloat() - 0.5f, 0f, rnd.nextFloat() - 0.5f) * 0.5f
                        val c = f.at(s, BED_TOP + rim * 0.6f); val ax = (f.u + tip).unit()
                        val echino = er.nextFloat() < 0.25f
                        if (!echino) { if (part == 1) mb.redCell(c, ax, rbcR, 4, 12) }
                        else echinocyte(mb, c + ax * 0.1f, ax, rbcR * 0.9f, part == 10)
                    }
                    s += 1.25f + rnd.nextFloat() * 0.3f
                }
                a += 1.55f
            }
            // three more lying on top of the rouleaux just ahead of the craft, where the pilot sees them
            for ((k, ap) in floatArrayOf(1.6f, 2.4f, 3.2f).withIndex()) {
                val f = rfv(b, ap)
                echinocyte(mb, f.at(-1.2f + k * 1.1f, BED_TOP + rbcR * 2.05f), (f.u + f.d * 0.2f).unit(), rbcR * 0.9f, part == 10)
            }
        }
        2 -> for (k in 0 until 6) {
            // a few platelets caught in the buffy coat (the rest stay suspended in the plasma)
            val f = rfv(b, -4f + rnd.nextFloat() * 15f)
            val c = f.at(-3.3f + rnd.nextFloat() * 5.2f, BED_TOP + rbcR * 1.95f + rnd.nextFloat() * 0.1f)
            val tip = (f.u + f.s * (rnd.nextFloat() - 0.5f) * 0.6f).unit()
            mb.ellipsoidAxes(c, tip, 0.5f / um, perp(tip), 1.25f / um, 1.1f / um, 4, 8)
        }
        3, 4, 12, 15 -> {
            // the buffy coat ahead of the craft: a packed monolayer of white cells, more than half sunk
            // into a thin pale crust over the red bed, rows offset, with platelets in the gaps (15):
            // 60% neutrophils (segmented nuclei, granules), 30% lymphocytes (round), 10% monocytes
            val cr = java.util.Random(23)
            val pr = java.util.Random(29)
            var row = 0
            var a = BUFFY_A0 + 0.6f
            while (a < 20f) {
                var sd = -3.1f + (if (row % 2 == 0) 0f else 0.6f) + cr.nextFloat() * 0.3f
                while (sd < glassSide - 0.5f) {
                    val kind = cr.nextFloat()
                    val dUm = if (kind < 0.6f) 12f else if (kind < 0.9f) 8.5f else 15f
                    val r = dUm / 2f / um
                    val aa = a + (cr.nextFloat() - 0.5f) * 0.6f; val ss = sd + (cr.nextFloat() - 0.5f) * 0.6f
                    val f = rfv(b, aa)
                    val c = f.at(ss, BUFFY_TOP - 0.55f * r)
                    when (part) {
                        4 -> mb.sectionedCell(c, f.u, r, 0.8f, 0.6f, 8, 16)
                        15 -> for (q in 0 until 3) {
                            val t = pr.nextFloat() * 6.28f
                            val pc = f.at(ss + cos(t) * (r + 0.25f), BUFFY_TOP - 0.27f) + f.d * (sin(t) * (r + 0.25f))
                            val tip = (f.u + V3(pr.nextFloat() - 0.5f, 0f, pr.nextFloat() - 0.5f) * 0.4f).unit()
                            mb.ellipsoidAxes(pc, tip, 0.05f, perp(tip), 0.16f, 0.15f, 5, 14)
                        }
                        else -> {
                            val (fc, fr) = faceOf(c, f.u, r, 0.8f, 0.6f)
                            val nk = if (kind < 0.6f) 1 else if (kind < 0.9f) 0 else 3
                            if (part == 3) mb.nucleusOnFace(fc, f.u, fr, nk, row * 17 + (sd * 3).toInt(), if (nk == 0) 1.15f else 1f)
                            else if (nk == 1) mb.granulesOnFace(fc, f.u, fr, 20, 0.03f, row * 13 + (sd * 5).toInt())
                        }
                    }
                    sd += 2f * r * 1.1f + 0.1f
                }
                a += 1.35f; row++
            }
        }
        13 -> {
            // the buffy coat's pale crust over the red bed: full (0.25 thick) from the cell monolayer on,
            // ramping down over 1.5 units to the thin coat that runs on under the craft (16)
            val top = BUFFY_TOP - 0.3f
            mb.gridFd(48, 6, { u, v ->
                val a = BUFFY_A0 - 1.5f + (24f - BUFFY_A0 + 1.5f) * u
                val ramp = smooth01((a - (BUFFY_A0 - 1.5f)) / 1.5f)
                wAt(b, a, -3.6f + (glassSide + 3.6f) * v, top - 0.13f * (1f - ramp))
            }) { u, _, _ -> rfv(b, BUFFY_A0 - 1.5f + (24f - BUFFY_A0 + 1.5f) * u).u }
        }
        16 -> mb.gridFd(24, 6, { u, v -> wAt(b, -8f + (BUFFY_A0 - 1.3f + 8f) * u, -3.6f + (glassSide + 3.6f) * v, BUFFY_TOP - 0.43f) }) { u, _, _ -> rfv(b, -8f + (BUFFY_A0 - 1.3f + 8f) * u).u }
        14 -> mb.gridFd(8, 2, { u, v -> wAt(b, -8f + 42f * u, glassSide + 0.35f, -3.4f + 7.4f * v) }) { u, _, _ -> -rfv(b, -8f + 42f * u).s }
        5 -> {
            // seen from inside: inward normals
            val f = rfv(b, 3f)
            // an open box of plasma above the buffy coat, running past both ends of the view
            val lo = BUFFY_TOP + 0.05f; val hi = 2.2f; val sL = -2.4f; val sR = glassSide - 0.02f
            val corners = listOf(floatArrayOf(sL, lo), floatArrayOf(sL, hi), floatArrayOf(sR, hi), floatArrayOf(sR, lo))
            for (q in 0 until 3) {
                val c0 = corners[q]; val c1 = corners[q + 1]
                mb.gridFd(40, 2, { u, v -> val a = -10f + 43f * u; wAt(b, a, c0[0] + (c1[0] - c0[0]) * v, c0[1] + (c1[1] - c0[1]) * v) }) { u, v, p -> rfv(b, -10f + 43f * u).at((sL + sR) / 2f, (lo + hi) / 2f) - p }
            }
        }
        6 -> mb.gridFd(8, 2, { u, v -> wAt(b, -8f + 42f * u, glassSide, -3.4f + 7.4f * v) }) { u, _, _ -> -rfv(b, -8f + 42f * u).s }
        11 -> {
            // condensation on the OUTER face of the cold glass: small beads, bulging outward
            val fr = java.util.Random(61)
            for (k in 0 until 40) {
                val a = -5f + fr.nextFloat() * 22f; val up = BED_TOP + 0.5f + fr.nextFloat() * 5.5f
                val f = rfv(b, a); val r = 0.08f + 0.12f * fr.nextFloat()
                mb.ellipsoid(f.at(glassSide + 0.35f, up), f.s * (r * 0.7f), f.d * r, f.u * r, 5, 10)
            }
        }
        8 -> mb.tube((0..24).map { q -> wAt(b, -6f + q * 1.3f, glassSide - 0.04f, BUFFY_TOP) }, FloatArray(25) { 0.04f }, 5, true)
        9 -> mb.gridFd(20, 4, { u, v -> wAt(b, -6f + 20f * u, -3.4f + (glassSide + 3.4f) * v, BED_TOP + rbcR * 1.9f + 1.0f) }) { u, _, _ -> rfv(b, -6f + 20f * u).u }
        else -> for (k in 0 until 2) {
            val up = 1.2f + k * 0.9f
            mb.tube((0..12).map { q -> wAt(b, -5f + q * 1.5f, glassSide - 0.03f, up + 0.15f * sin(q * 0.5f)) }, FloatArray(13) { 0.05f - 0.02f * k }, 4, true)
        }
    }
    return mb.build(twoSided = part == 6 || part == 9 || part == 11 || part == 16)
}

/**
 * Stop 4 (THE FRONT, 12 µm Mote: 1 unit = 8 µm): threading a war wound. Overhead run two skeletal
 * muscle fibres ~50 µm thick, cross-striated at 2.5 µm with peripheral nuclei; one is torn across,
 * its stumps frayed into myofibrils and capped by dark contraction bands, and fibrin ropes are strung
 * across the gap. Two more fibres line the floor. On the floor, a clot of fibrin with trapped red
 * cells and platelet clumps; grit and a twisted cotton fibre from the uniform, carried in by the
 * metal and already carrying Gram-positive bacteria (Clostridium rods, cocci); two neutrophils crawl
 * toward them. The Yan'an picture hangs well down the passage, clear of the tissue.
 */
internal fun StereoBodyRenderer.drawWound(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    val um = umu(i)
    if (!openWall(i, -8f, 34f, T3_WOUND_DARK)) return
    drawMesh(cached("wd_floor") { woundMesh(b, um, 11) }, T3_WOUND_FLOOR, T3_WOUND_FLOOR, 1f, 0.6f, 0.05f)
    // One muscle, its fibres all running the same way, the track torn across them. Striations fade
    // with distance (a far fibre reads as plain pink rather than aliasing into moiré).
    for (k in WOUND_FIBRES.indices) {
        val c = woundFibreAxis(b, um, k)
        val d = sqrt((c.x - camNowX).pow(2) + (c.y - camNowY).pow(2) + (c.z - camNowZ).pow(2))
        val lod = (1f - 0.75f * ((d - 6f) / 8f).coerceIn(0f, 1f))
        drawMesh(cached("wd_i$k") { woundFibre(b, um, k, false) }, T3_I_BAND, T3_I_BAND, 1f, 0f, 0.08f)
        drawMesh(cached("wd_a$k") { woundFibre(b, um, k, true) }, T3_A_BAND, T3_A_BAND, lod, 0f, 0.05f)
    }
    drawMesh(cached("wd_nuclei") { woundMesh(b, um, 2) }, T3_MUSCLE_NUCLEUS, T3_MUSCLE_NUCLEUS, 1f, 0f, 0.2f)
    drawMesh(cached("wd_fray") { woundMesh(b, um, 3) }, T3_MYOFIBRIL, T3_I_BAND, 1f, 0f, 0.12f)
    drawMesh(cached("wd_cband") { woundMesh(b, um, 4) }, T3_CONTRACTION, T3_CONTRACTION, 1f, 0f, 0.1f)
    drawMesh(cached("wd_fibrin") { woundMesh(b, um, 5) }, T3_FIBRIN, T3_FIBRIN, 1f, 0f, 0.35f)
    val camA = run { val f0 = rfv(b, 0f); (V3(camNowX, camNowY, camNowZ) - f0.c).dot(f0.d) }
    for (ch in 0 until 6) if (ch * 2f + 1f - camA !in -3.5f..3.5f) drawMesh(cached("wd_net$ch") { woundMesh(b, um, 18 + ch) }, T3_FIBRIN, T3_FIBRIN, 0.8f, 0f, 0.3f)
    drawMesh(cached("wd_clotrbc") { woundMesh(b, um, 6) }, T3_RBC, T3_RBC, 1f, 0f, 0.12f)
    drawMesh(cached("wd_plt") { woundMesh(b, um, 7) }, T3_PLATELET, T3_PLATELET, 1f, 0f, 0.3f)
    drawMesh(cached("wd_grit") { woundMesh(b, um, 8) }, T3_GRIT, T3_GRIT, 1f, 0.5f, 0.1f)
    drawMesh(cached("wd_cotton") { woundMesh(b, um, 9) }, T3_COTTON, T3_COTTON, 1f, 0f, 0.15f)
    drawMesh(cached("wd_lumen") { woundMesh(b, um, 16) }, T3_COTTON_LUMEN, T3_COTTON_LUMEN, 1f, 0f, 0.05f)
    drawMesh(cached("wd_bact") { woundMesh(b, um, 10) }, T3_GRAM_POS, T3_GRAM_POS, 1f, 0f, 0.45f)
    drawMesh(cached("wd_spores") { woundMesh(b, um, 12) }, T3_SPORE, T3_SPORE, 1f, 0f, 0.3f)
    drawMesh(cached("wd_caps") { woundMesh(b, um, 14) }, T3_CAP_MUSCLE, T3_CAP_MUSCLE, 1f, 0f, 0.1f)
    drawMesh(cached("wd_capbleed") { woundMesh(b, um, 15) }, T3_RBC, T3_RBC, 1f, 0f, 0.12f)
    // Neutrophils crawling over the clot toward the contaminated cloth: opaque, flattened, cut
    // through to show the segmented nucleus and granules, a thin lamellipodium leading.
    val r = 12.5f / 2f / um
    val body = cached("wd_pmnbody") { val mb = Mb(); mb.sectionedCell(V3(0f, 0f, 0f), V3(0f, 1f, 0f), r, 0.45f, 0.4f, 10, 20); mb.build() }
    val nucl = cached("wd_pmnnuc") { val mb = Mb(); val (fc, fr) = faceOf(V3(0f, 0f, 0f), V3(0f, 1f, 0f), r, 0.45f, 0.4f); mb.nucleusOnFace(fc, V3(0f, 1f, 0f), fr, 1, 2); mb.build() }
    val gran = cached("wd_pmngran") { val mb = Mb(); val (fc, fr) = faceOf(V3(0f, 0f, 0f), V3(0f, 1f, 0f), r, 0.45f, 0.4f); mb.granulesOnFace(fc, V3(0f, 1f, 0f), fr, 20, 0.03f, 8); mb.build() }
    val floorUp = woundFloorUp(b)
    for (k in 0 until 2) {
        // Crawling: the lamellipodium pushes out toward the bacteria (2 s), then the body catches up
        // (1.5 s); each cycle gains a step. The path loops slowly toward the contaminated cloth.
        val cyc = (seconds + k * 1.7f) / 3.5f
        val step = floor(cyc); val ph = (cyc - step) * 3.5f
        val catchUp = if (ph < 2f) 0f else smooth01((ph - 2f) / 1.5f)
        val reach = if (ph < 2f) smooth01(ph / 2f) else 1f - catchUp
        val t = (((step + catchUp) * 0.035f + k * 0.5f) % 1f)
        val (a0, s0) = pmnPath(k, t)
        val f = rfv(b, a0)
        // the cut face turned 40° toward the crew behind, so the nucleus and granules face them
        val y = (f.u - f.d * 0.85f).unit()
        val dir0 = (f.d * 2.2f + f.s * (if (k == 0) 0.5f else -0.4f)).unit()
        val dir = (dir0 - y * dir0.dot(y)).unit()
        val c = f.at(s0, floorUp + 1.2f)
        drawLocal(body, c, dir, y, T3_LEUKOCYTE, T3_LEUKOCYTE, 1f, 0.15f)
        drawLocal(nucl, c, dir, y, T3_NUCLEUS, T3_NUCLEUS, 1f, 0.2f)
        drawLocal(gran, c, dir, y, T3_NEUTRO_GRANULE, T3_NEUTRO_GRANULE, 1f, 0.2f)
        // the leading lamellipodium: a thin sheet ~0.9 wide, reaching toward the bacteria
        val ext = 0.35f + 0.25f * reach
        drawScaled(sphere, c + dir * (r * 0.8f + ext * 0.5f) - y * (r * 0.2f), dir, y, 0.45f, 0.025f, ext * 0.5f + 0.1f, T3_LEUKOCYTE, T3_LEUKOCYTE, 1f, 0.2f)
    }
    GLES20.glDepthMask(false)
    drawMesh(cached("wd_endo") { woundMesh(b, um, 13) }, T3_ENDOMYSIUM, T3_ENDOMYSIUM, 0.45f, 0f, 0.1f)
    GLES20.glDepthMask(true)
    drawMesh(cached("wd_rip") { woundMesh(b, um, 17) }, T3_ENDOMYSIUM, T3_ENDOMYSIUM, 1f, 0f, 0.1f)
    // Yan'an, spring 1938: hung well down the passage and up to starboard, clear of the tissue.
    drawPlate("yanan", frameAt(b + 14f / NODE_UNITS), radiusAt(b, 14f) * 0.62f, radiusAt(b, 14f) * 0.28f, 1.8f, seconds)
}

private fun StereoBodyRenderer.woundFloorUp(b: Float) = -tunnelRadius(b) * 0.84f * 0.78f

/** Where crawling neutrophil [k] is at path phase [t]: (along, side), 3-5 units ahead of the pilot. */
private fun pmnPath(k: Int, t: Float): Pair<Float, Float> =
    if (k == 0) (3.0f + 1.5f * t) to (-1.1f + 0.2f * t) else (7.5f + 1.5f * t) to (1.0f - 0.2f * t)

/** The cotton fibre's centre line: lying across the pilot's view at along 5.5-6.5. */
private fun StereoBodyRenderer.cottonCentre(b: Float, u: Float): V3 {
    val floorUp = woundFloorUp(b)
    return lerpV(wAt(b, 5.5f, -2.4f, floorUp + 1.25f), wAt(b, 6.5f, 2.2f, floorUp + 1.25f), u) + rfv(b, 6f).u * (0.08f * sin(u * PI.toFloat()))
}

/** The fibrin net over the clot: nodes on a jittered grid, each joined to its 3 nearest neighbours. */
private fun StereoBodyRenderer.fibrinNet(b: Float): List<Pair<V3, V3>> = cachedValue("wd_net") {
    val rnd = java.util.Random(77)
    val floorUp = woundFloorUp(b)
    val nodes = ArrayList<V3>()
    var a = 0.3f
    while (a < 11f) { var sd = -2.2f; while (sd < 2.3f) { nodes.add(wAt(b, a + (rnd.nextFloat() - 0.5f) * 0.4f, sd + (rnd.nextFloat() - 0.5f) * 0.4f, floorUp + 0.6f + 0.2f * rnd.nextFloat())); sd += 0.9f }; a += 0.9f }
    val edges = ArrayList<Pair<V3, V3>>(); val seen = HashSet<Long>()
    for ((i, p) in nodes.withIndex()) {
        nodes.indices.filter { it != i }.sortedBy { (nodes[it] - p).len() }.take(3).forEach { j ->
            val key = min(i, j).toLong() * 100000 + max(i, j)
            if (seen.add(key)) edges.add(p to nodes[j])
        }
    }
    edges
}

/**
 * The muscle's fibres, all parallel (across the track): (along, up of the axis above the floor or
 * below it, diameter µm). Index 1 is the torn one, lowered into view with its gap across the lane.
 */
private val WOUND_FIBRES = listOf(
    floatArrayOf(0.8f, 2.3f, 52f), floatArrayOf(5.0f, 1.55f, 46f), floatArrayOf(10.6f, 2.2f, 50f),
    floatArrayOf(-1.2f, -1f, 48f), floatArrayOf(3.4f, -1f, 54f), floatArrayOf(8.3f, -1f, 46f), floatArrayOf(12.8f, -1f, 50f))
private const val STUMP_L = 1.5f
private const val STUMP_R = -2.6f

/** Centre of fibre [k]'s axis (on the passage axis line). Overhead fibres hang with their lower face at up; floor fibres show their top above the floor. */
private fun StereoBodyRenderer.woundFibreAxis(b: Float, um: Float, k: Int): V3 {
    val w = WOUND_FIBRES[k]; val r = w[2] / 2f / um
    val f = rfv(b, w[0])
    return if (w[1] > 0f) f.at(0f, w[1] + r) else f.at(0f, woundFloorUp(b) + 0.45f - r)
}

/** Fibre [k]: its pale I-band body, or its dark A bands. */
private fun StereoBodyRenderer.woundFibre(b: Float, um: Float, k: Int, aBands: Boolean): T3Mesh {
    val mb = Mb()
    val sarc = 2.5f / um
    val w = WOUND_FIBRES[k]; val r = w[2] / 2f / um
    val f = rfv(b, w[0]); val ax = woundFibreAxis(b, um, k)
    if (k == 1) {
        mb.striatedFibre(ax + f.s * 11f, ax + f.s * STUMP_L, r, sarc, aBands)
        mb.striatedFibre(ax - f.s * 11f, ax + f.s * STUMP_R, r, sarc, aBands)
        // the torn ends: open faces of the fibre, from which the myofibrils fray out
        if (!aBands) for ((end, nrm) in listOf(STUMP_L to -f.s, STUMP_R to f.s)) {
            val e1 = f.d; val e2 = f.u
            mb.grid(1, 22, { u, v -> val t = v * 2f * PI.toFloat(); ax + f.s * end + (e1 * cos(t) + e2 * sin(t)) * (r * u) }) { _, _, _ -> nrm }
        }
    } else mb.striatedFibre(ax - f.s * 11f, ax + f.s * 11f, r, sarc, aBands)
    return mb.build()
}

/** A point on fibrin rope [k] at [t] (0..1) across the torn gap, and its strand radius. */
private fun StereoBodyRenderer.woundRope(b: Float, um: Float, k: Int, t: Float): V3 {
    val fB = rfv(b, WOUND_FIBRES[1][0]); val rB = WOUND_FIBRES[1][2] / 2f / um
    val axB = woundFibreAxis(b, um, 1)
    val ang = k * 0.7f
    val p0 = axB + fB.s * (STUMP_L + 0.2f) + fB.d * (cos(ang) * rB * 0.7f) + fB.u * (sin(ang) * rB * 0.6f - rB * 0.2f)
    val p1 = axB + fB.s * (STUMP_R - 0.2f) + fB.d * (cos(ang + 1.3f) * rB * 0.7f) + fB.u * (sin(ang + 1.3f) * rB * 0.6f - rB * 0.2f)
    return lerpV(p0, p1, t) - fB.u * (sin(t * PI.toFloat()) * 0.9f)
}

/** The wound's parts (see [drawWound]). */
private fun StereoBodyRenderer.woundMesh(b: Float, um: Float, part: Int): T3Mesh {
    val mb = Mb()
    val sarc = 2.5f / um
    val rnd = java.util.Random(61L + (if (part == 12) 10 else part))
    val floorUp = woundFloorUp(b)
    val fB = rfv(b, WOUND_FIBRES[1][0]); val rB = WOUND_FIBRES[1][2] / 2f / um
    val axB = woundFibreAxis(b, um, 1)
    val stumpL = STUMP_L; val stumpR = STUMP_R                             // the torn fibre's ends (side)
    when (part) {
        2 -> {
            // peripheral nuclei, flattened under the sarcolemma on the faces toward the passage
            for (k in WOUND_FIBRES.indices) {
                val w = WOUND_FIBRES[k]; val r = w[2] / 2f / um; val f = rfv(b, w[0])
                val ax = woundFibreAxis(b, um, k)
                val toward = if (w[1] > 0f) -f.u else f.u
                for (q in 0 until 7) {
                    val t = -8f + q * 2.6f + (k % 3) * 0.7f
                    if (k == 1 && t > STUMP_R - 0.3f && t < STUMP_L + 0.3f) continue
                    val ang = ((q + k) % 3 - 1) * 0.45f
                    val radial = (toward * cos(ang) + f.d * sin(ang)).unit()
                    mb.ellipsoidAxes(ax + f.s * t + radial * (r * 0.99f), f.s, 10f / 2f / um, radial, 1.2f / um, 2.6f / 2f / um, 5, 10)
                }
            }
        }
        3 -> for ((end, dirSign) in listOf(stumpL to -1f, stumpR to 1f)) {
            // the torn ends frayed into myofibrils (1-2 µm), splaying and drooping into the gap
            for (k in 0 until 16) {
                val ang = k * 2.399f; val rr = rB * 0.85f * sqrt((k + 0.5f) / 16f)
                val base = axB + fB.s * end + fB.d * (cos(ang) * rr) + fB.u * (sin(ang) * rr)
                val len = 0.6f + 1.4f * rnd.nextFloat()
                val pts = (0..4).map { q ->
                    val t = q / 4f * len
                    base + fB.s * (dirSign * t) - fB.u * (t * t * 0.18f) + fB.d * (cos(ang) * t * 0.25f)
                }
                mb.tube(pts, FloatArray(5) { 1.4f / 2f / um }, 5, true)
            }
        }
        4 -> for ((end, back) in listOf(stumpL to 1f, stumpR to -1f)) {
            // contraction band: a dark collar of hypercontracted sarcomeres a little way back from
            // the torn end, inside the fibre's outline and slightly swollen
            mb.cylinder(axB + fB.s * (end + back * 0.2f), axB + fB.s * (end + back * 0.9f), rB * 1.04f, 22)
        }
        13 -> {
            // the endomysium round every fibre, and its capillaries (two per fibre, on its surface);
            // the torn fibre's capillaries are torn too, and bleeding
            for (k in WOUND_FIBRES.indices) {
                val w = WOUND_FIBRES[k]; val r = w[2] / 2f / um; val f = rfv(b, w[0])
                val ax = woundFibreAxis(b, um, k)
                if (k == 1) { mb.cylinder(ax + f.s * 11f, ax + f.s * STUMP_L, r + 0.08f, 22); mb.cylinder(ax - f.s * 11f, ax + f.s * STUMP_R, r + 0.08f, 22) }
                else mb.cylinder(ax - f.s * 11f, ax + f.s * 11f, r + 0.08f, 22)
            }
        }
        14, 15 -> for (k in WOUND_FIBRES.indices) {
            val w = WOUND_FIBRES[k]; val r = w[2] / 2f / um; val f = rfv(b, w[0])
            val ax = woundFibreAxis(b, um, k)
            val toward = if (w[1] > 0f) -f.u else f.u
            val capR = 7f / 2f / um
            for (sg in SIGNS) {
                val dir = (toward * cos(70f * DEG) + f.d * (sg * sin(70f * DEG))).unit()
                val off = dir * (r + capR * 0.9f)
                val pieces = if (k == 1) listOf(11f to STUMP_L + 0.4f, -11f to STUMP_R - 0.4f) else listOf(11f to -11f)
                for ((s0, s1) in pieces) {
                    if (part == 14) mb.tube(listOf(ax + off + f.s * s0, ax + off + f.s * ((s0 + s1) / 2f) + dir * 0.1f, ax + off + f.s * s1), FloatArray(3) { capR }, 7, k != 1)
                    else if (k == 1) {
                        // red cells spilling from the open end
                        for (q in 0 until 3) {
                            val c = ax + off + f.s * (s1 + (s1 - s0).sign * (0.4f + q * 0.55f)) + toward * (0.25f * q) + f.d * (0.2f * sin(q * 2f + sg))
                            mb.redCell(c, (f.s * cos(q + sg) + f.u * sin(q + sg)).unit(), 7.5f / 2f / um, 4, 12)
                        }
                    }
                }
            }
        }
        5 -> {
            // fibrin ropes strung across the gap between the stumps (bundles of strands), sagging
            for (k in 0 until 9) {
                val ang = k * 0.7f
                val p0 = axB + fB.s * (stumpL + 0.2f) + fB.d * (cos(ang) * rB * 0.7f) + fB.u * (sin(ang) * rB * 0.6f - rB * 0.2f)
                val p1 = axB + fB.s * (stumpR - 0.2f) + fB.d * (cos(ang + 1.3f) * rB * 0.7f) + fB.u * (sin(ang + 1.3f) * rB * 0.6f - rB * 0.2f)
                for (strand in 0 until 3) {
                    val tw = strand * 2.1f
                    val pts = (0..10).map { q ->
                        val t = q / 10f
                        lerpV(p0, p1, t) - fB.u * (sin(t * PI.toFloat()) * 0.9f) + fB.d * (cos(tw + t * 9f) * 0.06f) + fB.u * (sin(tw + t * 9f) * 0.06f)
                    }
                    mb.tube(pts, FloatArray(11) { if (k % 3 == 0) 0.06f else 0.035f }, 4, false)
                }
            }
        }
        18, 19, 20, 21, 22, 23 -> {
            // the fibrin net over the clot, in along-bands (so the band at the lens can be left out)
            val lo = (part - 18) * 2f; val hi = lo + 2f
            for ((p, q) in fibrinNet(b)) {
                val m = lerpV(p, q, 0.5f); val am = (m - rfv(b, 0f).c).dot(rfv(b, 0f).d)
                if (am < lo || am >= hi) continue
                mb.tube(listOf(p, m - rfv(b, am).u * 0.05f, q), FloatArray(3) { 0.012f }, 4, false)
            }
        }
        6 -> for (k in 0 until 23) {
            if (k >= 18) {
                // red cells caught on the fibrin ropes in the gap
                val c = woundRope(b, um, (k * 5) % 9, 0.35f + 0.3f * rnd.nextFloat())
                mb.redCell(c + fB.d * 0.12f, V3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).unit(), 7.5f / 2f / um, 4, 12)
                continue
            }
            // red cells trapped in the fibrin (kept clear of the crawling neutrophils' paths)
            val aa = -1.5f + rnd.nextFloat() * 11f; val ss = -2.2f + rnd.nextFloat() * 4.4f
            val f = rfv(b, aa)
            val c = f.at(ss, floorUp + 0.55f + rnd.nextFloat() * 0.35f)
            val ax = (f.u + f.s * (rnd.nextFloat() - 0.5f) * 1.6f + f.d * (rnd.nextFloat() - 0.5f) * 1.6f).unit()
            val nearPmn = (0 until 2).any { kk -> (0..10).any { q -> val (pa, ps) = pmnPath(kk, q / 10f); (aa - pa).pow(2) + (ss - ps).pow(2) < 1.44f } }
            if (!nearPmn) mb.redCell(c, ax, 7.5f / 2f / um, 4, 12)
        }
        7 -> for (cl in 0 until 6) {
            // clumps of activated platelets in the clot and along the ropes: spread discs with filopodia
            val f = rfv(b, 1f + cl * 3.3f)
            val c = if (cl >= 3) woundRope(b, um, cl * 2 - 5, 0.35f + 0.12f * cl) - fB.d * 0.1f else f.at(-1.4f + cl * 1.3f, floorUp + 0.65f)
            for (k in 0 until 9) {
                val d = V3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).unit()
                val pc = c + d * 0.28f
                val e1 = perp(d); val e2 = d.cross(e1).unit()
                mb.ellipsoid(pc, d * 0.05f, e1 * 0.16f, e2 * 0.16f, 5, 10)
                val nf = 4 + rnd.nextInt(3)
                for (q in 0 until nf) {
                    val t = q * 2f * PI.toFloat() / nf + rnd.nextFloat() * 0.5f
                    val rad = e1 * cos(t) + e2 * sin(t)
                    mb.tube(listOf(pc + rad * 0.12f, pc + rad * (0.32f + 0.12f * rnd.nextFloat())), floatArrayOf(0.02f, 0.012f), 4, true)
                }
            }
        }
        8 -> {
            // grit: angular mineral fragments 9-20 µm, faceted
            val spots = listOf(floatArrayOf(2f, 1.9f, 14f), floatArrayOf(4.8f, -2.1f, 15f), floatArrayOf(8.5f, 1.3f, 11f), floatArrayOf(7.2f, -1.9f, 9f))
            for (sp in spots) {
                val f = rfv(b, sp[0]); val r = sp[2] / 2f / um
                val c = f.at(sp[1], floorUp + 0.3f + r * 0.7f)
                val jit = java.util.Random(sp[2].toLong())
                val verts = (0..5).map { q -> (0..6).map { w ->
                    val ph = q / 5f * PI.toFloat(); val th = w / 6f * 2f * PI.toFloat()
                    val k = 0.7f + 0.5f * jit.nextFloat()
                    c + (f.u * cos(ph) + (f.s * cos(th) + f.d * sin(th)) * sin(ph)) * (r * k)
                } }
                for (q in 0 until 5) for (w in 0 until 6) {
                    val w1 = (w + 1) % 6
                    val p00 = verts[q][w]; val p10 = verts[q + 1][w]; val p11 = verts[q + 1][w1]; val p01 = verts[q][w1]
                    val n1 = (p10 - p00).cross(p11 - p00).let { if (it.dot(p00 - c) < 0f) -it else it }
                    val n2 = (p11 - p00).cross(p01 - p00).let { if (it.dot(p00 - c) < 0f) -it else it }
                    mb.tri(p00, n1, p10, n1, p11, n1); mb.tri(p00, n2, p11, n2, p01, n2)
                }
            }
        }
        9 -> {
            // a cotton fibre from the uniform: a flattened, twisted ribbon ~16 µm wide
            val dir = (cottonCentre(b, 1f) - cottonCentre(b, 0f)).unit(); val up = rfv(b, 6f).u
            val w0 = up.cross(dir).unit()
            val half = 1.0f; val th = 0.175f
            fun centre(u: Float) = cottonCentre(b, u)
            // a flat ribbon lying across the view, tilted to face the crew, one convolution along it
            mb.gridFd(80, 10, { u, v ->
                val tw = smooth01((u - 0.35f) / 0.3f) * 2f * PI.toFloat() + 0.8f
                val across = (w0 * cos(tw) + up * sin(tw))
                val thick = (up * cos(tw) - w0 * sin(tw))
                val q = v * 2f * PI.toFloat()
                centre(u) + across * (cos(q) * half) + thick * (sin(q) * th)
            }) { u, _, p -> p - centre(u) }
        }
        16 -> {
            // the collapsed central lumen: a darker stripe along each flat face of the cotton ribbon
            val dir = (cottonCentre(b, 1f) - cottonCentre(b, 0f)).unit(); val up = rfv(b, 6f).u
            val w0 = up.cross(dir).unit()
            fun centre(u: Float) = cottonCentre(b, u)
            for (sg in floatArrayOf(-1f, 1f)) mb.gridFd(80, 1, { u, v ->
                val tw = smooth01((u - 0.35f) / 0.3f) * 2f * PI.toFloat() + 0.8f
                val across = (w0 * cos(tw) + up * sin(tw))
                val thick = (up * cos(tw) - w0 * sin(tw))
                centre(u) + across * ((v - 0.5f) * 0.3f) + thick * (sg * 0.19f)
            }) { u, _, p -> p - centre(u) }
        }
        17 -> for ((end, back) in listOf(STUMP_L to 1f, STUMP_R to -1f)) {
            // the endomysial sheath ripped with the fibre: ragged flaps round each stump
            val r = rB + 0.08f
            for (q in 0 until 12) {
                val t = q * 2f * PI.toFloat() / 12f
                val rad = fB.d * cos(t) + fB.u * sin(t)
                val len = 0.12f + 0.12f * rnd.nextFloat()
                val c = axB + fB.s * (end - back * 0.05f) + rad * (r + 0.02f)
                mb.ellipsoidAxes(c - fB.s * (back * len * 0.5f), fB.s, len, rad.cross(fB.s).unit(), 0.14f, 0.02f, 3, 6)
            }
        }
        10, 12 -> {
            // Gram-positive bacteria on the cloth and the grit: Clostridium rods (1 x 5 µm), some with a
            // swollen subterminal spore (12), and coccal clusters; free spores lie on the grit too
            if (part == 12) for ((gk, sp) in listOf(floatArrayOf(2f, 1.9f, 14f), floatArrayOf(4.8f, -2.1f, 15f), floatArrayOf(8.5f, 1.3f, 11f)).withIndex()) {
                val f = rfv(b, sp[0]); val rr = sp[2] / 2f / um
                for (q in 0 until 2) {
                    val c = f.at(sp[1] + (q - 0.5f) * 0.5f, floorUp + 0.3f + rr * 1.5f + 0.05f)
                    mb.ellipsoidAxes(c, f.d * cos(q + gk.toFloat()) + f.s * sin(q + gk.toFloat()), 0.08f, f.u, 0.06f, 0.06f, 5, 9)
                }
            }
            val on = listOf(floatArrayOf(5.75f, -1.2f, 1.45f), floatArrayOf(6.25f, 1.0f, 1.45f), floatArrayOf(2f, 1.9f, 1.55f), floatArrayOf(4.8f, -2.1f, 1.9f))
            for ((k, sp) in on.withIndex()) {
                val f = rfv(b, sp[0]); val base = f.at(sp[1], floorUp + sp[2])
                for (q in 0 until 6) {
                    val d = (f.d * cos(q * 1.1f + k) + f.s * sin(q * 1.1f + k)).unit()
                    val c = base + f.d * ((rnd.nextFloat() - 0.5f) * 1.2f) + f.s * ((rnd.nextFloat() - 0.5f) * 1.2f)
                    if (q % 3 == 2) { for (cc in 0 until 5) { val j = V3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f) * 0.22f; if (part == 10) mb.sphere(c + j, 0.5f / um, 4, 6) } }
                    else if (part == 10) mb.rod(c - d * (2.3f / um), c + d * (2.3f / um), 0.55f / um, 6)
                    else if (q == 0 || (q == 4 && k % 2 == 0)) mb.ellipsoidAxes(c + d * (2.3f / um * 0.6f), d, 0.08f, perp(d), 0.06f, 0.06f, 5, 9)
                }
            }
        }
        else -> {
            // the floor of the wound track: torn, blood-soaked connective tissue
            mb.gridFd(38, 10, { u, v ->
                val a = -6f + 19f * u; val s = (v * 2f - 1f) * 3.2f
                wAt(b, a, s, floorUp + 0.15f * sin(a * 2.1f + s * 1.7f) + 0.25f * (v * 2f - 1f).pow(2))
            }) { u, _, _ -> rfv(b, -6f + 19f * u).u }
        }
    }
    return mb.build(twoSided = part == 9 || part == 11 || part == 16 || part == 17)
}

/**
 * Stop 5 (THE TRANSFUSION, 12 µm Mote: 1 unit = 8 µm): a 32 µm vessel in skeletal muscle,
 * refilling. Its lining is a translucent sheet of endothelial cells (junctions and nuclei shown);
 * behind it the muscle fibres (cross-striated) and their capillaries turn, over forty seconds, from
 * the grey-blue of ischaemia to perfused red as the fresh cells arrive and the flow fills back up.
 */
internal fun StereoBodyRenderer.drawTransfusion(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    val um = umu(i)
    if (!openWall(i, -8f, 34f, T3_TISSUE_DARK)) return
    // The transfused blood arrives as a front: packed fresh cells behind it, the last few old,
    // dark cells of the emptied vessel ahead of it. The tissue reddens just behind the front, and
    // its collapsed capillaries open and fill with cells as it passes.
    val T = sinceArrival(i, seconds)
    // the front starts a few units ahead of the pilot and creeps on down the vessel
    val front = (3f + 0.14f * min(T, 40f) + 0.08f * max(0f, T - 40f)).coerceAtMost(22f)
    for (seg in 0 until TF_SEGS.size - 1) {
        val t = smooth01((front - TF_SEGS[seg]) / 6f)
        val fibre = mixCol(T3_ISCHAEMIC, T3_PERFUSED, t, tmpCol0)
        val band = mixCol(T3_ISCHAEMIC_A, T3_PERFUSED_A, t, tmpCol1)
        val cap = mixCol(T3_CAP_EMPTY, T3_CAPILLARY, t, tmpCol2)
        val mid = rfv(b, (TF_SEGS[seg] + TF_SEGS[seg + 1]) / 2f).c
        val d = sqrt((mid.x - camNowX).pow(2) + (mid.y - camNowY).pow(2) + (mid.z - camNowZ).pow(2))
        val lod = 1f - 0.75f * ((d - 6f) / 8f).coerceIn(0f, 1f)          // far striations fade to plain muscle
        drawMesh(cached("tf_fibres$seg") { transfusionMesh(b, um, 0, seg) }, fibre, fibre, 1f, 0f, 0.05f + 0.08f * t)
        drawMesh(cached("tf_bands$seg") { transfusionMesh(b, um, 1, seg) }, band, band, lod, 0f, 0.03f)
        val lvl = (t * 3.99f).toInt()
        drawMesh(cached("tf_caps${seg}_$lvl") { transfusionMesh(b, um, 3, seg, lvl) }, cap, cap, 1f, 0f, 0.1f + 0.25f * t)
        drawMesh(cached("tf_nuc2_$seg") { transfusionMesh(b, um, 2, seg) }, T3_MUSCLE_NUC_LOW, T3_MUSCLE_NUC_LOW, 1f, 0f, 0.05f)
        if (t > 0.9f) {
            // red cells in single file through the reopened capillaries
            val f = rfv(b, (TF_SEGS[seg] + TF_SEGS[seg + 1]) / 2f)
            val off = (seconds * 0.4f) % 1.6f
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, f.d.x * off, f.d.y * off, f.d.z * off)
            drawLitModel(cached("tf_capcells$seg") { transfusionMesh(b, um, 4, seg) }, T3_RBC, T3_RBC, landmarkFade, 0f, 0.12f)
        }
    }
    // The flood: behind the front the lumen is packed with fresh red cells (a microvascular
    // haematocrit, ~25%) in a core with a cell-free layer of plasma at the wall, drawn as short
    // prebuilt blocks of cells that advance with the front; the front itself is a denser leading
    // edge of cells turned face-on to the flow. Ahead of it, the few old dark cells left behind.
    val tileL = 1.5f
    val camA = run { val f0 = rfv(b, 0f); (V3(camNowX, camNowY, camNowZ) - f0.c).dot(f0.d) }
    val cam = V3(camNowX, camNowY, camNowZ)
    fun block(key: String, build: () -> List<Pair<V3, V3>>, a: Float, spin: Float) {
        if (a < -8f || a > 24f) return
        val f = rfv(b, a)
        val Y = f.radial(spin + 1.57f); val Z = f.d; val X = Y.cross(Z).unit()
        if (a + tileL > camA - 2.6f && a < camA + 2.6f) {
            // at the lens: the block's cells drawn one by one, any within 2.6 units of the eye left
            // out (the craft sits in a clear pocket of plasma, the column all round and ahead)
            for ((c, ax) in cachedValue("${key}_cells", build)) {
                val p = f.c + X * c.x + Y * c.y + Z * c.z
                if ((p - cam).len() < 2.6f) continue
                val axW = (X * ax.x + Y * ax.y + Z * ax.z).unit()
                drawScaled(rbc, p, perp(axW), axW, 7.5f / 2f / um, 7.5f / 2f / um, 7.5f / 2f / um, T3_RBC, T3_RBC_RIM3, 1f, 0.05f)
            }
        } else drawLocal(cached(key) { cellsMesh(cachedValue("${key}_cells", build), 7.5f / 2f / um) }, f.c, Z, Y, T3_RBC, T3_RBC_RIM3, 1f, 0.05f)
    }
    // the leading edge: a disc-shaped crowd of cells face-on to the flow, spanning the whole core
    block("tf_edge", { redCellDisc(b, um, 30, 0.5f) }, front - 0.5f, T * 0.05f)
    block("tf_lead", { redCellCells(b, um, tileL, 16f, 99, true) }, front - 0.5f - tileL, T * 0.05f)
    var k = 1
    while (front - 0.5f - tileL * (k + 1) > -8f - tileL) { block("tf_b${k % 6}", { redCellCells(b, um, tileL, 8f, 40 + k % 6, false) }, front - 0.5f - tileL * (k + 1), k * 1.7f + T * 0.05f); k++ }
    val rr = 7.5f / 2f / um
    val R = radiusAt(b, 0f) * 0.97f
    for (q in 0 until 8) {
        val h = ((q * 7919) % 1000) / 1000f
        val a = front + 1.5f + ((h * 20f + seconds * 0.8f) % 20f)
        if (a > 24f) continue
        val f = rfv(b, a); val th = q * 2.399f
        val p = f.pol(th, sqrt(h) * (R - 0.35f - rr))
        if ((p.x - camNowX).pow(2) + (p.y - camNowY).pow(2) + (p.z - camNowZ).pow(2) < 6.25f) continue
        val tb = seconds * (0.3f + 0.5f * h) + q
        val nrm = (f.radial(th + tb * 0.3f) * 0.6f + (f.d * cos(tb) + f.radial(th + 1.57f) * sin(tb)) * 0.4f).unit()
        drawScaled(rbc, p, perp(nrm), nrm, rr, rr, rr, T3_RBC_DEOXY, T3_RBC_RIM3, 1f, 0.05f)
    }
    // The vessel's own wall: endothelium, and outside it one layer of circumferential smooth
    // muscle cells (a terminal arteriole), each with its elongated nucleus.
    drawMesh(cached("tf_smcnuc2") { smoothMuscleCoat(b, um, true) }, T3_MUSCLE_NUCLEUS, T3_MUSCLE_NUCLEUS, 1f, 0f, 0.1f)
    val (tube, junc, nuc) = endotheliumMeshes("tf_endo", b, -8f, 24f, { a -> radiusAt(b, a) * 0.97f }, 30f / um, 10)
    drawMesh(cached("tf_smc2") { smoothMuscleCoat(b, um, false) }, T3_SMC, T3_SMC, 1f, 0f, 0.1f)
    drawMesh(junc, T3_JUNCTION, T3_JUNCTION, 0.45f, 0f, 0.08f)
    GLES20.glDepthMask(false)
    drawMesh(tube, T3_ENDOTHELIUM, T3_JUNCTION, 0.22f, 0f, 0.15f)
    GLES20.glDepthMask(true)
    // the endothelial nuclei, flat in the wall, barely brighter than the lining they belong to
    GLES20.glDepthMask(false)
    drawMesh(nuc, T3_ENDO_NUC_PALE, T3_ENDOTHELIUM, 0.4f, 0f, 0f)
    GLES20.glDepthMask(true)
}

/**
 * A block of red cells filling [len] units of the vessel's core (local z along the vessel, centred
 * at z = len/2), [perUnit] cells per unit length, a cell-free layer at the wall; [front]: denser
 * toward the leading end and turned face-on to the flow.
 */
private fun StereoBodyRenderer.redCellBlock(b: Float, um: Float, len: Float, perUnit: Float, seed: Int, front: Boolean,
                                            wallFrac: Float = 0.97f, rouleaux: Int = 0): T3Mesh =
    cellsMesh(redCellCells(b, um, len, perUnit, seed, front, wallFrac, rouleaux), 7.5f / 2f / um)

private fun cellsMesh(cells: List<Pair<V3, V3>>, rr: Float): T3Mesh { val mb = Mb(); for ((c, ax) in cells) mb.redCell(c, ax, rr, 4, 12); return mb.build() }

/** The cells of a block (see [redCellBlock]): centre and disc axis, in the block's local frame. */
private fun StereoBodyRenderer.redCellCells(b: Float, um: Float, len: Float, perUnit: Float, seed: Int, front: Boolean,
                                            wallFrac: Float = 0.97f, rouleaux: Int = 0): List<Pair<V3, V3>> {
    val out = ArrayList<Pair<V3, V3>>()
    val rnd = java.util.Random(seed.toLong())
    val rr = 7.5f / 2f / um
    val R = (radiusAt(b, 0f) * wallFrac - 0.35f) * 0.95f - rr * 0.3f
    val n = (len * perUnit).toInt()
    val placed = ArrayList<V3>()
    for (q in 0 until rouleaux) {
        // a short rouleau (4-6 cells face to face) in the slow centre stream
        val c = V3((rnd.nextFloat() - 0.5f) * R * 0.8f, (rnd.nextFloat() - 0.5f) * R * 0.8f, len * (q + 0.5f) / rouleaux)
        val ax = V3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).unit()
        val nc = 4 + rnd.nextInt(3)
        for (j in 0 until nc) { val p = c + ax * ((j - (nc - 1) / 2f) * 0.3f); out.add(p to ax); placed.add(p) }
    }
    var tries = 0
    while (placed.size < n && tries < n * 30) {
        tries++
        val z = if (front) len * sqrt(rnd.nextFloat()) else rnd.nextFloat() * len
        val rho = R * sqrt(rnd.nextFloat()); val th = rnd.nextFloat() * 2f * PI.toFloat()
        val c = V3(cos(th) * rho, sin(th) * rho, z)
        if (placed.any { (it - c).len() < rr * 0.95f }) continue
        placed.add(c)
        val ax = if (front && z > len * 0.2f) (V3(0f, 0f, 1f) + V3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, 0f) * 0.5f).unit()
                 else V3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).unit()
        out.add(c to ax)
    }
    return out
}

/** A disc-shaped crowd of [n] cells [thick] units deep, face-on to the flow, across the whole core. */
private fun StereoBodyRenderer.redCellDisc(b: Float, um: Float, n: Int, thick: Float): List<Pair<V3, V3>> {
    val rnd = java.util.Random(7)
    val rr = 7.5f / 2f / um
    val R = (radiusAt(b, 0f) * 0.97f - 0.35f) * 0.95f
    val out = ArrayList<Pair<V3, V3>>()
    for (q in 0 until n) {
        val rho = (R - rr * 0.5f) * sqrt((q + 0.5f) / n); val th = q * 2.39996f
        val ax = (V3(0f, 0f, 1f) + V3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, 0f) * 0.4f).unit()
        out.add(V3(cos(th) * rho, sin(th) * rho, thick * rnd.nextFloat()) to ax)
    }
    return out
}

/** Stretches of the vessel that reperfuse one after another. */
private val TF_SEGS = floatArrayOf(-8f, -1f, 6f, 14f, 24f)

/** Smooth muscle wrapped round the vessel: spindle cells (as arcs) or their nuclei. */
private fun StereoBodyRenderer.smoothMuscleCoat(b: Float, um: Float, nuclei: Boolean): T3Mesh {
    // One layer of spindle cells wound helically round the terminal arteriole (pitch 0.9 units), each
    // wrapping ~400°, flattened against the wall and tapering at both ends, a new one every 0.7 units
    // so neighbours overlap; the layer of a terminal arteriole is thin and in places discontinuous,
    // so the reperfusing muscle can be seen between them.
    val mb = Mb()
    var k = 0
    var a0 = -8f
    while (a0 < 23.5f) {
        val th0 = k * 137.5f * DEG
        val sweep = 400f * DEG
        val advance = 0.9f * sweep / (2f * PI.toFloat())
        fun centre(t: Float): Pair<V3, RF> { val a = a0 + advance * t; val f = rfv(b, a); return f.pol(th0 + sweep * t, radiusAt(b, a) * 1.03f) to f }
        if (!nuclei) {
            mb.gridFd(40, 8, { u, v ->
                val (c, f) = centre(u)
                val rad = f.radial(th0 + sweep * u)
                val taper = sin(u * PI.toFloat()).pow(0.7f) * 0.92f + 0.08f
                val q = v * 2f * PI.toFloat()
                c + rad * (0.05f * taper * sin(q)) + f.d * (0.25f * taper * cos(q))
            }) { u, _, p -> p - rfv(b, a0 + advance * u).c }
        } else {
            val (c, f) = centre(0.5f)
            val rad = f.radial(th0 + sweep * 0.5f); val tg = f.d.cross(rad).unit()
            mb.ellipsoidAxes(c + rad * 0.02f, tg, 0.3f, rad, 0.04f, 0.1f, 6, 14)
        }
        a0 += 0.7f; k++
    }
    return mb.build()
}

private val tmpCol0 = FloatArray(4)
private val tmpCol1 = FloatArray(4)
private val tmpCol2 = FloatArray(4)
private val tmpCol3 = FloatArray(4)
private fun mixCol(a: FloatArray, c: FloatArray, t: Float, out: FloatArray): FloatArray {
    for (k in 0 until 4) out[k] = a[k] + (c[k] - a[k]) * t
    return out
}

/** Muscle around the vessel, one stretch [seg] of it: fibres (0), their A bands (1), nuclei (2), capillaries (3). */
private fun StereoBodyRenderer.transfusionMesh(b: Float, um: Float, part: Int, seg: Int, lvl: Int = 3): T3Mesh {
    val s0 = TF_SEGS[seg]; val s1 = TF_SEGS[seg + 1]
    val mb = Mb()
    val sarc = 2.5f / um
    val rf = 50f / 2f / um
    val angles = floatArrayOf(40f, 140f, 220f, 320f)
    for ((k, deg) in angles.withIndex()) {
        val th = deg * DEG
        fun axisAt(a: Float): V3 = rfv(b, a).pol(th, radiusAt(b, a) + 0.55f + rf)
        when (part) {
            0, 1 -> mb.striatedFibre(axisAt(s0), axisAt(s1), rf, sarc, part == 1)
            2 -> for (q in 0 until 12) {
                val a = s0 + 1f + q * 2.6f + k
                if (a > s1 - 0.5f) break
                val f = rfv(b, a)
                val inward = -f.radial(th)
                mb.ellipsoidAxes(axisAt(a) + inward * (rf * 0.99f - 0.12f), f.d, 5f / um, inward, 0.08f, 0.3f, 8, 16)
            }
            3 -> {
                // capillaries in the clefts between fibres, running with them: collapsed and empty in
                // ischaemia (lvl 0), opening to their full 7 µm as blood returns (lvl 3)
                val thc = th + 50f * DEG
                val n = (s1 - s0).toInt()
                val pts = (0..n).map { q -> val a = s0 + q; rfv(b, a).pol(thc + 0.08f * sin(a * 0.9f + k), radiusAt(b, a) + 0.6f) }
                val cr = floatArrayOf(0.25f, 0.31f, 0.37f, 7f / 2f / um)[lvl]
                mb.tube(pts, FloatArray(n + 1) { cr }, 12, true)
            }
            else -> {
                // red cells in single file in each capillary, folded edge-on to fit
                val thc = th + 50f * DEG
                var a = s0 + 0.3f
                while (a < s1 - 0.3f) {
                    val f = rfv(b, a)
                    val c = f.pol(thc + 0.08f * sin(a * 0.9f + k), radiusAt(b, a) + 0.6f)
                    mb.redCell(c, f.radial(thc).cross(f.d).unit(), 3.2f / um, 4, 12)
                    a += 1.6f
                }
            }
        }
    }
    return mb.build()
}

/**
 * Stop 6 (THE TABLE, 12 mm Mote: 1 unit = 8 mm): a war wound handled properly — debrided and left
 * open, not one stitch in it, packed with fluffed dry gauze, to be closed at four or five days if
 * it stays clean. The craft hangs just above the skin, looking along the gaping cut: excised as an
 * ellipse ~27 cm long that tapers to a point in intact skin at each end, ~4 cm across at the skin
 * and ~3 cm deep. Its faces are uneven where dead tissue was cut away and show the layers in
 * section: the thin epidermis on a pale dermis at the lip, a continuous glistening face of lobulated
 * yellow subcutaneous fat (larger lobules above, smaller below, fine septa, a few cut vessels), the
 * deep fascia — opened along the whole wound to decompress the muscle, its white cut edge set back
 * as a step in each wall — and red muscle down into the wound bed. The debrided surfaces are clean,
 * with pinpoint bleeding. Three crumpled layers of open-weave gauze fill the depths, stained where
 * they wick blood from the tissue.
 */
internal fun StereoBodyRenderer.drawSuture(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    if (!openWall(i, -8f, 34f, T3_BLACK)) return
    // Viable, bleeding muscle: bare in the near end of the wound, the gauze lifted from it.
    drawMesh(cached("tb_muscle") { tableMesh(b, 0) }, T3_MUSCLE_VIABLE, T3_MUSCLE_VIABLE, 1f, 0f, 0.2f)
    drawMesh(cached("tb_mlines") { tableMesh(b, 1) }, T3_MUSCLE_LINE, T3_MUSCLE_LINE, 1f, 0f, 0.05f)
    drawMesh(cached("tb_membranous") { tableMesh(b, 13) }, T3_FASCIA, T3_FASCIA, 1f, 0f, 0.3f)
    drawMesh(cached("tb_fascia") { tableMesh(b, 2) }, T3_FASCIA, T3_FASCIA, 1f, 0f, 0.35f)
    drawMesh(cached("tb_fatface") { tableMesh(b, 3) }, T3_FAT_FACE, T3_FAT_FACE, 1f, 0f, 0.15f)
    drawMesh(cached("tb_fat") { tableMesh(b, 10) }, T3_FAT, T3_FAT_FACE, 1f, 0f, 0.18f)
    drawMesh(cached("tb_septa2") { tableMesh(b, 11) }, T3_SEPTA, T3_SEPTA, 1f, 0f, 0.15f)
    drawMesh(cached("tb_vessels2") { tableMesh(b, 12) }, T3_CUT_VESSEL, T3_CUT_VESSEL, 1f, 0f, 0.1f)
    drawMesh(cached("tb_dermis") { tableMesh(b, 4) }, T3_DERMIS, T3_DERMIS, 1f, 0f, 0.15f)
    drawMesh(cached("tb_epi") { tableMesh(b, 5) }, T3_SKIN_EDGE, T3_SKIN_EDGE, 1f, 0f, 0.1f)
    drawMesh(cached("tb_skin") { tableMesh(b, 6) }, T3_SKIN_TOP, T3_SKIN_TOP, 1f, 0f, 0.12f)
    // Pinpoint bleeding from the debrided faces: tissue that bleeds is tissue that lives.
    drawMesh(cached("tb_bleed2") { tableMesh(b, 9) }, T3_BLEED, T3_BLEED, 1f, 0f, 0.35f + 0.15f * sin(seconds * 1.1f))
    // The gauze: fine woven threads (lines), white where dry, stained where they touch tissue.
    val gauze = cachedLines("tb_gauze") { gauzeLines(b) }
    Matrix.setIdentityM(model, 0)
    Matrix.multiplyMM(mv, 0, view, 0, model, 0)
    Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    colorShader.use(mvp, 1f)
    lineWidth(1.5f)
    gauze.draw(colorShader.positionHandle, colorShader.colorHandle)
    lineWidth(1f)
}

private val tableLines = HashMap<String, LineMesh>()
private var tableLinesOwner: Any? = null
private fun StereoBodyRenderer.cachedLines(key: String, build: () -> FloatArray): LineMesh {
    if (tableLinesOwner !== sphere) { tableLines.clear(); tableLinesOwner = sphere }
    return tableLines.getOrPut(key) { LineMesh(build()) }
}

private const val SKIN_UP = -0.9f
private const val EPI_UP = SKIN_UP - 0.02f
private const val FAT_TOP = SKIN_UP - 0.28f
private const val FASCIA_UP = -2.9f
/** The membranous layer of the superficial fascia, splitting the fat into two layers. */
private const val MEMBRANOUS_UP = FAT_TOP - 0.75f
private const val FASCIA_HALF = 0.11f
private const val WOUND_FLOOR = -4.6f
private const val WOUND_A0 = -8f
private const val WOUND_A1 = 26f

/** The elliptical excision: 1 at the middle of the wound, 0 at its two pointed ends. */
private fun woundTaper(a: Float): Float { val x = (a - (WOUND_A0 + WOUND_A1) / 2f) / ((WOUND_A1 - WOUND_A0) / 2f); return sqrt(max(0f, 1f - x * x)) }
/** The wound bed rises to the skin at the ends. */
private fun woundFloor(a: Float) = SKIN_UP + (WOUND_FLOOR - SKIN_UP) * sqrt(woundTaper(a))
/**
 * Half-width of the wound at [a], height [up]: V-shaped (wider at the skin), tapered to the ends,
 * uneven where debrided, and stepped back where the deep fascia has been opened.
 */
private fun woundHalf(a: Float, up: Float, fasciaStep: Boolean = false): Float {
    val t = woundTaper(a)
    val v = 1.7f + 1.0f * (up - WOUND_FLOOR) / (SKIN_UP - WOUND_FLOOR)
    val rough = if (up < FAT_TOP) 0.08f * sin(1.7f * a + 2.3f * up) + 0.05f * sin(4.1f * a - 3.7f * up) else 0f
    return (v + rough + (if (fasciaStep) 0.35f else 0f)) * t
}

/** Three crumpled sheets of open-weave gauze (1 mm weave) loosely filling the depths, as coloured lines. */
private fun StereoBodyRenderer.gauzeLines(b: Float): FloatArray {
    val out = ArrayList<Float>(200000)
    val a0 = 6f; val a1 = 20f
    fun sheet(k: Int, a: Float, w: Float): V3 {
        val base = woundFloor(a) + 0.25f + 0.5f * k
        var up = base + 0.35f * sin(a * 0.9f + k * 1.7f) * cos(w * 3f + k) + 0.12f * sin(a * 2.3f - w * 5f + k)
        up = up.coerceIn(woundFloor(a) + 0.06f, FASCIA_UP + 0.5f)
        val half = woundHalf(a, up) - 0.06f
        return wAt(b, a, w * half, up)
    }
    fun wet(p: V3, a: Float): Boolean {
        val f = rfv(b, a); val rel = p - f.c
        val up = rel.dot(f.u); val sd = abs(rel.dot(f.s))
        return up < woundFloor(a) + 0.2f || sd > woundHalf(a, up) - 0.22f
    }
    fun seg(p: V3, q: V3, wetp: Boolean) {
        val c = if (wetp) T3_GAUZE_WET else T3_GAUZE
        for (v in arrayOf(p, q)) { out.add(v.x); out.add(v.y); out.add(v.z); out.add(c[0]); out.add(c[1]); out.add(c[2]); out.add(0.9f) }
    }
    val pitch = 0.12f
    for (k in 0 until 3) {
        var a = a0
        while (a <= a1) {
            var prev = sheet(k, a, -1f); var w = -1f
            while (w < 1f) { val w2 = w + 0.1f; val q = sheet(k, a, w2); seg(prev, q, wet(q, a)); prev = q; w = w2 }
            a += pitch
        }
        val half = woundHalf(9f, WOUND_FLOOR + 0.5f)
        var w = -1f
        while (w <= 1f) {
            var prev = sheet(k, a0, w); var aa = a0
            while (aa < a1) { val a2 = aa + 0.25f; val q = sheet(k, a2, w); seg(prev, q, wet(q, a2)); prev = q; aa = a2 }
            w += pitch / half
        }
    }
    return out.toFloatArray()
}

/**
 * The wound's layers: muscle faces and bed (0), fascicle lines (1), the opened fascia (2), the fat's
 * cut face (3), dermis (4), epidermis (5), skin surface (6), bleeding points (9), fat lobules (10),
 * fat septa (11), cut vessels (12).
 */
private fun StereoBodyRenderer.tableMesh(b: Float, part: Int): T3Mesh {
    val mb = Mb()
    val rnd = java.util.Random(71L + part)
    val a0 = WOUND_A0; val a1 = WOUND_A1
    val stA = 90
    // A cut face between two heights on both sides (clipped by the rising floor at the ends).
    fun face(u0: Float, u1: Float, stU: Int = 4, step: Boolean = false) {
        for (sg in floatArrayOf(-1f, 1f)) mb.gridFd(stA, stU, { u, v ->
            val a = a0 + (a1 - a0) * u; val fl = woundFloor(a)
            val up = max(u0, fl) + (max(u1, fl) - max(u0, fl)) * v
            wAt(b, a, sg * woundHalf(a, up, step), up)
        }) { u, _, _ -> rfv(b, a0 + (a1 - a0) * u).s * (-sg) }
    }
    // A horizontal ledge at [up] between the wall and the recessed fascia.
    fun ledge(up: Float) {
        for (sg in floatArrayOf(-1f, 1f)) mb.gridFd(stA, 1, { u, v ->
            val a = a0 + (a1 - a0) * u; val uu = max(up, woundFloor(a))
            wAt(b, a, sg * (woundHalf(a, uu) + (woundHalf(a, uu, true) - woundHalf(a, uu)) * v), uu)
        }) { u, _, _ -> rfv(b, a0 + (a1 - a0) * u).u * (if (up > FASCIA_UP) -1f else 1f) }
    }
    // Fat lobules on the cut face: (along, up, half-width along, half-height, side sign). Above the
    // membranous layer one row of large upright lobules; below it smaller, flatter ones in offset rows.
    fun lobules(): List<FloatArray> {
        val lr = java.util.Random(13)
        val list = ArrayList<FloatArray>()
        for (sg in floatArrayOf(-1f, 1f)) {
            val upS = (FAT_TOP + MEMBRANOUS_UP) / 2f
            var a = a0 + 0.3f
            while (a < a1) {
                if (upS - 0.32f > woundFloor(a) && woundTaper(a) > 0.3f) list.add(floatArrayOf(a + (lr.nextFloat() - 0.5f) * 0.06f, upS + (lr.nextFloat() - 0.5f) * 0.05f, 0.24f, 0.32f, sg))
                a += 0.5f
            }
            var row = 0
            var up = MEMBRANOUS_UP - 0.22f
            while (up > FASCIA_UP + FASCIA_HALF + 0.18f) {
                var a2 = a0 + (if (row % 2 == 0) 0f else 0.31f)
                while (a2 < a1) {
                    if (up - 0.16f > woundFloor(a2) && woundTaper(a2) > 0.3f) list.add(floatArrayOf(a2 + (lr.nextFloat() - 0.5f) * 0.08f, up + (lr.nextFloat() - 0.5f) * 0.04f, 0.30f, 0.16f, sg))
                    a2 += 0.62f
                }
                up -= 0.36f; row++
            }
        }
        return list
    }
    when (part) {
        0 -> {
            face(WOUND_FLOOR, FASCIA_UP - FASCIA_HALF)
            // the wound bed: muscle, gently curved, rising to the skin at the pointed ends
            mb.gridFd(stA, 8, { u, v ->
                val a = a0 + (a1 - a0) * u; val fl = woundFloor(a); val w = v * 2f - 1f
                wAt(b, a, w * woundHalf(a, fl), fl - 0.25f * woundTaper(a) * (1f - w * w))
            }) { u, _, _ -> rfv(b, a0 + (a1 - a0) * u).u }
        }
        1 -> for (sg in floatArrayOf(-1f, 1f)) for (k in 0 until 5) {
            // the grain of the muscle: fascicles running along the face
            val up = WOUND_FLOOR + 0.2f + k * 0.3f
            val pts = ArrayList<V3>()
            var a = a0
            while (a <= a1) { if (up > woundFloor(a) + 0.05f) pts.add(wAt(b, a, sg * (woundHalf(a, up) - 0.015f), up + 0.1f * sin(a * 0.8f + k))) else if (pts.size > 1) break; a += 0.5f }
            if (pts.size > 1) mb.tube(pts, FloatArray(pts.size) { 0.018f }, 4, false)
        }
        2 -> {
            // the opened deep fascia: its white cut edge recessed as a step in each wall
            face(FASCIA_UP - FASCIA_HALF, FASCIA_UP + FASCIA_HALF, 1, true)
            ledge(FASCIA_UP + FASCIA_HALF); ledge(FASCIA_UP - FASCIA_HALF)
        }
        3 -> face(FASCIA_UP + FASCIA_HALF, FAT_TOP - 0.02f, 6)
        10 -> for (l in lobules()) {
            // each lobule bulges only a little from the cut face
            val f = rfv(b, l[0]); val sg = l[4]
            val c = f.at(sg * (woundHalf(l[0], l[1]) + 0.2f), l[1])
            mb.ellipsoid(c, f.s * 0.3f, f.d * (l[2] * 0.96f), f.u * (l[3] * 0.96f), 6, 12)
        }
        11, 12 -> {
            // interlobular septa: each lobule outlined in the face plane, the outlines meeting as a
            // septal net (11); small vessels running in the septa, cut across (12)
            val vr = java.util.Random(5)
            for ((li, l) in lobules().withIndex()) {
                val f = rfv(b, l[0]); val sg = l[4]
                val pts = (0..16).map { q ->
                    val t = q * 2f * PI.toFloat() / 16f; val j = 1f + 0.06f * sin(q * 2.3f + li * 1.7f)
                    val a = l[0] + cos(t) * l[2] * 1.02f * j; val up = l[1] + sin(t) * l[3] * 1.02f * j
                    wAt(b, a, sg * (woundHalf(a, up) - 0.04f), up)
                }
                if (part == 11) mb.tube(pts, FloatArray(17) { 0.014f }, 4, false)
                else if (vr.nextFloat() < 0.07f) {
                    val q = vr.nextInt(16)
                    mb.torus(pts[q], f.s, 0.08f, 0.025f, 16, 6)
                }
            }
        }
        13 -> for (sg in floatArrayOf(-1f, 1f)) {
            // the membranous (Scarpa's) layer dividing the fat: a continuous fine white sheet in section
            val pts = ArrayList<V3>()
            var a = a0
            while (a <= a1) { if (MEMBRANOUS_UP > woundFloor(a) + 0.02f) pts.add(wAt(b, a, sg * (woundHalf(a, MEMBRANOUS_UP) - 0.03f), MEMBRANOUS_UP)); a += 0.4f }
            if (pts.size > 1) mb.tube(pts, FloatArray(pts.size) { 0.02f }, 5, false)
        }
        4 -> face(FAT_TOP - 0.02f, EPI_UP, 1)
        5 -> face(EPI_UP - 0.005f, SKIN_UP + 0.012f, 1)
        6 -> for (sg in floatArrayOf(-1f, 1f)) mb.gridFd(stA, 8, { u, v ->
            // the skin surface either side of the cut, falling away at its outer edge
            val a = a0 - 3f + (a1 - a0 + 6f) * u
            wAt(b, a, sg * (woundHalf(a, SKIN_UP) + v * 9f), SKIN_UP + 0.012f - 0.6f * v * v)
        }) { u, _, _ -> rfv(b, a0 - 3f + (a1 - a0 + 6f) * u).u }
        else -> {
            for (k in 0 until 30) {
                val a = 0f + rnd.nextFloat() * 18f
                val sg = if (k % 2 == 0) -1f else 1f
                val up = woundFloor(a) + 0.3f + rnd.nextFloat() * (FAT_TOP - woundFloor(a) - 0.5f)
                if (abs(up - FASCIA_UP) < FASCIA_HALF + 0.05f) continue
                val f = rfv(b, a)
                mb.ellipsoid(f.at(sg * (woundHalf(a, up) - 0.02f), up), f.s * 0.05f, f.d * 0.07f, f.u * 0.09f, 5, 9)
            }
            // concentrated on the bare, debrided muscle near the craft: tissue that bleeds is alive
            for (k in 0 until 60) {
                val a = -6f + rnd.nextFloat() * 12f
                val f = rfv(b, a); val fl = woundFloor(a); val r = 0.05f + 0.04f * rnd.nextFloat()
                if (k % 3 == 0) {
                    val w = rnd.nextFloat() * 1.6f - 0.8f
                    mb.ellipsoid(f.at(w * woundHalf(a, fl), fl - 0.25f * woundTaper(a) * (1f - w * w) + 0.02f), f.u * (r * 0.6f), f.d * r, f.s * r, 5, 9)
                } else {
                    val sg = if (k % 2 == 0) -1f else 1f
                    val up = fl + 0.1f + rnd.nextFloat() * (FASCIA_UP - FASCIA_HALF - fl - 0.2f).coerceAtLeast(0.05f)
                    mb.ellipsoid(f.at(sg * (woundHalf(a, up) - 0.02f), up), f.s * (r * 0.6f), f.d * r, f.u * (r * 1.2f), 5, 9)
                }
            }
        }
    }
    return mb.build(twoSided = part in intArrayOf(0, 2, 3, 4, 5, 6))
}

/**
 * Stop 7 (THE STUDENTS, 12 µm Mote: 1 unit = 8 µm): red marrow, the busiest room in the body. The
 * passage is a marrow sinusoid ~50 µm across, lined by a thin, translucent endothelium. Pressed
 * against it from outside sit megakaryocytes, 55-65 µm giants whose single polyploid nucleus is
 * lobed and folded; one pushes beaded proplatelet arms through the sinus wall into the current, and
 * the beads break off as platelets and are carried away. Around them in the cords: an erythroblastic
 * island (a macrophage ringed by erythroblasts maturing from blue to pink, one extruding its
 * nucleus), a reticulocyte squeezing through the wall, developing granulocytes with band nuclei, fat
 * cells, and below, a trabecula of bone lined by osteoblasts with a stem cell dividing in its niche.
 */
internal fun StereoBodyRenderer.drawStudents(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    val um = umu(i)
    if (!openWall(i, -8f, 34f, T3_CORD)) return
    drawMesh(cached("mw_bone") { marrowMesh(b, um, 0) }, T3_BONE, T3_BONE, 1f, 0f, 0.2f)
    drawMesh(cached("mw_lining") { marrowMesh(b, um, 1) }, T3_LINING, T3_LINING, 1f, 0f, 0f)
    drawMesh(cached("mw_osteoid") { marrowMesh(b, um, 15) }, T3_OSTEOID, T3_OSTEOID, 1f, 0f, 0.15f)
    drawMesh(cached("mw_obl") { marrowMesh(b, um, 16) }, T3_OSTEOBLAST, T3_OSTEOBLAST, 1f, 0f, 0.15f)
    // Every cell of the cords is opaque and cut through on the side facing the sinus, so its
    // nucleus (and granules) show on the section the way they do on a slide.
    // the megakaryocyte's cytoplasm solid and a shade deeper than its cut face, which covers most
    // of its silhouette
    drawMesh(cached("mw_mk2") { marrowMesh(b, um, 14) }, T3_MEGA_BODY, T3_MEGA_RIM, 1f, 0f, 0.1f)
    drawMesh(cached("mw_mkface") { marrowMesh(b, um, 22) }, T3_MEGA, T3_MEGA, 1f, 0f, 0.12f)
    drawMesh(cached("mw_mknuc2") { marrowMesh(b, um, 2) }, T3_MEGA_NUCLEUS, T3_MEGA_NUCLEUS, 1f, 0f, 0.2f)
    drawMesh(cached("mw_mkgran2") { marrowMesh(b, um, 3) }, T3_MEGA_GRANULE, T3_MEGA_GRANULE, 1f, 0f, 0.3f)
    drawMesh(cached("mw_arms2") { marrowMesh(b, um, 4) }, T3_MEGA, T3_PLATELET_LILAC, 1f, 0f, 0.3f)
    drawMesh(cached("mw_pores2") { marrowMesh(b, um, 20) }, T3_PORE, T3_PORE, 1f, 0f, 0.15f)
    drawMesh(cached("mw_vacuoles") { marrowMesh(b, um, 21) }, T3_VACUOLE, T3_VACUOLE, 1f, 0f, 0.15f)
    drawMesh(cached("mw_mac") { marrowMesh(b, um, 5) }, T3_MACRO_PINK, T3_MACRO_PINK, 1f, 0f, 0.12f)
    drawMesh(cached("mw_macnuc") { marrowMesh(b, um, 17) }, T3_EB_NUCLEUS, T3_EB_NUCLEUS, 1f, 0f, 0.15f)
    drawMesh(cached("mw_eb0") { marrowMesh(b, um, 6) }, T3_EB_EARLY, T3_EB_EARLY, 1f, 0f, 0.15f)
    drawMesh(cached("mw_eb1") { marrowMesh(b, um, 7) }, T3_EB_MID, T3_EB_MID, 1f, 0f, 0.15f)
    drawMesh(cached("mw_eb2") { marrowMesh(b, um, 8) }, T3_EB_LATE, T3_EB_LATE, 1f, 0f, 0.15f)
    drawMesh(cached("mw_ebnuc") { marrowMesh(b, um, 9) }, T3_EB_NUCLEUS, T3_EB_NUCLEUS, 1f, 0f, 0.2f)
    drawMesh(cached("mw_retic") { marrowMesh(b, um, 10) }, T3_RETICULOCYTE, T3_RETICULOCYTE, 1f, 0f, 0.2f)
    drawMesh(cached("mw_myelo") { marrowMesh(b, um, 11) }, T3_MYELOCYTE, T3_MYELOCYTE, 1f, 0f, 0.1f)
    drawMesh(cached("mw_mynuc") { marrowMesh(b, um, 12) }, T3_GRAN_NUCLEUS, T3_GRAN_NUCLEUS, 1f, 0f, 0.2f)
    drawMesh(cached("mw_mygran") { marrowMesh(b, um, 18) }, T3_MYELO_GRANULE, T3_MYELO_GRANULE, 1f, 0f, 0.2f)
    drawMesh(cached("mw_fatrim2") { marrowMesh(b, um, 19) }, T3_ADIPOCYTE, T3_ADIPOCYTE, 1f, 0f, 0.3f)
    // A haematopoietic stem cell dividing in its niche on the bone.
    val cyc = ((seconds / 24f) % 1f)
    val sep = smooth01((cyc - 0.35f) / 0.45f) * 0.55f
    val fs = rfv(b, 5f); val hscR = 8f / 2f / um
    for (sg in SIGNS) {
        val c = fs.pol(262f * DEG, marrowBoneR(b) - 0.95f - hscR) + fs.d * (sg * (hscR * 0.55f + sep))
        drawSphereAt(c.x, c.y, c.z, hscR, hscR * (1f - 0.1f * sep), hscR, T3_STEM, T3_STEM, 1f, 0f, 0f, 1f, 0f, sphere, 0f, 0.3f)
    }
    // Platelets breaking off the tips of the proplatelet arms and carried away by the current.
    val tips = cachedValue("mw_tips2") { (0 until 3).map { mkArmPath(b, it).last() } }
    for (arm in 0 until 3) {
        val t = ((seconds / 3.2f + arm * 0.33f) % 1f)
        val f = rfv(b, 3f + arm * 1.4f)
        val p = tips[arm] + f.d * (t * 4.5f) - f.radial(150f * DEG) * (t * 0.3f)
        val pr = 2.8f / 2f / um
        drawScaled(blob, p, f.d, f.u, pr, pr * 0.35f, pr * 0.85f, T3_PLATELET_LILAC, T3_PLATELET_LILAC, 1f - t * t, 0.15f)
    }
    // The endothelium and the megakaryocytes' translucent cytoplasm and fat cells, drawn last.
    val (tube, junc, nuc) = endotheliumMeshes("mw_endo", b, -8f, 24f, { a -> radiusAt(b, a) * 0.97f }, 28f / um, 12)
    drawMesh(nuc, T3_ENDO_NUCLEUS, T3_ENDOTHELIUM, 0.5f, 0f, 0.05f)
    drawMesh(junc, T3_JUNCTION, T3_JUNCTION, 0.45f, 0f, 0.08f)
    GLES20.glDepthMask(false)
    // fat cells: one big clear lipid droplet each (genuinely see-through), with a crisp rim
    drawMesh(cached("mw_fat2") { marrowMesh(b, um, 13) }, T3_ADIPOCYTE, T3_ADIPOCYTE, 0.25f, 0f, 0.2f)
    drawMesh(tube, T3_ENDOTHELIUM, T3_JUNCTION, 0.10f, 0f, 0.1f)
    GLES20.glDepthMask(true)
}

private fun StereoBodyRenderer.marrowBoneR(b: Float) = tunnelRadius(b) + 3.6f

/** Megakaryocytes: (along, angle°, diameter µm, lobes). */
private val MK_SPOTS = listOf(floatArrayOf(3.5f, 175f, 64f, 8f), floatArrayOf(11f, 318f, 54f, 5f))

private fun StereoBodyRenderer.mkCentre(b: Float, um: Float, k: Int): V3 {
    val s = MK_SPOTS[k]; val r = s[2] / 2f / um
    return rfv(b, s[0]).pol(s[1] * DEG, radiusAt(b, s[0]) + r + 0.15f)      // abluminal, in the cord
}

/** Proplatelet arm [arm] of the first megakaryocyte as a path: through the wall, then bending downstream. */
private fun StereoBodyRenderer.mkArmPath(b: Float, arm: Int): List<V3> {
    val s = MK_SPOTS[0]
    val a0 = s[0] - 1.2f + arm * 1.3f
    val th = (s[1] + (arm - 1) * 16f) * DEG
    // from the margin of the megakaryocyte's cut face nearest the wall, across the cord to the pore,
    // through it, and on downstream in the current
    val um = umu(b.toInt())
    val c = mkCentre(b, um, 0); val r = s[2] / 2f / um
    val n = (rfv(b, s[0]).c - c).unit()
    val (fc, fr) = faceOf(c, n, r, 1f, MK_CUT)
    val pore = rfv(b, a0).pol(th, radiusAt(b, a0))
    val toPore = (pore - fc).let { it - n * it.dot(n) }.unit()
    val start = fc + toPore * (fr * 0.75f) + n * 0.03f
    val lumen = (0..12).map { q ->
        val t = q / 12f
        val a = a0 + t * t * 5.5f
        rfv(b, a).pol(th + 0.15f * sin(t * 4f + arm), radiusAt(b, a) - t * 1.25f)
    }
    val lead = (0..3).map { q -> lerpV(start, lumen[0], q / 4f) }
    return lead + lumen
}

/** The erythroblastic island: its macrophage's centre, facing the sinus. */
private fun StereoBodyRenderer.islandFrame(b: Float): Triple<V3, V3, RF> {
    val ebA = 8f; val ebTh = 110f * DEG
    val f = rfv(b, ebA)
    val c = f.pol(ebTh, radiusAt(b, ebA) + 2.2f)
    return Triple(c, (f.c - c).unit(), f)
}

/** Marrow parts (see [drawStudents]). */
private fun StereoBodyRenderer.marrowMesh(b: Float, um: Float, part: Int): T3Mesh {
    val mb = Mb()
    val rnd = java.util.Random(83L + part)
    val boneR = marrowBoneR(b)
    val (ebC, ebN, ebF) = islandFrame(b)
    // the island's ring, maturing clockwise: proerythroblast, basophilic, polychromatic, orthochromatic
    val ebDiam = floatArrayOf(16f, 13f, 13f, 10f, 10f, 8f, 8f, 8f)
    val ebStage = intArrayOf(0, 0, 1, 1, 1, 2, 2, 2)
    fun ebCell(k: Int): Pair<V3, Float> {
        val e1 = ebF.d; val e2 = ebN.cross(e1).unit()
        val ang = -k * 2f * PI.toFloat() / 8f
        val r = ebDiam[k] / 2f / um
        return (ebC + (e1 * cos(ang) + e2 * sin(ang)) * (2.1f + r * 0.95f) + ebN * 0.3f) to r
    }
    fun mkFace(k: Int): Triple<V3, V3, Float> {
        val c = mkCentre(b, um, k); val r = MK_SPOTS[k][2] / 2f / um
        val f = rfv(b, MK_SPOTS[k][0]); val n = (f.c - c).unit()
        val (fc, fr) = faceOf(c, n, r, 1f, MK_CUT)
        return Triple(fc, n, fr)
    }
    when (part) {
        0 -> mb.gridFd(30, 10, { u, v ->
            // a trabecula of bone below the sinus, a thick cream plate
            val a = -6f + 19f * u; val th = (228f + 84f * v) * DEG
            rfv(b, a).pol(th, boneR + 0.25f * sin(u * 9f) + 0.3f * sin(v * 7f))
        }) { u, _, p -> rfv(b, -6f + 19f * u).c - p }
        1 -> {
            // most of the bone surface: flat, quiescent bone-lining cells, barely raised scales
            var a = -5f
            while (a < 12.5f) {
                var th = 234f
                while (th < 308f) {
                    val inPatch = a > 1.5f && a < 6.5f && th > 248f && th < 292f
                    if (!inPatch) {
                        val f = rfv(b, a); val rad = f.radial(th * DEG)
                        val u = (a + 6f) / 19f; val v = (th - 228f) / 84f
                        val surf = boneR + 0.25f * sin(u * 9f) + 0.3f * sin(v * 7f)
                        mb.ellipsoid(f.pol(th * DEG, surf - 0.012f), rad * 0.01f, f.d * 0.25f, f.d.cross(rad).unit() * 0.15f, 3, 10)
                    }
                    th += 7.5f
                }
                a += 1.3f
            }
        }
        15 -> mb.gridFd(6, 6, { u, v ->
            // a strip of new, unmineralised osteoid where bone is being laid down
            val a = 1.8f + 4.4f * u; val th = (250f + 40f * v) * DEG
            rfv(b, a).pol(th, boneR + 0.25f * sin(((a + 6f) / 19f) * 9f) + 0.3f * sin(((th / DEG - 228f) / 84f) * 7f) - 0.08f)
        }) { u, _, p -> rfv(b, 1.8f + 4.4f * u).c - p }
        16 -> for (a in floatArrayOf(2.3f, 3.5f, 4.7f, 5.9f)) for (th in floatArrayOf(256f, 270f, 284f)) {
            // a patch of plump, cuboidal osteoblasts on that osteoid
            val f = rfv(b, a); val rad = f.radial(th * DEG)
            mb.ellipsoid(f.pol(th * DEG, boneR - 0.55f), rad * 0.3f, f.d * 0.4f, f.d.cross(rad).unit() * 0.35f, 5, 8)
        }
        14 -> for ((k, sp) in MK_SPOTS.withIndex()) {
            val c = mkCentre(b, um, k); val f = rfv(b, sp[0])
            mb.sectionedCell(c, (f.c - c).unit(), sp[2] / 2f / um, 1f, MK_CUT, 12, 24)
        }
        2 -> for ((k, sp) in MK_SPOTS.withIndex()) {
            // ONE polyploid nucleus in section: many lobes round a folded ring, all joined (not the
            // separate nuclei of an osteoclast)
            val (fc, n, fr) = mkFace(k)
            val e1 = perp(n); val e2 = n.cross(e1).unit()
            val nl = sp[3].toInt() + 2
            val lobes = (0 until nl).map { q -> val t = q * 2f * PI.toFloat() / nl; fc + (e1 * cos(t) + e2 * sin(t)) * (fr * (0.42f + 0.1f * sin(t * 3f))) }
            // the lobes are swellings of one continuous folded ring, joined by broad bridges
            mb.torus(fc + n * 0.01f, n, fr * 0.42f, fr * 0.08f, 40, 8)
            for ((q, p) in lobes.withIndex()) {
                mb.inlay(p, n, p - fc, fr * (0.2f + 0.04f * rnd.nextFloat()), fr * 0.17f)
                val nx = lobes[(q + 1) % nl]
                mb.inlay(lerpV(p, nx, 0.5f), n, nx - p, (nx - p).len() * 0.5f, fr * 0.09f, 0.011f)
            }
            mb.inlay(fc, n, e1, fr * 0.2f, fr * 0.14f)                  // a central fold of the same nucleus
            mb.inlay(lerpV(fc, lobes[0], 0.5f), n, lobes[0] - fc, fr * 0.2f, fr * 0.06f, 0.011f)
        }
        22 -> for (k in MK_SPOTS.indices) { val (fc, n, fr) = mkFace(k); mb.inlay(fc, n, perp(n), fr * 0.99f, fr * 0.99f, 0.005f) }
        3 -> for (k in MK_SPOTS.indices) {
            val (fc, n, fr) = mkFace(k)
            mb.granulesOnFace(fc, n, fr, 90, 0.05f, 7 + k)
        }
        4 -> for (arm in 0 until 3) {
            // a proplatelet: a thin shaft with round platelet-sized beads every 4 µm, the last the largest
            val path = mkArmPath(b, arm)
            val lens = FloatArray(path.size); for (q in 1 until path.size) lens[q] = lens[q - 1] + (path[q] - path[q - 1]).len()
            val total = lens.last()
            val n = (total * 10f).toInt().coerceAtLeast(2)
            val fine = ArrayList<V3>(); val radii = ArrayList<Float>()
            var seg = 0
            for (q in 0..n) {
                val sArc = total * q / n
                while (seg < path.size - 2 && lens[seg + 1] < sArc) seg++
                val t = ((sArc - lens[seg]) / (lens[seg + 1] - lens[seg]).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
                fine.add(lerpV(path[seg], path[seg + 1], t))
                val m = sArc % 0.5f - 0.25f
                val bump = exp(-(m * m) / (2f * 0.06f * 0.06f))
                radii.add(0.05f + 0.13f * bump)
            }
            mb.tube(fine, radii.toFloatArray(), 8, true)
            mb.sphere(path.last(), 0.2f, 8, 12)
        }
        20 -> for (arm in 0 until 3) {
            // the transendothelial pore where each arm crosses the sinus wall: a small collar
            val path = mkArmPath(b, arm)
            val s = MK_SPOTS[0]
            for (q in 0 until path.size - 1) {
                val a = s[0] - 1.2f + arm * 1.3f
                val f = rfv(b, a); val R = radiusAt(b, a) * 0.97f
                val r0 = (path[q] - f.c).len(); val r1 = (path[q + 1] - f.c).len()
                if ((r0 - R) * (r1 - R) <= 0f) { val t = (r0 - R) / (r0 - r1); mb.torus(lerpV(path[q], path[q + 1], t), path[q + 1] - path[q], 0.18f, 0.04f, 20, 6); break }
            }
        }
        5 -> {
            // the island's central macrophage, pale pink-grey, with processes reaching every erythroblast
            mb.sectionedCell(ebC, ebN, 1.7f, 0.85f, 0.45f, 9, 18)
            for (k in 0 until 8) if (k % 4 != 3) { val (c, _) = ebCell(k); val ang = 0.25f * (if (k % 2 == 0) 1f else -1f); val mid = lerpV(ebC, c, 0.55f) + ebN * 0.3f + perp(ebN) * ang; mb.tube(listOf(ebC + (c - ebC) * 0.35f + ebN * 0.3f, mid, lerpV(ebC, c, 0.78f) + ebN * 0.35f), floatArrayOf(0.09f, 0.07f, 0.04f), 6, true) }
        }
        17, 21 -> {
            // on its section: its own kidney-shaped nucleus off to one side (17), and on the other a tight
            // cluster of three phagosomes, each a pale vacuole (21) round an eaten, condensed nucleus (17)
            val (fc, fr) = faceOf(ebC, ebN, 1.7f, 0.85f, 0.45f)
            val e1 = perp(ebN); val e2 = ebN.cross(e1).unit()
            val toCord = (e1 * 0.8f + e2 * 0.6f).unit(); val side = ebN.cross(toCord).unit()
            if (part == 17) {
                mb.inlay(fc + toCord * (fr * 0.45f) + side * (fr * 0.1f), ebN, side, 0.7f * 0.5f, 0.45f * 0.5f)
                mb.inlay(fc + toCord * (fr * 0.52f) - side * (fr * 0.18f), ebN, side + toCord, 0.3f, 0.2f)
            }
            val pc = fc - toCord * (fr * 0.4f) + side * (fr * 0.15f)
            for (q in 0 until 3) {
                val t = q * 2.094f + 0.4f
                val c = pc + (toCord * cos(t) + side * sin(t)) * 0.36f
                if (part == 21) mb.inlay(c, ebN, side, 0.3f, 0.3f, 0.016f) else mb.inlay(c, ebN, side, 0.18f, 0.18f, 0.024f)
            }
        }
        6, 7, 8 -> for (k in 0 until 8) {
            val (c, r) = ebCell(k)
            val stage = ebStage[k]
            if (stage == part - 6) mb.sectionedCell(c, ebN, r, 1f, 0.45f, 6, 12)
        }
        9 -> for (k in 0 until 8) {
            // nuclei: large and open in the proerythroblast, shrinking and condensing, then pushed out
            val (c, r) = ebCell(k)
            val (fc, fr) = faceOf(c, ebN, r, 1f, 0.45f)
            val e1 = perp(ebN)
            val frac = floatArrayOf(0.72f, 0.66f, 0.58f, 0.5f, 0.46f, 0.38f, 0.36f, 0.35f)[k]
            if (k == 7) {
                val out = (c - ebC).unit()
                mb.inlay(fc + out * (fr * 0.62f), ebN, e1, fr * frac, fr * frac)
                mb.sphere(c + out * (r * 1.05f), r * 0.32f, 5, 8)                 // the nucleus leaving the cell
            } else mb.inlay(fc, ebN, e1, fr * frac, fr * frac * 0.95f)
        }
        10 -> {
            // a reticulocyte squeezing through the sinus wall: pinched where it crosses
            val f = rfv(b, 4.6f); val th = -20f * DEG
            val R = radiusAt(b, 4.6f) * 0.97f
            val rad = f.radial(th); val t2 = rad.cross(f.d).unit()
            mb.ellipsoid(f.pol(th, R + 0.42f), rad * 0.42f, f.d * 0.5f, t2 * 0.45f, 6, 10)
            mb.ellipsoid(f.pol(th, R - 0.36f), rad * 0.36f, f.d * 0.42f, t2 * 0.4f, 6, 10)
            mb.rod(f.pol(th, R - 0.25f), f.pol(th, R + 0.25f), 0.17f, 6)
        }
        11, 12, 18 -> for (k in 0 until 6) {
            // developing granulocytes in the cords: myelocytes (round eccentric nuclei), metamyelocytes
            // (kidney nuclei) and band forms (C nuclei)
            val a = -3f + k * 2.6f
            val f = rfv(b, a)
            val th = (195f + 18f * (k % 3)) * DEG
            val r = 12f / 2f / um
            val c = f.pol(th, radiusAt(b, a) + 0.9f + r)
            val n = (f.c - c).unit()
            val (fc, fr) = faceOf(c, n, r, 1f, 0.45f)
            when (part) {
                11 -> mb.sectionedCell(c, n, r, 1f, 0.45f, 6, 12)
                12 -> when (k % 3) {
                    0 -> mb.nucleusOnFace(fc + perp(n) * (fr * 0.2f), n, fr, 2, k)
                    1 -> mb.nucleusOnFace(fc + perp(n) * (fr * 0.2f), n, fr, 3, k)
                    else -> mb.inlay(fc + perp(n) * (fr * 0.2f), n, perp(n), fr * 0.45f, fr * 0.4f)
                }
                else -> mb.granulesOnFace(fc, n, fr, 20, 0.04f, 40 + k)
            }
        }
        13, 19 -> {
            // adipocytes: big fat cells, a thin rim of cytoplasm round one lipid droplet
            val fats = listOf(rfv(b, -4f).pol(250f * DEG, radiusAt(b, -4f) + 5.3f) to 80f, rfv(b, 17f).pol(60f * DEG, radiusAt(b, 17f) + 5.6f) to 90f)
            for ((c, d) in fats) {
                val r = d / 2f / um
                if (part == 13) mb.sphere(c, r, 12, 18)
                else { val f = rfv(b, 0f); mb.torus(c, (f.c - c).unit(), r, 0.03f, 40, 5) }
            }
        }
    }
    return mb.build(twoSided = part == 0)
}

/**
 * Stop 8 (THE CUT, 12 µm Mote: 1 unit = 8 µm): the nick in his finger. The craft hangs in the cleft
 * the splinter left in the thick skin of the fingertip, 32-56 µm wide, among the living cells. Both
 * faces are cut sections of the skin, flat as on a slide: ~100 µm of stratum corneum (flat keratin
 * plates, pale pink), the glassy stratum lucidum, a double row of granular cells full of
 * keratohyalin, the polygonal spinous cells joined across narrow pale gaps by the desmosomal
 * "prickles" that name the layer, each with its nucleus, the basal row of columnar cells on an
 * undulating basement membrane with deep rete ridges, and the tall dermal papillae below with a
 * hairpin capillary loop in each; a neutrophil is leaving one. Chains of Streptococcus, purple, are
 * carried down the cut faces from the dead keratin into the living layers.
 */
internal fun StereoBodyRenderer.drawCut(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    val um = umu(i)
    if (!openWall(i, -8f, 34f, T3_BLACK)) return
    drawMesh(cached("ct_dermis") { cutMesh(b, um, 0) }, T3_DERMIS, T3_DERMIS, 1f, 0f, 0.12f)
    drawMesh(cached("ct_coll") { cutMesh(b, um, 1) }, T3_COLLAGEN, T3_COLLAGEN, 1f, 0f, 0.1f)
    drawMesh(cached("ct_caps2") { cutMesh(b, um, 2) }, T3_CAPILLARY, T3_CAPILLARY, 1f, 0f, 0.2f)
    drawMesh(cached("ct_caprim") { cutMesh(b, um, 26) }, T3_ENDOTHELIUM, T3_ENDOTHELIUM, 1f, 0f, 0.1f)
    drawMesh(cached("ct_bm") { cutMesh(b, um, 3) }, T3_BASEMENT, T3_BASEMENT, 1f, 0f, 0.3f)
    // Behind the cells: the pale intercellular spaces of the living epidermis, the corneum's keratin.
    drawMesh(cached("ct_back0") { cutMesh(b, um, 12) }, T3_INTERCELL, T3_INTERCELL, 1f, 0f, 0.1f)
    drawMesh(cached("ct_back2") { cutMesh(b, um, 14) }, T3_CORN_BACK, T3_CORN_BACK, 1f, 0f, 0.05f)
    drawMesh(cached("ct_basal") { cutMesh(b, um, 4) }, T3_BASAL, T3_BASAL, 1f, 0f, 0.1f)
    drawMesh(cached("ct_spin") { cutMesh(b, um, 5) }, T3_SPINOUS, T3_SPINOUS, 1f, 0f, 0.1f)
    drawMesh(cached("ct_crushed2") { cutMesh(b, um, 22) }, T3_CRUSHED, T3_CRUSHED, 1f, 0f, 0.1f)
    drawMesh(cached("ct_pyknotic") { cutMesh(b, um, 25) }, T3_PYKNOTIC, T3_PYKNOTIC, 1f, 0f, 0.05f)
    // the prickles, in along-bands: those on the cells nearest the pilot are left out (at the lens
    // they would read as pins; the cells further on show the bridges)
    val camA = run { val f0 = rfv(b, 0f); (V3(camNowX, camNowY, camNowZ) - f0.c).dot(f0.d) }
    for (ch in 0 until 8) {
        val lo = -8f + ch * 4f; val hi = lo + 4f
        if (camA + 1.5f > lo && camA - 1.5f < hi) continue
        drawMesh(cached("ct_sp$ch") { cutMesh(b, um, 16, lo, hi) }, T3_SPINOUS, T3_SPINOUS, 1f, 0f, 0.12f)
        drawMesh(cached("ct_ds$ch") { cutMesh(b, um, 24, lo, hi) }, T3_DESMOSOME, T3_DESMOSOME, 1f, 0f, 0.1f)
    }
    drawMesh(cached("ct_nuc2") { cutMesh(b, um, 6) }, T3_NUCLEUS, T3_NUCLEUS, 1f, 0f, 0.15f)
    drawMesh(cached("ct_gran") { cutMesh(b, um, 7) }, T3_GRANULAR, T3_GRANULAR, 1f, 0f, 0.1f)
    drawMesh(cached("ct_khg") { cutMesh(b, um, 8) }, T3_KERATOHYALIN, T3_KERATOHYALIN, 1f, 0f, 0.05f)
    drawMesh(cached("ct_corn") { cutMesh(b, um, 9) }, T3_CORNEUM, T3_CORNEUM, 1f, 0f, 0.06f)
    drawMesh(cached("ct_lucidum") { cutMesh(b, um, 15) }, T3_LUCIDUM, T3_LUCIDUM, 1f, 0f, 0.35f)
    drawMesh(cached("ct_rbc") { cutMesh(b, um, 10) }, T3_RBC, T3_RBC, 1f, 0f, 0.15f)
    drawMesh(cached("ct_fibrin") { cutMesh(b, um, 23) }, T3_FIBRIN, T3_FIBRIN, 1f, 0f, 0.3f)
    // A neutrophil squeezing out of a papillary capillary toward the invaders (diapedesis),
    // cut through like the tissue so its segmented nucleus shows.
    drawMesh(cached("ct_pmn") { cutMesh(b, um, 17) }, T3_LEUKOCYTE, T3_LEUKOCYTE, 1f, 0f, 0.1f)
    drawMesh(cached("ct_pmnnuc") { cutMesh(b, um, 18) }, T3_NUCLEUS, T3_NUCLEUS, 1f, 0f, 0.2f)
    // Streptococcus pyogenes: chains of ~1 µm Gram-positive cocci. Five carried down the cleft from
    // the skin surface (contamination falling in); three pushing into the dermis where the splinter's
    // track cut it, beside the capillary loops of the papillae (the epidermis itself is sealed); and
    // ten caught in the blood and fibrin at the floor of the cut.
    for (k in 0 until 18) {
        val mesh = cached("ct_chain${k % 11}") { chainMesh(8 + (k * 3) % 5, 1.05f / 2f / um, 100 + k % 11) }
        val sg = if (k % 2 == 0) -1f else 1f
        when {
            k < 5 -> {
                val t = ((seconds / 14f + k * 0.19f) % 1f)
                val a = 0.4f + k * 1.3f
                val up = 9f - t * 11f
                val f = rfv(b, a + 0.3f * sin(t * 3f + k))
                val p = f.at(sg * (cutHalf(up) - 0.35f), up)
                val tumble = 0.6f + 0.3f * sin(seconds * 0.3f + k)
                drawLocal(mesh, p, f.d * cos(tumble) + f.u * sin(tumble), f.s, T3_STREP, T3_STREP, 1f - smooth01((t - 0.9f) / 0.1f), 0.6f)
            }
            k < 8 -> {
                // crossing into the papillary dermis beside a capillary loop, at 30° to the cut face
                val q = k - 5
                val pa = floatArrayOf(2f, 10f, 2f)[q] + floatArrayOf(-1.3f, 1.2f, 1.4f)[q]
                val up = cutJunction(pa) - 1.6f - 0.6f * q
                val t = ((seconds / 9f + q * 0.33f) % 1f)
                val depth = -0.3f + t * 0.7f
                val f = rfv(b, pa)
                val dir = (f.s * (sg * cos(30f * DEG)) + f.d * sin(30f * DEG)).unit()
                val p = f.at(sg * (cutHalf(up) + depth * cos(30f * DEG)), up) + f.d * (depth * sin(30f * DEG))
                drawLocal(mesh, p, dir, f.u, T3_STREP, T3_STREP, 1f - smooth01((t - 0.8f) / 0.2f), 0.6f)
            }
            else -> {
                val q = k - 8
                val a = -1.5f + q * 1.3f; val up = CUT_FLOOR + 0.8f + (q % 3) * 0.9f
                val f = rfv(b, a)
                drawLocal(mesh, f.at((q % 3 - 1) * 1.1f, up), (f.d + f.u * 0.6f * (q % 2 - 0.5f)).unit(), f.s, T3_STREP, T3_STREP, 1f, 0.5f)
            }
        }
    }
}

// Thick (palmar) skin of the finger, heights in units (8 µm), the craft at 0 in the spinous layer:
// deep rete ridges and tall papillae, ~105 µm of living epidermis, the lucidum, ~190 µm of corneum.
// (the whole section sits 3 units higher than the spinous-level hold of round 4, so the pilot's eye
// is at the dermal papillae, where the bacteria get in)
private const val CUT_SURFACE = 33f
private const val CUT_CORNEUM_BASE = 9.3f
private const val CUT_LUCIDUM_BASE = 8.9f
private const val CUT_GRANULAR_BASE = 8.0f
private const val CUT_FLOOR = -8f
/** Half-width of the cleft at height [up]: a clean wedge, wider at the surface (32-56 µm). */
private fun cutHalf(up: Float) = 2.0f + 1.5f * (up - CUT_FLOOR) / (CUT_SURFACE - CUT_FLOOR)
/** The dermo-epidermal junction: deep rete ridges and tall dermal papillae (period 64 µm). */
private fun cutJunction(a: Float) = -3.0f + 2.4f * sin(a * 2f * PI.toFloat() / 8f)
/** Where the papillae peak (and their capillary loops rise). */
private val PAPILLAE = floatArrayOf(-6f, 2f, 10f, 18f)

/** The cut's parts (see [drawCut]). */
private fun StereoBodyRenderer.cutMesh(b: Float, um: Float, part: Int, lo: Float = -1e9f, hi: Float = 1e9f): T3Mesh {
    val mb = Mb()
    val rnd = java.util.Random(91L + (if (part == 6 || part == 16) 5 else part))
    val a0 = -8f; val a1 = 24f
    val kerR = 11f / 2f / um
    // A point on a cut face: along [a], height [up], [depth] behind the face (negative: into the cleft).
    fun onFace(sg: Float, a: Float, up: Float, depth: Float) = wAt(b, a, sg * (cutHalf(up) + depth), up)
    fun faceN(sg: Float, a: Float) = -rfv(b, a).s * sg
    // A flat polygonal cell section on the face: [sides]-gon of circumradius [r] (x along, y up
    // scaled by [aspect]), domed very slightly toward the cleft.
    fun plate(sg: Float, a: Float, up: Float, r: Float, sides: Int, aspect: Float, rot: Float, depth: Float = 0f) {
        mb.grid(2, sides, { u, v ->
            val t = v * 2f * PI.toFloat() + rot
            onFace(sg, a + cos(t) * r * u, up + sin(t) * r * aspect * u, depth - 0.05f * (1f - u * u))
        }) { _, _, _ -> faceN(sg, a) }
    }
    // The spinous layer: a mosaic of polyhedral cells, spacing 2.1·kerR, bottom row just above the
    // basal row, each cell a little irregular and flattening toward the granular layer; about a third
    // of those on the cut face crushed by the splinter.
    val sp = kerR * 2.1f; val pitch = sp * sqrt(3f) / 2f
    fun cellHash(sg: Float, row: Int, col: Int, k: Int): Float { val h = sin((row * 127.1f + col * 311.7f + sg * 74.7f + k * 19.3f)) * 43758.547f; return h - floor(h) }
    fun aspectAt(up: Float) = (1f - 0.2f * ((up + 3f) / (CUT_GRANULAR_BASE + 3f)).coerceIn(0f, 1f))
    fun cellR(sg: Float, row: Int, col: Int) = (sp / 2f / cos(30f * DEG)) * 0.93f * (0.93f + 0.1f * cellHash(sg, row, col, 1))
    fun crushed(sg: Float, row: Int, col: Int) = cellHash(sg, row, col, 2) < 0.2f
    fun spinous(visit: (Float, Float, Float, Int, Int) -> Unit) {
        for (sg in floatArrayOf(-1f, 1f)) {
            var row = 0
            var up = -5.6f
            while (up < CUT_GRANULAR_BASE - pitch * 0.5f) {
                var a = a0 + (if (row % 2 == 0) 0f else sp / 2f); var col = 0
                while (a < a1) { if (up > cutJunction(a) + 1.45f) visit(sg, a, up, row, col); a += sp; col++ }
                up += pitch; row++
            }
        }
    }
    // An irregular polygon cell section: vertices jittered, flattened by [aspect].
    fun cell(sg: Float, a: Float, up: Float, r: Float, sides: Int, aspect: Float, jit: Float, seed: Float) {
        val rs = FloatArray(sides + 1) { k -> 1f + jit * sin(seed * 13.1f + (k % sides) * 2.7f) }
        mb.grid(2, sides, { u, v ->
            val k = (v * sides).roundToInt(); val t = v * 2f * PI.toFloat() + PI.toFloat() / 6f
            val rr = r * u * rs[k.coerceIn(0, sides)]
            onFace(sg, a + cos(t) * rr, up + sin(t) * rr * aspect, -0.05f * (1f - u * u))
        }) { _, _, _ -> faceN(sg, a) }
    }
    when (part) {
        0 -> {
            // the dermis in section on both faces, from the junction down, and the cleft's floor
            for (sg in floatArrayOf(-1f, 1f)) mb.gridFd(96, 10, { u, v ->
                val a = a0 + (a1 - a0) * u; val top = cutJunction(a) - 0.2f
                val up = CUT_FLOOR + (top - CUT_FLOOR) * v
                onFace(sg, a, up, 0f)
            }) { u, _, _ -> faceN(sg, a0 + (a1 - a0) * u) }
            mb.gridFd(48, 4, { u, v -> val a = a0 + (a1 - a0) * u; wAt(b, a, (v * 2f - 1f) * cutHalf(CUT_FLOOR), CUT_FLOOR - 0.2f * (1f - (v * 2f - 1f).pow(2))) }) { u, _, _ -> rfv(b, a0 + (a1 - a0) * u).u }
        }
        1 -> for (sg in floatArrayOf(-1f, 1f)) for (k in 0 until 6) {
            // wavy collagen bundles of the reticular dermis, cut across the face
            val up0 = CUT_FLOOR + 0.5f + k * 0.5f
            val pts = (0..40).map { q -> val a = a0 + q * 0.8f; val up = up0 + 0.18f * sin(q * 1.3f + k); onFace(sg, a, up, -0.03f) }
            mb.tube(pts, FloatArray(41) { 0.08f }, 5, false)
        }
        2, 26 -> for (sg in floatArrayOf(-1f, 1f)) for (pa in PAPILLAE) {
            // a hairpin capillary loop rising 3 units into each papilla, cut through by the section:
            // mostly embedded, only its front showing as a low red band flush with the face (2),
            // edged by its pale endothelium (26)
            val top = cutJunction(pa) - 0.7f
            val cr = 7f / 2f / um; val depth = 0.34f
            val path = ArrayList<FloatArray>()
            for (q in 0..6) path.add(floatArrayOf(pa - 0.6f, top - 3f + 3f * q / 6f))
            for (q in 1..6) { val ang = q / 6f * PI.toFloat(); path.add(floatArrayOf(pa - 0.6f * cos(ang), top + 0.35f * sin(ang))) }
            for (q in 1..6) path.add(floatArrayOf(pa + 0.6f, top - 3f * q / 6f))
            if (part == 2) mb.tube(path.map { onFace(sg, it[0], it[1], depth) }, FloatArray(path.size) { cr }, 12, true)
            else {
                val w = sqrt(cr * cr - depth * depth)
                for (side in floatArrayOf(-1f, 1f)) {
                    val edge = path.indices.map { q ->
                        val pv = path[max(q - 1, 0)]; val nx = path[min(q + 1, path.size - 1)]
                        var ta = nx[0] - pv[0]; var tu = nx[1] - pv[1]; val l = sqrt(ta * ta + tu * tu).coerceAtLeast(1e-4f); ta /= l; tu /= l
                        onFace(sg, path[q][0] - tu * w * side, path[q][1] + ta * w * side, -0.01f)
                    }
                    mb.tube(edge, FloatArray(edge.size) { 0.03f }, 5, false)
                }
            }
        }
        3 -> for (sg in floatArrayOf(-1f, 1f)) {
            val pts = (0..160).map { q -> val a = a0 + q * 0.2f; onFace(sg, a, cutJunction(a) - 0.12f, -0.02f) }
            mb.tube(pts, FloatArray(161) { 0.07f }, 5, false)
        }
        4 -> for (sg in floatArrayOf(-1f, 1f)) {
            // the basal layer: one row of columnar cells standing on the basement membrane
            var a = a0
            while (a < a1) {
                val up = cutJunction(a) + 0.55f
                val slope = atan(2.4f * 2f * PI.toFloat() / 8f * cos(a * 2f * PI.toFloat() / 8f))
                plate(sg, a, up, 0.55f, 4, 1.1f, PI.toFloat() / 4f + slope * 0.5f)
                a += 0.72f
            }
        }
        5, 22 -> spinous { sg, a, up, row, col ->
            val cr = crushed(sg, row, col)
            if (part == 5 && !cr) cell(sg, a, up, cellR(sg, row, col), 6, aspectAt(up), 0.12f, row * 7f + col)
            // crushed cells at the torn edge: shrunken, ragged, paler
            if (part == 22 && cr) {
                // shrunken and ragged, one edge torn into a jagged notch
                val r = cellR(sg, row, col) * (0.6f + 0.2f * cellHash(sg, row, col, 3)); val asp = aspectAt(up)
                val notch = (cellHash(sg, row, col, 4) * 7).toInt()
                val rs = FloatArray(10) { k -> when { k == notch -> 0.55f; k == notch + 1 -> 0.8f; k == (notch + 9) % 10 -> 0.78f; else -> 1f + 0.2f * sin(row * 5f + col + k * 2.7f) } }
                mb.grid(2, 10, { u, v ->
                    val k = (v * 10).roundToInt() % 10; val t = v * 2f * PI.toFloat()
                    onFace(sg, a + cos(t) * r * u * rs[k], up + sin(t) * r * asp * u * rs[k], -0.05f * (1f - u * u))
                }) { _, _, _ -> faceN(sg, a) }
            }
        }
        25 -> spinous { sg, a, up, row, col ->
            // the crushed cells' nuclei: one shrunken, dense, irregular (pyknotic) nucleus, off-centre
            if (crushed(sg, row, col)) mb.inlay(onFace(sg, a + 0.12f, up - 0.05f, -0.07f), faceN(sg, a), rfv(b, a).d + rfv(b, a).u * 0.4f, 0.12f, 0.08f)
        }
        16, 24 -> spinous { sg, a, up, row, col ->
            if (a < lo || a >= hi) return@spinous
            // desmosomal bridges ("prickles") across the narrow gaps to the neighbours: four fine
            // cytoplasmic bridges per shared edge, each with a dense desmosome midway (24)
            if (crushed(sg, row, col)) return@spinous
            val nbr = listOf(Triple(0f, 0, 1), Triple(60f, 1, if (row % 2 == 0) 0 else 1), Triple(120f, 1, if (row % 2 == 0) -1 else 0))
            for ((dirDeg, dr, dc) in nbr) {
                val d = dirDeg * DEG
                val ex = cos(d); val ey = sin(d)
                val r2 = row + dr; val c2 = col + dc
                if (crushed(sg, r2, c2)) continue
                val dist = sp
                val midA = a + ex * dist / 2f; val midU = up + ey * dist / 2f
                if (midU > CUT_GRANULAR_BASE - pitch * 0.5f || midU < cutJunction(midA) + 1.45f) continue
                val asp1 = aspectAt(up); val asp2 = aspectAt(up + ey * dist)
                fun rb(r: Float, asp: Float) = r / sqrt(ex * ex + (ey / asp) * (ey / asp))
                val gap = dist - rb(cellR(sg, row, col), asp1) - rb(cellR(sg, r2, c2), asp2)
                if (gap > 0.35f) continue                                   // only across the narrow gaps
                val half = (gap / 2f + 0.04f).coerceIn(0.06f, 0.18f)
                for (q in 0 until 4) {
                    val off = (q - 1.5f) * 0.11f
                    val ta = midA - ey * off; val tu = midU + ex * off
                    if (part == 16) mb.tube(listOf(onFace(sg, ta - ex * half, tu - ey * half, -0.02f), onFace(sg, ta + ex * half, tu + ey * half, -0.02f)), floatArrayOf(0.018f, 0.018f), 5, true)
                    else mb.sphere(onFace(sg, ta, tu, -0.025f), 0.02f, 5, 8)
                }
            }
        }
        6 -> {
            spinous { sg, a, up, row, col ->
                val p = onFace(sg, a, up, -0.06f)
                if (!crushed(sg, row, col)) mb.inlay(p, faceN(sg, a), rfv(b, a).d, 0.26f + 0.03f * rnd.nextFloat(), 0.22f * aspectAt(up) + 0.04f)
                else {}
            }
            // basal nuclei, tall
            for (sg in floatArrayOf(-1f, 1f)) {
                var a = a0
                while (a < a1) { mb.inlay(onFace(sg, a, cutJunction(a) + 0.55f, -0.06f), faceN(sg, a), rfv(b, a).u, 0.3f, 0.17f); a += 0.72f }
            }
        }
        7, 8 -> for (sg in floatArrayOf(-1f, 1f)) for (row in 0 until 2) {
            // the granular layer: two rows of flattened, diamond-shaped cells packed with keratohyalin
            var a = a0 + row * 1.0f
            while (a < a1) {
                val up = CUT_GRANULAR_BASE + 0.22f + row * 0.45f
                if (part == 7) plate(sg, a, up, 0.95f, 4, 0.25f, 0f)
                else for (q in 0 until 7) mb.inlay(onFace(sg, a + (rnd.nextFloat() - 0.5f) * 1.2f, up + (rnd.nextFloat() - 0.5f) * 0.15f, -0.06f), faceN(sg, a), rfv(b, a).d, 0.07f, 0.06f)
                a += 2.0f
            }
        }
        9 -> for (sg in floatArrayOf(-1f, 1f)) {
            // the stratum corneum: flat, anucleate keratin plates stacked in staggered layers
            var layer = 0
            var up = CUT_CORNEUM_BASE + 0.08f
            while (up < CUT_SURFACE) {
                var a = a0 + (layer % 3) * 1.1f
                while (a < a1) {
                    mb.grid(1, 4, { u, v ->
                        val t = v * 2f * PI.toFloat() + PI.toFloat() / 4f
                        onFace(sg, a + cos(t) * 2.1f * u, up + sin(t) * 0.07f * u, -0.03f)
                    }) { _, _, _ -> faceN(sg, a) }
                    a += 3.3f
                }
                up += 0.16f; layer++
            }
            // plates torn loose where the splinter went through, hanging into the cleft
            for (k in 0 until 6) {
                val a = 0.5f + k * 2.3f + 0.6f * sg; val u2 = 5.0f + k * 0.9f
                val f = rfv(b, a)
                val tilt = (20f + 5f * k) * DEG
                val ax = (f.u * cos(tilt) - f.s * (sg * sin(tilt))).unit()
                mb.ellipsoid(onFace(sg, a, u2, -0.35f), ax * 0.05f, f.d * 1.4f, ax.cross(f.d).unit() * 0.5f, 3, 8)
            }
        }
        23 -> for (k in 0 until 40) {
            // a fibrin mesh already forming in the depth of the cleft, face to face
            val a = -2f + rnd.nextFloat() * 14f; val u0 = CUT_FLOOR + 0.2f + rnd.nextFloat() * 3.3f; val u1 = (u0 + (rnd.nextFloat() - 0.5f) * 2f).coerceIn(CUT_FLOOR + 0.2f, CUT_FLOOR + 3.5f)
            val s0 = -cutHalf(u0) + 0.05f; val s1 = cutHalf(u1) - 0.05f
            val p0 = wAt(b, a, s0, u0); val p1 = wAt(b, a + (rnd.nextFloat() - 0.5f) * 3f, s1, u1)
            mb.tube(listOf(p0, lerpV(p0, p1, 0.5f) - rfv(b, a).u * 0.15f, p1), FloatArray(3) { 0.03f }, 5, false)
        }
        10 -> for (k in 0 until 30) {
            // red cells from the cut loops, trapped in the fibrin at the bottom of the cleft
            val f = rfv(b, -2f + rnd.nextFloat() * 14f)
            val c = f.at((rnd.nextFloat() - 0.5f) * 3.2f, CUT_FLOOR + 0.35f + rnd.nextFloat() * 3f)
            mb.redCell(c, (f.u + f.s * (rnd.nextFloat() - 0.5f) + f.d * (rnd.nextFloat() - 0.5f)).unit(), 7.5f / 2f / um, 4, 12)
        }
        12, 14, 15 -> {
            // continuous faces behind the cells: the living layers' intercellular pale (12), the
            // corneum's keratin (14), and the glassy stratum lucidum (15) in the face itself
            val depth = if (part == 15) -0.04f else 0.06f
            for (sg in floatArrayOf(-1f, 1f)) mb.gridFd(96, 6, { u, v ->
                val a = a0 + (a1 - a0) * u
                val lo = when (part) { 12 -> cutJunction(a) - 0.2f; 14 -> CUT_LUCIDUM_BASE; else -> CUT_LUCIDUM_BASE }
                val hi = when (part) { 12 -> CUT_LUCIDUM_BASE + 0.02f; 14 -> CUT_SURFACE; else -> CUT_CORNEUM_BASE }
                onFace(sg, a, lo + (hi - lo) * v, depth)
            }) { u, _, _ -> faceN(sg, a0 + (a1 - a0) * u) }
        }
        17, 18 -> {
            // a neutrophil halfway out of the capillary loop in the papilla at along 10, starboard face
            val pa = 10f; val top = cutJunction(pa) - 0.7f
            val c = onFace(1f, pa + 0.6f, top - 0.9f, -0.55f)
            val nrm = faceN(1f, pa)
            val r = 12f / 2f / um
            if (part == 17) mb.sectionedCell(c, nrm, r, 1f, 0.3f, 7, 14)
            else { val (fc, fr) = faceOf(c, nrm, r, 1f, 0.3f); mb.nucleusOnFace(fc, nrm, fr, 1, 4) }
        }
    }
    return mb.build(twoSided = part == 0 || part == 12 || part == 14 || part == 15 || part == 5 || part == 22)
}

/**
 * Stop 9 (THE FEVER, 12 µm Mote: 1 unit = 8 µm): septicaemia in a postcapillary venule ~45 µm
 * across, drawn with honest numbers: a few short chains of Streptococcus (1 µm Gram-positive cocci,
 * 2-8 per chain, dividing) among the red cells, and more neutrophils than bacteria — adherent to the
 * wall, cut through to show their segmented nuclei and granules, reaching out and every few seconds
 * engulfing a chain into a phagosome. What is overwhelming the body is the response, as the crew
 * say: the venule dilates; its endothelium opens small gaps through which plasma streams out and a
 * red cell squeezes, soaking the tissue outside (oedema, the collagen pushed apart); platelet-fibrin
 * microthrombi stick to the damaged wall; pericytes wrap the venule outside.
 */
internal fun StereoBodyRenderer.drawSepsis(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    val um = umu(i)
    val t = sinceArrival(i, seconds)
    if (!openWall(i, -8f, 34f, T3_INTERSTITIUM)) return
    val leak = smooth01(t / 30f)
    val dil = 1f + 0.12f * leak                                         // vasodilatation
    // Oedema: the fluid pushes the tissue's collagen fibres apart as it accumulates.
    drawMeshRadial(cached("sp_coll") { sepsisMesh(b, um, 0) }, b, 1f + 0.6f / (radiusAt(b, 0f) + 2f) * leak, T3_GRANULOMA, T3_GRANULOMA, 0.6f, 0f, 0f)
    drawMesh(cached("sp_leaked") { sepsisMesh(b, um, 1) }, T3_RBC_DEOXY, T3_RBC, 1f, 0f, 0.2f)
    drawMesh(cached("sp_peri") { sepsisMesh(b, um, 5) }, T3_PERICYTE, T3_PERICYTE, 1f, 0f, 0.02f)
    drawMesh(cached("sp_perinuc") { sepsisMesh(b, um, 6) }, T3_NUCLEUS, T3_NUCLEUS, 1f, 0f, 0.15f)
    val holes = SEPSIS_GAPS
    val (tube, junc, nuc) = endotheliumMeshes("sp_endo2", b, -8f, 24f, { a -> radiusAt(b, a) * 0.9f }, 30f / um, 10, holes, 0.2f, 0.07f)
    drawMeshRadial(nuc, b, dil, T3_ENDO_NUCLEUS, T3_ENDOTHELIUM, 0.6f, 0f, 0.05f)
    drawMeshRadial(junc, b, dil, T3_JUNCTION, T3_JUNCTION, 0.45f, 0f, 0.08f)
    drawMeshRadial(cached("sp_rims") { sepsisMesh(b, um, 2) }, b, dil, T3_GAP_LINE, T3_GAP_LINE, 0.6f, 0f, 0.1f)
    // Microthrombi on the damaged wall: platelet mounds with fibrin trailing downstream, red cells caught.
    drawMeshRadial(cached("sp_thr") { sepsisMesh(b, um, 7) }, b, dil, T3_PLATELET_LILAC, T3_PLATELET_LILAC, 1f, 0f, 0.1f)
    drawMeshRadial(cached("sp_thrfib") { sepsisMesh(b, um, 8) }, b, dil, T3_FIBRIN, T3_FIBRIN, 1f, 0f, 0.25f)
    drawMeshRadial(cached("sp_thrrbc") { sepsisMesh(b, um, 9) }, b, dil, T3_RBC_DEOXY, T3_RBC, 1f, 0f, 0.1f)
    // Neutrophils adherent to the wall, cut through: segmented nuclei, granules, phagosomes.
    drawMeshRadial(cached("sp_pmn") { sepsisMesh(b, um, 10) }, b, dil, T3_LEUKOCYTE, T3_LEUKOCYTE, 1f, 0f, 0.1f)
    drawMeshRadial(cached("sp_pmnnuc") { sepsisMesh(b, um, 11) }, b, dil, T3_NUCLEUS, T3_NUCLEUS, 1f, 0f, 0.2f)
    drawMeshRadial(cached("sp_pmngran") { sepsisMesh(b, um, 12) }, b, dil, T3_NEUTRO_GRANULE, T3_NEUTRO_GRANULE, 1f, 0f, 0.2f)
    drawMeshRadial(cached("sp_phago") { sepsisMesh(b, um, 13) }, b, dil, T3_PHAGOSOME, T3_PHAGOSOME, 1f, 0f, 0.2f)
    drawMeshRadial(cached("sp_eaten") { sepsisMesh(b, um, 14) }, b, dil, T3_GRAM_POS, T3_GRAM_POS, 1f, 0f, 0.3f)
    // A red cell squeezing out through a gap: a dumbbell pinched at the gap, sliding out over 14 s.
    run {
        val (ha, hth) = holes[0]
        val q = ((t / 14f) % 1f)
        val f = rfv(b, ha); val rad = f.radial(hth)
        val R = radiusAt(b, ha) * 0.9f * dil
        // two halves of one biconcave cell, face-on to the gap, the volume passing from the lumen
        // half to the tissue half through a narrowing neck
        val rr = 0.47f
        val kIn = 1f - q; val kOut = q
        val cIn = f.pol(hth, R - 0.1f - rr * 0.35f * (0.6f + 0.4f * kIn)); val cOut = f.pol(hth, R + 0.1f + rr * 0.35f * (0.6f + 0.4f * kOut))
        if (kIn > 0.05f) drawScaled(rbc, cIn, f.d, rad, rr * (0.55f + 0.45f * kIn), rr * (0.6f + 0.4f * kIn), rr * (0.55f + 0.45f * kIn), T3_RBC_DEOXY, T3_RBC, 1f, 0.12f)
        if (kOut > 0.05f) drawScaled(rbc, cOut, f.d, rad, rr * (0.55f + 0.45f * kOut), rr * (0.6f + 0.4f * kOut), rr * (0.55f + 0.45f * kOut), T3_RBC_DEOXY, T3_RBC, 1f, 0.12f)
        val mid = lerpV(cIn, cOut, 0.5f); val nl = (cOut - cIn).len()
        drawScaled(cylinder, mid, rad, f.d, 0.12f, 0.12f, nl * 0.5f, T3_RBC_DEOXY, T3_RBC, 1f, 0.12f)
    }
    // Sluggish, cell-rich venous blood: blocks of deoxygenated red cells (haematocrit ~25%) with
    // short rouleaux in the slow centre stream, a cell-poor layer at the wall, creeping along.
    run {
        val tileL = 3f
        val tiles = Array(4) { k -> cached("sp_block$k") { redCellBlock(b, um, tileL, 8f, 60 + k, false, 0.9f, 2) } }
        val f0 = rfv(b, 0f)
        val camA = (V3(camNowX, camNowY, camNowZ) - f0.c).dot(f0.d)
        val off = (seconds * 0.3f) % tileL
        var k = 0
        var a = -8f - tileL + off
        while (a < 24f) {
            if (!(a + tileL > camA - 1f && a < camA + 2.2f)) {
                val f = rfv(b, a)
                drawLocal(tiles[k % 4], f.c, f.d, f.radial(k * 1.7f), T3_RBC_DEOXY, T3_RBC_RIM3, 1f, 0.05f)
            }
            a += tileL; k++
        }
    }
    // Neutrophils reaching toward the nearest chains with a pseudopod.
    val pmns = sepsisPmns(b, um)
    for ((w, pm) in pmns.withIndex()) {
        val (c0, nrm, f) = pm
        val reach = 0.5f + 0.4f * sin(seconds * 0.6f + w * 1.7f)
        val c = f.c + (c0 - f.c) * dil
        // a thin lamellipodium spreading along the wall from the dome's leading edge, downstream
        val len = 0.5f + 0.3f * reach
        val p = c + f.d * (PMN_R * 0.85f) + nrm * 0.03f
        drawScaled(sphere, p, f.d, nrm, 0.6f, 0.02f, len, T3_LEUKOCYTE, T3_LEUKOCYTE, 1f, 0.1f)
    }
    // The bacteria: a handful of short chains (2-8 cocci), dividing; every 8 s one is engulfed by a
    // neutrophil (drawn into its pseudopod over 2 s) and another arrives from upstream.
    val rc = 1.0f / 2f / um
    val nChains = 5
    val ev = floor(t / 8f).toInt(); val evT = t - ev * 8f
    fun chainPos(k: Int): V3 {
        val gen = (ev + (nChains - 1 - k)) / nChains
        val age = t - gen * 8f * nChains + k * 3f
        val a = -2f + ((k * 3.1f + gen * 1.7f + age * 0.3f) % 12f)
        val rho = (0.3f + 0.35f * ((k * 29 % 10) / 10f)) * radiusAt(b, a) * 0.9f
        return rfv(b, a).pol(k * 2.399f + gen, rho)
    }
    for (k in 0 until nChains) {
        val gen = (ev + (nChains - 1 - k)) / nChains               // how many times this slot has been eaten
        val fresh = ((ev - 1) % nChains + nChains) % nChains == k && ev > 0
        val eatenNow = ev % nChains == k && evT > 5f
        val age = t - gen * 8f * nChains + k * 3f
        val len = (2 + (age / 5f).toInt()).coerceIn(2, 8)
        val mesh = cached("sp_chain${len}_${k % 3}") { chainMesh(len, rc, 200 + len * 3 + k % 3) }
        val a = -2f + ((k * 3.1f + gen * 1.7f + age * 0.3f) % 12f)
        val th = k * 2.399f + gen
        val rho = (0.3f + 0.35f * ((k * 29 % 10) / 10f)) * radiusAt(b, a) * 0.9f
        val f = rfv(b, a)
        var p = f.pol(th, rho)
        var alpha = 1f
        if (eatenNow) {
            // drawn into the pseudopod of the nearest neutrophil
            val (c, nrm, pf) = pmns[ev % pmns.size]
            val q = smooth01((evT - 5f) / 2f)
            p = lerpV(p, c + pf.d * (PMN_R * 1.2f) + nrm * 0.1f, q)
            alpha = 1f - smooth01((evT - 7f) / 1f)
        }
        val tumble = seconds * 0.2f + k * 1.3f
        // the new chain is born by division: it splits off the end of another and drifts apart
        if (fresh) { val parent = chainPos((k + 1) % nChains); p = lerpV(parent + f.d * 0.25f, p, smooth01(evT / 4f)) }
        drawLocal(mesh, p, f.d * cos(tumble) + f.s * sin(tumble), f.u, T3_GRAM_POS, T3_GRAM_POS, alpha, 0.45f)
    }
    // Plasma streaming out through each gap into the tissue.
    linesBegin(2f)
    for ((k, h) in holes.withIndex()) {
        val (ha, hth) = h
        val f = rfv(b, ha); val R = radiusAt(b, ha) * 0.9f * dil
        for (q in 0 until 6) {
            val ph = ((seconds * 0.3f / 1.6f + q / 6f + k * 0.13f) % 1f)
            val off = f.d * (0.15f * sin(q * 2.1f)) + f.d.cross(f.radial(hth)).unit() * (0.12f * cos(q * 1.7f))
            val p0 = f.pol(hth, R - 0.2f + ph * 1.6f) + off
            line(p0, p0 + f.radial(hth) * 0.3f, T3_OEDEMA, 0.6f * (1f - ph) * (0.3f + 0.7f * leak))
        }
    }
    linesEnd()
    GLES20.glDepthMask(false)
    // ...and soaking the tissue: an interstitial haze that thickens as the leak goes on.
    drawMesh(cached("sp_haze") { sepsisMesh(b, um, 3) }, T3_OEDEMA, T3_OEDEMA, 0.22f * leak, 0f, 0.3f)
    drawMeshRadial(tube, b, dil, T3_ENDOTHELIUM, T3_JUNCTION, 0.32f, 0f, 0.12f)
    GLES20.glDepthMask(true)
}

/** Small gaps opened between endothelial cells (~3 x 4 µm): (along, angle). */
private val SEPSIS_GAPS = listOf(0.8f to 30f * DEG, 3.2f to 160f * DEG, 5.5f to 300f * DEG, 7.8f to 95f * DEG, 10.2f to 220f * DEG)

/** The adherent neutrophils: centre, face normal (toward the axis), rail frame. */
private fun StereoBodyRenderer.sepsisPmns(b: Float, um: Float): List<Triple<V3, V3, RF>> = cachedValue("sp_pmns2") {
    // adherent and crawling: flattened domes centred on the endothelium
    listOf(0f to 200f, 3f to 310f, 6f to 60f, 9f to 150f).map { (a, deg) ->
        val f = rfv(b, a); val th = deg * DEG
        val c = f.pol(th, radiusAt(b, a) * 0.9f - 0.02f)
        Triple(c, (f.c - c).unit(), f)
    }
}

/** A neutrophil crawling on the wall: a dome ~16 µm across and ~3 µm high. */
private const val PMN_R = 1.0f
private const val PMN_FLAT = 0.35f

/**
 * Around and on the venule: collagen of the tissue (0), leaked red cells (1), thin outlines of the
 * gaps (2), the oedema haze (3), pericytes (5) and their nuclei (6), microthrombi — platelets (7),
 * fibrin (8), caught red cells (9) — and the adherent neutrophils: bodies (10), segmented nuclei
 * (11), granules (12), phagosomes (13) and the cocci inside them (14).
 */
private fun StereoBodyRenderer.sepsisMesh(b: Float, um: Float, part: Int): T3Mesh {
    val mb = Mb()
    val rnd = java.util.Random(101L + part)
    when (part) {
        2 -> for ((ha, hth) in SEPSIS_GAPS) {
            val pts = (0..24).map { q ->
                val ang = q * 2f * PI.toFloat() / 24f
                val a = ha + 0.2f * cos(ang)
                rfv(b, a).pol(hth + 0.07f * sin(ang), radiusAt(b, a) * 0.9f - 0.01f)
            }
            mb.tube(pts, FloatArray(25) { 0.012f }, 4, false)
        }
        3 -> return railTube(b, -8f, 26f, (radiusAt(b, 0f) + 1.2f) / radiusAt(b, 0f), true).build(twoSided = true)
        0 -> for (k in 0 until 8) {
            val th = rnd.nextFloat() * 2f * PI.toFloat()
            val a0 = -5f + rnd.nextFloat() * 16f
            val lift = 1.2f + 1.6f * rnd.nextFloat()
            val pts = (0..10).map { q -> val a = a0 + (q - 5) * 0.9f; rfv(b, a).pol(th + 0.5f * sin(q * 0.7f + k), radiusAt(b, a) + lift + 0.4f * sin(q * 1.1f)) }
            mb.tube(pts, FloatArray(11) { 0.07f }, 5, true)
        }
        1 -> for (h in SEPSIS_GAPS) {
            // leaked red cells lying in the oedematous tissue just outside each gap
            val (ha, hth) = h
            for (q in 0 until 2) {
                val f = rfv(b, ha + (q - 0.5f) * 1.3f)
                val c = f.pol(hth + (q - 0.5f) * 0.35f, radiusAt(b, ha) + 1.1f + 0.5f * q)
                mb.redCell(c, (f.radial(hth) + f.d * (rnd.nextFloat() - 0.5f)).unit(), 7.5f / 2f / um, 4, 12)
            }
        }
        5, 6 -> for ((k, a) in floatArrayOf(-1.5f, 2.5f, 6.5f, 11f).withIndex()) {
            // pericytes: flattened cells on the outer surface, their processes wrapping the venule
            val th0 = (k * 97f + 20f) * DEG
            val f = rfv(b, a); val R = radiusAt(b, a) * 0.9f + 0.05f
            val rad = f.radial(th0); val tg = f.d.cross(rad).unit()
            if (part == 5) {
                mb.ellipsoid(f.pol(th0, R + 0.03f), rad * 0.06f, f.d * 0.8f, tg * 0.5f, 6, 14)
                for (q in 0 until 3) {
                    val off = (q - 1) * 0.5f
                    val pts = (0..10).map { j -> val t2 = th0 + (if (q % 2 == 0) 1f else -1f) * j / 10f * 1.9f; rfv(b, a + off).pol(t2, R) }
                    mb.tube(pts, FloatArray(11) { 0.03f }, 6, true)
                }
            } else mb.ellipsoid(f.pol(th0, R + 0.07f), rad * 0.03f, f.d * 0.25f, tg * 0.12f, 5, 10)
        }
        7, 8, 9 -> for ((a, deg) in listOf(2.0f to 120f, 8.5f to 330f)) {
            val f = rfv(b, a); val th = deg * DEG
            val R = radiusAt(b, a) * 0.9f
            val base = f.pol(th, R - 0.02f); val inw = -f.radial(th); val tg = f.d.cross(f.radial(th)).unit()
            when (part) {
                7 -> for (q in 0 until 12) {
                    // 12 platelets heaped into a mound ~12 µm across on the wall
                    val ang = q * 2.399f; val rr = 0.55f * sqrt(q / 12f)
                    val c = base + f.d * (cos(ang) * rr) + tg * (sin(ang) * rr) + inw * (0.12f + 0.25f * (1f - rr / 0.6f))
                    val ax = (inw + f.d * (rnd.nextFloat() - 0.5f) + tg * (rnd.nextFloat() - 0.5f)).unit()
                    mb.ellipsoidAxes(c, ax, 0.05f, perp(ax), 0.16f, 0.15f, 4, 8)
                }
                8 -> for (q in 0 until 10) {
                    // fibrin trailing downstream from the mound's base, lying on the wall in a narrow fan
                    val ang = (q - 4.5f) / 4.5f * 15f * DEG
                    val len = 1f + 1.5f * rnd.nextFloat()
                    val p0 = base + f.d * 0.3f + inw * 0.03f + tg * ((q - 4.5f) * 0.04f)
                    val pts = (0..6).map { j ->
                        val t = j / 6f
                        val along = f.d * cos(ang) + tg * sin(ang)
                        val q2 = p0 + along * (len * t)
                        val ra = rfv(b, a + (q2 - base).dot(f.d)); val rel = q2 - ra.c
                        val rr = (rel - ra.d * rel.dot(ra.d)).len()
                        q2 + (rel - ra.d * rel.dot(ra.d)).unit() * ((radiusAt(b, a) * 0.9f - 0.06f - 0.03f * sin(t * 9f + q)) - rr)
                    }
                    mb.tube(pts, FloatArray(7) { 0.02f }, 5, true)
                }
                else -> for (q in 0 until 3) {
                    val c = base + f.d * (1.0f + q * 0.5f) + tg * ((q - 1) * 0.35f) + inw * 0.2f
                    mb.redCell(c, (inw + f.d * (q - 1f) * 0.3f).unit(), 7.5f / 2f / um, 4, 12)
                }
            }
        }
        else -> for ((w, pm) in sepsisPmns(b, um).withIndex()) {
            val (c, nrm, f) = pm
            val r = PMN_R
            val (fc, fr) = faceOf(c, nrm, r, PMN_FLAT, 0.4f)
            when (part) {
                10 -> mb.sectionedCell(c, nrm, r, PMN_FLAT, 0.4f, 10, 20)
                11 -> mb.nucleusOnFace(fc, nrm, fr, 1, w * 3 + 1)
                12 -> mb.granulesOnFace(fc, nrm, fr, 30, 0.035f, 70 + w)
                13 -> { val e1 = perp(nrm); mb.inlay(fc + e1 * (fr * 0.55f), nrm, e1, 0.3f, 0.3f, 0.009f) }
                else -> {
                    // cocci of an engulfed chain inside the phagosome
                    val e1 = perp(nrm); val e2 = nrm.cross(e1).unit()
                    for (q in 0 until 4) mb.inlay(fc + e1 * (fr * 0.55f) + e2 * ((q - 1.5f) * 0.12f), nrm, e1, 0.06f, 0.06f, 0.02f)
                }
            }
        }
    }
    return mb.build()
}
