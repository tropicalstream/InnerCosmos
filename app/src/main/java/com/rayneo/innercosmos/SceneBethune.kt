package com.rayneo.innercosmos

import android.opengl.GLES20
import android.opengl.Matrix
import kotlin.math.*

// Chapter III — Bethune: a life told through the tissues it touched.
// Landmark scenes, drawn by StereoBodyRenderer.drawLandmarks via the stop's Scene.

/** What drifts past at each stop of this tour, by stop index; stops not listed use DriftSpec.forAmb. */
internal val BETHUNE_DRIFT: Map<Int, DriftSpec> = mapOf()

// ------------------------------------------- draw: chapter III landmarks
/**
 * Stop 1: a tuberculous lung. Air sacs on every side, and eaten out of the wall a cavity —
 * the hole Bethune had in his own lung in 1926, ringed by the fibrous scar that walls it off,
 * with caseous nodules scattered around the rim.
 */
internal fun StereoBodyRenderer.drawCavity(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    val f = frameAt(b); val rr = tunnelRadius(b)
    // Healthy alveoli around the passage, breathing.
    GLES20.glDepthMask(false)
    for (k in 0 until (if (quality == 0) 7 else 4)) {
        val a = 2f * PI.toFloat() * k / 7f + 0.4f
        val breathe = 1f + 0.07f * sin(seconds * 1.2f + k)
        val r = (0.9f + 0.3f * (k % 3)) * breathe
        blobAt(f, (k - 3) * 0.8f, cos(a) * rr * 0.95f, sin(a) * rr * 0.95f, r, r, r,
            COL_ALVEOLUS, COL_RED_CELL, 0.38f, 0f, 0f, 1f, 0f, sphere, 0f, 0.22f)
    }
    GLES20.glDepthMask(true)
    // The cavity itself: a dark bowl set into the wall, ringed with fibrous scar.
    val fc = frameAt(b + 0.3f); val rc = tunnelRadius(b + 0.3f)
    val cs = -rc * 0.92f; val cu = rc * 0.18f
    val cx = fx(fc, 0f, cs, cu); val cy = fy(fc, 0f, cs, cu); val cz = fz(fc, 0f, cs, cu)
    drawSphereAt(cx, cy, cz, 1.85f, 1.85f, 1.85f, COL_CAVITY, COL_CAVITY, 1f, 0f, 0f, 1f, 0f, sphere, 0f, 0f)
    drawLinesAt(scarMesh, cx, cy, cz, 1f, yawOf(fc), 0f, 1f, 0f)
    for (k in 0 until 9) {   // caseous nodules on the rim: the cheesy debris that names the lesion
        val a = 2f * PI.toFloat() * k / 9f + 0.3f
        val d = 1.9f + 0.18f * sin(k * 2.3f)
        drawSphereAt(cx + fc.dx * cos(a) * d + fc.ux * sin(a) * d,
            cy + fc.dy * cos(a) * d + fc.uy * sin(a) * d,
            cz + fc.dz * cos(a) * d + fc.uz * sin(a) * d,
            0.3f, 0.26f, 0.3f, COL_CASEUM, COL_SKIN, 1f, k * 40f, 0f, 1f, 0f, blob, 1f)
    }
}

/**
 * Stop 2: a donor's vein from the inside. A cannula comes through the wall on a bevel and red
 * cells stream into it — Madrid, 1936, where Bethune's people took the blood that his trucks
 * carried to the front. A pair of venous valve leaflets sits downstream.
 */
internal fun StereoBodyRenderer.drawDonor(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    val f = frameAt(b); val rr = tunnelRadius(b)
    val ts = rr * 0.98f; val tu = -rr * 0.25f
    // The needle: shaft through the wall, bevelled tip in the lumen, bright rim.
    strutAt(f, -2.6f, ts + 1.5f, tu + 0.9f, 0.2f, ts * 0.42f, tu * 0.42f, 0.17f, COL_STEEL, COL_LAMP, 0.15f)
    blobAt(f, 0.2f, ts * 0.42f, tu * 0.42f, 0.19f, 0.19f, 0.34f, COL_STEEL_BRIGHT, COL_LAMP, 1f,
        yawOf(f), 0f, 1f, 0f, blob, 0f, 0.35f)
    // Cells drawn out of the flow and into the bore.
    for (k in 0 until (if (quality == 0) 12 else 6)) {
        val t = ((seconds * 0.5f + k * 0.083f) % 1f)
        val a = k * 0.9f
        val start = 2.4f + 1.2f * sin(a)
        val alongV = start - t * (start - 0.2f)
        val sp = ts * 0.42f + (1f - t) * (1.1f * cos(a))
        val up = tu * 0.42f + (1f - t) * (1.1f * sin(a))
        val sz = 0.2f * (1f - 0.45f * t)
        blobAt(f, alongV, sp, up, sz, sz * 0.34f, sz, COL_RED_CELL, COL_RED_CELL_DARK, 1f,
            seconds * 60f + k * 30f, 0.4f, 1f, 0.2f, blob, 1f)
    }
    // A venous valve downstream: two cusps that keep the blood going one way.
    val fv = frameAt(b + 0.62f); val rv = tunnelRadius(b + 0.62f)
    for (side in 0 until 2) {
        val sgn = if (side == 0) 1f else -1f
        val open = 0.55f + 0.25f * sin(seconds * 0.8f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, fx(fv, 0f, sgn * rv * open, 0f), fy(fv, 0f, sgn * rv * open, 0f), fz(fv, 0f, sgn * rv * open, 0f))
        applyFrameRotation(fv)
        Matrix.rotateM(model, 0, sgn * 24f, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, rv * 0.5f, rv * 0.85f, 0.07f)
        drawLitModel(sphere, COL_VALVE, COL_VALVE_EDGE, 0.8f, 1f, 0f)
    }
}

