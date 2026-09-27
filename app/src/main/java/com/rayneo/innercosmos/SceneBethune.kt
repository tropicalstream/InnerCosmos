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
    0 to DriftSpec.of(BodyField.DUST to 1f, density = 0.25f, flow = Float.NaN),
    // A 1.2 mm Mote in a donor's vein: every cell is below the resolution of the scene.
    1 to DriftSpec.NONE,
    // Stored blood: settled, still. The cells are drawn lying on the floor, not drifting.
    2 to DriftSpec.NONE,
    // The wound: blood seeping slowly through the torn tissue.
    3 to DriftSpec.of(BodyField.RED_CELL to 0.93f, BodyField.PLATELET to 0.07f, density = 0.35f, flow = 0.35f),
    // The transfusion: a vessel refilling from 15% to full over forty seconds, flowing with the craft.
    4 to DriftSpec.of(BodyField.RED_CELL to 0.94f, BodyField.PLATELET to 0.055f, BodyField.WHITE_CELL to 0.005f, flow = 1.1f, fill = 40f),
    // The table, at 12 mm: cells are far below the resolution of the scene.
    5 to DriftSpec.NONE,
    // A marrow sinusoid: slow flow, new red cells and platelets.
    6 to DriftSpec.of(BodyField.RED_CELL to 0.84f, BodyField.PLATELET to 0.16f, density = 0.4f, flow = 0.35f),
    // The cut: tissue, not a vessel; the few red cells in the cleft are part of the scene.
    7 to DriftSpec.NONE,
    // Septicaemia: sluggish venous blood (the neutrophils are drawn by the scene).
    8 to DriftSpec.of(BodyField.RED_CELL to 0.93f, BodyField.PLATELET to 0.07f, density = 0.7f, flow = 0.55f, oxy = false),
)

