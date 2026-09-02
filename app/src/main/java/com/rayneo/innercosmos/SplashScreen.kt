package com.rayneo.innercosmos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * TITLE CARD — drawn over the live 3D scene, which the activity parks in "showcase" mode:
 * the Mote floating outside the nose, chase camera slowly orbiting. No dark wash (on the
 * X3 Pro waveguides dark is transparent, so a wash only greys the picture); the words are
 * bright white with a hot rose bloom and saturated amber that hold their colour on the
 * glasses. TAP boards and opens the depth menu.
 */
class SplashScreen(context: Context) : View(context) {
    var onTap: (() -> Unit)? = null

    private val t0 = SystemClock.uptimeMillis()
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.20f
        color = Color.WHITE
    }
    private val titleBloom = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.20f
        color = Color.rgb(255, 45, 92)
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        color = Color.rgb(255, 176, 32)
        setShadowLayer(5f, 0f, 0f, Color.rgb(120, 30, 40))
    }
    private val ticker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        color = Color.rgb(64, 224, 208)
        setShadowLayer(4f, 0f, 0f, Color.rgb(0, 60, 60))
    }
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)   // blur-mask bloom needs the software layer
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - t0) / 1000f
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f

        // Title at the top third, floating over the nose scene. A breathing rose bloom under
        // a pure white face: the brightest thing on the waveguide, never washed out.
        title.textSize = h * 0.115f
        fitWidth(title, "INNERCOSMOS", w * 0.92f); titleBloom.textSize = title.textSize
        val breathe = 0.5f + 0.5f * sin(t * 1.6f)
        val ty = h * 0.24f
        titleBloom.maskFilter = android.graphics.BlurMaskFilter(14f + 10f * breathe, android.graphics.BlurMaskFilter.Blur.NORMAL)
        titleBloom.alpha = (150 + 90 * breathe).toInt()
        canvas.drawText("INNERCOSMOS", cx, ty, titleBloom)
        canvas.drawText("INNERCOSMOS", cx, ty, title)
        sub.textSize = h * 0.038f
        canvas.drawText("the universe inside you", cx, ty + h * 0.07f, sub)

        // The powers-of-ten ticker: the whole idea of the ride, counting down.
        ticker.textSize = h * 0.03f
        val rung = ((t * 0.9f).toInt()) % LADDER.size
        canvas.drawText("M.S.V. MOTE  ·  ${LADDER[rung]}", cx, h * 0.865f, ticker)

        // Boarding call, blinking, hot amber.
        val blink = 0.55f + 0.45f * sin(t * 3.4f)
        sub.textSize = h * 0.042f
        sub.alpha = (255 * blink).toInt()
        canvas.drawText("TAP TO BOARD", cx, h * 0.95f, sub)
        sub.alpha = 255

        postInvalidateDelayed(33)
    }

    private fun fitWidth(p: Paint, text: String, maxW: Float) {
        val tw = p.measureText(text)
        if (tw > maxW && tw > 0f) p.textSize *= maxW / tw
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downAt = event.eventTime
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX; val dy = event.y - downY
                if (event.eventTime - downAt < 400 && abs(dx) < 60f && abs(dy) < 60f) onTap?.invoke()
                return true
            }
        }
        return true
    }

    private companion object {
        val LADDER = arrayOf("12 METRES", "12 MILLIMETRES", "1.2 MILLIMETRES", "120 MICROMETRES", "12 MICROMETRES",
            "1.2 MICROMETRES", "120 NANOMETRES", "12 NANOMETRES", "12 PICOMETRES", "…AND BACK")
    }
}