/**
 * Stop 3: blood in store. Bethune's service is remembered for the refrigerated truck: citrated
 * blood, settled, cold, waiting. The cells lie packed in a layer with clear plasma above, the
 * glass of the bottle curving away, and nothing moving fast.
 */
internal fun StereoBodyRenderer.drawStored(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    val f = frameAt(b); val rr = tunnelRadius(b)
    // The glass: a ring of faint pillars around the passage, and the cold light between them.
    for (k in 0 until 12) {
        val a = 2f * PI.toFloat() * k / 12f
        strutAt(f, -3.5f, cos(a) * rr * 1.02f, sin(a) * rr * 1.02f, 3.5f, cos(a) * rr * 1.02f, sin(a) * rr * 1.02f,
            0.05f, COL_GLASS, COL_COLD, 0.25f)
    }
    // Packed cells: a settled layer along the floor, barely stirring.
    val settle = -rr * 0.55f
    for (k in 0 until (if (quality == 0) 26 else 12)) {
        val a = k * 2.399f
        val d = 0.35f + 0.65f * sqrt((k + 1f) / 26f)
        val drift = 0.06f * sin(seconds * 0.25f + k)
        blobAt(f, (k % 7 - 3) * 0.85f + drift, cos(a) * rr * d, settle + 0.22f * sin(a * 2.1f),
            0.24f, 0.09f, 0.24f, COL_STORED_CELL, COL_RED_CELL_DARK, 1f, a * 57f, 0.2f, 1f, 0.1f, blob, 1f)
    }
    // Plasma above: a few slow motes and the straw-coloured light.
    for (k in 0 until 8) {
        val a = k * 1.7f
        blobAt(f, (k - 4) * 0.9f, cos(a) * rr * 0.5f, rr * 0.45f + 0.3f * sin(seconds * 0.2f + k),
            0.07f, 0.07f, 0.07f, COL_PLASMA, COL_LAMP, 0.7f, 0f, 0f, 1f, 0f, blob, 0f, 0.25f)
    }
}

/**
 * Stops 4 and 8: a wound from the inside. Torn muscle fibres with ragged ends, fibrin strands
 * bridging the gap, dirt and debris driven in with it. This is what Bethune cut away — dead
 * tissue is where the bacteria breed — and, later, the nick in his own finger.
 */