// ------------------------------------------------------------------ palette
internal val T3_ALVEOLUS = floatArrayOf(0.95f, 0.77f, 0.77f, 1f)
internal val T3_SEPTUM = floatArrayOf(0.99f, 0.90f, 0.90f, 1f)
internal val T3_CAPILLARY = floatArrayOf(0.88f, 0.20f, 0.24f, 1f)
internal val T3_PARENCHYMA = floatArrayOf(0.62f, 0.44f, 0.46f, 1f)
internal val T3_CASEUM = floatArrayOf(0.94f, 0.88f, 0.62f, 1f)
internal val T3_CASEUM_DEEP = floatArrayOf(0.80f, 0.74f, 0.52f, 1f)
internal val T3_CASEUM_MID = floatArrayOf(0.84f, 0.76f, 0.52f, 1f)
internal val T3_CASEUM_DEPTH = floatArrayOf(0.70f, 0.62f, 0.42f, 1f)
internal val T3_SCAR = floatArrayOf(0.84f, 0.80f, 0.80f, 1f)
internal val T3_ANTHRACOTIC = floatArrayOf(0.25f, 0.22f, 0.24f, 1f)
internal val T3_GRANULOMA = floatArrayOf(0.84f, 0.68f, 0.72f, 1f)
internal val T3_EPITHELIOID = floatArrayOf(0.93f, 0.80f, 0.78f, 1f)
internal val T3_FIBROUS = floatArrayOf(0.96f, 0.94f, 0.89f, 1f)
internal val T3_LANGHANS = floatArrayOf(0.94f, 0.80f, 0.88f, 1f)
internal val T3_NUCLEUS = floatArrayOf(0.44f, 0.30f, 0.68f, 1f)
internal val T3_LYMPHOCYTE = floatArrayOf(0.42f, 0.40f, 0.78f, 1f)
internal val T3_INTIMA = floatArrayOf(0.74f, 0.64f, 0.68f, 1f)
internal val T3_INTIMA_RIM = floatArrayOf(0.62f, 0.52f, 0.56f, 1f)
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
internal val T3_STORED_RBC = floatArrayOf(0.50f, 0.06f, 0.12f, 1f)
internal val T3_PACKED = floatArrayOf(0.40f, 0.05f, 0.09f, 1f)
internal val T3_PLASMA = floatArrayOf(0.96f, 0.86f, 0.50f, 1f)
internal val T3_PLASMA_BG = floatArrayOf(0.86f, 0.80f, 0.56f, 1f)
internal val T3_GLASS_COLD = floatArrayOf(0.60f, 0.78f, 0.98f, 1f)
internal val T3_GLASS_EDGE = floatArrayOf(0.90f, 0.95f, 1f, 1f)
internal val T3_LEVEL = floatArrayOf(1f, 0.95f, 0.80f, 1f)
internal val T3_GLASS = floatArrayOf(0.72f, 0.86f, 0.98f, 1f)
internal val T3_PLATELET = floatArrayOf(0.96f, 0.82f, 0.52f, 1f)
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
internal val T3_RBC = floatArrayOf(0.86f, 0.12f, 0.14f, 1f)
internal val T3_RBC_DEOXY = floatArrayOf(0.56f, 0.07f, 0.13f, 1f)
internal val T3_ENDOTHELIUM = floatArrayOf(0.94f, 0.82f, 0.85f, 1f)
internal val T3_JUNCTION = floatArrayOf(0.94f, 0.80f, 0.84f, 1f)
internal val T3_ENDO_NUCLEUS = floatArrayOf(0.80f, 0.64f, 0.82f, 1f)
internal val T3_TISSUE_DARK = floatArrayOf(0.20f, 0.11f, 0.15f, 1f)
internal val T3_ISCHAEMIC = floatArrayOf(0.52f, 0.50f, 0.60f, 1f)
internal val T3_ISCHAEMIC_A = floatArrayOf(0.40f, 0.38f, 0.50f, 1f)
internal val T3_PERFUSED = floatArrayOf(0.78f, 0.24f, 0.26f, 1f)
internal val T3_PERFUSED_A = floatArrayOf(0.56f, 0.12f, 0.16f, 1f)
internal val T3_SMC = floatArrayOf(0.82f, 0.52f, 0.54f, 1f)
internal val T3_CAP_EMPTY = floatArrayOf(0.40f, 0.30f, 0.40f, 1f)
internal val T3_SKIN_EDGE = floatArrayOf(0.74f, 0.52f, 0.42f, 1f)
internal val T3_SKIN_TOP = floatArrayOf(0.95f, 0.76f, 0.66f, 1f)
internal val T3_DERMIS = floatArrayOf(0.96f, 0.80f, 0.78f, 1f)
internal val T3_FAT = floatArrayOf(0.99f, 0.88f, 0.52f, 1f)
internal val T3_FAT_FACE = floatArrayOf(0.96f, 0.82f, 0.44f, 1f)
internal val T3_SEPTA = floatArrayOf(0.98f, 0.92f, 0.88f, 1f)
internal val T3_CUT_VESSEL = floatArrayOf(0.55f, 0.08f, 0.12f, 1f)
internal val T3_FASCIA = floatArrayOf(0.97f, 0.96f, 0.92f, 1f)
internal val T3_MUSCLE = floatArrayOf(0.76f, 0.22f, 0.24f, 1f)
internal val T3_MUSCLE_LINE = floatArrayOf(0.56f, 0.14f, 0.18f, 1f)
internal val T3_GAUZE = floatArrayOf(0.97f, 0.97f, 0.94f, 1f)
internal val T3_GAUZE_WET = floatArrayOf(0.70f, 0.22f, 0.20f, 1f)
internal val T3_BLEED = floatArrayOf(0.90f, 0.10f, 0.12f, 1f)
internal val T3_CORD = floatArrayOf(0.50f, 0.32f, 0.48f, 1f)
internal val T3_MEGA = floatArrayOf(0.88f, 0.74f, 0.92f, 1f)
internal val T3_MEGA_NUCLEUS = floatArrayOf(0.40f, 0.20f, 0.60f, 1f)
internal val T3_MEGA_GRANULE = floatArrayOf(0.96f, 0.72f, 0.90f, 1f)
internal val T3_MACROPHAGE = floatArrayOf(0.82f, 0.86f, 0.74f, 1f)
internal val T3_EB_EARLY = floatArrayOf(0.46f, 0.40f, 0.80f, 1f)
internal val T3_EB_MID = floatArrayOf(0.66f, 0.46f, 0.72f, 1f)
internal val T3_EB_LATE = floatArrayOf(0.90f, 0.46f, 0.52f, 1f)
internal val T3_EB_NUCLEUS = floatArrayOf(0.30f, 0.20f, 0.46f, 1f)
internal val T3_RETICULOCYTE = floatArrayOf(0.88f, 0.44f, 0.58f, 1f)
internal val T3_ADIPOCYTE = floatArrayOf(0.99f, 0.95f, 0.78f, 1f)
internal val T3_MYELOCYTE = floatArrayOf(0.92f, 0.86f, 0.82f, 1f)
internal val T3_BONE = floatArrayOf(0.96f, 0.91f, 0.78f, 1f)
internal val T3_OSTEOBLAST = floatArrayOf(0.74f, 0.64f, 0.88f, 1f)
internal val T3_LINING = floatArrayOf(0.88f, 0.84f, 0.90f, 1f)
internal val T3_OSTEOID = floatArrayOf(0.96f, 0.80f, 0.84f, 1f)
internal val T3_GRAN_NUCLEUS = floatArrayOf(0.40f, 0.30f, 0.66f, 1f)
internal val T3_STEM = floatArrayOf(0.82f, 0.87f, 0.98f, 1f)
internal val T3_CORNEUM = floatArrayOf(0.96f, 0.84f, 0.86f, 1f)
internal val T3_CORN_BACK = floatArrayOf(0.92f, 0.80f, 0.82f, 1f)
internal val T3_GRAN_BACK = floatArrayOf(0.88f, 0.70f, 0.72f, 1f)
internal val T3_EPI_BACK = floatArrayOf(0.90f, 0.68f, 0.70f, 1f)
internal val T3_LUCIDUM = floatArrayOf(0.97f, 0.94f, 0.88f, 1f)
internal val T3_GRANULAR = floatArrayOf(0.90f, 0.72f, 0.72f, 1f)
internal val T3_KERATOHYALIN = floatArrayOf(0.36f, 0.22f, 0.44f, 1f)
internal val T3_SPINOUS = floatArrayOf(0.94f, 0.70f, 0.72f, 1f)
internal val T3_BASAL = floatArrayOf(0.80f, 0.48f, 0.60f, 1f)
internal val T3_BASEMENT = floatArrayOf(0.99f, 0.95f, 0.95f, 1f)
internal val T3_COLLAGEN = floatArrayOf(0.99f, 0.86f, 0.86f, 1f)
internal val T3_INTERSTITIUM = floatArrayOf(0.44f, 0.28f, 0.30f, 1f)
internal val T3_OEDEMA = floatArrayOf(0.98f, 0.88f, 0.56f, 1f)
internal val T3_GAP_RIM = floatArrayOf(1f, 0.95f, 0.75f, 1f)
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
        val a = ax.unit(); val e1 = perp(a); val e2 = a.cross(e1).unit()
        fun p(u: Float, v: Float): V3 {
            val top = u < 0.5f
            val rr = (if (top) 1f - u * 2f else (u - 0.5f) * 2f).coerceIn(0f, 0.999f)
            val q = rr * rr
            val h = 0.5f * sqrt(1f - q) * (0.81f + 7.83f * q - 4.39f * q * q) / 3.91f
            val th = v * 2f * PI.toFloat()
            return c + (e1 * cos(th) + e2 * sin(th)) * (rr * r) + a * (if (top) h * r else -h * r)
        }
        gridFd(rings * 2, sl, ::p) { u, _, q -> val rel = q - c; if (rel.len() < 1e-4f) (if (u < 0.5f) a else -a) else rel }
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
private fun StereoBodyRenderer.openWall(i: Int, a0: Float, a1: Float, paint: FloatArray?): Boolean {
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
    drawLitModel(tube, paint ?: T3_BLACK, T3_BLACK, landmarkFade, 0f, 0f)
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
                                                  cellLen: Float, around: Int, holes: List<Pair<Float, Float>> = emptyList()): Triple<LitMesh, LitMesh, LitMesh> {
    val tube = cached("${key}_tube") {
        val mb = Mb()
        val st = ((a1 - a0) / 0.4f).toInt()
        val sl = around * 3
        // Holes (gaps opened between cells) are left out of the sheet: (along, angle) centres.
        val P = Array(st + 1) { i -> Array(sl + 1) { j -> val a = a0 + (a1 - a0) * i / st; rfv(b, a).pol(j * 2f * PI.toFloat() / sl, rAt(a)) } }
        val C = Array(st + 1) { i -> rfv(b, a0 + (a1 - a0) * i / st).c }
        for (i in 0 until st) for (j in 0 until sl) {
            val a = a0 + (a1 - a0) * (i + 0.5f) / st; val th = (j + 0.5f) * 2f * PI.toFloat() / sl
            if (holes.any { (ha, hth) -> abs(a - ha) < 0.55f && angDiff(th, hth) < 0.16f }) continue
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
                // Flattened, elongated along the vessel, bulging into the lumen.
                mb.ellipsoid(f.pol(th, rAt(a) - 0.05f), f.radial(th) * 0.08f, f.d * (cellLen * 0.16f), f.d.cross(f.radial(th)).unit() * 0.22f, 5, 10)
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
            mb.sphere(V3(x, y, z - r * 0.22f), r * 0.92f, 5, 8)
            mb.sphere(V3(x, y, z + r * 0.62f), r * 0.86f, 5, 8)
        } else mb.sphere(V3(x, y, z), r, 5, 8)
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

// ============================================================== the stops

/**
 * Stop 1 (THE CAVITY, 120 µm Mote: 1 unit = 80 µm): a respiratory bronchiole / alveolar duct whose
 * wall is a honeycomb of alveoli — cups ~220 µm across opening onto the duct, rimmed by septa that
 * carry the capillaries, all breathing together. Ahead to port the wall is breached by a small
 * tuberculous cavity (~1 mm across) that has drained into this airway: through its mouth the crew
 * look into the hollow, lined by soft cheesy caseum, and the cut lip shows the wall in section —
 * caseum, a granulomatous band of epithelioid cells and Langhans giant cells (horseshoe nuclei) with
 * a lymphocyte cuff, and the white fibrous capsule. Crumbs of caseum drift out of the mouth and away
 * up the airway (coughed up). Two satellite tubercles sit in the nearby wall.
 */
internal fun StereoBodyRenderer.drawCavity(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    val um = umu(i)
    if (!openWall(i, -8f, 34f, T3_PARENCHYMA)) return
    val g = cavityGeometry(b)
    // Every alveolus inflates together, ~13 breaths a minute.
    val breath = 1f + 0.035f * sin(seconds * 2f * PI.toFloat() / 4.6f)
    drawMeshRadial(cached("cav_cups") { cavityAlveoli(b, g, um, 0) }, b, breath, T3_ALVEOLUS, T3_SEPTUM, 1f, 0f, 0.12f)
    drawMeshRadial(cached("cav_rims") { cavityAlveoli(b, g, um, 1) }, b, breath, T3_SEPTUM, T3_SEPTUM, 1f, 0f, 0.2f)
    drawMeshRadial(cached("cav_caps") { cavityAlveoli(b, g, um, 2) }, b, breath, T3_CAPILLARY, T3_CAPILLARY, 0.85f, 0f, 0.05f)
    // Next to the cavity the lung is scarred and collapsed: shrunken alveoli that no longer
    // inflate, then a pad of grey-white fibrous scar with anthracotic pigment round the mouth.
    drawMesh(cached("cav_dead") { cavityAlveoli(b, g, um, 3) }, T3_SCAR, T3_SCAR, 1f, 0f, 0.1f)
    drawMesh(cached("cav_deadrim") { cavityAlveoli(b, g, um, 4) }, T3_SCAR, T3_FIBROUS, 1f, 0f, 0.15f)
    drawMesh(cached("cav_scar") { cavityAlveoli(b, g, um, 5) }, T3_SCAR, T3_FIBROUS, 1f, 0f, 0.12f)
    drawMesh(cached("cav_soot") { cavityAlveoli(b, g, um, 6) }, T3_ANTHRACOTIC, T3_ANTHRACOTIC, 1f, 0f, 0f)

    // The cavity: its caseous lining, shading from the pale lip down to the depths, and the
    // layered lip of its mouth (the fibrous capsule is seen only in that section).
    drawMesh(cached("cav_inner") { cavityShell(g, 1) }, T3_CASEUM, T3_CASEUM, 1f, 0f, 0.25f)
    drawMesh(cached("cav_inner2") { cavityShell(g, 3) }, T3_CASEUM_MID, T3_CASEUM_MID, 1f, 0f, 0.1f)
    drawMesh(cached("cav_inner3") { cavityShell(g, 4) }, T3_CASEUM_DEPTH, T3_CASEUM_DEPTH, 1f, 0f, 0.02f)
    drawMesh(cached("cav_lumps") { cavityShell(g, 2) }, T3_CASEUM_MID, T3_CASEUM, 1f, 0f, 0.1f)
    drawMesh(cached("cav_lip0") { cavityLip(g, 0) }, T3_CASEUM, T3_CASEUM, 1f, 0f, 0.25f)
    drawMesh(cached("cav_lip1") { cavityLip(g, 1) }, T3_GRANULOMA, T3_GRANULOMA, 1f, 0f, 0.15f)
    drawMesh(cached("cav_lip2") { cavityLip(g, 2) }, T3_FIBROUS, T3_FIBROUS, 1f, 0f, 0.15f)
    drawMesh(cached("cav_epi") { cavityLip(g, 3) }, T3_EPITHELIOID, T3_FIBROUS, 1f, 0f, 0.1f)
    drawMesh(cached("cav_giant") { cavityLip(g, 4) }, T3_LANGHANS, T3_FIBROUS, 1f, 0f, 0.12f)
    drawMesh(cached("cav_gnuc") { cavityLip(g, 5) }, T3_NUCLEUS, T3_NUCLEUS, 1f, 0f, 0.25f)
    drawMesh(cached("cav_lymph") { cavityLip(g, 6) }, T3_LYMPHOCYTE, T3_LYMPHOCYTE, 1f, 0f, 0.3f)
    // Satellite tubercles: a caseous core in a grey-white granuloma.
    drawMesh(cached("cav_tub_core") { cavityTubercles(b, 0) }, T3_CASEUM, T3_CASEUM, 1f, 0f, 0.3f)

    // Caseous crumbs: liquefied debris drifting out of the mouth and away up the airway.
    for (k in 0 until (if (quality == 0) 5 else 2)) {
        val t = ((seconds / 16f + k * 0.2f) % 1f)
        val r = (12f + 6f * (k % 3)) / um
        val p: V3
        if (t < 0.35f) {
            // inside the hollow, easing toward the mouth
            val q = smooth01(t / 0.35f)
            val start = g.centre + g.inward * (g.rc * 0.2f) + g.e1 * (1.2f * cos(k * 2.1f)) + g.e2 * (1.2f * sin(k * 2.1f))
            p = lerpV(start, g.mouth + g.e1 * (0.6f * cos(k * 1.3f)), q)
        } else {
            // out through the mouth and back along the airway, toward the trachea
            val q = (t - 0.35f) / 0.65f
            val a = g.mouthAlong - q * 13f
            val th = g.mouthAngle + 0.4f * sin(k * 1.7f + q * 3f)
            p = rfv(b, a).pol(th, lerp(g.rimR, 1.7f, smooth01(q * 3f)))
        }
        drawSphereAt(p.x, p.y, p.z, r, r * 0.8f, r * 1.1f, T3_CASEUM, T3_CASEUM, 1f - smooth01((t - 0.85f) / 0.15f), k * 50f + seconds * 20f, 0.3f, 1f, 0.2f, blob, 0f, 0.3f)
    }
    // The tubercles' translucent granulomatous coat last, so their cores show through.
    GLES20.glDepthMask(false)
    drawMesh(cached("cav_tub_coat") { cavityTubercles(b, 1) }, T3_GRANULOMA, T3_FIBROUS, 0.55f, 0f, 0.1f)
    GLES20.glDepthMask(true)
}

/** Where the cavity sits: its mouth on the port-upper wall ahead, turned a little toward the craft. */
private class CavityGeom(
    val mouth: V3, val inward: V3, val e1: V3, val e2: V3, val centre: V3,
    val rm: Float, val rc: Float, val rimR: Float, val mouthAlong: Float, val mouthAngle: Float
)

private fun StereoBodyRenderer.cavityGeometry(b: Float): CavityGeom = cachedValue("cav_geom") {
    val um = umu(0)
    val along = 8.5f; val angle = 158f * DEG
    val f = rfv(b, along)
    val rimR = radiusAt(b, along) - 1.45f
    val mouth = f.pol(angle, rimR)
    // The mouth faces the axis, tilted 20° back toward the approaching craft.
    val inward = (-f.radial(angle) * cos(20f * DEG) - f.d * sin(20f * DEG)).unit()
    val e1 = perp(inward); val e2 = inward.cross(e1).unit()
    val rm = 165f / um          // mouth ~330 µm across
    val rc = 480f / um          // cavity ~1 mm across
    val centre = mouth - inward * sqrt(rc * rc - rm * rm)
    CavityGeom(mouth, inward, e1, e2, centre, rm, rc, rimR, along, angle)
}

/**
 * The duct's wall: breathing alveolar cups (part 0), their septal rims (1) and the capillary mesh
 * in their walls (2); round the cavity, collapsed scarred alveoli (3) and their rims (4), the
 * fibrous scar pad at the mouth (5) and anthracotic pigment in it (6).
 */
private fun StereoBodyRenderer.cavityAlveoli(b: Float, g: CavityGeom, um: Float, part: Int): T3Mesh {
    val mb = Mb()
    val tub = tubercleSpots(b)
    val pad = g.rm + 2.6f
    if (part == 5 || part == 6) {
        // The scar pad: the duct wall round the mouth, from under the cut lip out to the first cups.
        val re = g.rc + 0.8f
        val dMouth = sqrt(g.rc * g.rc - g.rm * g.rm)
        val rOuter = sqrt(re * re - dMouth * dMouth)
        fun planar(p: V3): Float { val v = p - g.mouth; return (v - g.inward * v.dot(g.inward)).len() }
        fun padAt(a: Float, th: Float): V3 {
            val w = 0.08f * sin(a * 5.3f + th * 7.1f) * sin(a * 2.1f - th * 4.3f)
            return rfv(b, a).pol(th, radiusAt(b, a) - 1.45f + 0.06f + w)
        }
        if (part == 5) {
            val na = 48; val nt = 40
            val a0 = g.mouthAlong - 7f; val a1 = g.mouthAlong + 7f
            val t0 = g.mouthAngle - 1.7f; val t1 = g.mouthAngle + 1.7f
            val P = Array(na + 1) { i -> Array(nt + 1) { j -> padAt(a0 + (a1 - a0) * i / na, t0 + (t1 - t0) * j / nt) } }
            val C = Array(na + 1) { i -> rfv(b, a0 + (a1 - a0) * i / na).c }
            for (i in 0 until na) for (j in 0 until nt) {
                val q = arrayOf(P[i][j], P[i + 1][j], P[i + 1][j + 1], P[i][j + 1])
                val mid = (q[0] + q[2]) * 0.5f
                if ((mid - g.mouth).len() > pad + 0.5f || planar(mid) < rOuter - 0.3f) continue
                val n0 = C[i] - P[i][j]; val n1 = C[i + 1] - P[i + 1][j]
                mb.tri(q[0], n0, q[1], n1, q[2], n1); mb.tri(q[0], n0, q[2], n1, q[3], n0)
            }
        } else {
            val rnd = java.util.Random(19)
            var k = 0
            while (k < 9) {
                val a = g.mouthAlong + (rnd.nextFloat() - 0.5f) * 12f; val th = g.mouthAngle + (rnd.nextFloat() - 0.5f) * 3f
                val p = padAt(a, th)
                if ((p - g.mouth).len() > pad || planar(p) < rOuter + 0.1f) continue
                val f = rfv(b, a); val rad = f.radial(th)
                mb.ellipsoid(p - rad * 0.03f, rad * 0.05f, f.d * (0.12f + 0.08f * rnd.nextFloat()), rad.cross(f.d).unit() * 0.15f, 4, 8)
                k++
            }
        }
        return mb.build(twoSided = part == 5)
    }
    var row = 0
    var a = -5.5f
    while (a < 13.5f) {
        val f = rfv(b, a)
        val rimR = radiusAt(b, a) - 1.45f
        val count = 5
        val R0 = min(110f / um * 1.18f, rimR * sin(PI.toFloat() / count) * 0.92f)   // ~220 µm mouths
        for (k in 0 until count) {
            val th = (k + (if (row % 2 == 0) 0f else 0.5f)) * 2f * PI.toFloat() / count + 0.3f
            val q = f.pol(th, rimR)
            val dist = (q - g.mouth).len()
            if (dist < pad) continue                                   // the scar pad round the mouth
            if (tub.any { (q - it).len() < 1.9f }) continue           // the tubercles sit in the wall
            val collapsed = dist < g.rm + 5f
            if (collapsed != (part == 3 || part == 4)) continue
            val R = if (collapsed) R0 * 0.7f else R0
            val depth = if (collapsed) 0.4f else 1f
            val out = f.radial(th)                                     // from the duct into the alveolus
            val e1 = f.d; val e2 = out.cross(e1).unit()
            fun cup(ph: Float, t2: Float) = q + out * (R * 1.05f * depth * cos(ph)) + (e1 * cos(t2) + e2 * sin(t2)) * (R * sin(ph))
            when (part) {
                0, 3 -> mb.grid(5, 14, { u, v -> cup((1f - u) * PI.toFloat() / 2f, v * 2f * PI.toFloat()) }) { _, _, p -> q - p }
                1, 4 -> mb.torus(q, out, R, 0.07f, 20, 5)
                else -> {
                    // the capillary mesh in the alveolar wall: three rings and six meridians
                    fun onWall(ph: Float, t2: Float): V3 { val p = cup(ph, t2); return p + (q - p).unit() * 0.03f }
                    for (lat in floatArrayOf(20f, 45f, 70f)) mb.tube((0..16).map { j -> onWall(lat * DEG, j * 2f * PI.toFloat() / 16f) }, FloatArray(17) { 0.035f }, 4, false)
                    for (m in 0 until 6) mb.tube((0..6).map { j -> onWall(j / 6f * 88f * DEG, m * PI.toFloat() / 3f + 0.2f) }, FloatArray(7) { 0.035f }, 4, false)
                }
            }
        }
        a += 2.55f; row++
    }
    return mb.build(twoSided = part == 0 || part == 3)
}

private fun StereoBodyRenderer.tubercleSpots(b: Float): List<V3> = listOf(
    rfv(b, -1.5f).pol(-40f * DEG, radiusAt(b, -1.5f) - 1.1f),
    rfv(b, 3.0f).pol(250f * DEG, radiusAt(b, 3f) - 1.1f),
)

/** Satellite tubercles: caseous cores (0) and their granulomatous coats (1). */
private fun StereoBodyRenderer.cavityTubercles(b: Float, part: Int): T3Mesh {
    val mb = Mb()
    for (p in tubercleSpots(b)) if (part == 0) mb.sphere(p, 0.5f, 7, 12) else mb.sphere(p, 1.05f, 10, 16)
    return mb.build()
}

/** The cavity wall: fibrous capsule seen from outside (0), caseous lining seen from inside (1), caseous lumps (2). */
private fun cavityShell(g: CavityGeom, part: Int): T3Mesh {
    val mb = Mb()
    val c = g.centre; val ax = g.inward
    val e1 = g.e1; val e2 = g.e2
    val dMouth = sqrt(g.rc * g.rc - g.rm * g.rm)
    when (part) {
        0 -> {
            val re = g.rc + 0.8f
            val hole = acos(dMouth / re)            // opens where the capsule crosses the mouth plane
            mb.grid(12, 28, { u, v ->
                val ph = hole + (PI.toFloat() - hole) * u; val th = v * 2f * PI.toFloat()
                c + ax * (re * cos(ph)) + (e1 * cos(th) + e2 * sin(th)) * (re * sin(ph))
            }) { _, _, p -> p - c }
        }
        1, 3, 4 -> {
            // the lining in three depth bands: pale at the lip, darker toward the bottom
            val hole = asin(g.rm / g.rc)
            val (p0, p1) = when (part) { 1 -> hole to 110f * DEG; 3 -> 110f * DEG to 140f * DEG; else -> 140f * DEG to PI.toFloat() }
            mb.grid(8, 28, { u, v ->
                val ph = p0 + (p1 - p0) * u; val th = v * 2f * PI.toFloat()
                c + ax * (g.rc * cos(ph)) + (e1 * cos(th) + e2 * sin(th)) * (g.rc * sin(ph))
            }) { _, _, p -> c - p }
        }
        else -> {
            val rnd = java.util.Random(31)
            for (k in 0 until 16) {
                // soft caseous masses plastered on the far part of the lining, the part seen through the mouth
                val ph = (70f + 100f * rnd.nextFloat()) * DEG; val th = rnd.nextFloat() * 2f * PI.toFloat()
                val dir = ax * cos(ph) + (e1 * cos(th) + e2 * sin(th)) * sin(ph)
                val t1 = perp(dir); val t2 = dir.cross(t1).unit()
                val s = 0.55f + 0.5f * rnd.nextFloat()
                mb.ellipsoid(c + dir * (g.rc - 0.1f), dir * (0.22f + 0.15f * rnd.nextFloat()), t1 * s, t2 * (s * 0.8f), 5, 9)
            }
        }
    }
    return mb.build()
}

/**
 * The cut lip of the cavity mouth, in section from the hollow outward: caseum (0), the
 * granulomatous band (1), the fibrous capsule (2); in the band, epithelioid cells (3), Langhans
 * giant cells (4) with their horseshoe of peripheral nuclei (5), and a cuff of lymphocytes (6).
 */
private fun cavityLip(g: CavityGeom, part: Int): T3Mesh {
    val mb = Mb()
    val re = g.rc + 0.8f
    val dMouth = sqrt(g.rc * g.rc - g.rm * g.rm)
    val rOuter = sqrt(re * re - dMouth * dMouth)
    val r1 = g.rm + (rOuter - g.rm) * 0.32f; val r2 = g.rm + (rOuter - g.rm) * 0.68f
    val n = g.inward; val c = g.mouth
    fun at(r: Float, th: Float, lift: Float) = c + (g.e1 * cos(th) + g.e2 * sin(th)) * r + n * lift
    val giants = floatArrayOf(0.7f, 3.9f)
    when (part) {
        0 -> mb.annulus(c - n * 0.02f, n, g.rm, r1, 36, 0.03f)
        1 -> mb.annulus(c, n, r1, r2, 36, 0.03f)
        2 -> mb.annulus(c + n * 0.02f, n, r2, rOuter + 0.1f, 36, 0.02f)
        3 -> for (k in 0 until 22) {
            val th = k * 2f * PI.toFloat() / 22f + 0.1f * sin(k * 3f)
            if (giants.any { angDiff(th, it) < 0.35f }) continue
            val r = (r1 + r2) / 2f + 0.12f * sin(k * 2.3f)
            val tg = (g.e2 * cos(th) - g.e1 * sin(th))
            mb.ellipsoid(at(r, th, 0.08f), n * 0.09f, tg * 0.24f, (g.e1 * cos(th) + g.e2 * sin(th)) * 0.15f, 4, 8)
        }
        4 -> for (th in giants) {
            val tg = (g.e2 * cos(th) - g.e1 * sin(th))
            mb.ellipsoid(at((r1 + r2) / 2f, th, 0.12f), n * 0.14f, tg * 0.52f, (g.e1 * cos(th) + g.e2 * sin(th)) * 0.36f, 6, 14)
        }
        5 -> for (th in giants) {
            // nuclei ranged round the cell margin in a horseshoe, open toward one side
            val cc = at((r1 + r2) / 2f, th, 0.2f)
            val tg = (g.e2 * cos(th) - g.e1 * sin(th)); val rad = (g.e1 * cos(th) + g.e2 * sin(th))
            for (k in 0 until 11) {
                val a = (-0.75f + 1.5f * k / 10f) * PI.toFloat()
                mb.ellipsoid(cc + tg * (0.4f * cos(a)) + rad * (0.27f * sin(a)), n * 0.05f, tg * 0.07f, rad * 0.06f, 3, 6)
            }
        }
        else -> for (k in 0 until 52) {
            // lymphocytes, ~7 µm
            val th = k * 2f * PI.toFloat() / 52f
            mb.sphere(at(r2 + 0.1f + 0.1f * sin(k * 1.9f), th, 0.05f), 0.045f, 4, 6)
        }
    }
    return mb.build(twoSided = part <= 2)
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
    // The vein's own wall: smooth intima with a few longitudinal folds, bulging out into a sinus
    // behind each valve cusp.
    drawMesh(cached("dn_wall") { veinWall(b, 0) }, T3_INTIMA, T3_INTIMA_RIM, 1f, 0f, 0.08f)
    drawMesh(cached("dn_folds") { veinWall(b, 1) }, T3_INTIMA_FOLD, T3_INTIMA_FOLD, 1f, 0f, 0.05f)
    // The needle: opaque steel, cut away on the side facing the crew (a textbook cutaway) to show
    // the bore full of blood being drawn back; the bevel face with its open bore and polished edge.
    drawMesh(cached("dn_outer") { needleMesh(nd, 0) }, T3_STEEL, T3_STEEL_EDGE, 1f, 0f, 0.1f)
    drawMesh(cached("dn_bore") { needleMesh(nd, 1) }, T3_BORE, T3_BORE, 1f, 0f, 0f)
    drawMesh(cached("dn_bevel") { needleMesh(nd, 2) }, T3_STEEL_EDGE, T3_STEEL_EDGE, 1f, 0f, 0.35f)
    drawMesh(cached("dn_collar") { needleMesh(nd, 3) }, T3_INTIMA, T3_VALVE, 1f, 0f, 0.1f)
    drawMesh(cached("dn_column") { needleMesh(nd, 4) }, T3_BLOOD_IN, T3_BLOOD_IN, 1f, 0f, 0.2f)
    drawMesh(cached("dn_opening") { needleMesh(nd, 5) }, T3_BLOOD_IN, T3_BLOOD_IN, 1f, 0f, 0.1f)
    drawMesh(cached("dn_cutedge") { needleMesh(nd, 6) }, T3_STEEL_EDGE, T3_STEEL_EDGE, 1f, 0f, 0.4f)
    // The valve: cusps flutter a little with each surge of flow, never closing while blood runs forward.
    val flutter = 1f + 0.035f * sin(seconds * 2f * PI.toFloat() / 1.6f)
    drawMeshRadial(cached("dn_cusps") { valveMesh(b, 0) }, b + 8.5f / NODE_UNITS, flutter, T3_VALVE, T3_SEPTUM, 0.92f, 0f, 0.12f)
    drawMeshRadial(cached("dn_edges") { valveMesh(b, 1) }, b + 8.5f / NODE_UNITS, flutter, T3_SEPTUM, T3_SEPTUM, 1f, 0f, 0.35f)
    // The vein is full of dark venous blood (at 0.8 mm per unit a continuous fluid, not cells).
    fillLumen("dn_blood", b, -8f, 34f, 0.84f, T3_VENOUS, 0.22f, 0.15f)
    // Blood flow as streamlines: with the craft, through the valve, and into the bevel.
    val arr = dynLines.data
    var v = 0
    val lines = if (quality == 0) 26 else 14
    for (k in 0 until lines) {
        val th = k * 2.399f
        val rho = 0.9f + 1.5f * ((k * 37 % 100) / 100f)
        val t = ((seconds * 0.09f + k * 0.137f) % 1f)
        val a = -6f + 20f * t
        val squeeze = if (a > 6f && a < 10f) 0.75f + 0.25f * abs(a - 8.5f) / 2.5f else 1f   // narrowing through the valve
        val f0 = rfv(b, a)
        val p0 = f0.pol(th, rho * squeeze); val p1 = p0 + f0.d * 0.7f
        v = putLine(arr, v, p0, p1, T3_FLOW_BRIGHT, 0.8f * (1f - smooth01((t - 0.85f) / 0.15f)))
    }
    for (k in 0 until (if (quality == 0) 6 else 3)) {
        // Drawn into the needle: from beside the shaft, curving into the bevel's opening.
        val t = ((seconds * 0.35f + k / 6f) % 1f)
        val side = if (k % 2 == 0) 1f else -1f
        val start = nd.o + nd.x * (side * 1.6f) - nd.z * 5.5f + nd.y * 0.2f
        val ctrl = nd.o + nd.x * (side * 1.2f) - nd.z * 2.0f + nd.y * 1.3f
        val end = nd.o - nd.z * (nd.bevelLen * 0.5f) + nd.y * 0.1f
        val p0 = bez(start, ctrl, end, t); val p1 = bez(start, ctrl, end, (t + 0.08f).coerceAtMost(1f))
        v = putLine(arr, v, p0, p1, T3_FLOW_BRIGHT, 0.9f * (1f - t * 0.5f))
    }
    for (k in 0 until 3) {
        // and back up the bore, seen through the cutaway
        val t = ((seconds * 0.3f + k / 3f) % 1f)
        val z0 = -1.3f - t * (-1.3f - nd.zWall)
        val c = nd.o + nd.z * z0 + (nd.x * cos(nd.win) + nd.y * sin(nd.win)) * (nd.ri * 0.5f)
        v = putLine(arr, v, c, c - nd.z * 0.5f, T3_FLOW_BRIGHT, 0.9f)
    }
    drawDyn(v, 3f)
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

/**
 * The needle's own frame: origin on its axis at the tip, z forward along the shaft, y toward the
 * bevel (skin side); [win] is the angle (in x/y) of the cutaway window, facing the passage axis;
 * [zWall] where the shaft passes through the vein wall.
 */
private class NeedleFrame(val o: V3, val x: V3, val y: V3, val z: V3, val ro: Float, val ri: Float, val bevelLen: Float, val win: Float, val zWall: Float)

private fun StereoBodyRenderer.needleFrame(b: Float, um: Float): NeedleFrame = cachedValue("needle") {
    val ro = 1270f / 2f / um; val ri = 840f / 2f / um          // 18 G
    val f = rfv(b, 6.0f)
    val ang = 16f * DEG
    val z = (f.d * cos(ang) - f.u * sin(ang)).unit()
    // Bevel up: facing the skin side the needle came in from.
    val yh = f.u
    val y = (yh - z * yh.dot(z)).unit()
    val x = y.cross(z).unit()
    // Off to starboard of the craft's lane, so the crew see the tip side-on: the slanted bevel in profile.
    val o = f.at(1.95f, 0.45f + ro * 0.7f)
    val toAxis = f.c - o
    val win = atan2(toAxis.dot(y), toAxis.dot(x))
    var zw = 0f
    while (zw > -12f) {
        val p = o + z * zw; val fr = rfv(b, 6f + (p - o).dot(f.d))
        val rad = p - fr.c; val r = (rad - fr.d * rad.dot(fr.d)).len()
        if (r > radiusAt(b, 6f) * VEIN_WALL) break
        zw -= 0.05f
    }
    NeedleFrame(o, x, y, z, ro, ri, 2f * ro / tan(18f * DEG), win, zw)
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
    val back = -9f
    // The bevel plane: the point at the bottom (y = -ro, z = 0), rising back to the heel at the top.
    fun zCut(y: Float) = -(y + nd.ro) / (2f * nd.ro) * nd.bevelLen
    fun w(lx: Float, ly: Float, lz: Float) = nd.o + nd.x * lx + nd.y * ly + nd.z * lz
    val wz0 = nd.zWall + 0.35f; val wz1 = -1.3f                          // the window, inside the vein, facing the crew
    val wHalf = 58f * DEG
    fun inWindow(th: Float, z: Float) = angDiff(th, nd.win) < wHalf && z > wz0 && z < wz1
    when (part) {
        0, 1 -> {
            val r = if (part == 0) nd.ro else nd.ri
            val st = 40; val sl = 36
            fun pt(i: Int, j: Int): V3 {
                val th = j * 2f * PI.toFloat() / sl; val ly = r * sin(th)
                return w(r * cos(th), ly, back + (zCut(ly) - back) * i / st)
            }
            fun nr(j: Int): V3 { val th = j * 2f * PI.toFloat() / sl; val rad = nd.x * cos(th) + nd.y * sin(th); return if (part == 0) rad else -rad }
            for (i in 0 until st) for (j in 0 until sl) {
                val th = (j + 0.5f) * 2f * PI.toFloat() / sl
                val zm = ((pt(i, j) + pt(i + 1, j + 1)) * 0.5f - nd.o).dot(nd.z)
                if (inWindow(th, zm)) continue
                mb.tri(pt(i, j), nr(j), pt(i + 1, j), nr(j), pt(i + 1, j + 1), nr(j + 1))
                mb.tri(pt(i, j), nr(j), pt(i + 1, j + 1), nr(j + 1), pt(i, j + 1), nr(j + 1))
            }
        }
        2 -> {
            val nb = (nd.y * nd.bevelLen + nd.z * (2f * nd.ro)).unit()
            mb.grid(2, 32, { u, v ->
                val th = v * 2f * PI.toFloat(); val r = nd.ri + (nd.ro - nd.ri) * u
                val ly = r * sin(th); w(r * cos(th), ly, zCut(ly))
            }) { _, _, _ -> nb }
            // the polished cutting edge round the bevel
            mb.tube((0..40).map { k -> val th = k * 2f * PI.toFloat() / 40f; val ly = nd.ro * sin(th); w(nd.ro * cos(th), ly, zCut(ly)) }, FloatArray(41) { 0.035f }, 4, false)
        }
        3 -> mb.torus(w(0f, 0f, nd.zWall), nd.z, nd.ro + 0.1f, 0.12f, 24, 6)
        4 -> {
            // blood filling the bore, back from the bevel
            val r = nd.ri * 0.96f
            mb.grid(20, 20, { u, v ->
                val th = v * 2f * PI.toFloat(); val ly = r * sin(th)
                w(r * cos(th), ly, back + (zCut(ly) - 0.02f - back) * u)
            }) { _, v, _ -> val th = v * 2f * PI.toFloat(); nd.x * cos(th) + nd.y * sin(th) }
        }
        5 -> {
            // the open end of the bore in the bevel plane: a dark oval inside the bright steel rim
            val nb = (nd.y * nd.bevelLen + nd.z * (2f * nd.ro)).unit()
            mb.grid(1, 32, { u, v ->
                val th = v * 2f * PI.toFloat(); val r = nd.ri * u
                val ly = r * sin(th); w(r * cos(th), ly, zCut(ly) - 0.01f)
            }) { _, _, _ -> nb }
        }
        else -> {
            val ths = floatArrayOf(nd.win - wHalf, nd.win + wHalf)
            for (th in ths) mb.tube(listOf(wz0, wz1).map { z -> w(nd.ro * cos(th), nd.ro * sin(th), z) }, floatArrayOf(0.03f, 0.03f), 4, true)
            for (z in floatArrayOf(wz0, wz1)) mb.tube((0..10).map { k -> val th = nd.win - wHalf + 2f * wHalf * k / 10f; w(nd.ro * cos(th), nd.ro * sin(th), z) }, FloatArray(11) { 0.03f }, 4, true)
        }
    }
    return mb.build(twoSided = part == 2 || part == 4 || part == 5)
}

/** The vein wall (0) with a sinus bulging out behind each valve cusp, and its intimal folds (1). */
private fun StereoBodyRenderer.veinWall(b: Float, part: Int): T3Mesh {
    val mb = Mb()
    val a0 = -8f; val a1 = min(34f, (nodes.lastIndex - b) * NODE_UNITS)
    fun r(a: Float, th: Float): Float {
        var bulge = 0f
        for (thc in floatArrayOf(90f * DEG, 270f * DEG)) {
            val da = angDiff(th, thc)
            if (da < 80f * DEG && a > 5.9f && a < 9.4f) bulge = max(bulge, 0.45f * cos(da / (80f * DEG) * PI.toFloat() / 2f) * sin((a - 5.9f) / 3.5f * PI.toFloat()))
        }
        return radiusAt(b, a) * VEIN_WALL + bulge
    }
    if (part == 0) mb.gridFd(((a1 - a0) / 0.35f).toInt(), 36, { u, v ->
        val a = a0 + (a1 - a0) * u; val th = v * 2f * PI.toFloat(); rfv(b, a).pol(th, r(a, th))
    }) { u, _, p -> rfv(b, a0 + (a1 - a0) * u).c - p }
    else for (k in 0 until 4) {
        val th = (45f + 90f * k) * DEG
        val pts = ArrayList<V3>(); var a = a0
        while (a <= a1) { val t2 = th + 0.04f * sin(a * 0.7f + k); pts.add(rfv(b, a).pol(t2, r(a, t2) - 0.025f)); a += 0.5f }
        mb.tube(pts, FloatArray(pts.size) { 0.03f }, 5, true)
    }
    return mb.build(twoSided = part == 0)
}

/** A bicuspid venous valve 8.5 units downstream: cusps (0) and their free edges (1). */
private fun StereoBodyRenderer.valveMesh(b: Float, part: Int): T3Mesh {
    val mb = Mb()
    val av = 8.5f
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
        else mb.tube((0..20).map { k -> p(k / 20f, 1f) }, FloatArray(21) { 0.06f }, 5, true)
    }
    return mb.build(twoSided = part == 0)
}

/**
 * Stop 3 (THE BOTTLE, 12 µm Mote: 1 unit = 8 µm): citrated blood that has stood in the cold. The
 * red cells have settled into a dark packed floor — many stacked face to face in rouleaux, others
 * lying flat — with a thin buffy coat of white cells and platelets on top, and pale straw plasma
 * standing above. To starboard the glass of the bottle; the light is cold blue.
 */
internal fun StereoBodyRenderer.drawStored(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    val um = umu(i)
    if (!openWall(i, -8f, 34f, T3_PLASMA_BG)) return
    drawMesh(cached("st_packed") { storedMesh(b, um, 0) }, T3_PACKED, T3_STORED_RBC, 1f, 0f, 0.05f)
    drawMesh(cached("st_cells") { storedMesh(b, um, 1) }, T3_STORED_RBC, T3_RBC, 1f, 0f, 0f)
    drawMesh(cached("st_plt") { storedMesh(b, um, 2) }, T3_PLATELET, T3_PLATELET, 1f, 0f, 0.3f)
    drawMesh(cached("st_nuc") { storedMesh(b, um, 3) }, T3_NUCLEUS, T3_NUCLEUS, 1f, 0f, 0.2f)
    drawMesh(cached("st_edge") { storedMesh(b, um, 8) }, T3_GLASS_EDGE, T3_GLASS_EDGE, 1f, 0f, 0.6f)
    // Platelets are too small to settle in days: they stay suspended in the plasma, sinking very slowly.
    val pr = 2.5f / 2f / um
    val drops = cachedValue("st_float") {
        val rnd = java.util.Random(5)
        val out = ArrayList<FloatArray>()
        while (out.size < 20) {
            val a = -4f + rnd.nextFloat() * 16f; val sd = -2.8f + rnd.nextFloat() * 5f; val up = BED_TOP + 0.9f + rnd.nextFloat() * 4.4f
            if (sd * sd + up * up < 3.4f) continue                       // not in the craft's lane
            out.add(floatArrayOf(a, sd, up, rnd.nextFloat() * 6.28f))
        }
        out
    }
    for (d in drops) {
        val up = BED_TOP + 0.9f + ((d[2] - BED_TOP - 0.9f - seconds * 0.01f) % 4.4f + 4.4f) % 4.4f
        val f = rfv(b, d[0])
        val tilt = f.u * cos(d[3] + seconds * 0.1f) + f.s * sin(d[3] + seconds * 0.1f)
        drawScaled(blob, f.at(d[1], up), f.d, tilt, pr, pr * 0.35f, pr * 0.85f, T3_PLATELET, T3_PLATELET, 1f, 0.35f)
    }
    GLES20.glDepthMask(false)
    // The buffy coat: a continuous pale layer of white cells lying on the red cells.
    drawMesh(cached("st_wbc") { storedMesh(b, um, 4) }, T3_LEUKOCYTE, T3_FIBROUS, 0.5f, 0f, 0.05f)
    // The level surface where the cells end and the plasma begins.
    drawMesh(cached("st_level") { storedMesh(b, um, 9) }, T3_LEVEL, T3_LEVEL, 0.12f, 0f, 0.4f)
    // Plasma: a straw-tinted body filling everything above the cells (seen from within it).
    drawMesh(cached("st_plasma") { storedMesh(b, um, 5) }, T3_PLASMA, T3_PLASMA, 0.3f, 0f, 0.6f)
    // The glass, carrying the cold: blue, frosted light on it.
    drawMesh(cached("st_glass") { storedMesh(b, um, 6) }, T3_GLASS_COLD, T3_GLASS_COLD, 0.35f, 0f, 0.4f)
    drawMesh(cached("st_glint") { storedMesh(b, um, 7) }, T3_STEEL_EDGE, T3_STEEL_EDGE, 0.45f + 0.1f * sin(seconds * 0.4f), 0f, 0.8f)
    GLES20.glDepthMask(true)
}

private const val BED_TOP = -2.5f

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
        1 -> {
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
                        for (k in 0 until nCells) mb.redCell(c + ax * ((k - (nCells - 1) / 2f) * rim * 1.02f), ax, rbcR, 4, 12)
                    } else {
                        val tip = V3(rnd.nextFloat() - 0.5f, 0f, rnd.nextFloat() - 0.5f) * 0.5f
                        mb.redCell(f.at(s, BED_TOP + rim * 0.6f), (f.u + tip).unit(), rbcR, 4, 12)
                    }
                    s += 1.25f + rnd.nextFloat() * 0.3f
                }
                a += 1.55f
            }
        }
        2 -> for (k in 0 until 6) {
            // a few platelets caught in the buffy coat (the rest stay suspended in the plasma)
            val f = rfv(b, -4f + rnd.nextFloat() * 15f)
            val c = f.at(-3.3f + rnd.nextFloat() * 5.2f, BED_TOP + rbcR * 1.95f + rnd.nextFloat() * 0.1f)
            val tip = (f.u + f.s * (rnd.nextFloat() - 0.5f) * 0.6f).unit()
            mb.ellipsoidAxes(c, tip, 0.5f / um, perp(tip), 1.25f / um, 1.1f / um, 4, 8)
        }
        3, 4 -> {
            // the buffy coat: a monolayer of white cells packed edge to edge on the red cells,
            // ~60% neutrophils (lobed nuclei), 30% lymphocytes (round nuclei), 10% monocytes (kidney)
            val cr = java.util.Random(23)
            val nr = java.util.Random(29)                                // nucleus details only, so cells and nuclei stay in step
            var row = 0
            var a = -4.5f
            while (a < 12.5f) {
                var sd = -3.1f + (if (row % 2 == 0) 0f else 0.72f)
                while (sd < glassSide - 0.5f) {
                    val kind = cr.nextFloat()
                    if (cr.nextFloat() < 0.4f) { sd += 1.45f; continue }        // a thin layer: red cells show between
                    val dUm = if (kind < 0.6f) 12f else if (kind < 0.9f) 8.5f else 15f
                    val r = dUm / 2f / um
                    val f = rfv(b, a + (cr.nextFloat() - 0.5f) * 0.3f)
                    val c = f.at(sd + (cr.nextFloat() - 0.5f) * 0.2f, BED_TOP + rbcR * 1.9f + r * 0.72f)
                    if (part == 4) mb.ellipsoid(c, f.u * (r * 0.75f), f.d * r, f.s * r, 7, 12)
                    else if (kind >= 0.9f) mb.torus(c, f.u, r * 0.42f, r * 0.22f, 12, 6, 0.6f, nr.nextFloat())
                    else if (kind >= 0.6f) mb.sphere(c, r * 0.78f, 6, 10)
                    else for (q in 0 until 3) {
                        val a2 = (q - 1) * 0.9f + nr.nextFloat()
                        mb.sphere(c + f.d * (cos(a2) * r * 0.42f) + f.s * (sin(a2) * r * 0.42f), r * 0.27f, 5, 8)
                    }
                    sd += 1.45f
                }
                a += 1.25f; row++
            }
        }
        5 -> {
            // seen from inside: inward normals
            val f = rfv(b, 3f)
            mb.ellipsoid(f.at(-0.4f, BED_TOP + 5.2f), f.u * 5.1f, f.s * 5.5f, f.d * 16f, 10, 20, inward = true)
        }
        6 -> mb.gridFd(8, 2, { u, v -> wAt(b, -7f + 23f * u, glassSide, -3.4f + 7.4f * v) }) { u, _, _ -> -rfv(b, -7f + 23f * u).s }
        8 -> mb.tube((0..16).map { q -> wAt(b, -6f + q * 1.3f, glassSide - 0.04f, BED_TOP + rbcR * 1.9f) }, FloatArray(17) { 0.04f }, 5, true)
        9 -> mb.gridFd(20, 4, { u, v -> wAt(b, -6f + 20f * u, -3.4f + (glassSide + 3.4f) * v, BED_TOP + rbcR * 1.9f + 1.0f) }) { u, _, _ -> rfv(b, -6f + 20f * u).u }
        else -> for (k in 0 until 2) {
            val up = 1.2f + k * 0.9f
            mb.tube((0..12).map { q -> wAt(b, -5f + q * 1.5f, glassSide - 0.03f, up + 0.15f * sin(q * 0.5f)) }, FloatArray(13) { 0.05f - 0.02f * k }, 4, true)
        }
    }
    return mb.build(twoSided = part == 6 || part == 9)
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
    drawMesh(cached("wd_clotrbc") { woundMesh(b, um, 6) }, T3_RBC, T3_RBC, 1f, 0f, 0.12f)
    drawMesh(cached("wd_plt") { woundMesh(b, um, 7) }, T3_PLATELET, T3_PLATELET, 1f, 0f, 0.3f)
    drawMesh(cached("wd_grit") { woundMesh(b, um, 8) }, T3_GRIT, T3_GRIT, 1f, 0.5f, 0.1f)
    drawMesh(cached("wd_cotton") { woundMesh(b, um, 9) }, T3_COTTON, T3_COTTON, 1f, 0f, 0.15f)
    drawMesh(cached("wd_bact") { woundMesh(b, um, 10) }, T3_GRAM_POS, T3_GRAM_POS, 1f, 0f, 0.45f)
    // Neutrophils crawling over the clot toward the contaminated cloth.
    val r = 12f / 2f / um
    val nucMesh = cached("wd_pmn") { neutrophilNucleus(r, 3, 5) }
    val floorUp = woundFloorUp(b)
    for (k in 0 until 2) {
        // Crawling: the pseudopod pushes out toward the bacteria (2 s), then the body catches up
        // (1.5 s); each cycle gains a step. The path loops slowly toward the contaminated cloth.
        val cyc = (seconds + k * 1.7f) / 3.5f
        val step = floor(cyc); val ph = (cyc - step) * 3.5f
        val catchUp = if (ph < 2f) 0f else smooth01((ph - 2f) / 1.5f)
        val reach = if (ph < 2f) smooth01(ph / 2f) else 1f - catchUp
        val t = (((step + catchUp) * 0.035f + k * 0.5f) % 1f)
        val a0 = (if (k == 0) 3.2f else 6.5f) + 2.2f * t; val s0 = if (k == 0) -1.4f + 0.7f * t else 1.8f - 0.5f * t
        val f = rfv(b, a0)
        val dir = (f.d * 2.2f + f.s * (if (k == 0) 0.7f else -0.5f)).unit()
        val c = f.at(s0, floorUp + 0.35f + r * 0.6f)
        drawLocal(nucMesh, c, dir, f.u, T3_NUCLEUS, T3_NUCLEUS, 1f, 0.2f)
        GLES20.glDepthMask(false)
        drawScaled(sphere, c, dir, f.u, r * 1.05f, r * 0.7f, r * 1.1f, T3_LEUKOCYTE, T3_FIBROUS, 0.5f, 0.12f)
        drawScaled(sphere, c + dir * (r * (0.55f + 0.45f * reach)), dir, f.u, r * 0.4f, r * 0.3f, r * (0.3f + 0.3f * reach), T3_LEUKOCYTE, T3_FIBROUS, 0.5f, 0.12f)
        GLES20.glDepthMask(true)
    }
    // Yan'an, spring 1938: hung well down the passage and up to starboard, clear of the tissue.
    drawPlate("yanan", frameAt(b + 11f / NODE_UNITS), radiusAt(b, 11f) * 0.62f, radiusAt(b, 11f) * 0.28f, 1.8f, seconds)
}

