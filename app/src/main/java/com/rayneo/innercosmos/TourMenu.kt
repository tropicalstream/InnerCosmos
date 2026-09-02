package com.rayneo.innercosmos

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.widget.TextView
import kotlin.math.abs

/**
 * TOUR MENU — shown after boarding: which ride to take. The Descent (nose to atom) or
 * The Living Machine (mouth to mitosis by way of phages, organs, motor proteins and
 * a dividing cell). Same controls as the depth menu: swipe to choose, tap to board.
 */
class TourMenu(context: Context) : TextView(context) {
    var index = 0
        private set
    var onPick: ((TourMap) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    init {
        setBackgroundColor(Color.argb(218, 8, 3, 10))
        setTextColor(Color.rgb(255, 196, 107))
        setShadowLayer(10f, 0f, 0f, Color.rgb(255, 77, 109))
        typeface = Typeface.MONOSPACE
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(40, 32, 40, 32)
        isClickable = true
        isFocusable = true
        render()
    }

    fun moveBy(delta: Int) {
        val n = Tours.ALL.size
        index = (index + delta + n) % n
        render()
    }

    private fun render() {
        val sb = StringBuilder("◄  CHOOSE A TOUR  ►\n")
            .append("SWIPE: CHOOSE   TAP: BOARD THE MOTE\n\n")
        Tours.ALL.forEachIndexed { i, t ->
            val numeral = if (t.id == 1) "I. " else "II."
            val line = String.format("%-4s%-20s %-30s %s", numeral, t.title.take(20), t.subtitle.take(30), t.durationLabel)
            sb.append(if (i == index) "▶ $line" else "  $line").append('\n')
        }
        sb.append("\nEITHER RIDE CAN BE STARTED AT ANY STOP")
        text = sb.toString()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downAt = event.eventTime
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val dt = event.eventTime - downAt
                if (dt < 300 && abs(dx) < 46f && abs(dy) < 46f) {
                    onPick?.invoke(Tours.ALL[index])
                    return true
                }
                val move = if (abs(dx) >= abs(dy)) dx else dy
                if (abs(move) > 70f) moveBy(if (move > 0f) 1 else -1)
                return true
            }
        }
        return true
    }
}
