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
internal val T3_GRANULOMA = floatArrayOf(0.84f, 0.68f, 0.72f, 1f)
internal val T3_EPITHELIOID = floatArrayOf(0.93f, 0.80f, 0.78f, 1f)
internal val T3_FIBROUS = floatArrayOf(0.96f, 0.94f, 0.89f, 1f)
internal val T3_LANGHANS = floatArrayOf(0.94f, 0.80f, 0.88f, 1f)
internal val T3_NUCLEUS = floatArrayOf(0.44f, 0.30f, 0.68f, 1f)
internal val T3_LYMPHOCYTE = floatArrayOf(0.42f, 0.40f, 0.78f, 1f)
internal val T3_INTIMA = floatArrayOf(0.74f, 0.64f, 0.68f, 1f)
internal val T3_STEEL = floatArrayOf(0.70f, 0.74f, 0.80f, 1f)
internal val T3_STEEL_EDGE = floatArrayOf(0.96f, 0.98f, 1f, 1f)
internal val T3_BORE = floatArrayOf(0.36f, 0.38f, 0.44f, 1f)
internal val T3_VALVE = floatArrayOf(0.93f, 0.82f, 0.86f, 1f)
internal val T3_FLOW = floatArrayOf(0.86f, 0.16f, 0.20f, 1f)
internal val T3_COLD = floatArrayOf(0.24f, 0.33f, 0.52f, 1f)
internal val T3_STORED_RBC = floatArrayOf(0.50f, 0.06f, 0.12f, 1f)
internal val T3_PACKED = floatArrayOf(0.40f, 0.05f, 0.09f, 1f)
internal val T3_PLASMA = floatArrayOf(0.96f, 0.86f, 0.50f, 1f)
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
internal val T3_JUNCTION = floatArrayOf(0.90f, 0.72f, 0.78f, 1f)
internal val T3_ENDO_NUCLEUS = floatArrayOf(0.80f, 0.64f, 0.82f, 1f)
internal val T3_TISSUE_DARK = floatArrayOf(0.20f, 0.11f, 0.15f, 1f)
internal val T3_ISCHAEMIC = floatArrayOf(0.50f, 0.48f, 0.58f, 1f)
internal val T3_ISCHAEMIC_A = floatArrayOf(0.36f, 0.34f, 0.46f, 1f)
internal val T3_PERFUSED = floatArrayOf(0.90f, 0.40f, 0.40f, 1f)
internal val T3_PERFUSED_A = floatArrayOf(0.66f, 0.18f, 0.24f, 1f)
internal val T3_CAP_EMPTY = floatArrayOf(0.40f, 0.30f, 0.40f, 1f)
internal val T3_SKIN_EDGE = floatArrayOf(0.74f, 0.52f, 0.42f, 1f)
internal val T3_SKIN_TOP = floatArrayOf(0.95f, 0.76f, 0.66f, 1f)
internal val T3_DERMIS = floatArrayOf(0.96f, 0.80f, 0.78f, 1f)
internal val T3_FAT = floatArrayOf(0.99f, 0.88f, 0.52f, 1f)
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
internal val T3_STEM = floatArrayOf(0.82f, 0.87f, 0.98f, 1f)
internal val T3_CORNEUM = floatArrayOf(0.96f, 0.91f, 0.76f, 1f)
internal val T3_GRANULAR = floatArrayOf(0.90f, 0.72f, 0.72f, 1f)
internal val T3_KERATOHYALIN = floatArrayOf(0.36f, 0.22f, 0.44f, 1f)
internal val T3_SPINOUS = floatArrayOf(0.94f, 0.70f, 0.72f, 1f)
internal val T3_BASAL = floatArrayOf(0.80f, 0.48f, 0.60f, 1f)
internal val T3_BASEMENT = floatArrayOf(0.99f, 0.95f, 0.95f, 1f)
internal val T3_COLLAGEN = floatArrayOf(0.99f, 0.86f, 0.86f, 1f)
internal val T3_INTERSTITIUM = floatArrayOf(0.44f, 0.28f, 0.30f, 1f)
internal val T3_OEDEMA = floatArrayOf(0.98f, 0.88f, 0.56f, 1f)
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
    val tube = cached("wall$i") { railTube(i.toFloat(), a0, end, 0.84f, true).build(twoSided = true) }
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
            mb.tube(pts, FloatArray(pts.size) { 0.016f }, 4, false)
            // The cells' ends, staggered column to column.
            var e = a0 + (if (col % 2 == 0) 0f else cellLen * 0.5f)
            while (e <= a1) {
                val arc = ArrayList<V3>()
                for (k in 0..5) arc.add(rfv(b, e + 0.2f * sin(k * 1.1f)).pol(th - dth + k * dth / 5f, rAt(e) - 0.02f))
                mb.tube(arc, FloatArray(arc.size) { 0.016f }, 4, false)
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
    drawMeshRadial(cached("cav_caps") { cavityAlveoli(b, g, um, 2) }, b, breath, T3_CAPILLARY, T3_CAPILLARY, 1f, 0f, 0.15f)

    // The cavity: fibrous outer capsule, caseous lining, the layered lip of its mouth.
    drawMesh(cached("cav_shell") { cavityShell(g, 0) }, T3_FIBROUS, T3_FIBROUS, 1f, 0f, 0.1f)
    drawMesh(cached("cav_inner") { cavityShell(g, 1) }, T3_CASEUM_DEEP, T3_CASEUM, 1f, 0f, 0.28f)
    drawMesh(cached("cav_lumps") { cavityShell(g, 2) }, T3_CASEUM, T3_CASEUM, 1f, 0f, 0.3f)
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
    // The mouth faces the axis, tilted 30° back toward the approaching craft.
    val inward = (-f.radial(angle) * cos(30f * DEG) - f.d * sin(30f * DEG)).unit()
    val e1 = perp(inward); val e2 = inward.cross(e1).unit()
    val rm = 165f / um          // mouth ~330 µm across
    val rc = 480f / um          // cavity ~1 mm across
    val centre = mouth - inward * sqrt(rc * rc - rm * rm)
    CavityGeom(mouth, inward, e1, e2, centre, rm, rc, rimR, along, angle)
}