private fun StereoBodyRenderer.woundFloorUp(b: Float) = -tunnelRadius(b) * 0.84f * 0.78f

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
    val rnd = java.util.Random(61L + part)
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
        4 -> for (end in floatArrayOf(stumpL, stumpR)) {
            // contraction band: the hypercontracted cap where the torn fibre sealed its end
            mb.ellipsoidAxes(axB + fB.s * end, fB.s, 0.28f, fB.u, rB * 0.97f, rB * 0.97f, 6, 18)
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
            // and a fine fibrin net over the clot on the floor
            for (k in 0 until 26) {
                val a0 = -2f + rnd.nextFloat() * 12f; val s0 = -2.2f + rnd.nextFloat() * 4.4f
                val a1 = a0 + (rnd.nextFloat() - 0.5f) * 4f; val s1 = (s0 + (rnd.nextFloat() - 0.5f) * 3f).coerceIn(-2.4f, 2.4f)
                val p0 = wAt(b, a0, s0, floorUp + 0.3f + rnd.nextFloat() * 0.5f); val p1 = wAt(b, a1, s1, floorUp + 0.3f + rnd.nextFloat() * 0.5f)
                mb.tube(listOf(p0, lerpV(p0, p1, 0.5f) + rfv(b, a0).u * 0.25f, p1), FloatArray(3) { 0.03f }, 4, false)
            }
        }
        6 -> for (k in 0 until 26) {
            if (k >= 18) {
                // red cells caught on the fibrin ropes in the gap
                val c = woundRope(b, um, (k * 5) % 9, 0.3f + 0.4f * rnd.nextFloat())
                mb.redCell(c + fB.d * 0.12f, V3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).unit(), 7.5f / 2f / um, 4, 12)
                continue
            }
            // red cells trapped in the fibrin
            val f = rfv(b, -1.5f + rnd.nextFloat() * 11f)
            val c = f.at(-2.2f + rnd.nextFloat() * 4.4f, floorUp + 0.55f + rnd.nextFloat() * 0.35f)
            val ax = (f.u + f.s * (rnd.nextFloat() - 0.5f) * 1.6f + f.d * (rnd.nextFloat() - 0.5f) * 1.6f).unit()
            mb.redCell(c, ax, 7.5f / 2f / um, 4, 12)
        }
        7 -> for (cl in 0 until 6) {
            // platelet clumps adhering in the clot, and along the ropes in the gap
            val f = rfv(b, 1f + cl * 3.3f)
            val c = if (cl >= 3) woundRope(b, um, cl * 2 - 5, 0.35f + 0.12f * cl) - fB.d * 0.1f else f.at(-1.4f + cl * 1.3f, floorUp + 0.65f)
            for (k in 0 until 9) {
                val d = V3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).unit()
                mb.ellipsoidAxes(c + d * 0.28f, d, 0.4f / um, perp(d), 1.25f / um, 1.1f / um, 4, 7)
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
            val p0 = wAt(b, 3.5f, -2.5f, floorUp + 0.7f); val p1 = wAt(b, 12.5f, 2.2f, floorUp + 0.8f)
            val dir = (p1 - p0).unit(); val up = rfv(b, 4.5f).u
            val w0 = up.cross(dir).unit()
            val half = 16f / 2f / um
            fun centre(u: Float) = lerpV(p0, p1, u) + up * (sin(u * PI.toFloat()) * 0.35f)
            mb.gridFd(40, 6, { u, v ->
                val tw = u * 3f * PI.toFloat()
                val across = (w0 * cos(tw) + up * sin(tw))
                val thick = (up * cos(tw) - w0 * sin(tw))
                val q = v * 2f * PI.toFloat()
                centre(u) + across * (cos(q) * half) + thick * (sin(q) * half * 0.28f)
            }) { u, _, p -> p - centre(u) }
        }
        10 -> {
            // Gram-positive bacteria on the cloth and the grit: Clostridium rods (1 x 5 µm) and coccal clusters
            val on = listOf(floatArrayOf(6.3f, -1.2f, 1.1f), floatArrayOf(9.6f, 0.9f, 1.2f), floatArrayOf(2f, 1.9f, 1.55f), floatArrayOf(4.8f, -2.1f, 1.9f))
            for ((k, sp) in on.withIndex()) {
                val f = rfv(b, sp[0]); val base = f.at(sp[1], floorUp + sp[2])
                for (q in 0 until 6) {
                    val d = (f.d * cos(q * 1.1f + k) + f.s * sin(q * 1.1f + k)).unit()
                    val c = base + f.d * ((rnd.nextFloat() - 0.5f) * 1.2f) + f.s * ((rnd.nextFloat() - 0.5f) * 1.2f)
                    if (q % 3 == 2) for (cc in 0 until 5) mb.sphere(c + V3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f) * 0.22f, 0.5f / um, 4, 6)
                    else mb.rod(c - d * (2.3f / um), c + d * (2.3f / um), 0.55f / um, 6)
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
    return mb.build(twoSided = part == 9 || part == 11)
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
    // Reperfusion spreads down the vessel with the incoming blood: each stretch of muscle turns
    // from ischaemic mauve-grey to deep perfused red a few seconds after the one before it, so a
    // front of returning colour is always somewhere in view.
    val T = sinceArrival(i, seconds)
    for (seg in 0 until TF_SEGS.size - 1) {
        val t = smooth01((T - 4f - 8f * seg) / 14f)
        val fibre = mixCol(T3_ISCHAEMIC, T3_PERFUSED, t, tmpCol0)
        val band = mixCol(T3_ISCHAEMIC_A, T3_PERFUSED_A, t, tmpCol1)
        val cap = mixCol(T3_CAP_EMPTY, T3_CAPILLARY, t, tmpCol2)
        drawMesh(cached("tf_fibres$seg") { transfusionMesh(b, um, 0, seg) }, fibre, fibre, 1f, 0f, 0.05f + 0.08f * t)
        drawMesh(cached("tf_bands$seg") { transfusionMesh(b, um, 1, seg) }, band, band, 1f, 0f, 0.03f)
        drawMesh(cached("tf_caps$seg") { transfusionMesh(b, um, 3, seg) }, cap, cap, 1f, 0f, 0.1f + 0.25f * t)
        drawMesh(cached("tf_nuclei$seg") { transfusionMesh(b, um, 2, seg) }, T3_MUSCLE_NUCLEUS, T3_MUSCLE_NUCLEUS, 1f, 0f, 0.15f)
    }
    // The vessel's own wall: endothelium, and outside it one layer of circumferential smooth
    // muscle cells (a terminal arteriole), each with its elongated nucleus.
    drawMesh(cached("tf_smcnuc") { smoothMuscleCoat(b, um, true) }, T3_MUSCLE_NUCLEUS, T3_MUSCLE_NUCLEUS, 1f, 0f, 0.1f)
    val (tube, junc, nuc) = endotheliumMeshes("tf_endo", b, -8f, 24f, { a -> radiusAt(b, a) * 0.97f }, 30f / um, 10)
    drawMesh(nuc, T3_ENDO_NUCLEUS, T3_ENDOTHELIUM, 0.75f, 0f, 0.05f)
    drawMesh(junc, T3_JUNCTION, T3_JUNCTION, 0.45f, 0f, 0.08f)
    GLES20.glDepthMask(false)
    drawMesh(cached("tf_smc") { smoothMuscleCoat(b, um, false) }, T3_SMC, T3_SMC, 0.55f, 0f, 0.1f)
    drawMesh(tube, T3_ENDOTHELIUM, T3_JUNCTION, 0.22f, 0f, 0.15f)
    GLES20.glDepthMask(true)
}

/** Stretches of the vessel that reperfuse one after another. */
private val TF_SEGS = floatArrayOf(-8f, -1f, 6f, 14f, 24f)

/** Smooth muscle wrapped round the vessel: spindle cells (as arcs) or their nuclei. */
private fun StereoBodyRenderer.smoothMuscleCoat(b: Float, um: Float, nuclei: Boolean): T3Mesh {
    val mb = Mb()
    var a = -7.5f; var ring = 0
    while (a < 23.5f) {
        val f = rfv(b, a); val R = radiusAt(b, a) * 1.04f
        for (c in 0 until 2) {
            val start = ring * 60f * DEG + c * PI.toFloat()
            val arc = 110f * DEG
            if (!nuclei) {
                // a spindle: thickest mid-arc, tapering to both ends
                val pts = (0..12).map { q -> f.pol(start + arc * q / 12f, R) }
                mb.tube(pts, FloatArray(13) { q -> 0.12f * sin(q / 12f * PI.toFloat()).pow(0.6f) + 0.01f }, 6, false)
            } else {
                val mid = start + arc / 2f
                val tg = (f.u * cos(mid) - f.s * sin(mid)).unit()
                mb.ellipsoidAxes(f.pol(mid, R + 0.02f), tg, 0.6f / 2f * 1.4f, f.radial(mid), 0.1f, 0.1f, 5, 10)
            }
        }
        a += 0.9f; ring++
    }
    return mb.build()
}

private val tmpCol0 = FloatArray(4)
private val tmpCol1 = FloatArray(4)
private val tmpCol2 = FloatArray(4)
private fun mixCol(a: FloatArray, c: FloatArray, t: Float, out: FloatArray): FloatArray {
    for (k in 0 until 4) out[k] = a[k] + (c[k] - a[k]) * t
    return out
}

/** Muscle around the vessel, one stretch [seg] of it: fibres (0), their A bands (1), nuclei (2), capillaries (3). */
private fun StereoBodyRenderer.transfusionMesh(b: Float, um: Float, part: Int, seg: Int): T3Mesh {
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
                mb.ellipsoidAxes(axisAt(a) + inward * (rf * 0.99f), f.d, 5f / um, inward, 0.16f, 0.3f, 5, 10)
            }
            else -> {
                // capillaries in the clefts between fibres, running with them
                val thc = th + 50f * DEG
                val n = (s1 - s0).toInt()
                val pts = (0..n).map { q -> val a = s0 + q; rfv(b, a).pol(thc + 0.08f * sin(a * 0.9f + k), radiusAt(b, a) + 0.6f) }
                mb.tube(pts, FloatArray(n + 1) { 7f / 2f / um }, 8, true)
            }
        }
    }
    return mb.build()
}

