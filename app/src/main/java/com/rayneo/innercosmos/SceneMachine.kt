package com.rayneo.innercosmos

import android.opengl.GLES20
import android.opengl.Matrix
import kotlin.math.*

// Tour II — The Living Machine: mouth to mitosis.
// Landmark scenes, drawn by StereoBodyRenderer.drawLandmarks via the stop's Scene.

/** What drifts past at each stop of this tour, by stop index; stops not listed use DriftSpec.forAmb. */
internal val MACHINE_DRIFT: Map<Int, DriftSpec> = mapOf()

/** Tour II stop 1: the lips as a wide oval of flesh, an upper and a lower arch of teeth with a
 *  dark gape between them, the tongue below, the uvula above, daylight behind. */
internal fun StereoBodyRenderer.drawMouth(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawGut(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawPhage(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawLiver(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawKidney(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawMuscle(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawMarrow(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawVdj(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawHighway(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawFactory(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawMotor(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawDivision(n: TourNode, i: Int, seconds: Float) {
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
