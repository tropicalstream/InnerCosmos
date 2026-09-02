package com.rayneo.innercosmos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * BODY MAP — a small inset (top-right) showing where in the human the Mote is: a human
 * silhouette with a glowing marker that travels between the organs of the current tour (see
 * TourNode.mapX/mapY), and concentric "zoom" rings once the ride is inside a cell. Drawn in
 * saturated colours that read on the X3 Pro waveguides (dark = transparent there).
 */
class BodyMapView(context: Context) : View(context) {
    @Volatile private var progress = 0f
    @Volatile private var map: TourMap = Tours.DESCENT
    private val t0 = SystemClock.uptimeMillis()

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.2f; color = Color.rgb(255, 196, 107)
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(46, 255, 196, 107) }
    private val organ = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f; color = Color.argb(150, 255, 196, 107) }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(255, 61, 110) }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f; color = Color.rgb(64, 224, 208) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER; color = Color.rgb(255, 196, 107)
        setShadowLayer(4f, 0f, 0f, Color.rgb(255, 61, 110))
    }
    private val body = Path()
    private val organs = Path()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** Rail progress in node units, from the director. */
    fun setProgress(p: Float) {
        val q = p.coerceIn(0f, map.nodes.lastIndex.toFloat())
        if (kotlin.math.abs(q - progress) > 0.002f) { progress = q; postInvalidate() }
    }

    /** The tour whose stops the marker follows. */
    fun setTour(m: TourMap) { map = m; postInvalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // Figure drawn in a 100 x 150 box, scaled to fit above the label.
        val s = min(w / 100f, (h - 26f) / 150f)
        val ox = (w - 100f * s) / 2f; val oy = 4f
        fun X(x: Float) = ox + x * s
        fun Y(y: Float) = oy + y * s
        val t = (SystemClock.uptimeMillis() - t0) / 1000f
        val m = map
        val nodes = m.nodes

        // Silhouette: head, neck, torso, arms, legs.
        body.reset()
        body.addCircle(X(50f), Y(14f), 10.5f * s, Path.Direction.CW)
        body.moveTo(X(45f), Y(24f)); body.lineTo(X(55f), Y(24f)); body.lineTo(X(56f), Y(31f)); body.lineTo(X(44f), Y(31f)); body.close()
        body.moveTo(X(30f), Y(33f)); body.quadTo(X(50f), Y(28f), X(70f), Y(33f))
        body.lineTo(X(66f), Y(84f)); body.quadTo(X(50f), Y(89f), X(34f), Y(84f)); body.close()
        body.moveTo(X(30f), Y(34f)); body.lineTo(X(17f), Y(74f)); body.lineTo(X(24f), Y(76f)); body.lineTo(X(35f), Y(46f)); body.close()
        body.moveTo(X(70f), Y(34f)); body.lineTo(X(83f), Y(74f)); body.lineTo(X(76f), Y(76f)); body.lineTo(X(65f), Y(46f)); body.close()
        body.moveTo(X(36f), Y(86f)); body.lineTo(X(33f), Y(146f)); body.lineTo(X(44f), Y(146f)); body.lineTo(X(48f), Y(94f))
        body.lineTo(X(52f), Y(94f)); body.lineTo(X(56f), Y(146f)); body.lineTo(X(67f), Y(146f)); body.lineTo(X(64f), Y(86f)); body.close()
        canvas.drawPath(body, fill)
        canvas.drawPath(body, outline)

        // Organs as faint guides. Tour I: lungs, heart, brain. Tour II adds the liver, the gut,
        // the kidneys and the femur (marrow).
        organs.reset()
        organs.addOval(X(36f), Y(40f), X(48f), Y(66f), Path.Direction.CW)
        organs.addOval(X(52f), Y(40f), X(64f), Y(66f), Path.Direction.CW)
        organs.addCircle(X(54f), Y(50f), 4.2f * s, Path.Direction.CW)
        organs.addOval(X(42f), Y(6f), X(58f), Y(18f), Path.Direction.CW)
        if (m.id == 2) {
            organs.addOval(X(36f), Y(46f), X(52f), Y(58f), Path.Direction.CW)      // liver (figure's right)
            organs.addCircle(X(50f), Y(69f), 8f * s, Path.Direction.CW)            // small intestine
            organs.addOval(X(37f), Y(57f), X(43f), Y(67f), Path.Direction.CW)      // kidneys
            organs.addOval(X(57f), Y(57f), X(63f), Y(67f), Path.Direction.CW)
            organs.moveTo(X(40f), Y(92f)); organs.lineTo(X(39f), Y(140f))          // femur
        }
        canvas.drawPath(organs, organ)

        // The marker travels between the tour's organ points; smoothstep within a leg.
        val p = progress.coerceIn(0f, nodes.lastIndex.toFloat())
        val i = p.toInt().coerceIn(0, nodes.size - 2)
        val f = (p - i).coerceIn(0f, 1f); val e = f * f * (3f - 2f * f)
        val a = nodes[i]; val b = nodes[i + 1]
        val mx = X(a.mapX + (b.mapX - a.mapX) * e); val my = Y(a.mapY + (b.mapY - a.mapY) * e)
        val pulse = 0.5f + 0.5f * sin(t * 4f)
        for (k in 3 downTo 1) {
            glow.color = Color.argb((28 + 18 * pulse).toInt(), 255, 61, 110)
            canvas.drawCircle(mx, my, (3f + k * 3.2f + pulse * 1.5f) * s * 0.6f + 2f, glow)
        }
        canvas.drawCircle(mx, my, 2.6f * s * 0.6f + 1.5f, marker)
        // Inside a cell: zoom rings, one more per few decades of depth (from the stop's Mote length).
        val stop = p.toInt().coerceIn(0, nodes.lastIndex)
        val len = nodes[stop].shipLenM
        val rings = when { len <= 1.3e-11 -> 3; len <= 1.3e-8 -> 2; len <= 1.3e-6 -> 1; else -> 0 }
        for (k in 1..rings) {
            val r = (5f + k * 4.5f) * s * 0.6f + 2f + ((t * 6f + k * 1.7f) % 3f)
            ring.alpha = (170 - k * 30).coerceAtLeast(60)
            canvas.drawCircle(mx, my, r, ring)
        }

        // Label: where we are, and how deep.
        label.textSize = min(11f, w / 9.5f)
        canvas.drawText(nodes[stop].mapLabel, w / 2f, h - 6f, label)

        postInvalidateDelayed(160)   // ~6 Hz is plenty for a pulse; every redraw re-mirrors the whole overlay
    }
}
