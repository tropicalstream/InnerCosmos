package com.rayneo.innercosmos

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * InnerCosmos — a railed, stereoscopic descent through the human body for the
 * RayNeo X3 Pro. Flow: title card → tour menu (The Descent / The Living Machine) → depth menu → the tour.
 *
 * Layers (bottom to top): the GL scene (two eye viewports), then a
 * BinocularSbsLayout that mirrors every 2D overlay into both lenses:
 * telemetry HUD (top-left), captions (bottom), the depth menu, the title card.
 *
 * Right-arm touchpad during the tour:
 *   TAP          switch camera view          DOUBLE-TAP   pause + reopen the depth menu
 *   SWIPE fwd    cycle the audio mix         SWIPE back   toggle the telemetry HUD
 */
class MainActivity : Activity() {
    private lateinit var sceneView: InnerCosmosView
    private val audioEngine = BodyAudioEngine()
    private val crewVoices by lazy { CrewVoices(this) }
    private val sfxPlayer by lazy { SfxPlayer(this) }
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var telemetryView: TextView
    private lateinit var captionView: TextView
    private lateinit var bodyMap: BodyMapView
    private val gaze by lazy { GazeCamera(this) }
    private lateinit var tourDirector: TourDirector
    private lateinit var segmentMenu: SegmentMenu
    private lateinit var tourMenu: TourMenu
    private lateinit var splash: SplashScreen
    private var currentMap: TourMap = Tours.DESCENT
    private var tourStarted = false
    private var resumeAtMs = 0L
    private var audioMix = 0
    private var hudVisible = true

    // Telemetry stays on screen and refreshes every 10 seconds (like the sibling starship).
    private val telemetryTicker = object : Runnable {
        override fun run() {
            telemetryView.text = sceneView.telemetry()
            uiHandler.postDelayed(this, 10_000)
        }
    }
    private val captionClear = Runnable { captionView.visibility = View.INVISIBLE }

    // Captions are paged: a 30-second line does not fit in five lines of text, and truncating it
    // with an ellipsis loses the half of the sentence that carries the point. Each page is shown
    // for as long as it takes to say, so the words on screen track the voice.
    private val captionQueue = ArrayDeque<Pair<String, Long>>()
    private val captionNext = object : Runnable {
        override fun run() {
            val page = captionQueue.removeFirstOrNull()
            if (page == null) { captionView.visibility = View.INVISIBLE; return }
            captionView.text = page.first
            captionView.visibility = View.VISIBLE
            uiHandler.postDelayed(this, page.second)
        }
    }