/**
 * Stop 6 (THE TABLE, 12 mm Mote: 1 unit = 8 mm): a war wound handled properly — debrided and left
 * open, not one stitch in it, packed with fluffed dry gauze, to be closed at four or five days if
 * it stays clean. The craft hangs just above the skin, looking along the gaping cut, ~4 cm across
 * at the skin and ~3 cm deep. Each cut face shows the layers in section: the thin epidermis on a
 * pale dermis at the lip, a continuous glistening face of lobulated yellow subcutaneous fat (larger
 * lobules above, smaller below, fine septa between, a few cut vessels), the white deep fascia, and
 * red muscle down into the wound bed. The debrided surfaces are clean, with pinpoint bleeding. Three
 * crumpled layers of open-weave gauze fill the depths, stained where they wick blood from the tissue.
 */
internal fun StereoBodyRenderer.drawSuture(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    if (!openWall(i, -8f, 34f, T3_BLACK)) return
    drawMesh(cached("tb_muscle") { tableMesh(b, 0) }, T3_MUSCLE, T3_MUSCLE, 1f, 0f, 0.1f)
    drawMesh(cached("tb_mlines") { tableMesh(b, 1) }, T3_MUSCLE_LINE, T3_MUSCLE_LINE, 1f, 0f, 0.05f)
    drawMesh(cached("tb_fascia") { tableMesh(b, 2) }, T3_FASCIA, T3_FASCIA, 1f, 0f, 0.2f)
    drawMesh(cached("tb_fatface") { tableMesh(b, 3) }, T3_FAT_FACE, T3_FAT_FACE, 1f, 0f, 0.15f)
    drawMesh(cached("tb_fat") { tableMesh(b, 10) }, T3_FAT, T3_FAT_FACE, 1f, 0f, 0.18f)
    drawMesh(cached("tb_septa") { tableMesh(b, 11) }, T3_SEPTA, T3_SEPTA, 1f, 0f, 0.15f)
    drawMesh(cached("tb_vessels") { tableMesh(b, 12) }, T3_CUT_VESSEL, T3_CUT_VESSEL, 1f, 0f, 0.1f)
    drawMesh(cached("tb_dermis") { tableMesh(b, 4) }, T3_DERMIS, T3_DERMIS, 1f, 0f, 0.15f)
    drawMesh(cached("tb_epi") { tableMesh(b, 5) }, T3_SKIN_EDGE, T3_SKIN_EDGE, 1f, 0f, 0.1f)
    drawMesh(cached("tb_skin") { tableMesh(b, 6) }, T3_SKIN_TOP, T3_SKIN_TOP, 1f, 0f, 0.12f)
    // Pinpoint bleeding from the debrided faces: tissue that bleeds is tissue that lives.
    drawMesh(cached("tb_bleed") { tableMesh(b, 9) }, T3_BLEED, T3_BLEED, 1f, 0f, 0.35f + 0.15f * sin(seconds * 1.1f))
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
private const val EPI_UP = SKIN_UP - 0.06f
private const val FAT_TOP = SKIN_UP - 0.28f
private const val FASCIA_UP = -2.9f
private const val WOUND_FLOOR = -4.6f

/** Half-width of the wound at height [up] (V-shaped: wider at the skin). */
private fun woundHalf(up: Float) = 1.7f + 1.0f * (up - WOUND_FLOOR) / (SKIN_UP - WOUND_FLOOR)

/** Three crumpled sheets of open-weave gauze (1 mm weave) loosely filling the depths, as coloured lines. */
private fun StereoBodyRenderer.gauzeLines(b: Float): FloatArray {
    val out = ArrayList<Float>(200000)
    val a0 = -5f; val a1 = 13f
    fun sheet(k: Int, a: Float, w: Float): V3 {
        val base = WOUND_FLOOR + 0.25f + 0.5f * k
        var up = base + 0.35f * sin(a * 0.9f + k * 1.7f) * cos(w * 3f + k) + 0.12f * sin(a * 2.3f - w * 5f + k)
        up = up.coerceIn(WOUND_FLOOR + 0.06f, FASCIA_UP + 0.5f)
        val half = woundHalf(up) - 0.06f
        return wAt(b, a, w * half, up)
    }
    fun wet(p: V3, a: Float, w: Float, k: Int): Boolean {
        val f = rfv(b, a); val rel = p - f.c
        val up = rel.dot(f.u); val sd = abs(rel.dot(f.s))
        return up < WOUND_FLOOR + 0.2f || sd > woundHalf(up) - 0.22f
    }
    fun seg(p: V3, q: V3, wetp: Boolean) {
        val c = if (wetp) T3_GAUZE_WET else T3_GAUZE
        for (v in arrayOf(p, q)) { out.add(v.x); out.add(v.y); out.add(v.z); out.add(c[0]); out.add(c[1]); out.add(c[2]); out.add(0.9f) }
    }
    val pitch = 0.12f
    for (k in 0 until 3) {
        // threads across the wound
        var a = a0
        while (a <= a1) {
            var prev = sheet(k, a, -1f); var w = -1f
            while (w < 1f) { val w2 = w + 0.1f; val q = sheet(k, a, w2); seg(prev, q, wet(q, a, w2, k)); prev = q; w = w2 }
            a += pitch
        }
        // threads along it
        val half = woundHalf(WOUND_FLOOR + 0.5f)
        var w = -1f
        while (w <= 1f) {
            var prev = sheet(k, a0, w); var aa = a0
            while (aa < a1) { val a2 = aa + 0.25f; val q = sheet(k, a2, w); seg(prev, q, wet(q, a2, w, k)); prev = q; aa = a2 }
            w += pitch / half
        }
    }
    return out.toFloatArray()
}

/**
 * The wound's layers: muscle (0), fascicle lines (1), fascia (2), the fat's cut face (3), dermis (4),
 * epidermis (5), skin surface (6), bleeding points (9), fat lobules (10), fat septa (11), cut vessels (12).
 */
private fun StereoBodyRenderer.tableMesh(b: Float, part: Int): T3Mesh {
    val mb = Mb()
    val rnd = java.util.Random(71L + part)
    val a0 = -8f; val a1 = 26f
    val stA = 68
    // A cut face as a surface between two heights, both sides; normals face into the wound.
    fun face(u0: Float, u1: Float, bulge: Float, stU: Int = 4) {
        for (sg in floatArrayOf(-1f, 1f)) mb.gridFd(stA, stU, { u, v ->
            val a = a0 + (a1 - a0) * u; val up = u0 + (u1 - u0) * v
            wAt(b, a, sg * (woundHalf(up) + bulge * sin(v * PI.toFloat())), up)
        }) { u, _, _ -> rfv(b, a0 + (a1 - a0) * u).s * (-sg) }
    }
    // Fat lobules on a jittered grid: (along, up, radius, side sign).
    fun lobules(): List<FloatArray> {
        val lr = java.util.Random(13)
        val list = ArrayList<FloatArray>()
        for (sg in floatArrayOf(-1f, 1f)) {
            var up = FAT_TOP - 0.3f
            while (up > FASCIA_UP + 0.15f) {
                val depth = (FAT_TOP - up) / (FAT_TOP - FASCIA_UP)
                val r = 0.55f - 0.3f * depth                           // big lobules above, small flat ones below
                var a = a0 + lr.nextFloat() * r
                while (a < a1) {
                    list.add(floatArrayOf(a + (lr.nextFloat() - 0.5f) * 0.6f, up + (lr.nextFloat() - 0.5f) * 0.3f * r, r * (0.8f + 0.4f * lr.nextFloat()), sg))
                    a += r * 1.9f
                }
                up -= r * 1.7f
            }
        }
        return list
    }
    when (part) {
        0 -> {
            face(WOUND_FLOOR, FASCIA_UP - 0.05f, 0f)
            // the wound bed: muscle, gently curved
            mb.gridFd(stA, 8, { u, v ->
                val a = a0 + (a1 - a0) * u; val sd = (v * 2f - 1f) * woundHalf(WOUND_FLOOR)
                wAt(b, a, sd, WOUND_FLOOR - 0.25f * (1f - (v * 2f - 1f).pow(2)))
            }) { u, _, _ -> rfv(b, a0 + (a1 - a0) * u).u }
        }
        1 -> for (sg in floatArrayOf(-1f, 1f)) for (k in 0 until 5) {
            // the grain of the muscle: fascicles running along the face
            val up = WOUND_FLOOR + 0.2f + k * 0.3f
            val pts = (0..34).map { q -> val a = a0 + q; wAt(b, a, sg * (woundHalf(up) - 0.015f), up + 0.1f * sin(q * 0.8f + k)) }
            mb.tube(pts, FloatArray(35) { 0.018f }, 4, false)
        }
        2 -> face(FASCIA_UP - 0.08f, FASCIA_UP + 0.08f, -0.02f, 1)
        3 -> face(FASCIA_UP + 0.08f, FAT_TOP - 0.02f, 0f, 6)
        10 -> for (l in lobules()) {
            // each lobule bulges only a little from the cut face
            val f = rfv(b, l[0]); val sg = l[3]; val r = l[2]
            val c = f.at(sg * (woundHalf(l[1]) + r - 0.1f), l[1])
            mb.ellipsoid(c, f.s * r, f.d * r, f.u * (r * 0.85f), 5, 10)
        }
        11 -> {
            // interlobular septa: fine pale lines wandering between the lobules
            for (sg in floatArrayOf(-1f, 1f)) {
                var up = FAT_TOP - 0.05f
                var k = 0
                while (up > FASCIA_UP + 0.1f) {
                    val pts = (0..68).map { q -> val a = a0 + q * 0.5f; val u2 = up + 0.08f * sin(a * 3.1f + k * 2f); wAt(b, a, sg * (woundHalf(u2) - 0.03f), u2) }
                    mb.tube(pts, FloatArray(69) { 0.015f }, 4, false)
                    up -= 0.55f - 0.08f * k; k++
                }
                var a = a0
                while (a < a1) {
                    val pts = (0..10).map { q -> val u2 = FASCIA_UP + (FAT_TOP - FASCIA_UP) * q / 10f; wAt(b, a + 0.12f * sin(q * 1.7f + a), sg * (woundHalf(u2) - 0.03f), u2) }
                    mb.tube(pts, FloatArray(11) { 0.015f }, 4, false)
                    a += 0.7f + 0.5f * rnd.nextFloat()
                }
            }
        }
        12 -> for (k in 0 until 8) {
            // small vessels cut across on the fat face: dark red rings
            val a = a0 + 6f + rnd.nextFloat() * 18f; val sg = if (k % 2 == 0) -1f else 1f
            val up = FASCIA_UP + 0.3f + rnd.nextFloat() * (FAT_TOP - FASCIA_UP - 0.6f)
            val f = rfv(b, a)
            mb.torus(f.at(sg * (woundHalf(up) - 0.03f), up), f.s, 0.08f, 0.025f, 14, 5)
        }
        4 -> face(FAT_TOP - 0.02f, EPI_UP, 0.01f, 1)
        5 -> face(EPI_UP - 0.005f, SKIN_UP + 0.012f, 0.015f, 1)
        6 -> for (sg in floatArrayOf(-1f, 1f)) mb.gridFd(stA, 6, { u, v ->
            // the skin surface either side of the cut, 5 units (4 cm) wide
            val a = a0 + (a1 - a0) * u; wAt(b, a, sg * (woundHalf(SKIN_UP) + v * 5f), SKIN_UP + 0.012f - 0.25f * v * v)
        }) { u, _, _ -> rfv(b, a0 + (a1 - a0) * u).u }
        else -> for (k in 0 until 40) {
            val a = a0 + 4f + rnd.nextFloat() * 20f
            val sg = if (k % 2 == 0) -1f else 1f
            val up = WOUND_FLOOR + 0.3f + rnd.nextFloat() * (FAT_TOP - WOUND_FLOOR - 0.5f)
            val f = rfv(b, a)
            mb.ellipsoid(f.at(sg * (woundHalf(up) - 0.02f), up), f.s * 0.05f, f.d * 0.07f, f.u * 0.09f, 4, 7)
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
    drawMesh(cached("mw_lining") { marrowMesh(b, um, 1) }, T3_LINING, T3_LINING, 1f, 0f, 0.1f)
    drawMesh(cached("mw_osteoid") { marrowMesh(b, um, 15) }, T3_OSTEOID, T3_OSTEOID, 1f, 0f, 0.15f)
    drawMesh(cached("mw_obl") { marrowMesh(b, um, 16) }, T3_OSTEOBLAST, T3_OSTEOBLAST, 1f, 0f, 0.15f)
    drawMesh(cached("mw_mknuc") { marrowMesh(b, um, 2) }, T3_MEGA_NUCLEUS, T3_MEGA_NUCLEUS, 1f, 0f, 0.2f)
    drawMesh(cached("mw_mkgran") { marrowMesh(b, um, 3) }, T3_MEGA_GRANULE, T3_MEGA_GRANULE, 1f, 0f, 0.3f)
    drawMesh(cached("mw_arms") { marrowMesh(b, um, 4) }, T3_MEGA, T3_PLATELET, 1f, 0f, 0.3f)
    drawMesh(cached("mw_mac") { marrowMesh(b, um, 5) }, T3_MACROPHAGE, T3_MACROPHAGE, 1f, 0f, 0.15f)
    drawMesh(cached("mw_eb0") { marrowMesh(b, um, 6) }, T3_EB_EARLY, T3_EB_EARLY, 1f, 0f, 0.2f)
    drawMesh(cached("mw_eb1") { marrowMesh(b, um, 7) }, T3_EB_MID, T3_EB_MID, 1f, 0f, 0.2f)
    drawMesh(cached("mw_eb2") { marrowMesh(b, um, 8) }, T3_EB_LATE, T3_EB_LATE, 1f, 0f, 0.2f)
    drawMesh(cached("mw_ebnuc") { marrowMesh(b, um, 9) }, T3_EB_NUCLEUS, T3_EB_NUCLEUS, 1f, 0f, 0.25f)
    drawMesh(cached("mw_retic") { marrowMesh(b, um, 10) }, T3_RETICULOCYTE, T3_RETICULOCYTE, 1f, 0f, 0.2f)
    drawMesh(cached("mw_mynuc") { marrowMesh(b, um, 12) }, T3_GRAN_NUCLEUS, T3_GRAN_NUCLEUS, 1f, 0f, 0.2f)
    // A haematopoietic stem cell dividing in its niche on the bone.
    val cyc = ((seconds / 24f) % 1f)
    val sep = smooth01((cyc - 0.35f) / 0.45f) * 0.55f
    val fs = rfv(b, 5f); val hscR = 8f / 2f / um
    for (sg in SIGNS) {
        val c = fs.pol(262f * DEG, marrowBoneR(b) - 0.95f - hscR) + fs.d * (sg * (hscR * 0.55f + sep))
        drawSphereAt(c.x, c.y, c.z, hscR, hscR * (1f - 0.1f * sep), hscR, T3_STEM, T3_STEM, 1f, 0f, 0f, 1f, 0f, sphere, 0f, 0.3f)
    }
    // Platelets breaking off the tips of the proplatelet arms and carried away by the current.
    val tips = cachedValue("mw_tips") { (0 until 3).map { mkArmPath(b, it).last() } }
    for (arm in 0 until 3) {
        val t = ((seconds / 3.2f + arm * 0.33f) % 1f)
        val f = rfv(b, 3f + arm * 1.4f)
        val p = tips[arm] + f.d * (t * 4.5f) - f.radial(150f * DEG) * (t * 0.3f)
        val pr = 2.8f / 2f / um
        drawScaled(blob, p, f.d, f.u, pr, pr * 0.35f, pr * 0.85f, T3_PLATELET, T3_LAMP_SOFT, 1f - t * t, 0.3f)
    }
    // The endothelium and the megakaryocytes' translucent cytoplasm and fat cells, drawn last.
    val (tube, junc, nuc) = endotheliumMeshes("mw_endo", b, -8f, 24f, { a -> radiusAt(b, a) * 0.97f }, 28f / um, 12)
    drawMesh(nuc, T3_ENDO_NUCLEUS, T3_ENDOTHELIUM, 0.75f, 0f, 0.05f)
    drawMesh(junc, T3_JUNCTION, T3_JUNCTION, 0.45f, 0f, 0.08f)
    GLES20.glDepthMask(false)
    drawMesh(cached("mw_myelo") { marrowMesh(b, um, 11) }, T3_MYELOCYTE, T3_MYELOCYTE, 0.45f, 0f, 0.15f)
    drawMesh(cached("mw_fat") { marrowMesh(b, um, 13) }, T3_ADIPOCYTE, T3_ADIPOCYTE, 0.32f, 0f, 0.2f)
    drawMesh(cached("mw_mk") { marrowMesh(b, um, 14) }, T3_MEGA, T3_MEGA, 0.38f, 0f, 0.15f)
    drawMesh(tube, T3_ENDOTHELIUM, T3_JUNCTION, 0.16f, 0f, 0.1f)
    GLES20.glDepthMask(true)
}

private fun StereoBodyRenderer.marrowBoneR(b: Float) = tunnelRadius(b) + 3.6f

/** Megakaryocytes: (along, angle°, diameter µm, lobes). */
private val MK_SPOTS = listOf(floatArrayOf(3.5f, 175f, 64f, 8f), floatArrayOf(11f, 318f, 54f, 5f))

private fun StereoBodyRenderer.mkCentre(b: Float, um: Float, k: Int): V3 {
    val s = MK_SPOTS[k]; val r = s[2] / 2f / um
    return rfv(b, s[0]).pol(s[1] * DEG, radiusAt(b, s[0]) + r - 0.35f)
}

/** Proplatelet arm [arm] of the first megakaryocyte as a path: through the wall, then bending downstream. */
private fun StereoBodyRenderer.mkArmPath(b: Float, arm: Int): List<V3> {
    val s = MK_SPOTS[0]
    val a0 = s[0] - 1.2f + arm * 1.3f
    val th = (s[1] + (arm - 1) * 16f) * DEG
    return (0..14).map { q ->
        val t = q / 14f
        val a = a0 + t * t * 5.5f
        rfv(b, a).pol(th + 0.15f * sin(t * 4f + arm), radiusAt(b, a) + 0.2f - t * 1.25f)
    }
}

/** Marrow parts (see [drawStudents]). */
private fun StereoBodyRenderer.marrowMesh(b: Float, um: Float, part: Int): T3Mesh {
    val mb = Mb()
    val rnd = java.util.Random(83L + part)
    val boneR = marrowBoneR(b)
    val ebA = 8f; val ebTh = 110f * DEG
    val ebC = rfv(b, ebA).pol(ebTh, radiusAt(b, ebA) + 2.0f)
    val ebR = 9f / 2f / um
    when (part) {
        0 -> mb.gridFd(30, 10, { u, v ->
            // a trabecula of bone below the sinus, a thick cream plate
            val a = -6f + 19f * u; val th = (228f + 84f * v) * DEG
            rfv(b, a).pol(th, boneR + 0.25f * sin(u * 9f) + 0.3f * sin(v * 7f))
        }) { u, _, p -> rfv(b, -6f + 19f * u).c - p }
        1 -> {
            // most of the bone surface: flat, quiescent bone-lining cells
            var a = -5f
            while (a < 12.5f) {
                var th = 234f
                while (th < 308f) {
                    val inPatch = a > 1.5f && a < 6.5f && th > 248f && th < 292f
                    if (!inPatch) {
                        val f = rfv(b, a); val rad = f.radial(th * DEG)
                        mb.ellipsoid(f.pol(th * DEG, boneR - 0.08f), rad * 0.06f, f.d * 0.6f, f.d.cross(rad).unit() * 0.4f, 4, 8)
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
        }) { u, v, p -> rfv(b, 1.8f + 4.4f * u).c - p }
        16 -> {
            // a patch of plump, cuboidal osteoblasts on that osteoid
            var k = 0
            for (a in floatArrayOf(2.3f, 3.5f, 4.7f, 5.9f)) for (th in floatArrayOf(256f, 270f, 284f)) {
                val f = rfv(b, a); val rad = f.radial(th * DEG)
                mb.ellipsoid(f.pol(th * DEG, boneR - 0.55f), rad * 0.3f, f.d * 0.4f, f.d.cross(rad).unit() * 0.35f, 5, 8)
                k++
            }
        }
        2 -> for ((k, s) in MK_SPOTS.withIndex()) {
            // ONE polyploid nucleus: its lobes chained round a closed, folded path and joined, a
            // single multilobed mass (not separate nuclei, as in an osteoclast)
            val c = mkCentre(b, um, k); val r = s[2] / 2f / um
            val n = s[3].toInt()
            val ax = V3(0.3f, 1f, 0.2f).unit(); val e1 = perp(ax); val e2 = ax.cross(e1).unit()
            val lobes = (0 until n).map { q ->
                val t = q * 2f * PI.toFloat() / n
                c + (e1 * cos(t) + e2 * sin(t)) * (r * 0.3f) + ax * (r * 0.12f * sin(t * 3f))
            }
            for ((q, p) in lobes.withIndex()) {
                val lr = r * (0.17f + 0.05f * rnd.nextFloat())
                val d = (p - c).unit()
                mb.ellipsoid(p, d * lr, perp(d) * (lr * 1.25f), d.cross(perp(d)) * (lr * 0.95f), 6, 10)
                mb.rod(p, lobes[(q + 1) % n], r * 0.12f, 6)
            }
        }
        3 -> for ((k, s) in MK_SPOTS.withIndex()) {
            val c = mkCentre(b, um, k); val r = s[2] / 2f / um
            for (q in 0 until 70) {
                val d = V3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).unit()
                mb.sphere(c + d * (r * (0.6f + 0.35f * rnd.nextFloat())), 0.07f, 3, 5)
            }
        }
        4 -> for (arm in 0 until 3) {
            // a proplatelet: a thin shaft with platelet-sized swellings every few µm
            val path = mkArmPath(b, arm)
            val fine = ArrayList<V3>(); val radii = ArrayList<Float>()
            for (q in 0 until path.size - 1) for (w in 0 until 4) {
                fine.add(lerpV(path[q], path[q + 1], w / 4f))
                val t = (q * 4 + w).toFloat()
                radii.add(0.08f + 0.1f * max(0f, sin(t * PI.toFloat() / 2.5f)).pow(2))
            }
            fine.add(path.last()); radii.add(0.16f)
            mb.tube(fine, radii.toFloatArray(), 6, true)
        }
        5 -> mb.ellipsoid(ebC, V3(0f, 1.4f, 0f), V3(1.8f, 0f, 0f), V3(0f, 0f, 1.6f), 8, 14)   // central macrophage
        6, 7, 8, 9 -> for (k in 0 until 8) {
            val ang = k * 2f * PI.toFloat() / 8f
            val f = rfv(b, ebA)
            val rad = f.radial(ebTh)
            val e1 = f.d; val e2 = rad.cross(e1).unit()
            val stage = k * 3 / 8                                 // 0 early (blue) .. 2 late (pink)
            val r = ebR * (1.15f - 0.12f * stage)
            val out = e1 * cos(ang) + e2 * sin(ang)
            val c = ebC + out * (2.1f + r) - rad * 0.3f
            if (part in 6..8 && stage == part - 6) mb.sphere(c, r, 7, 12)
            if (part == 9) {
                val nr = r * (0.62f - 0.12f * stage)
                if (k == 7) mb.sphere(c + out * (r * 0.95f), nr * 0.9f, 6, 10)     // extruding its nucleus
                else mb.sphere(c + out * (r * 0.35f), nr, 6, 10)
            }
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
        11, 12 -> for (k in 0 until 6) {
            // developing granulocytes (myelocytes to band forms) in the cords
            val a = -3f + k * 2.6f
            val f = rfv(b, a)
            val th = (195f + 18f * (k % 3)) * DEG
            val r = 12f / 2f / um
            val c = f.pol(th, radiusAt(b, a) + 0.9f + r)
            val rad = f.radial(th)
            if (part == 11) mb.sphere(c, r, 7, 12)
            else if (k % 2 == 0) mb.torus(c - rad * (r * 0.25f), rad, r * 0.45f, r * 0.2f, 12, 6, 0.62f, 0.2f * k)   // band form: C-shaped nucleus
            else {
                // myelocyte: an eccentric kidney-shaped nucleus (two overlapping lobes, indented between)
                val nc = c - rad * (r * 0.25f); val tg = f.d
                mb.ellipsoid(nc + tg * (r * 0.18f), tg * (r * 0.3f), rad * (r * 0.35f), rad.cross(tg).unit() * (r * 0.38f), 6, 10)
                mb.ellipsoid(nc - tg * (r * 0.18f), tg * (r * 0.3f), rad * (r * 0.35f), rad.cross(tg).unit() * (r * 0.38f), 6, 10)
            }
        }
        13 -> {
            // adipocytes: big fat cells with a thin rim
            mb.sphere(rfv(b, -1.5f).pol(200f * DEG, radiusAt(b, -1.5f) + 5.3f), 80f / 2f / um, 12, 18)
            mb.sphere(rfv(b, 17f).pol(60f * DEG, radiusAt(b, 17f) + 5.6f), 90f / 2f / um, 12, 18)
        }
        else -> for ((k, s) in MK_SPOTS.withIndex()) mb.sphere(mkCentre(b, um, k), s[2] / 2f / um, 14, 22)
    }
    return mb.build(twoSided = part == 0)
}

/**
 * Stop 8 (THE CUT, 12 µm Mote: 1 unit = 8 µm): the nick in his finger. The craft hangs in the cleft
 * the splinter left, ~20-40 µm wide and cut clean. On both faces the skin is seen in section: the
 * dead, flattened plates of the stratum corneum at the top, a row of granular cells, the living
 * polygonal cells of the spinous layer with their nuclei, the basal row on its undulating basement
 * membrane, and the dermis below with papillae and their capillary loops, blood pooling in the bottom
 * of the cleft; a neutrophil is already leaving a loop. Chains of Streptococcus, purple, are being
 * carried down from the surface past the dead layer into living tissue.
 */
internal fun StereoBodyRenderer.drawCut(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    val um = umu(i)
    if (!openWall(i, -8f, 34f, T3_BLACK)) return
    drawMesh(cached("ct_dermis") { cutMesh(b, um, 0) }, T3_DERMIS, T3_DERMIS, 1f, 0f, 0.12f)
    drawMesh(cached("ct_coll") { cutMesh(b, um, 1) }, T3_COLLAGEN, T3_COLLAGEN, 1f, 0f, 0.15f)
    drawMesh(cached("ct_caps") { cutMesh(b, um, 2) }, T3_CAPILLARY, T3_CAPILLARY, 1f, 0f, 0.2f)
    drawMesh(cached("ct_bm") { cutMesh(b, um, 3) }, T3_BASEMENT, T3_BASEMENT, 1f, 0f, 0.3f)
    // The epidermis is one tightly joined sheet: a continuous cut face behind the cells, grading
    // from the living layers up through the glassy stratum lucidum to the dead keratin.
    drawMesh(cached("ct_back0") { cutMesh(b, um, 12) }, T3_EPI_BACK, T3_EPI_BACK, 1f, 0f, 0.08f)
    drawMesh(cached("ct_back1") { cutMesh(b, um, 13) }, T3_GRAN_BACK, T3_GRAN_BACK, 1f, 0f, 0.08f)
    drawMesh(cached("ct_back2") { cutMesh(b, um, 14) }, T3_CORN_BACK, T3_CORN_BACK, 1f, 0f, 0.06f)
    drawMesh(cached("ct_basal") { cutMesh(b, um, 4) }, T3_BASAL, T3_BASAL, 1f, 0f, 0.12f)
    drawMesh(cached("ct_spin") { cutMesh(b, um, 5) }, T3_SPINOUS, T3_SPINOUS, 1f, 0f, 0.12f)
    drawMesh(cached("ct_nuc") { cutMesh(b, um, 6) }, T3_NUCLEUS, T3_NUCLEUS, 1f, 0f, 0.2f)
    drawMesh(cached("ct_gran") { cutMesh(b, um, 7) }, T3_GRANULAR, T3_GRANULAR, 1f, 0f, 0.12f)
    drawMesh(cached("ct_khg") { cutMesh(b, um, 8) }, T3_KERATOHYALIN, T3_KERATOHYALIN, 1f, 0f, 0.1f)
    drawMesh(cached("ct_corn") { cutMesh(b, um, 9) }, T3_CORNEUM, T3_CORNEUM, 1f, 0f, 0.08f)
    drawMesh(cached("ct_rbc") { cutMesh(b, um, 10) }, T3_RBC, T3_RBC, 1f, 0f, 0.15f)
    drawMesh(cached("ct_fibrin") { cutMesh(b, um, 11) }, T3_FIBRIN, T3_FIBRIN, 1f, 0f, 0.3f)
    // A neutrophil squeezing out of a papillary capillary toward the invaders (diapedesis).
    val nr = 12f / 2f / um
    val np = cutPmn(b, seconds)
    val nf = rfv(b, 5.25f)
    drawLocal(cached("ct_pmn") { neutrophilNucleus(nr, 4, 9) }, np, nf.d, nf.u, T3_NUCLEUS, T3_NUCLEUS, 1f, 0.2f)
    // Streptococcus pyogenes: chains of ~1 µm Gram-positive cocci carried in from the skin surface.
    val chains = if (quality == 0) 11 else 6
    for (k in 0 until chains) {
        val mesh = cached("ct_chain$k") { chainMesh(5 + (k * 3) % 8, 1.05f / 2f / um, 100 + k) }
        val t = ((seconds / 26f + k * 0.091f) % 1f)
        val sg = if (k % 2 == 0) -1f else 1f
        val a = -1.5f + (k * 1.37f) % 11f
        // from the surface down the face of the cleft, then into the living layers
        val up = 6.8f - t * 7.6f
        val depthIn = smooth01((CUT_CORNEUM_BASE - up) / 1.6f) * 0.35f
        val f = rfv(b, a + 0.4f * sin(t * 3f + k))
        val p = f.at(sg * (cutHalf(up) - 0.25f + depthIn), up)
        val tumble = seconds * 0.15f + k
        drawLocal(mesh, p, f.d * cos(tumble) + f.u * sin(tumble), f.s, T3_GRAM_POS, T3_GRAM_POS, 1f - smooth01((t - 0.9f) / 0.1f), 0.45f)
    }
    GLES20.glDepthMask(false)
    drawScaled(sphere, np, nf.d, nf.u, nr * 1.05f, nr * 0.85f, nr * 1.15f, T3_LEUKOCYTE, T3_FIBROUS, 0.5f, 0.12f)
    drawMesh(cached("ct_lucidum") { cutMesh(b, um, 15) }, T3_LUCIDUM, T3_LUCIDUM, 0.8f, 0f, 0.3f)
    GLES20.glDepthMask(true)
}

// Thick (palmar) skin of the finger, heights in units (8 µm) with the craft at 0 in the living
// epidermis: dermis below, spinous and granular layers, stratum lucidum, and ~50 µm of corneum.
private const val CUT_SURFACE = 9.6f
private const val CUT_CORNEUM_BASE = 3.6f
private const val CUT_LUCIDUM_BASE = 3.2f
private const val CUT_GRANULAR_BASE = 2.6f
private const val CUT_FLOOR = -6.4f
/** Half-width of the cleft at height [up]: a clean wedge, wider at the surface (32-56 µm). */
private fun cutHalf(up: Float) = 2.0f + 1.5f * (up - CUT_FLOOR) / (CUT_SURFACE - CUT_FLOOR)
/** The dermo-epidermal junction: rete ridges and dermal papillae. */
private fun cutJunction(a: Float) = -3.1f + 0.8f * sin(a * 2f * PI.toFloat() / 6.4f)

private fun StereoBodyRenderer.cutPmn(b: Float, seconds: Float): V3 {
    // half out of the capillary loop in the papilla at along 5.25 on the starboard face, creeping out
    val t = 0.35f + 0.25f * ((seconds / 60f) % 1f)
    val loopTop = cutJunction(5.25f) - 0.75f
    return wAt(b, 5.25f, cutHalf(loopTop) - t * 0.7f, loopTop - 0.4f)
}

/** The cut's parts (see [drawCut]). */
private fun StereoBodyRenderer.cutMesh(b: Float, um: Float, part: Int): T3Mesh {
    val mb = Mb()
    // cells (5) and their nuclei (6) share one random sequence so each nucleus sits in its cell
    val rnd = java.util.Random(91L + (if (part == 6) 5 else part))
    val a0 = -6f; val a1 = 13f
    val kerR = 11f / 2f / um
    when (part) {
        0 -> {
            // the dermis in section on both faces, from the junction down, and the cleft's floor
            for (sg in floatArrayOf(-1f, 1f)) mb.gridFd(48, 8, { u, v ->
                val a = a0 + (a1 - a0) * u; val top = cutJunction(a) - 0.25f
                val up = CUT_FLOOR + (top - CUT_FLOOR) * v
                wAt(b, a, sg * cutHalf(up), up)
            }) { u, _, _ -> -rfv(b, a0 + (a1 - a0) * u).s * sg }
            mb.gridFd(48, 4, { u, v -> val a = a0 + (a1 - a0) * u; wAt(b, a, (v * 2f - 1f) * cutHalf(CUT_FLOOR), CUT_FLOOR - 0.2f * (1f - (v * 2f - 1f).pow(2))) }) { u, _, _ -> rfv(b, a0 + (a1 - a0) * u).u }
        }
        1 -> for (sg in floatArrayOf(-1f, 1f)) for (k in 0 until 5) {
            // wavy collagen bundles in the dermis, cut across the face
            val up0 = CUT_FLOOR + 0.45f + k * 0.42f
            val pts = (0..24).map { q -> val a = a0 + q * 0.8f; val up = up0 + 0.18f * sin(q * 1.3f + k); wAt(b, a, sg * (cutHalf(up) - 0.05f), up) }
            mb.tube(pts, FloatArray(25) { 0.09f }, 5, false)
        }
        2 -> for (sg in floatArrayOf(-1f, 1f)) {
            // a capillary loop rising into each dermal papilla, half-exposed on the cut face
            var a = 1.6f - 6.4f * 2f
            while (a < a1) {
                if (a > a0 + 0.5f) {
                    val top = cutJunction(a) - 0.75f
                    val pts = (0..12).map { q ->
                        val t = q / 12f; val ang = t * PI.toFloat()
                        val up = top - 1.8f + 1.8f * sin(ang)
                        wAt(b, a - 0.45f * cos(ang), sg * (cutHalf(up) + 0.05f), up)
                    }
                    mb.tube(pts, FloatArray(13) { 8f / 2f / um }, 7, true)
                }
                a += 6.4f
            }
        }
        3 -> for (sg in floatArrayOf(-1f, 1f)) {
            val pts = (0..95).map { q -> val a = a0 + q * 0.2f; val up = cutJunction(a) - 0.18f; wAt(b, a, sg * (cutHalf(up) - 0.02f), up) }
            mb.tube(pts, FloatArray(96) { 0.07f }, 5, false)
        }
        4 -> for (sg in floatArrayOf(-1f, 1f)) {
            // the basal layer: one row of columnar cells standing on the basement membrane
            var a = a0
            while (a < a1) {
                val up = cutJunction(a) + 0.55f
                val f = rfv(b, a)
                mb.ellipsoid(f.at(sg * (cutHalf(up) + 0.05f), up), f.u * 0.62f, f.d * 0.34f, f.s * 0.3f, 5, 8)
                a += 0.7f
            }
        }
        5, 6 -> for (sg in floatArrayOf(-1f, 1f)) {
            // the spinous layer: polygonal keratinocytes cut through, each with its nucleus
            var row = 0
            var up = -3.1f + 1.2f - 0.8f
            while (up < CUT_GRANULAR_BASE - kerR * 0.6f) {
                var a = a0 + (if (row % 2 == 0) 0f else kerR)
                while (a < a1) {
                    val f = rfv(b, a)
                    val u2 = up + 0.08f * rnd.nextFloat()
                    if (u2 > cutJunction(a) + 1.1f) {
                        // cells packed edge to edge, alternate rows offset: a polygonal mosaic
                        val c = f.at(sg * (cutHalf(u2) + kerR * 0.3f), u2)
                        if (part == 5) mb.ellipsoid(c, f.s * (kerR * 0.55f), f.d * kerR, f.u * kerR, 5, 7)
                        else mb.ellipsoid(f.at(sg * (cutHalf(u2) - 0.05f), u2), f.s * 0.16f, f.d * 0.27f, f.u * 0.24f, 5, 8)
                    }
                    a += kerR * 2f
                }
                up += kerR * 1.6f; row++
            }
            if (part == 6) {
                // basal nuclei
                var a = a0
                while (a < a1) {
                    val up2 = cutJunction(a) + 0.6f; val f = rfv(b, a)
                    mb.ellipsoid(f.at(sg * (cutHalf(up2) - 0.22f), up2), f.u * 0.3f, f.d * 0.2f, f.s * 0.14f, 4, 7)
                    a += 0.7f
                }
            }
        }
        7, 8 -> for (sg in floatArrayOf(-1f, 1f)) {
            // the granular layer: flattened cells packed with dark keratohyalin granules
            var a = a0
            while (a < a1) {
                val f = rfv(b, a); val up = CUT_GRANULAR_BASE + 0.3f
                // (two staggered rows of flattened granular cells)
                val c = f.at(sg * (cutHalf(up) + 0.08f), up)
                if (part == 7) mb.ellipsoid(c, f.s * 0.28f, f.d * 1.05f, f.u * 0.32f, 5, 8)
                else for (q in 0 until 6) mb.sphere(c - f.s * (sg * 0.24f) + f.d * ((rnd.nextFloat() - 0.5f) * 1.6f) + f.u * ((rnd.nextFloat() - 0.5f) * 0.4f), 0.07f, 3, 5)
                a += 2.0f
            }
        }
        9 -> for (sg in floatArrayOf(-1f, 1f)) {
            // the stratum corneum: stacked, flattened dead plates, loosening at the top
            // densely stacked flattened plates (corneocytes ~30 µm across, ~1 µm thick), no gaps
            var layer = 0
            var up = CUT_CORNEUM_BASE + 0.06f
            while (up < CUT_SURFACE) {
                var a = a0 + (layer % 3) * 1.1f
                while (a < a1) {
                    val f = rfv(b, a)
                    mb.ellipsoid(f.at(sg * (cutHalf(up) + 0.05f), up + 0.03f * sin(a * 3f + layer)), f.u * 0.07f, f.d * 1.75f, f.s * 0.35f, 3, 8)
                    a += 3.3f
                }
                up += 0.12f; layer++
            }
            // plates torn by the splinter, hanging into the cleft near the surface: a puncture, not a scalpel line
            for (k in 0 until 5) {
                val a = 0.5f + k * 2.3f + 0.6f * sg; val u2 = 5.2f + k * 0.8f
                val f = rfv(b, a)
                val tilt = (20f + 5f * k) * DEG
                val ax = (f.u * cos(tilt) - f.s * (sg * sin(tilt))).unit()
                mb.ellipsoid(f.at(sg * (cutHalf(u2) - 0.3f), u2 - 0.2f), ax * 0.07f, f.d * 1.4f, ax.cross(f.d).unit() * 0.5f, 3, 8)
            }
        }
        12, 13, 14, 15 -> {
            // continuous cut faces behind the epidermal cells: living layers (12), granular (13),
            // corneum (14), and the glassy stratum lucidum in the face itself (15)
            val depth = if (part == 15) 0.02f else 0.35f
            for (sg in floatArrayOf(-1f, 1f)) mb.gridFd(64, 6, { u, v ->
                val a = a0 + (a1 - a0) * u
                val lo = when (part) { 12 -> cutJunction(a) - 0.22f; 13 -> CUT_GRANULAR_BASE - 0.05f; 14 -> CUT_LUCIDUM_BASE; else -> CUT_LUCIDUM_BASE }
                val hi = when (part) { 12 -> CUT_GRANULAR_BASE; 13 -> CUT_LUCIDUM_BASE + 0.02f; 14 -> CUT_SURFACE; else -> CUT_CORNEUM_BASE }
                val up = lo + (hi - lo) * v
                wAt(b, a, sg * (cutHalf(up) + depth), up)
            }) { u, _, _ -> -rfv(b, a0 + (a1 - a0) * u).s * sg }
        }
        10 -> for (k in 0 until 10) {
            // blood from the cut loops pooling at the bottom of the cleft
            val f = rfv(b, 3.5f + rnd.nextFloat() * 4f)
            val c = f.at((rnd.nextFloat() - 0.5f) * 1.6f, CUT_FLOOR + 0.35f + rnd.nextFloat() * 0.6f)
            mb.redCell(c, (f.u + f.s * (rnd.nextFloat() - 0.5f) + f.d * (rnd.nextFloat() - 0.5f)).unit(), 7.5f / 2f / um, 4, 12)
        }
        else -> for (k in 0 until 18) {
            val a = 2.5f + rnd.nextFloat() * 6f; val s0 = -cutHalf(CUT_FLOOR + 1f) + 0.1f; val s1 = -s0
            val u0 = CUT_FLOOR + 0.3f + rnd.nextFloat() * 1.3f; val u1 = CUT_FLOOR + 0.3f + rnd.nextFloat() * 1.3f
            val p0 = wAt(b, a, s0, u0); val p1 = wAt(b, a + (rnd.nextFloat() - 0.5f) * 2f, s1, u1)
            mb.tube(listOf(p0, lerpV(p0, p1, 0.5f) - rfv(b, a).u * 0.2f, p1), FloatArray(3) { 0.03f }, 4, false)
        }
    }
    return mb.build(twoSided = part == 0 || part >= 12)
}

/**
 * Stop 9 (THE FEVER, 12 µm Mote: 1 unit = 8 µm): septicaemia in a postcapillary venule ~45 µm
 * across. Chains of Streptococcus (1 µm cocci, Gram-positive purple) multiply in the blood, every
 * chain lengthening and the count doubling on a steady clock that never resets while we watch.
 * Neutrophils (lobed nuclei, granules, one engulfed chain each in a phagosome, a pseudopod reaching
 * out) are outnumbered. The vessel is dilating, and its endothelium has opened gaps through which
 * straw plasma and red cells leak into the oedematous tissue outside.
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
    val holes = SEPSIS_GAPS
    val (tube, junc, nuc) = endotheliumMeshes("sp_endo", b, -8f, 24f, { a -> radiusAt(b, a) * 0.9f }, 30f / um, 10, holes)
    drawMeshRadial(nuc, b, dil, T3_ENDO_NUCLEUS, T3_ENDOTHELIUM, 0.75f, 0f, 0.05f)
    drawMeshRadial(junc, b, dil, T3_JUNCTION, T3_JUNCTION, 0.45f, 0f, 0.08f)
    // Each gap between endothelial cells picked out by a bright rim, so the leak points read.
    drawMeshRadial(cached("sp_rims") { sepsisMesh(b, um, 2) }, b, dil, T3_GAP_RIM, T3_GAP_RIM, 1f, 0f, 0.4f)
    // Red cells squeezing out through two of the gaps.
    val rr = 7.5f / 2f / um
    for (k in 0 until 2) {
        val (ha, hth) = holes[k * 2]
        val q = ((t / 14f + k * 0.5f) % 1f)
        val f = rfv(b, ha)
        val R = radiusAt(b, ha) * 0.9f * dil
        val rad = f.radial(hth)
        val c = f.pol(hth, R - 0.5f + q * 1.4f)
        val pinch = 1f - 0.45f * (1f - abs(q - 0.35f) / 0.65f).coerceIn(0f, 1f)
        drawScaled(rbc, c, rad, f.d, rr * pinch, rr * 0.7f, rr * (1.2f / pinch).coerceAtMost(1.6f), T3_RBC_DEOXY, T3_RBC, 1f, 0.15f)
    }
    // The bacteria (time-lapse): the number of chains doubles every 16 s, each lengthening to the
    // short chains (up to 8 cocci) that S. pyogenes forms in blood.
    val cap = if (quality == 0) 12 else 8
    val live = min(cap.toFloat(), 4f * 2f.pow(t / 16f)).toInt()
    val rc = 1.0f / 2f / um
    for (k in 0 until live) {
        val born = if (k < 4) 0f else 16f * log2((k + 1) / 4f)
        val age = t - born
        val len = min(8, 2 + (age / 4f).toInt())
        val mesh = cached("sp_chain${len}_${k % 3}") { chainMesh(len, rc, 200 + len * 3 + k % 3) }
        val a = -3f + ((k * 3.7f + age * 0.35f) % 14f)
        val th = k * 2.399f
        val rho = (0.35f + 0.5f * ((k * 29 % 10) / 10f)) * radiusAt(b, a) * 0.9f
        val f = rfv(b, a)
        val tumble = seconds * 0.2f + k * 1.3f
        drawLocal(mesh, f.pol(th, rho), f.d * cos(tumble) + f.s * sin(tumble), f.u, T3_GRAM_POS, T3_GRAM_POS, min(1f, age / 1.5f + 0.2f), 0.45f)
    }
    // Neutrophils reaching for the chains: lobed nucleus, granules, a phagosome with a swallowed chain.
    val pr = 13f / 2f / um
    val nucM = cached("sp_pmn") { neutrophilNucleus(pr, 4, 21) }
    val granM = cached("sp_gran") { granuleMesh(pr, 36, 22) }
    val eaten = cached("sp_eaten") { chainMesh(4, rc, 300) }
    val pmn = if (quality == 0) 3 else 2
    for (pass in 0 until 2) {
        if (pass == 1) GLES20.glDepthMask(false)
        for (w in 0 until pmn) {
            val a = 1.5f + w * 3.6f + 0.6f * sin(seconds * 0.15f + w)
            val th = (200f + w * 110f) * DEG
            val f = rfv(b, a)
            val rad = f.radial(th)
            val c = f.pol(th, radiusAt(b, a) * 0.9f * dil - pr * 1.05f)
            val ph = c - rad * (pr * 0.35f) + f.d * (pr * 0.3f)
            if (pass == 0) {
                drawLocal(nucM, c, f.d, rad, T3_NUCLEUS, T3_NUCLEUS, 1f, 0.2f)
                if (quality == 0) drawLocal(granM, c, f.d, f.u, T3_NEUTRO_GRANULE, T3_NEUTRO_GRANULE, 1f, 0.3f)
                drawLocal(eaten, ph, f.s, f.u, T3_GRAM_POS, T3_GRAM_POS, 1f, 0.3f)
            } else {
                drawScaled(sphere, c, f.d, rad, pr * 1.05f, pr * 0.8f, pr * 1.15f, T3_LEUKOCYTE, T3_FIBROUS, 0.45f, 0.12f)
                // the phagosome's membrane, and a pseudopod reaching into the lumen toward the chains
                drawSphereAt(ph.x, ph.y, ph.z, 0.42f, 0.42f, 0.42f, T3_FIBROUS, T3_FIBROUS, 0.35f, 0f, 0f, 1f, 0f, sphere, 0f, 0.3f)
                val reach = 0.6f + 0.25f * sin(seconds * 0.7f + w * 2f)
                val pp = c - rad * (pr * (0.9f + reach * 0.5f)) + f.d * (pr * 0.4f)
                drawScaled(sphere, pp, -rad, f.d, pr * 0.42f, pr * 0.3f, pr * (0.5f + reach * 0.4f), T3_LEUKOCYTE, T3_FIBROUS, 0.45f, 0.12f)
            }
        }
    }
    // Plasma streaming out through each gap into the tissue.
    val arr = dynLines.data
    var v = 0
    for ((k, h) in holes.withIndex()) {
        val (ha, hth) = h
        val f = rfv(b, ha); val R = radiusAt(b, ha) * 0.9f * dil
        for (q in 0 until 6) {
            val ph = ((seconds * 0.3f / 1.6f + q / 6f + k * 0.13f) % 1f)
            val off = f.d * (0.35f * sin(q * 2.1f)) + f.d.cross(f.radial(hth)).unit() * (0.25f * cos(q * 1.7f))
            val p0 = f.pol(hth, R - 0.2f + ph * 1.6f) + off
            v = putLine(arr, v, p0, p0 + f.radial(hth) * 0.3f, T3_OEDEMA, 0.6f * (1f - ph) * (0.3f + 0.7f * leak))
        }
    }
    drawDyn(v, 2f)
    GLES20.glDepthMask(false)
    // ...and soaking the tissue: an interstitial haze that thickens as the leak goes on.
    drawMeshRadial(cached("sp_haze1") { sepsisMesh(b, um, 3) }, b, 1f, T3_OEDEMA, T3_OEDEMA, 0.12f * leak, 0f, 0.3f)
    drawMeshRadial(cached("sp_haze2") { sepsisMesh(b, um, 4) }, b, 1f, T3_OEDEMA, T3_OEDEMA, 0.12f * leak, 0f, 0.3f)
    drawMeshRadial(tube, b, dil, T3_ENDOTHELIUM, T3_JUNCTION, 0.32f, 0f, 0.12f)
    GLES20.glDepthMask(true)
    // The venule is full of dark venous blood plasma.
    fillLumen("sp_blood", b, -8f, 34f, 0.86f, T3_VENOUS, 0.18f, 0.1f)
}

/** Gaps opened between endothelial cells: (along, angle). */
private val SEPSIS_GAPS = listOf(0.8f to 30f * DEG, 3.2f to 160f * DEG, 5.5f to 300f * DEG, 7.8f to 95f * DEG, 10.2f to 220f * DEG)

/** Outside the leaking venule: collagen of the tissue (0) and red cells already leaked into it (1). */
private fun StereoBodyRenderer.sepsisMesh(b: Float, um: Float, part: Int): T3Mesh {
    val mb = Mb()
    val rnd = java.util.Random(101L + part)
    if (part == 2) {
        for ((ha, hth) in SEPSIS_GAPS) {
            val pts = (0..24).map { q ->
                val ang = q * 2f * PI.toFloat() / 24f
                val a = ha + 0.55f * cos(ang)
                rfv(b, a).pol(hth + 0.16f * sin(ang), radiusAt(b, a) * 0.9f - 0.01f)
            }
            mb.tube(pts, FloatArray(25) { 0.025f }, 4, false)
        }
        return mb.build()
    }
    if (part == 3 || part == 4) return railTube(b, -8f, 26f, (radiusAt(b, 0f) + (if (part == 3) 1.2f else 2.5f)) / radiusAt(b, 0f), true).build(twoSided = true)
    if (part == 0) for (k in 0 until 8) {
        val th = rnd.nextFloat() * 2f * PI.toFloat()
        val a0 = -5f + rnd.nextFloat() * 16f
        val lift = 1.2f + 1.6f * rnd.nextFloat()
        val pts = (0..10).map { q -> val a = a0 + (q - 5) * 0.9f; rfv(b, a).pol(th + 0.5f * sin(q * 0.7f + k), radiusAt(b, a) + lift + 0.4f * sin(q * 1.1f)) }
        mb.tube(pts, FloatArray(11) { 0.07f }, 5, true)
    } else for (h in SEPSIS_GAPS) {
        // leaked red cells lying in the oedematous tissue just outside each gap
        val (ha, hth) = h
        for (q in 0 until 2) {
            val f = rfv(b, ha + (q - 0.5f) * 1.3f)
            val c = f.pol(hth + (q - 0.5f) * 0.35f, radiusAt(b, ha) + 1.1f + 0.5f * q)
            mb.redCell(c, (f.radial(hth) + f.d * (rnd.nextFloat() - 0.5f)).unit(), 7.5f / 2f / um, 4, 12)
        }
    }
    return mb.build()
}
