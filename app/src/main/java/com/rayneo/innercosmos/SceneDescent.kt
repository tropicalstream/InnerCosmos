package com.rayneo.innercosmos

import android.opengl.GLES20
import android.opengl.Matrix
import kotlin.math.*

// Tour I — The Descent: nose to carbon atom.
// Landmark scenes, drawn by StereoBodyRenderer.drawLandmarks via the stop's Scene.

/** Node 0: the nostril as a cave mouth (a ring of flesh) with a forest of nasal hairs behind a warm bay glow. */
internal fun StereoBodyRenderer.drawThreshold(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawAirway(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawAlveolus(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawHeart(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawSentinel(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawNeuron(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawMembrane(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawMitochondrion(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawNucleus(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawRibosome(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawAtom(n: TourNode, i: Int, seconds: Float) {
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
internal fun StereoBodyRenderer.drawLookBack(n: TourNode, i: Int, seconds: Float) {
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
    // Chapter III closes on the man himself: the scroll portrait, out among the cells.
    if (map.id == 3) drawPlate("portrait", frameAt(i + 0.12f), tunnelRadius(i + 0.12f) * 0.34f, tunnelRadius(i + 0.12f) * 0.10f, 3.6f, seconds)
    // The warm world ahead appears only once the re-expansion is under way (not from inside the atom).
    if (routeProgress > i - 0.4f) drawSphereAt(n.x, n.y + 0.4f, n.z - 9f, 3.2f, 3.2f, 3.2f, COL_WORLD, COL_LAMP, 1f, seconds * 4f, 0f, 1f, 0f, sphere, 1f, 0.35f)
}