    /** Split a spoken line into caption pages at sentence boundaries (about four lines each). */
    private fun paginate(text: String): List<String> {
        if (text.length <= CAPTION_CHARS) return listOf(text)
        val parts = Regex("(?<=[.!?…])\\s+").split(text)
        val pages = ArrayList<String>()
        val sb = StringBuilder()
        for (p in parts) {
            var piece = p
            // A single sentence longer than a page is broken at a comma, then at a word.
            while (piece.length > CAPTION_CHARS) {
                val cut = piece.lastIndexOf(", ", CAPTION_CHARS).let { if (it > CAPTION_CHARS / 2) it + 1 else piece.lastIndexOf(' ', CAPTION_CHARS) }
                if (cut <= 0) break
                if (sb.isNotEmpty()) { pages.add(sb.toString()); sb.setLength(0) }
                pages.add(piece.substring(0, cut).trim())
                piece = piece.substring(cut).trim()
            }
            if (sb.isNotEmpty() && sb.length + 1 + piece.length > CAPTION_CHARS) { pages.add(sb.toString()); sb.setLength(0) }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(piece)
        }
        if (sb.isNotEmpty()) pages.add(sb.toString())
        return pages
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sceneView = InnerCosmosView(this, audioEngine)
        // View changes (driven by the tour, or a tap) update the ambience.
        sceneView.setViewListener { mode -> uiHandler.post { audioEngine.setAmbience(mode) } }
        tourDirector = TourDirector(
            context = this,
            crewVoices = crewVoices,
            onProgress = { p -> sceneView.setProgress(p); bodyMap.setProgress(p) },
            onView = { m -> sceneView.setView(m) },
            onSfx = { name -> uiHandler.post { onSfx(name) } },
            onCaption = { role, text -> uiHandler.post { showCaption(role, text) } },
            onNode = { node -> audioEngine.setStage(currentMap.nodes[node.coerceIn(0, currentMap.nodes.lastIndex)].amb) }
        )
        telemetryView = TextView(this).apply {
            setTextColor(Color.rgb(255, 196, 107))
            setShadowLayer(8f, 0f, 0f, Color.rgb(255, 61, 110))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(24, 18, 24, 18)
            alpha = 0.92f
            setLayerType(View.LAYER_TYPE_HARDWARE, null)   // static between refreshes: cache, don't re-record
        }
        bodyMap = BodyMapView(this)
        captionView = TextView(this).apply {
            setTextColor(Color.rgb(255, 244, 232))
            setShadowLayer(6f, 0f, 0f, Color.rgb(8, 3, 10))
            setBackgroundColor(Color.argb(150, 8, 3, 10))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(28, 12, 28, 12)
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.INVISIBLE
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        segmentMenu = SegmentMenu(this).apply {
            visibility = View.GONE
            onPick = { seg ->
                visibility = View.GONE
                tourStarted = true
                resumeAtMs = seg.startMs
                // Not a teleport: the Mote makes the run along the rail — 5-20 s by
                // distance — then the script resumes at the chosen depth.
                tourDirector.scaleJumpTo(seg.startMs) { jumping -> sceneView.setJumping(jumping) }
            }
            onBack = {
                visibility = View.GONE
                tourDirector.stop()
                sceneView.setJumping(false)
                crewVoices.silence()
                tourStarted = false
                tourMenu.visibility = View.VISIBLE
            }
        }
        tourMenu = TourMenu(this).apply {
            visibility = View.GONE
            onPick = { m ->
                visibility = View.GONE
                applyTour(m)
                segmentMenu.selectByTime(0L)
                segmentMenu.visibility = View.VISIBLE
            }
        }
        splash = SplashScreen(this).apply {
            onTap = {
                visibility = View.GONE
                gaze.recenter()                       // boarding: this head pose is straight ahead
                sceneView.setShowcase(false)
                telemetryView.visibility = if (hudVisible) View.VISIBLE else View.INVISIBLE
                bodyMap.visibility = View.VISIBLE
                audioEngine.engage()
                tourMenu.visibility = View.VISIBLE
            }
        }
        applyTour(Tours.DESCENT)
        // Head look-around from the IMU (look direction only; the rail is never steered).
        sceneView.setGaze(if (gaze.available()) gaze else null)
        // The HUD names the tour and its stops: refresh it once the GL thread has adopted a new map.
        sceneView.setMapListener { uiHandler.post { telemetryView.text = sceneView.telemetry() } }
        // Behind the title card: the Mote idling just outside the nose, camera orbiting.
        sceneView.setShowcase(true)
        sceneView.setProgress(0.42f)
        sceneView.setView(StereoBodyRenderer.VIEW_CHASE)
        telemetryView.visibility = View.INVISIBLE
        bodyMap.visibility = View.INVISIBLE
        // DOUBLE-TAP during the tour: pause and reopen the depth menu.
        sceneView.setDoubleTapListener { uiHandler.post { openMenuFromTour() } }
        sceneView.setSwipeListener { forward -> if (forward) cycleAudioMix() else toggleHud() }

        val overlay = FrameLayout(this)
        overlay.addView(
            telemetryView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        )
        overlay.addView(
            bodyMap,
            FrameLayout.LayoutParams(118, 178, Gravity.TOP or Gravity.END).apply { setMargins(0, 8, 8, 0) }
        )
        overlay.addView(
            captionView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                setMargins(36, 0, 36, 22)
            }
        )
        overlay.addView(segmentMenu, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        overlay.addView(tourMenu, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        overlay.addView(splash, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val sbs = BinocularSbsLayout(this).apply { addView(overlay) }
        // Stereo (two eye viewports + mirrored overlays) on the glasses; a single flat view on an
        // emulator or phone, or when launched with `--ez mono true` for demos and screenshots.
        val mono = intent?.getBooleanExtra("mono", false) == true || isEmulator()
        sbs.sbsEnabled = !mono
        sceneView.setStereo(!mono)

        val root = FrameLayout(this)
        root.addView(sceneView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(sbs, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        setContentView(root)
        hideSystemUi()
    }

    /** Load a tour: its script, rail, body map and depth menu (the tour itself starts from the depth menu). */
    private fun applyTour(m: TourMap) {
        currentMap = m
        tourDirector.setScript(m.scriptAsset)
        sceneView.setJumping(false)
        sceneView.setMap(m)
        sceneView.setProgress(0f)          // the new rail starts at its first stop, not the old one's depth
        bodyMap.setTour(m)
        bodyMap.setProgress(0f)
        segmentMenu.scaleLabels = m.nodes.map { it.scaleLabel }
        segmentMenu.setSegments(tourDirector.segments())
        resumeAtMs = 0L
        telemetryView.text = sceneView.telemetry()
    }

    /**
     * Debug / demo control channel (adb):
     *   am broadcast -a com.rayneo.innercosmos.CONTROL --ei tour 2        load tour 1 (The Descent) or 2 (The Living Machine)
     *   am broadcast -a com.rayneo.innercosmos.CONTROL --ei segment 4     start at stop index 4 (scale jump)
     *   am broadcast -a com.rayneo.innercosmos.CONTROL --ei view 1        camera view 0..3
     *   am broadcast -a com.rayneo.innercosmos.CONTROL --ez menu true     pause + open the depth menu
     *   am broadcast -a com.rayneo.innercosmos.CONTROL --ez board true    leave the title card
     *   am broadcast -a com.rayneo.innercosmos.CONTROL --ez hud false     hide / show telemetry
     */
    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            intent ?: return
            if (intent.getBooleanExtra("board", false) && splash.visibility == View.VISIBLE) splash.onTap?.invoke()
            if (intent.hasExtra("tour")) {
                if (splash.visibility == View.VISIBLE) splash.onTap?.invoke()
                tourDirector.stop()
                tourMenu.onPick?.invoke(Tours.byId(intent.getIntExtra("tour", 1)))
            }
            if (intent.hasExtra("segment")) {
                val segs = tourDirector.segments()
                val i = intent.getIntExtra("segment", 0).coerceIn(0, segs.lastIndex)
                if (splash.visibility == View.VISIBLE) splash.onTap?.invoke()
                tourMenu.visibility = View.GONE
                segmentMenu.onPick?.invoke(segs[i])
            }
            if (intent.hasExtra("view")) sceneView.setView(intent.getIntExtra("view", 1))
            if (intent.getBooleanExtra("menu", false)) sceneView.post { openMenuFromTour() }
            if (intent.hasExtra("hud")) { hudVisible = !intent.getBooleanExtra("hud", true); toggleHud() }
            if (intent.hasExtra("debug")) { sceneView.setDebugHud(intent.getBooleanExtra("debug", false)); telemetryView.text = sceneView.telemetry() }
            if (intent.hasExtra("quality")) sceneView.setQuality(intent.getIntExtra("quality", 0))
            if (intent.getBooleanExtra("recenter", false)) gaze.recenter()
            if (intent.hasExtra("gaze")) gaze.enabled = intent.getBooleanExtra("gaze", true)
            if (intent.getBooleanExtra("shrink", false)) onSfx("shrink")
            if (intent.getBooleanExtra("grow", false)) onSfx("grow")
            if (intent.getBooleanExtra("lysis", false)) onSfx("lysis")
        }
    }

    private fun openMenuFromTour() {
        tourDirector.stop()                 // also cancels a jump in flight (keeps its destination)
        resumeAtMs = tourDirector.currentElapsedMs()
        sceneView.setJumping(false)
        crewVoices.silence()
        segmentMenu.selectByTime(resumeAtMs)
        segmentMenu.visibility = View.VISIBLE
    }

    private val powerManager by lazy { getSystemService(POWER_SERVICE) as PowerManager }
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status -> applyThermal(status) }

    private var thermalStatus = 0
    private var batteryTenths = 0
    private var qualityLevel = -1

    /**
     * Heat is the enemy on the glasses: step the frame rate and detail down as the device warms.
     * Android's thermal status is one input; the battery temperature is the other, because this
     * device reports status 0 even with the SoC near 60 °C.
     */
    private fun applyThermal(status: Int) { thermalStatus = status; updateQuality() }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            batteryTenths = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            updateQuality()
        }
    }