internal fun StereoBodyRenderer.drawWound(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    val f = frameAt(b); val rr = tunnelRadius(b)
    // The breach: a ragged hole torn through one wall, with the dark of the tissue behind it.
    val ws = -rr * 0.95f; val wu = rr * 0.12f
    val wx = fx(f, 0f, ws, wu); val wy = fy(f, 0f, ws, wu); val wz = fz(f, 0f, ws, wu)
    drawSphereAt(wx, wy, wz, 1.6f, 1.6f, 1.6f, COL_CLOT, COL_CLOT, 1f, 0f, 0f, 1f, 0f, sphere, 0f, 0f)
    // Torn muscle fibres standing out of the rim at every angle, their ends frayed.
    val fibres = if (quality == 0) 11 else 6
    for (k in 0 until fibres) {
        val a = 2f * PI.toFloat() * k / fibres + 0.35f
        val ca = cos(a); val sa = sin(a)
        val r0 = 1.45f; val len = 0.75f + 0.5f * sin(k * 2.1f)
        val lean = 0.35f * sin(k * 1.7f)
        drawStrut(wx + f.dx * ca * r0 + f.ux * sa * r0, wy + f.dy * ca * r0 + f.uy * sa * r0, wz + f.dz * ca * r0 + f.uz * sa * r0,
            wx + f.dx * ca * (r0 + len) + f.ux * sa * (r0 + len) - f.sx * lean,
            wy + f.dy * ca * (r0 + len) + f.uy * sa * (r0 + len) - f.sy * lean,
            wz + f.dz * ca * (r0 + len) + f.uz * sa * (r0 + len) - f.sz * lean,
            0.17f, COL_FIBRE, COL_FIBRE_DARK)
        for (m in 0 until 2) {   // the frayed end: a couple of loose threads
            val fr = r0 + len + 0.16f + m * 0.14f
            drawSphereAt(wx + f.dx * ca * fr + f.ux * sa * fr - f.sx * lean * 1.3f,
                wy + f.dy * ca * fr + f.uy * sa * fr - f.sy * lean * 1.3f,
                wz + f.dz * ca * fr + f.uz * sa * fr - f.sz * lean * 1.3f,
                0.07f, 0.07f, 0.07f, COL_FIBRE_DARK, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob)
        }
    }
    // Fibrin: the veil the blood throws across the gap within minutes of the wound.
    drawLinesAt(fibrinMesh, wx, wy, wz, 0.55f, seconds * 0.8f, ca(f), 0f, sa(f))
    // Dirt driven in with the wound — the reason a wound has to be cut clean.
    for (k in 0 until (if (quality == 0) 6 else 3)) {
        val a = k * 1.9f
        val d = 0.5f + 0.55f * ((k * 31 % 10) / 10f)
        blobAt(f, 0.35f * sin(a * 1.3f), ws + cos(a) * d + 0.5f, wu + sin(a) * d,
            0.15f, 0.12f, 0.17f, COL_DEBRIS, COL_FIBRE_DARK, 1f, k * 51f, 0.3f, 1f, 0.5f, blob, 1f)
    }
    // Yan'an, spring 1938: the night he sat down with Mao Zedong, hung where the crew pass it.
    drawPlate("yanan", frameAt(b + 0.45f), tunnelRadius(b + 0.45f) * 0.62f, tunnelRadius(b + 0.45f) * 0.30f, 2.6f, seconds)
    // Blood seeping out of the breach and turning down the passage.
    for (k in 0 until (if (quality == 0) 9 else 4)) {
        val t = ((seconds * 0.28f + k * 0.111f) % 1f)
        val a = k * 1.4f
        val out = 0.6f + t * 2.6f
        blobAt(f, t * 3.4f - 0.6f, ws + out + 0.25f * cos(a), wu + 0.45f * sin(a) * (1f - t),
            0.19f, 0.07f, 0.19f, COL_RED_CELL, COL_RED_CELL_DARK, 1f - 0.35f * t,
            seconds * 40f + k * 44f, 0.4f, 1f, 0.2f, blob, 1f)
    }
}

/**
 * Stop 6: the table. Two banks of tissue drawn together and held: forceps at the edges,
 * sutures arcing across the gap, the wound closing over a slow cycle and opening again for the
 * next casualty. Bethune's rule was that no wounded man should wait more than eight hours.
 */
internal fun StereoBodyRenderer.drawSuture(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    val f = frameAt(b); val rr = tunnelRadius(b)
    val cyc = ((seconds / 26f) % 1f)
    val close = smooth01((cyc - 0.15f) / 0.55f)          // the edges come together, then reset
    val gap = rr * (0.86f - 0.34f * close)     // the edges never close across the Mote's lane
    for (side in 0 until 2) {
        val sgn = if (side == 0) 1f else -1f
        for (k in 0 until (if (quality == 0) 6 else 3)) {
            val along = (k - 2.5f) * 0.95f
            blobAt(f, along, sgn * (gap + 0.45f), -rr * 0.2f, 0.45f, 0.34f, 0.5f,
                COL_TISSUE, COL_TISSUE_EDGE, 1f, k * 33f, 0f, 1f, 0f, blob, 1f)
        }
    }
    // Sutures: arcs of thread crossing the gap, tightening as the edges meet.
    for (k in 0 until 5) {
        val along = (k - 2f) * 1.15f
        val lift = 0.5f * (1f - close) + 0.12f
        strutAt(f, along, -gap - 0.15f, -rr * 0.2f, along, -gap * 0.45f, -rr * 0.2f - lift, 0.05f, COL_THREAD_S, COL_LAMP, 0.25f)
        strutAt(f, along, -gap * 0.45f, -rr * 0.2f - lift, along, gap * 0.45f, -rr * 0.2f - lift, 0.05f, COL_THREAD_S, COL_LAMP, 0.25f)
        strutAt(f, along, gap * 0.45f, -rr * 0.2f - lift, along, gap + 0.15f, -rr * 0.2f, 0.05f, COL_THREAD_S, COL_LAMP, 0.25f)
        blobAt(f, along, 0f, -rr * 0.2f - lift - 0.05f, 0.09f, 0.09f, 0.09f, COL_THREAD_S, COL_LAMP, 1f, 0f, 0f, 1f, 0f, blob, 0f, 0.35f)
    }
    // Forceps: two bright jaws holding the near edge steady.
    for (sgn in SIGNS) {
        strutAt(f, 3.4f, sgn * (gap + 0.7f), rr * 0.45f, 0.9f, sgn * (gap + 0.2f), -rr * 0.05f, 0.09f, COL_STEEL, COL_STEEL_BRIGHT, 0.2f)
    }
}