/** Alveolar cups (part 0), their septal rims (1) and the capillaries in the septa (2). */
private fun StereoBodyRenderer.cavityAlveoli(b: Float, g: CavityGeom, um: Float, part: Int): T3Mesh {
    val mb = Mb()
    val tub = tubercleSpots(b)
    var row = 0
    var a = -5.5f
    while (a < 13.5f) {
        val f = rfv(b, a)
        val rimR = radiusAt(b, a) - 1.45f
        val count = 5
        val R = min(110f / um * 1.18f, rimR * sin(PI.toFloat() / count) * 0.92f)   // ~220 µm mouths
        for (k in 0 until count) {
            val th = (k + (if (row % 2 == 0) 0f else 0.5f)) * 2f * PI.toFloat() / count + 0.3f
            val q = f.pol(th, rimR)
            if ((q - g.mouth).len() < g.rm + 2.6f) continue          // the cavity's mouth breaks the honeycomb here
            if (tub.any { (q - it).len() < 1.9f }) continue           // and the tubercles sit in the wall
            val out = f.radial(th)                                     // from the duct into the alveolus
            val e1 = f.d; val e2 = out.cross(e1).unit()
            when (part) {
                0 -> mb.grid(5, 14, { u, v ->
                    val ph = u * PI.toFloat() / 2f; val t2 = v * 2f * PI.toFloat()
                    q + out * (R * 1.05f * cos(ph)) + (e1 * cos(t2) + e2 * sin(t2)) * (R * sin(ph))
                }) { _, _, p -> q - p }                                   // lit from inside the cup
                1 -> mb.torus(q, out, R, 0.1f, 20, 5)
                else -> mb.torus(q + out * 0.12f, out, R + 0.17f, 0.06f, 20, 4)
            }
        }
        a += 2.55f; row++
    }
    return mb.build(twoSided = part == 0)
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
        1 -> {
            val hole = asin(g.rm / g.rc)
            mb.grid(12, 28, { u, v ->
                val ph = hole + (PI.toFloat() - hole) * u; val th = v * 2f * PI.toFloat()
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
    return mb.build(twoSided = part == 1)
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
        else -> for (k in 0 until 26) {
            val th = k * 2f * PI.toFloat() / 26f
            mb.sphere(at(r2 + 0.12f + 0.08f * sin(k * 1.9f), th, 0.07f), 0.09f, 4, 6)
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
    val nd = needleFrame(b, um)
    // The bore first, then the steel drawn a little translucent (a schematic cutaway) so the hollow
    // of the needle reads from behind, where the bevel itself is seen almost edge-on.
    drawMesh(cached("dn_bore") { needleMesh(nd, 1) }, T3_BORE, T3_BORE, 1f, 0f, 0f)
    drawMesh(cached("dn_bevel") { needleMesh(nd, 2) }, T3_STEEL_EDGE, T3_STEEL_EDGE, 1f, 0f, 0.35f)
    drawMesh(cached("dn_collar") { needleMesh(nd, 3) }, T3_INTIMA, T3_VALVE, 1f, 0f, 0.1f)
    GLES20.glDepthMask(false)
    drawMesh(cached("dn_outer") { needleMesh(nd, 0) }, T3_STEEL, T3_STEEL_EDGE, 0.62f, 0f, 0.12f)
    GLES20.glDepthMask(true)
    // The valve: cusps flutter a little with each surge of flow, never closing while blood runs forward.
    val flutter = 1f + 0.035f * sin(seconds * 2f * PI.toFloat() / 1.6f)
    drawMeshRadial(cached("dn_cusps") { valveMesh(b, 0) }, b + 8.5f / NODE_UNITS, flutter, T3_VALVE, T3_SEPTUM, 0.92f, 0f, 0.12f)
    drawMeshRadial(cached("dn_edges") { valveMesh(b, 1) }, b + 8.5f / NODE_UNITS, flutter, T3_SEPTUM, T3_SEPTUM, 1f, 0f, 0.35f)
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
        v = putLine(arr, v, p0, p1, T3_FLOW, 0.75f * (1f - smooth01((t - 0.85f) / 0.15f)))
    }
    for (k in 0 until (if (quality == 0) 6 else 3)) {
        // Drawn into the needle: from beside the shaft, curving into the bevel's opening.
        val t = ((seconds * 0.35f + k / 6f) % 1f)
        val side = if (k % 2 == 0) 1f else -1f
        val start = nd.o + nd.x * (side * 1.6f) - nd.z * 5.5f + nd.y * 0.2f
        val ctrl = nd.o + nd.x * (side * 1.2f) - nd.z * 2.0f + nd.y * 1.3f
        val end = nd.o - nd.z * (nd.bevelLen * 0.5f) + nd.y * 0.1f
        val p0 = bez(start, ctrl, end, t); val p1 = bez(start, ctrl, end, (t + 0.08f).coerceAtMost(1f))
        v = putLine(arr, v, p0, p1, T3_FLOW, 0.9f * (1f - t * 0.5f))
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

/** The needle's own frame: origin on its axis at the tip, z forward along the shaft, y toward the bevel (skin side). */
private class NeedleFrame(val o: V3, val x: V3, val y: V3, val z: V3, val ro: Float, val ri: Float, val bevelLen: Float)

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
    NeedleFrame(o, x, y, z, ro, ri, 2f * ro / tan(18f * DEG))
}

/** Needle parts: outer shaft (0), bore (1), the polished bevel face (2), the vein wall dimpled round the entry (3). */
private fun needleMesh(nd: NeedleFrame, part: Int): T3Mesh {
    val mb = Mb()
    val back = -9f
    // The bevel plane: the point at the bottom (y = -ro, z = 0), rising back to the heel at the top.
    fun zCut(y: Float) = -(y + nd.ro) / (2f * nd.ro) * nd.bevelLen
    fun w(lx: Float, ly: Float, lz: Float) = nd.o + nd.x * lx + nd.y * ly + nd.z * lz
    when (part) {
        0, 1 -> {
            val r = if (part == 0) nd.ro else nd.ri
            mb.grid(10, 28, { u, v ->
                val th = v * 2f * PI.toFloat(); val ly = r * sin(th); val lx = r * cos(th)
                w(lx, ly, back + (zCut(ly) - back) * u)
            }) { _, v, _ ->
                val th = v * 2f * PI.toFloat(); val rad = nd.x * cos(th) + nd.y * sin(th)
                if (part == 0) rad else -rad
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
        else -> mb.torus(w(0f, 0f, -5.6f), nd.z, nd.ro + 0.1f, 0.1f, 24, 6)
    }
    return mb.build(twoSided = part == 2)
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
            val R = radiusAt(b, a) * 0.93f
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
    if (!openWall(i, -8f, 34f, T3_COLD)) return
    drawMesh(cached("st_packed") { storedMesh(b, um, 0) }, T3_PACKED, T3_STORED_RBC, 1f, 0f, 0.05f)
    drawMesh(cached("st_cells") { storedMesh(b, um, 1) }, T3_STORED_RBC, T3_RBC, 1f, 0f, 0f)
    drawMesh(cached("st_plt") { storedMesh(b, um, 2) }, T3_PLATELET, T3_PLATELET, 1f, 0f, 0.3f)
    drawMesh(cached("st_nuc") { storedMesh(b, um, 3) }, T3_NUCLEUS, T3_NUCLEUS, 1f, 0f, 0.2f)
    GLES20.glDepthMask(false)
    drawMesh(cached("st_wbc") { storedMesh(b, um, 4) }, T3_LEUKOCYTE, T3_FIBROUS, 0.55f, 0f, 0.15f)
    // Plasma: a straw-tinted body filling everything above the cells (seen from within it).
    drawMesh(cached("st_plasma") { storedMesh(b, um, 5) }, T3_PLASMA, T3_PLASMA, 0.26f, 0f, 0.55f)
    // The glass, and the cold light on it.
    drawMesh(cached("st_glass") { storedMesh(b, um, 6) }, T3_GLASS, T3_GLASS, 0.30f, 0f, 0.35f)
    drawMesh(cached("st_glint") { storedMesh(b, um, 7) }, T3_STEEL_EDGE, T3_STEEL_EDGE, 0.45f + 0.1f * sin(seconds * 0.4f), 0f, 0.8f)
    GLES20.glDepthMask(true)
}

private const val BED_TOP = -1.85f

/** Stored blood: packed mass (0), rouleaux and single cells (1), platelets (2), leukocyte nuclei (3), leukocytes (4), plasma (5), glass (6), glint (7). */
private fun StereoBodyRenderer.storedMesh(b: Float, um: Float, part: Int): T3Mesh {
    val mb = Mb()
    val rbcR = 7.5f / 2f / um
    val rim = 2.5f / um                                            // cell thickness at the rim: the rouleau spacing
    val rnd = java.util.Random(47)
    val glassSide = 2.75f
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
        2 -> for (k in 0 until 26) {
            // platelets (2.5 µm) scattered on top of the cells: the buffy coat
            val f = rfv(b, -4f + rnd.nextFloat() * 15f)
            val c = f.at(-3.3f + rnd.nextFloat() * 5.6f, BED_TOP + rbcR * 1.95f + rnd.nextFloat() * 0.1f)
            val tip = (f.u + f.s * (rnd.nextFloat() - 0.5f) * 0.6f).unit()
            mb.ellipsoidAxes(c, tip, 0.5f / um, perp(tip), 1.25f / um, 1.1f / um, 4, 8)
        }
        3, 4 -> {
            // white cells in the buffy coat, kept to the sides of the craft's lane
            val spots = listOf(Triple(1.5f, -2.3f, 12f), Triple(4.5f, 1.9f, 12f), Triple(7.5f, -2.6f, 9f), Triple(10.5f, 1.6f, 14f))
            for ((k, sp) in spots.withIndex()) {
                val (a, s, dUm) = sp
                val f = rfv(b, a)
                val r = dUm / 2f / um
                val c = f.at(s, BED_TOP + rbcR * 1.9f + r * 0.8f)
                if (part == 4) mb.ellipsoid(c, f.u * (r * 0.85f), f.d * r, f.s * r, 8, 14)
                else if (k == 2) mb.sphere(c, r * 0.8f, 8, 12)                       // a lymphocyte: a round nucleus filling the cell
                else if (k == 3) mb.torus(c, f.u, r * 0.45f, r * 0.24f, 14, 6, 0.6f)  // a monocyte: kidney-shaped nucleus
                else for (q in 0 until 3) {                                          // neutrophils: lobed nuclei
                    val a2 = (q - 1) * 0.9f
                    mb.sphere(c + f.d * (cos(a2) * r * 0.42f) + f.s * (sin(a2) * r * 0.42f), r * 0.27f, 6, 10)
                }
            }
        }
        5 -> {
            // seen from inside: inward normals
            val f = rfv(b, 3f)
            mb.ellipsoid(f.at(-0.4f, BED_TOP + 3.9f), f.u * 3.95f, f.s * 5.5f, f.d * 14f, 10, 20, inward = true)
        }
        6 -> mb.gridFd(8, 2, { u, v -> wAt(b, -7f + 21f * u, glassSide, -3.4f + 7.4f * v) }) { u, _, _ -> -rfv(b, -7f + 21f * u).s }
        else -> for (k in 0 until 2) {
            val up = 1.2f + k * 0.9f
            mb.tube((0..12).map { q -> wAt(b, -5f + q * 1.5f, glassSide - 0.03f, up + 0.15f * sin(q * 0.5f)) }, FloatArray(13) { 0.05f - 0.02f * k }, 4, true)
        }
    }
    return mb.build(twoSided = part == 6)
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
    drawMesh(cached("wd_ibands") { woundMesh(b, um, 0) }, T3_I_BAND, T3_I_BAND, 1f, 0f, 0.08f)
    drawMesh(cached("wd_abands") { woundMesh(b, um, 1) }, T3_A_BAND, T3_A_BAND, 1f, 0f, 0.05f)
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
        val t = ((seconds / 40f + k * 0.5f) % 1f)
        val a = (if (k == 0) 0.5f else 5.5f) + 2.5f * t
        val s = if (k == 0) -1.1f + 0.9f * t else 1.8f - 0.6f * t
        val f = rfv(b, a)
        val c = f.at(s, floorUp + 0.3f + r * 0.6f)
        val sq = 1f + 0.08f * sin(seconds * 1.3f + k)
        drawLocal(nucMesh, c, f.d, f.u, T3_NUCLEUS, T3_NUCLEUS, 1f, 0.2f)
        GLES20.glDepthMask(false)
        drawScaled(sphere, c, f.d, f.u, r * 1.1f * sq, r * 0.72f, r * 1.25f / sq, T3_LEUKOCYTE, T3_FIBROUS, 0.5f, 0.12f)
        GLES20.glDepthMask(true)
    }
    // Yan'an, spring 1938: hung well down the passage and up to starboard, clear of the tissue.
    drawPlate("yanan", frameAt(b + 11f / NODE_UNITS), radiusAt(b, 11f) * 0.62f, radiusAt(b, 11f) * 0.28f, 1.8f, seconds)
}

private fun StereoBodyRenderer.woundFloorUp(b: Float) = -tunnelRadius(b) * 0.84f * 0.78f

/** The wound's parts (see [drawWound]). */
private fun StereoBodyRenderer.woundMesh(b: Float, um: Float, part: Int): T3Mesh {
    val mb = Mb()
    val sarc = 2.5f / um
    val rnd = java.util.Random(61L + part)
    val floorUp = woundFloorUp(b)
    // Two fibres crossing overhead (the second torn across), two running with the passage low down.
    val fA = rfv(b, 1.0f); val fB = rfv(b, 6.8f)
    val rA = 52f / 2f / um; val rB = 46f / 2f / um
    val axA = fA.at(0f, 2.3f + rA); val axB = fB.at(0f, 2.1f + rB)
    val stumpL = 1.5f; val stumpR = -2.6f                                  // the torn fibre's ends (side)
    val rC = 46f / 2f / um
    val fC0 = wAt(b, -6f, -2.7f - rC, -1.9f); val fC1 = wAt(b, 13f, -2.7f - rC, -2.2f)
    val fD0 = wAt(b, -6f, 2.8f + rC, -2.4f); val fD1 = wAt(b, 13f, 2.8f + rC, -2.1f)
    when (part) {
        0, 1 -> {
            val ab = part == 1
            mb.striatedFibre(axA - fA.s * 11f, axA + fA.s * 11f, rA, sarc, ab)
            mb.striatedFibre(axB + fB.s * 11f, axB + fB.s * stumpL, rB, sarc, ab)
            mb.striatedFibre(axB - fB.s * 11f, axB + fB.s * stumpR, rB, sarc, ab)
            mb.striatedFibre(fC0, fC1, rC, sarc, ab)
            mb.striatedFibre(fD0, fD1, rC, sarc, ab)
        }
        2 -> {
            // peripheral nuclei, flattened under the sarcolemma on the faces toward the passage
            fun nuc(ax: V3, dir: V3, r: Float, t: Float, radial: V3) =
                mb.ellipsoidAxes(ax + dir * t + radial * (r * 0.99f), dir, 10f / 2f / um, radial, 1.2f / um, 2.6f / 2f / um, 5, 10)
            for (k in 0 until 6) { val ang = (k % 3 - 1) * 0.5f; nuc(axA, fA.s, rA, -7f + k * 2.7f, (-fA.u * cos(ang) + fA.d * sin(ang)).unit()) }
            for (k in 0 until 3) { val ang = (k % 2) * 0.6f - 0.3f; nuc(axB, fB.s, rB, 3.5f + k * 2.4f, (-fB.u * cos(ang) + fB.d * sin(ang)).unit()) }
            for (k in 0 until 3) { val ang = (k % 2) * 0.6f - 0.3f; nuc(axB, fB.s, rB, -4.5f - k * 2.4f, (-fB.u * cos(ang) + fB.d * sin(ang)).unit()) }
            val dC = (fC1 - fC0).unit(); val dD = (fD1 - fD0).unit()
            for (k in 0 until 6) {
                val f = rfv(b, -3f + k * 2.6f)
                val ang = (k % 2) * 0.5f
                nuc(fC0, dC, rC, 3f + k * 2.6f, (f.s * cos(ang) + f.u * sin(ang)).unit())
                nuc(fD0, dD, rC, 4.2f + k * 2.6f, (-f.s * cos(ang) + f.u * sin(ang)).unit())
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
                    mb.tube(pts, FloatArray(11) { 0.035f }, 4, false)
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
        6 -> for (k in 0 until 18) {
            // red cells trapped in the fibrin
            val f = rfv(b, -1.5f + rnd.nextFloat() * 11f)
            val c = f.at(-2.2f + rnd.nextFloat() * 4.4f, floorUp + 0.55f + rnd.nextFloat() * 0.35f)
            val ax = (f.u + f.s * (rnd.nextFloat() - 0.5f) * 1.6f + f.d * (rnd.nextFloat() - 0.5f) * 1.6f).unit()
            mb.redCell(c, ax, 7.5f / 2f / um, 4, 12)
        }
        7 -> for (cl in 0 until 3) {
            // platelet clumps adhering in the clot
            val f = rfv(b, 1f + cl * 3.3f)
            val c = f.at(-1.4f + cl * 1.3f, floorUp + 0.65f)
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
    val t = smooth01(sinceArrival(i, seconds) / 40f)
    val fibre = mixCol(T3_ISCHAEMIC, T3_PERFUSED, t, tmpCol0)
    val band = mixCol(T3_ISCHAEMIC_A, T3_PERFUSED_A, t, tmpCol1)
    val cap = mixCol(T3_CAP_EMPTY, T3_CAPILLARY, t, tmpCol2)
    drawMesh(cached("tf_fibres") { transfusionMesh(b, um, 0) }, fibre, fibre, 1f, 0f, 0.05f + 0.1f * t)
    drawMesh(cached("tf_bands") { transfusionMesh(b, um, 1) }, band, band, 1f, 0f, 0.05f)
    drawMesh(cached("tf_nuclei") { transfusionMesh(b, um, 2) }, T3_MUSCLE_NUCLEUS, T3_MUSCLE_NUCLEUS, 1f, 0f, 0.15f)
    drawMesh(cached("tf_caps") { transfusionMesh(b, um, 3) }, cap, cap, 1f, 0f, 0.1f + 0.25f * t)
    val (tube, junc, nuc) = endotheliumMeshes("tf_endo", b, -6f, 13f, { a -> radiusAt(b, a) * 0.97f }, 30f / um, 10)
    drawMesh(nuc, T3_ENDO_NUCLEUS, T3_ENDOTHELIUM, 0.75f, 0f, 0.05f)
    drawMesh(junc, T3_JUNCTION, T3_JUNCTION, 0.8f, 0f, 0.08f)
    GLES20.glDepthMask(false)
    drawMesh(tube, T3_ENDOTHELIUM, T3_JUNCTION, 0.22f, 0f, 0.15f)
    GLES20.glDepthMask(true)
}

private val tmpCol0 = FloatArray(4)
private val tmpCol1 = FloatArray(4)
private val tmpCol2 = FloatArray(4)
private fun mixCol(a: FloatArray, c: FloatArray, t: Float, out: FloatArray): FloatArray {
    for (k in 0 until 4) out[k] = a[k] + (c[k] - a[k]) * t
    return out
}

/** Muscle around the vessel: fibres (0), their A bands (1), nuclei (2), capillaries (3). */
private fun StereoBodyRenderer.transfusionMesh(b: Float, um: Float, part: Int): T3Mesh {
    val mb = Mb()
    val sarc = 2.5f / um
    val rf = 50f / 2f / um
    val angles = floatArrayOf(40f, 140f, 220f, 320f)
    for ((k, deg) in angles.withIndex()) {
        val th = deg * DEG
        fun axisAt(a: Float): V3 = rfv(b, a).pol(th, radiusAt(b, a) + 0.55f + rf)
        when (part) {
            0, 1 -> mb.striatedFibre(axisAt(-6f), axisAt(13f), rf, sarc, part == 1)
            2 -> for (q in 0 until 7) {
                val a = -4f + q * 2.6f + k
                val f = rfv(b, a)
                val inward = -f.radial(th)
                mb.ellipsoidAxes(axisAt(a) + inward * (rf * 0.99f), f.d, 5f / um, inward, 0.16f, 0.3f, 5, 10)
            }
            else -> {
                // capillaries in the clefts between fibres, running with them
                val thc = th + 50f * DEG
                val pts = (0..19).map { q -> val a = -6f + q; rfv(b, a).pol(thc + 0.08f * sin(q * 0.9f + k), radiusAt(b, a) + 0.6f) }
                mb.tube(pts, FloatArray(20) { 7f / 2f / um }, 8, true)
            }
        }
    }
    return mb.build()
}

/**
 * Stop 6 (THE TABLE, 12 mm Mote: 1 unit = 8 mm): a war wound handled properly — debrided and left
 * open, not one stitch in it, loosely packed with dry gauze, to be closed at four or five days if it
 * stays clean. The craft hangs in the gaping cut, ~3-5 cm across. Each cut face shows the layers in
 * section: the skin (a thin epidermis on a pale dermis), yellow lobulated subcutaneous fat, the white
 * deep fascia, and red muscle down into the wound bed; the debrided surfaces are clean, with pinpoint
 * bleeding. The gauze lies folded in the depths, its threads wicking blood where it touches tissue.
 */
internal fun StereoBodyRenderer.drawSuture(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    if (!openWall(i, -8f, 34f, T3_BLACK)) return
    drawMesh(cached("tb_muscle") { tableMesh(b, 0) }, T3_MUSCLE, T3_MUSCLE, 1f, 0f, 0.1f)
    drawMesh(cached("tb_mlines") { tableMesh(b, 1) }, T3_MUSCLE_LINE, T3_MUSCLE_LINE, 1f, 0f, 0.05f)
    drawMesh(cached("tb_fascia") { tableMesh(b, 2) }, T3_FASCIA, T3_FASCIA, 1f, 0f, 0.2f)
    drawMesh(cached("tb_fat") { tableMesh(b, 3) }, T3_FAT, T3_FAT, 1f, 0f, 0.18f)
    drawMesh(cached("tb_dermis") { tableMesh(b, 4) }, T3_DERMIS, T3_DERMIS, 1f, 0f, 0.15f)
    drawMesh(cached("tb_epi") { tableMesh(b, 5) }, T3_SKIN_EDGE, T3_SKIN_EDGE, 1f, 0f, 0.1f)
    drawMesh(cached("tb_skin") { tableMesh(b, 6) }, T3_SKIN_TOP, T3_SKIN_TOP, 1f, 0f, 0.12f)
    drawMesh(cached("tb_gauze") { tableMesh(b, 7) }, T3_GAUZE, T3_GAUZE, 1f, 0f, 0.3f)
    drawMesh(cached("tb_wet") { tableMesh(b, 8) }, T3_GAUZE_WET, T3_GAUZE_WET, 1f, 0f, 0.15f)
    // Pinpoint bleeding from the debrided faces: tissue that bleeds is tissue that lives.
    drawMesh(cached("tb_bleed") { tableMesh(b, 9) }, T3_BLEED, T3_BLEED, 1f, 0f, 0.35f + 0.15f * sin(seconds * 1.1f))
}

/** Half-width of the wound at height [up] (V-shaped: wider at the skin). */
private fun woundHalf(up: Float) = 2.05f + 0.28f * (up + 2.6f)
private const val SKIN_UP = 2.35f
private const val FAT_TOP = SKIN_UP - 0.28f
private const val FASCIA_UP = -0.35f
private const val WOUND_FLOOR = -2.7f

/** The wound's layers: muscle (0), fascicle lines (1), fascia (2), fat (3), dermis (4), epidermis (5), skin surface (6), gauze (7), wet gauze (8), bleeding points (9). */
private fun StereoBodyRenderer.tableMesh(b: Float, part: Int): T3Mesh {
    val mb = Mb()
    val rnd = java.util.Random(71L + part)
    val a0 = -6f; val a1 = 13f
    // A cut face as a surface between two heights, both sides; normals face into the wound.
    fun face(u0: Float, u1: Float, bulge: Float, stA: Int = 38, stU: Int = 4) {
        for (sg in floatArrayOf(-1f, 1f)) mb.gridFd(stA, stU, { u, v ->
            val a = a0 + (a1 - a0) * u; val up = u0 + (u1 - u0) * v
            wAt(b, a, sg * (woundHalf(up) + bulge * sin(v * PI.toFloat())), up)
        }) { u, _, _ -> rfv(b, a0 + (a1 - a0) * u).s * (-sg) }
    }
    when (part) {
        0 -> {
            face(WOUND_FLOOR, FASCIA_UP - 0.05f, 0f)
            // the wound bed: muscle, gently curved
            mb.gridFd(38, 8, { u, v ->
                val a = a0 + (a1 - a0) * u; val s = (v * 2f - 1f) * woundHalf(WOUND_FLOOR)
                wAt(b, a, s, WOUND_FLOOR - 0.25f * (1f - (v * 2f - 1f).pow(2)))
            }) { u, _, _ -> rfv(b, a0 + (a1 - a0) * u).u }
        }
        1 -> for (sg in floatArrayOf(-1f, 1f)) for (k in 0 until 7) {
            // the grain of the muscle: fascicles running along the face
            val up = WOUND_FLOOR + 0.15f + k * 0.33f
            val pts = (0..19).map { q -> val a = a0 + q; wAt(b, a, sg * (woundHalf(up) - 0.015f), up + 0.1f * sin(q * 0.8f + k)) }
            mb.tube(pts, FloatArray(20) { 0.018f }, 4, false)
        }
        2 -> face(FASCIA_UP - 0.08f, FASCIA_UP + 0.08f, -0.02f, 38, 1)
        3 -> for (sg in floatArrayOf(-1f, 1f)) {
            // subcutaneous fat: lobules a few mm across, bulging slightly from the cut face
            var a = a0
            while (a < a1) {
                var up = FASCIA_UP + 0.2f
                while (up < FAT_TOP - 0.1f) {
                    val f = rfv(b, a + rnd.nextFloat() * 0.3f)
                    val r = 0.26f + 0.08f * rnd.nextFloat()
                    val c = f.at(sg * (woundHalf(up) + r * 0.45f), up + r * 0.4f)
                    mb.ellipsoid(c, f.s * (r * 0.7f), f.d * r, f.u * (r * 0.9f), 5, 9)
                    up += r * 1.7f
                }
                a += 0.55f
            }
        }
        4 -> face(FAT_TOP - 0.02f, SKIN_UP - 0.015f, 0.01f, 38, 1)
        5 -> face(SKIN_UP - 0.02f, SKIN_UP + 0.012f, 0.015f, 38, 1)
        6 -> for (sg in floatArrayOf(-1f, 1f)) mb.gridFd(38, 4, { u, v ->
            // the skin surface either side of the cut
            val a = a0 + (a1 - a0) * u; wAt(b, a, sg * (woundHalf(SKIN_UP) + v * 3.5f), SKIN_UP + 0.012f - 0.15f * v * v)
        }) { u, _, _ -> rfv(b, a0 + (a1 - a0) * u).u }
        7, 8 -> {
            // dry gauze: an open-weave strip fluffed loosely into the depths of the wound; the threads
            // lying against the wound bed and faces have wicked up blood
            val wet = part == 8
            fun sheet(u: Float, v: Float): V3 {
                val a = a0 + 1f + (a1 - a0 - 2f) * u
                val w = (v * 2f - 1f)
                val fold = 0.35f * sin(u * 11f) * (1f - w * w)
                val s = w * (woundHalf(WOUND_FLOOR) - 0.1f) * 1.02f
                val up = WOUND_FLOOR + 0.22f + 0.55f * w * w + fold + 0.15f * sin(u * 23f + w * 3f)
                return wAt(b, a, s, up)
            }
            val nx = 60; val ny = 16
            for (q in 0..nx) {
                // threads across the strip: the outer ends (against tissue) wet, the middle dry
                val pts = (0..ny).map { k -> sheet(q / nx.toFloat(), k / ny.toFloat()) }
                if (wet) { mb.tube(pts.subList(0, 3), FloatArray(3) { 0.02f }, 4, false); mb.tube(pts.subList(ny - 2, ny + 1), FloatArray(3) { 0.02f }, 4, false) }
                else mb.tube(pts.subList(2, ny - 1), FloatArray(ny - 3) { 0.02f }, 4, false)
            }
            for (k in 0..ny) {
                // threads along the strip
                if ((k <= 2 || k >= ny - 2) != wet) continue
                val pts = (0..nx * 2).map { q -> sheet(q / (nx * 2f), k / ny.toFloat()) }
                mb.tube(pts, FloatArray(pts.size) { 0.02f }, 4, false)
            }
        }
        else -> for (k in 0 until 26) {
            val a = a0 + 1f + rnd.nextFloat() * (a1 - a0 - 2f)
            val sg = if (k % 2 == 0) -1f else 1f
            val up = WOUND_FLOOR + 0.3f + rnd.nextFloat() * (FAT_TOP - WOUND_FLOOR - 0.5f)
            val f = rfv(b, a)
            mb.ellipsoid(f.at(sg * (woundHalf(up) - 0.02f), up), f.s * 0.05f, f.d * 0.07f, f.u * 0.09f, 4, 7)
        }
    }
    return mb.build(twoSided = part in intArrayOf(0, 2, 4, 5, 6))
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
    drawMesh(cached("mw_obl") { marrowMesh(b, um, 1) }, T3_OSTEOBLAST, T3_OSTEOBLAST, 1f, 0f, 0.15f)
    drawMesh(cached("mw_mknuc") { marrowMesh(b, um, 2) }, T3_MEGA_NUCLEUS, T3_MEGA_NUCLEUS, 1f, 0f, 0.2f)
    drawMesh(cached("mw_mkgran") { marrowMesh(b, um, 3) }, T3_MEGA_GRANULE, T3_MEGA_GRANULE, 1f, 0f, 0.3f)
    drawMesh(cached("mw_arms") { marrowMesh(b, um, 4) }, T3_MEGA, T3_PLATELET, 1f, 0f, 0.3f)
    drawMesh(cached("mw_mac") { marrowMesh(b, um, 5) }, T3_MACROPHAGE, T3_MACROPHAGE, 1f, 0f, 0.15f)
    drawMesh(cached("mw_eb0") { marrowMesh(b, um, 6) }, T3_EB_EARLY, T3_EB_EARLY, 1f, 0f, 0.2f)
    drawMesh(cached("mw_eb1") { marrowMesh(b, um, 7) }, T3_EB_MID, T3_EB_MID, 1f, 0f, 0.2f)
    drawMesh(cached("mw_eb2") { marrowMesh(b, um, 8) }, T3_EB_LATE, T3_EB_LATE, 1f, 0f, 0.2f)
    drawMesh(cached("mw_ebnuc") { marrowMesh(b, um, 9) }, T3_EB_NUCLEUS, T3_EB_NUCLEUS, 1f, 0f, 0.25f)
    drawMesh(cached("mw_retic") { marrowMesh(b, um, 10) }, T3_RETICULOCYTE, T3_RETICULOCYTE, 1f, 0f, 0.2f)
    drawMesh(cached("mw_myelo") { marrowMesh(b, um, 11) }, T3_MYELOCYTE, T3_MYELOCYTE, 1f, 0f, 0.15f)
    drawMesh(cached("mw_mynuc") { marrowMesh(b, um, 12) }, T3_NUCLEUS, T3_NUCLEUS, 1f, 0f, 0.2f)
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
    val (tube, junc, nuc) = endotheliumMeshes("mw_endo", b, -6f, 13f, { a -> radiusAt(b, a) * 0.97f }, 28f / um, 12)
    drawMesh(nuc, T3_ENDO_NUCLEUS, T3_ENDOTHELIUM, 0.75f, 0f, 0.05f)
    drawMesh(junc, T3_JUNCTION, T3_JUNCTION, 0.8f, 0f, 0.08f)
    GLES20.glDepthMask(false)
    drawMesh(cached("mw_fat") { marrowMesh(b, um, 13) }, T3_ADIPOCYTE, T3_ADIPOCYTE, 0.32f, 0f, 0.2f)
    drawMesh(cached("mw_mk") { marrowMesh(b, um, 14) }, T3_MEGA, T3_MEGA, 0.38f, 0f, 0.15f)
    drawMesh(tube, T3_ENDOTHELIUM, T3_JUNCTION, 0.16f, 0f, 0.1f)
    GLES20.glDepthMask(true)
}

private fun StereoBodyRenderer.marrowBoneR(b: Float) = tunnelRadius(b) + 3.6f

/** Megakaryocytes: (along, angle°, diameter µm, lobes). */
private val MK_SPOTS = listOf(floatArrayOf(5f, 165f, 64f, 8f), floatArrayOf(11f, 318f, 54f, 5f))

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
    val ebC = rfv(b, 8f).pol(18f * DEG, radiusAt(b, 8f) + 2.2f)
    val ebR = 9f / 2f / um
    when (part) {
        0 -> mb.gridFd(30, 10, { u, v ->
            // a trabecula of bone below the sinus, a thick cream plate
            val a = -6f + 19f * u; val th = (228f + 84f * v) * DEG
            rfv(b, a).pol(th, boneR + 0.25f * sin(u * 9f) + 0.3f * sin(v * 7f))
        }) { u, _, p -> rfv(b, -6f + 19f * u).c - p }
        1 -> {
            // osteoblasts lining the bone surface facing the marrow
            var a = -5f
            while (a < 12.5f) {
                var th = 236f
                while (th < 306f) {
                    val f = rfv(b, a)
                    val rad = f.radial(th * DEG)
                    mb.ellipsoid(f.pol(th * DEG, boneR - 0.45f), rad * 0.5f, f.d * 0.75f, f.d.cross(rad).unit() * 0.7f, 5, 8)
                    th += 9f
                }
                a += 1.55f
            }
        }
        2 -> for ((k, s) in MK_SPOTS.withIndex()) {
            // one polyploid nucleus, folded into many lobes
            val c = mkCentre(b, um, k); val r = s[2] / 2f / um
            for (q in 0 until s[3].toInt()) {
                val d = V3(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).unit()
                val lr = r * (0.24f + 0.08f * rnd.nextFloat())
                mb.ellipsoid(c + d * (r * 0.28f), d * lr, perp(d) * (lr * 1.2f), d.cross(perp(d)) * (lr * 0.9f), 6, 10)
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
            val f = rfv(b, 8f)
            val rad = f.radial(18f * DEG)
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
            if (part == 11) mb.sphere(c, r, 7, 12)
            else mb.torus(c - f.radial(th) * (r * 0.3f), f.radial(th), r * 0.45f, r * 0.2f, 12, 6, 0.62f, 0.2f * k)
        }
        13 -> {
            // adipocytes: big fat cells with a thin rim
            mb.sphere(rfv(b, -1.5f).pol(200f * DEG, radiusAt(b, -1.5f) + 5.3f), 80f / 2f / um, 12, 18)
            mb.sphere(rfv(b, 11.5f).pol(75f * DEG, radiusAt(b, 11.5f) + 5.6f), 90f / 2f / um, 12, 18)
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
    drawMesh(cached("ct_basal") { cutMesh(b, um, 4) }, T3_BASAL, T3_BASAL, 1f, 0f, 0.12f)
    drawMesh(cached("ct_spin") { cutMesh(b, um, 5) }, T3_SPINOUS, T3_SPINOUS, 1f, 0f, 0.12f)
    drawMesh(cached("ct_nuc") { cutMesh(b, um, 6) }, T3_NUCLEUS, T3_NUCLEUS, 1f, 0f, 0.2f)
    drawMesh(cached("ct_gran") { cutMesh(b, um, 7) }, T3_GRANULAR, T3_GRANULAR, 1f, 0f, 0.12f)
    drawMesh(cached("ct_khg") { cutMesh(b, um, 8) }, T3_KERATOHYALIN, T3_KERATOHYALIN, 1f, 0f, 0.1f)
    drawMesh(cached("ct_corn") { cutMesh(b, um, 9) }, T3_CORNEUM, T3_CORNEUM, 1f, 0f, 0.18f)
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
        val up = CUT_SURFACE + 0.4f - t * (CUT_SURFACE - CUT_CORNEUM_BASE + 3.2f)
        val depthIn = smooth01((CUT_CORNEUM_BASE - up) / 1.6f) * 0.35f
        val f = rfv(b, a + 0.4f * sin(t * 3f + k))
        val p = f.at(sg * (cutHalf(up) - 0.25f + depthIn), up)
        val tumble = seconds * 0.15f + k
        drawLocal(mesh, p, f.d * cos(tumble) + f.u * sin(tumble), f.s, T3_GRAM_POS, T3_GRAM_POS, 1f - smooth01((t - 0.9f) / 0.1f), 0.45f)
    }
    GLES20.glDepthMask(false)
    drawScaled(sphere, np, nf.d, nf.u, nr * 1.05f, nr * 0.85f, nr * 1.15f, T3_LEUKOCYTE, T3_FIBROUS, 0.5f, 0.12f)
    GLES20.glDepthMask(true)
}

private const val CUT_SURFACE = 4.4f
private const val CUT_CORNEUM_BASE = 2.9f
private const val CUT_GRANULAR_BASE = 2.3f
private const val CUT_FLOOR = -6.4f
/** Half-width of the cleft at height [up]: a clean wedge, wider at the surface. */
private fun cutHalf(up: Float) = 1.2f + 1.2f * (up - CUT_FLOOR) / (CUT_SURFACE - CUT_FLOOR)
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
            while (up < CUT_GRANULAR_BASE - 0.5f) {
                var a = a0 + (if (row % 2 == 0) 0f else kerR)
                while (a < a1) {
                    val f = rfv(b, a)
                    val u2 = up + 0.15f * rnd.nextFloat()
                    if (u2 > cutJunction(a) + 1.2f) {
                        val c = f.at(sg * (cutHalf(u2) + kerR * 0.3f), u2)
                        if (part == 5) mb.ellipsoid(c, f.s * (kerR * 0.55f), f.d * (kerR * 0.95f), f.u * (kerR * 0.92f), 5, 7)
                        else mb.ellipsoid(f.at(sg * (cutHalf(u2) - 0.05f), u2), f.s * 0.16f, f.d * 0.27f, f.u * 0.24f, 5, 8)
                    }
                    a += kerR * 2f
                }
                up += kerR * 1.75f; row++
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
                val c = f.at(sg * (cutHalf(up) + 0.08f), up)
                if (part == 7) mb.ellipsoid(c, f.s * 0.28f, f.d * 1.05f, f.u * 0.32f, 5, 8)
                else for (q in 0 until 6) mb.sphere(c - f.s * (sg * 0.24f) + f.d * ((rnd.nextFloat() - 0.5f) * 1.6f) + f.u * ((rnd.nextFloat() - 0.5f) * 0.4f), 0.07f, 3, 5)
                a += 2.0f
            }
        }
        9 -> for (sg in floatArrayOf(-1f, 1f)) {
            // the stratum corneum: stacked, flattened dead plates, loosening at the top
            var layer = 0
            var up = CUT_CORNEUM_BASE + 0.08f
            while (up < CUT_SURFACE) {
                var a = a0 + (layer % 3) * 1.1f
                while (a < a1) {
                    val f = rfv(b, a)
                    val loose = ((up - CUT_CORNEUM_BASE) / (CUT_SURFACE - CUT_CORNEUM_BASE)).pow(2) * 0.25f
                    mb.ellipsoid(f.at(sg * (cutHalf(up) + 0.05f), up + loose * sin(a * 3f)), f.u * 0.075f, f.d * 1.6f, f.s * 0.35f, 3, 8)
                    a += 3.3f
                }
                up += 0.19f; layer++
            }
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
    return mb.build(twoSided = part == 0)
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
    val dil = 1f + 0.12f * smooth01(t / 30f)                          // vasodilatation
    drawMesh(cached("sp_coll") { sepsisMesh(b, um, 0) }, T3_GRANULOMA, T3_GRANULOMA, 1f, 0f, 0f)
    drawMesh(cached("sp_leaked") { sepsisMesh(b, um, 1) }, T3_RBC_DEOXY, T3_RBC, 1f, 0f, 0.2f)
    val holes = SEPSIS_GAPS
    val (tube, junc, nuc) = endotheliumMeshes("sp_endo", b, -6f, 13f, { a -> radiusAt(b, a) * 0.9f }, 30f / um, 10, holes)
    drawMeshRadial(nuc, b, dil, T3_ENDO_NUCLEUS, T3_ENDOTHELIUM, 0.75f, 0f, 0.05f)
    drawMeshRadial(junc, b, dil, T3_JUNCTION, T3_JUNCTION, 0.8f, 0f, 0.08f)
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
    // The bacteria: the number of chains doubles every 16 s, and every chain keeps lengthening.
    val cap = if (quality == 0) 22 else 12
    val live = min(cap.toFloat(), 4f * 2f.pow(t / 16f)).toInt()
    val rc = 1.0f / 2f / um
    for (k in 0 until live) {
        val born = if (k < 4) 0f else 16f * log2((k + 1) / 4f)
        val age = t - born
        val len = min(14, 3 + (age / 3.5f).toInt())
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
    // Plasma leaking out through the gaps into the tissue: straw plumes growing as the leak goes on.
    for ((k, h) in holes.withIndex()) {
        val (ha, hth) = h
        val grow = smooth01((t - k * 2f) / 22f)
        if (grow <= 0.01f) continue
        val f = rfv(b, ha); val rad = f.radial(hth)
        val c = f.pol(hth, radiusAt(b, ha) * 0.9f * dil + 0.6f * grow)
        drawScaled(sphere, c, rad, f.d, 0.9f * grow + 0.2f, 0.7f * grow + 0.15f, 0.8f * grow + 0.2f, T3_OEDEMA, T3_OEDEMA, 0.28f, 0.35f)
    }
    drawMeshRadial(tube, b, dil, T3_ENDOTHELIUM, T3_JUNCTION, 0.2f, 0f, 0.12f)
    GLES20.glDepthMask(true)
}

/** Gaps opened between endothelial cells: (along, angle). */
private val SEPSIS_GAPS = listOf(0.8f to 30f * DEG, 3.2f to 160f * DEG, 5.5f to 300f * DEG, 7.8f to 95f * DEG, 10.2f to 220f * DEG)

/** Outside the leaking venule: collagen of the tissue (0) and red cells already leaked into it (1). */
private fun StereoBodyRenderer.sepsisMesh(b: Float, um: Float, part: Int): T3Mesh {
    val mb = Mb()
    val rnd = java.util.Random(101L + part)
    if (part == 0) for (k in 0 until 14) {
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