    private fun updateQuality() {
        val level = when {
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE || batteryTenths >= 420 -> 2
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE || batteryTenths >= 390 -> 1
            else -> 0
        }
        // Hysteresis: only relax one step when the battery has cooled 1.5 °C below the threshold.
        val relaxed = if (level < qualityLevel && batteryTenths > (if (qualityLevel == 2) 405 else 375)) qualityLevel else level
        if (relaxed != qualityLevel) {
            qualityLevel = relaxed
            sceneView.setQuality(relaxed)
            android.util.Log.i("ICThermal", "status $thermalStatus battery ${batteryTenths / 10f}C -> quality $relaxed")
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        runCatching {
            powerManager.addThermalStatusListener(thermalListener)
            applyThermal(powerManager.currentThermalStatus)
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val filter = IntentFilter(CONTROL_ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(controlReceiver, filter, RECEIVER_EXPORTED)
        else registerReceiver(controlReceiver, filter)
        crewVoices.init()
        audioEngine.start()
        gaze.start()
        sceneView.onResume()
        sceneView.setScripted(true)
        uiHandler.post(telemetryTicker)
        // Menu-first flow: the tour begins when a depth is picked. After a
        // pause (glasses doze, app switch) it resumes where it left off.
        if (tourStarted && segmentMenu.visibility != View.VISIBLE && tourMenu.visibility != View.VISIBLE && splash.visibility != View.VISIBLE) {
            tourDirector.startFrom(resumeAtMs, resetView = false)   // keep the guest's chosen view
        }
    }

    override fun onPause() {
        runCatching { powerManager.removeThermalStatusListener(thermalListener) }
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { unregisterReceiver(controlReceiver) }
        uiHandler.removeCallbacks(telemetryTicker)
        uiHandler.removeCallbacks(captionClear)
        uiHandler.removeCallbacks(captionNext)
        captionQueue.clear()
        tourDirector.stop()                 // (mid-jump: the chosen depth is kept as the resume point)
        resumeAtMs = tourDirector.currentElapsedMs()
        crewVoices.silence()                // no crew talking to a dark display
        sfxPlayer.release()
        captionView.visibility = View.INVISIBLE
        sceneView.setJumping(false)
        sceneView.onPause()
        gaze.stop()
        audioEngine.stop()
        super.onPause()
    }

    override fun onDestroy() {
        crewVoices.shutdown()
        sfxPlayer.release()
        super.onDestroy()
    }

    /** Closed captions: the line, stripped of Fish acting tags, for about as long as it takes to say. */
    private fun showCaption(role: CrewVoices.Role, text: String) {
        if (audioMix == 2 || audioMix == 3) return   // dialogue is muted: no captions either
        val spoken = text.replace(Regex("\\[[^\\]]*\\]"), " ").replace(Regex("\\s+"), " ").trim()
        if (spoken.isEmpty()) return
        uiHandler.removeCallbacks(captionClear)
        uiHandler.removeCallbacks(captionNext)
        captionQueue.clear()
        val pages = paginate(spoken)
        pages.forEachIndexed { i, page ->
            val words = page.split(' ').size
            // Roughly two and a half words a second, the pace the crew actually speaks at.
            val ms = (words / 2.5f * 1000f).toLong() + if (i == pages.lastIndex) 1500L else 350L
            captionQueue.add((if (i == 0) "${role.label}:  $page" else page) to ms.coerceAtLeast(1600L))
        }
        uiHandler.post(captionNext)
    }

    /** SWIPE forward: Full mix → Dialogue + ambient → Ambient + SFX → Mute all. */
    private fun cycleAudioMix() {
        audioMix = (audioMix + 1) % 4
        val (dialogue, sfx, ambient) = when (audioMix) {
            0 -> Triple(1f, 1f, 1f)
            1 -> Triple(1f, 0f, 1f)
            2 -> Triple(0f, 1f, 1f)
            else -> Triple(0f, 0f, 0f)
        }
        crewVoices.dialogueVolume = dialogue
        sfxPlayer.volume = sfx
        audioEngine.masterGain = ambient
        val label = arrayOf("FULL MIX", "DIALOGUE + AMBIENT", "AMBIENT + SFX", "MUTE ALL")[audioMix]
        uiHandler.removeCallbacks(captionNext)
        captionQueue.clear()
        captionView.text = "AUDIO:  $label"
        captionView.visibility = View.VISIBLE
        uiHandler.removeCallbacks(captionClear)
        uiHandler.postDelayed(captionClear, 1800L)
    }

    /** SWIPE back: show / hide the telemetry HUD. */
    private fun toggleHud() {
        hudVisible = !hudVisible
        if (splash.visibility == View.VISIBLE) return
        telemetryView.visibility = if (hudVisible) View.VISIBLE else View.INVISIBLE
        bodyMap.visibility = telemetryView.visibility
    }

    /** Play a bundled SFX (assets/sfx/<name>.wav) + a synced visual beat; synth fallback if absent. */
    private fun onSfx(name: String) {
        val beat = when (name) {
            "klaxon", "alarm" -> 0.9f
            "impact_soft", "lysis" -> 0.7f
            "drive_engage", "shrink", "grow" -> 0.55f
            "spark" -> 0.5f
            "squelch" -> 0.45f
            "chime", "heartbeat" -> 0.35f
            else -> 0.5f
        }
        sceneView.triggerBeat(beat)
        when (name) {
            "shrink" -> sceneView.triggerShrink()
            "grow" -> sceneView.triggerGrow()
            "lysis" -> { sceneView.triggerLysis(); sceneView.triggerProbe() }
            "heartbeat" -> sceneView.triggerHeartbeat()
            "spark", "squelch", "impact_soft" -> sceneView.triggerProbe()   // the arm probes reach out
        }
        if (sfxPlayer.play(name)) return
        when (name) {
            "drive_engage" -> audioEngine.engage()
            "shrink" -> audioEngine.shrink()
            "grow" -> audioEngine.grow()
            "lysis" -> audioEngine.lysis()
            "heartbeat" -> audioEngine.heartbeat()
            "chime" -> audioEngine.chime()
            "spark" -> audioEngine.spark()
            "squelch" -> audioEngine.squelch()
            "klaxon", "alarm", "impact_soft" -> audioEngine.tap()
            else -> audioEngine.tap()
        }
    }

    private fun isEmulator(): Boolean {
        val fp = Build.FINGERPRINT.lowercase(); val model = Build.MODEL.lowercase(); val hw = Build.HARDWARE.lowercase()
        return fp.startsWith("generic") || fp.contains("emulator") || model.contains("emulator") ||
            model.contains("android sdk built for") || hw.contains("ranchu") || hw.contains("goldfish") ||
            Build.PRODUCT.lowercase().contains("sdk")
    }

    private companion object {
        const val CONTROL_ACTION = "com.rayneo.innercosmos.CONTROL"
        /** About four lines of caption at this text size and lens width. */
        const val CAPTION_CHARS = 230
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