/**
 * Stop 9: septicaemia. Rods multiplying in the bloodstream faster than the neutrophils can
 * clear them — what killed Bethune on 12 November 1939, in the last year before penicillin
 * became a usable medicine. The count doubles on a cycle, and the white cells lose.
 */
internal fun StereoBodyRenderer.drawSepsis(n: TourNode, i: Int, seconds: Float) {
    val b = i.toFloat()
    val f = frameAt(b); val rr = tunnelRadius(b)
    val cyc = ((seconds / 18f) % 1f)                     // one doubling every eighteen seconds
    val cap = if (quality == 0) 22 else 10
    val live = 4 + (cap - 4) * cyc
    for (k in 0 until cap) {
        if (k > live) continue
        val split = (live - k).coerceIn(0f, 1f)          // the newest rods are still pulling apart
        val a = k * 2.399f + seconds * 0.06f
        val d = rr * (0.34f + 0.60f * ((k * 37 % 100) / 100f))
        val along = ((k * 53 % 100) / 100f - 0.5f) * 7f + 0.5f * sin(seconds * 0.4f + k)
        val sp = cos(a) * d; val up = sin(a) * d
        // Broadside to the lane (the rod's long axis across the passage, not down it) so a rod
        // reads as a rod and not as a disc seen end-on.
        val yaw = yawOf(f) + 90f + (k * 37 % 60) - 30f
        blobAt(f, along, sp - 0.17f * split, up, 0.10f, 0.10f, 0.30f, COL_MICROBE, COL_MICROBE_DARK, 1f, yaw, 0f, 1f, 0f, blob, 1f)
        if (split > 0.05f) blobAt(f, along + 0.05f, sp + 0.17f * split, up, 0.10f, 0.10f, 0.30f,
            COL_MICROBE, COL_MICROBE_DARK, split, yaw, 0f, 1f, 0f, blob, 1f)
    }
    // Two neutrophils still working, and losing: pseudopods out, rods slipping past them.
    for (w in 0 until 2) {
        val ph = seconds * 0.2f + w * 3.1f
        val a = ph * 0.7f + w * 2f
        val d = rr * 0.55f
        val along = 2.2f - w * 3.4f + 0.6f * sin(ph)
        val sp = cos(a) * d; val up = sin(a) * d
        val pulse = 1f + 0.09f * sin(seconds * 2.4f + w)
        blobAt(f, along, sp, up, 0.40f * pulse, 0.36f * pulse, 0.40f * pulse,
            COL_WHITE_CELL, COL_WHITE_CELL_DARK, 0.92f, seconds * 12f, 0.2f, 1f, 0.3f, sphere, 1f)
        for (m in 0 until 4) {
            val pa = m * 1.57f + ph
            blobAt(f, along + 0.28f * cos(pa), sp + 0.28f * sin(pa), up + 0.14f * sin(pa * 1.7f),
                0.11f, 0.11f, 0.17f, COL_WHITE_CELL, COL_WHITE_CELL_DARK, 0.85f, pa * 57f, 0f, 1f, 0f, blob)
        }
    }
    // The fever: the wall itself running hot on the heartbeat.
    val glow = 0.35f + 0.3f * exp(-heartPhase * 5f)
    blobAt(f, 0f, 0f, 0f, rr * 1.04f, rr * 1.04f, 4.5f, COL_FEVER, COL_FEVER, 0.10f + 0.05f * glow,
        yawOf(f), 0f, 1f, 0f, sphere, 0f, glow)
}

/** Chapter III stop 5: the transfusion (red cells come from the BodyField). */
internal fun StereoBodyRenderer.drawTransfusion(n: TourNode, i: Int, seconds: Float) {
}

/** Chapter III stop 7: the students — bone marrow making new cells (placeholder: Tour II's marrow). */
internal fun StereoBodyRenderer.drawStudents(n: TourNode, i: Int, seconds: Float) {
    drawMarrow(n, i, seconds)
}

/** Chapter III stop 8: the cut in his finger. */
internal fun StereoBodyRenderer.drawCut(n: TourNode, i: Int, seconds: Float) {
}
