package com.rayneo.innercosmos

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.Choreographer
import android.view.MotionEvent
import kotlin.math.abs

/**
 * The stereo scene surface + temple-touchpad gestures during the tour:
 *   TAP          switch camera view (Bridge → External → Scale Drive → Observation)
 *   DOUBLE-TAP   pause and reopen the depth menu
 *   SWIPE fwd    cycle the audio mix
 *   SWIPE back   toggle the telemetry HUD
 * (Long-press is reserved by the X3 Pro system OS and never reaches apps.)
 *
 * Frame pacing: the surface renders on demand, driven by a Choreographer callback that
 * requests a frame every [frameDivider]th vsync (2 = 30 fps on a 60 Hz panel). Rendering
 * both eyes at full refresh cooked the glasses; 30 fps is plenty for a railed ride and the
 * thermal governor in MainActivity lowers it further when the device reports heat.
 */
class InnerCosmosView(
    context: Context,
    private val audioEngine: BodyAudioEngine
) : GLSurfaceView(context) {
    private val renderer = StereoBodyRenderer(audioEngine, context.applicationContext)
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var lastTapAt = 0L
    private var onDoubleTap: (() -> Unit)? = null
    private var onSwipe: ((forward: Boolean) -> Unit)? = null
    @Volatile private var frameDivider = 2
    private var vsyncCount = 0
    private var pacing = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!pacing) return
            if (++vsyncCount % frameDivider == 0) requestRender()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun setDoubleTapListener(listener: () -> Unit) { onDoubleTap = listener }
    fun setSwipeListener(listener: (forward: Boolean) -> Unit) { onSwipe = listener }

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
    }

    override fun onResume() {
        super.onResume()
        pacing = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onPause() {
        pacing = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onPause()
    }

    /**
     * Quality / thermal level: 0 = 30 fps full detail, 1 = 20 fps reduced detail,
     * 2 = 15 fps minimal detail (set from PowerManager's thermal status).
     */
    fun setQuality(level: Int) {
        val q = level.coerceIn(0, 2)
        frameDivider = 2 + q
        renderer.quality = q
    }

    fun telemetry(): String = renderer.telemetry()
    fun currentNodeName(): String = renderer.currentNodeName()
    fun setViewListener(listener: (Int) -> Unit) = renderer.setViewListener(listener)
    // Volatile scalars can be set from any thread; multi-field state changes run on the GL thread.
    fun setScripted(on: Boolean) = renderer.setScripted(on)
    fun setStereo(on: Boolean) { renderer.stereo = on }
    fun setDebugHud(on: Boolean) { renderer.debugHud = on }
    fun setShowcase(on: Boolean) { renderer.showcase = on }
    fun setGaze(g: GazeCamera?) { renderer.gaze = g }
    fun setProgress(p: Float) = renderer.setProgress(p)
    fun setJumping(on: Boolean) = renderer.setJumping(on)
    fun setView(mode: Int) = queueEvent { renderer.setView(mode) }
    fun triggerBeat(intensity: Float) = queueEvent { renderer.triggerBeat(intensity) }
    fun triggerShrink() = queueEvent { renderer.triggerShrink() }
    fun triggerHeartbeat() = queueEvent { renderer.triggerHeartbeat() }
    fun triggerProbe() = queueEvent { renderer.triggerProbe() }
    fun triggerGrow() = queueEvent { renderer.triggerGrow() }
    fun triggerLysis() = queueEvent { renderer.triggerLysis() }
    /** Switch tours; the renderer rebuilds the passage on its own thread before the next frame. */
    fun setMap(m: TourMap) = renderer.setMap(m)
    /** Called on the GL thread when a new tour's passage is ready. */
    fun setMapListener(l: (TourMap) -> Unit) { renderer.mapListener = l }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = event.eventTime
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val elapsed = event.eventTime - downAt
                if (elapsed < 280 && abs(dx) < 42f && abs(dy) < 42f) {
                    if (event.eventTime - lastTapAt < 320) {   // double-tap → menu
                        lastTapAt = 0L
                        onDoubleTap?.invoke()
                        return true
                    }
                    lastTapAt = event.eventTime
                    queueEvent { renderer.switchView() }       // single tap, instant
                    audioEngine.tap()
                    return true
                }
                if (abs(dx) > abs(dy) && abs(dx) > 80f) {
                    onSwipe?.invoke(dx > 0f)
                    audioEngine.tap()
                    return true
                }
            }
        }
        return true
    }
}
