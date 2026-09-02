package com.rayneo.innercosmos

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

/**
 * Runs the scripted 33-minute descent from assets/tour_script.json.
 * Drives the Mote's position on the rail (node index 0..12, interpolated from
 * keyframes), automatic view shifts, timed crew dialog (with Fish acting
 * tags), sound-effect cues, captions, and — whenever more than ~10s of silence
 * opens up — random crew banter so the bridge never goes quiet.
 * All dialog is pre-rendered WAV, so no API is needed at runtime.
 */
class TourDirector(
    private val context: Context,
    private val crewVoices: CrewVoices,
    private val onProgress: (Float) -> Unit,
    private val onView: (Int) -> Unit,
    private val onSfx: (String) -> Unit,
    private val onCaption: (CrewVoices.Role, String) -> Unit = { _, _ -> },
    private val onNode: (Int) -> Unit = {}
) {
    /** A start point in the menu: label, timeline position, and the node it belongs to. */
    data class Segment(val label: String, val startMs: Long, val node: Int)

    private data class Cue(val t: Long, val view: Int, val role: String?, val clip: String?, val text: String?, val sfx: String?)
    private data class Filler(val role: String, val clip: String, val text: String)

    private var title = "InnerCosmos"
    private var durationMs = 1_980_000L
    private var kfTime = LongArray(0)
    private var kfProg = FloatArray(0)
    private var cues = emptyList<Cue>()
    private var fillers = emptyList<Filler>()
    private var segments: List<Segment> = DEFAULT_SEGMENTS
    private var loaded = false
    private var scriptAsset = "tour_script.json"

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var startAt = 0L
    private var nextCue = 0
    private var lastSpokeMs = 0L
    private var lastFillerIdx = -1
    /** Fillers already spoken: no line is ever repeated within a session. */
    private val usedFillers = HashSet<Int>()
    private var lastNode = -1
    @Volatile private var elapsedNow = 0L

    init {
        // Captions follow the audio: CrewVoices reports when a line actually starts playing
        // (a queued line may wait behind the one in progress).
        crewVoices.onLineStart = { role, text -> onCaption(role, text) }
    }

    companion object {
        private const val TAG = "ICTour"
        const val SILENCE_MS = 10_000L      // fill silences longer than this
        const val PROTECT_NEXT_MS = 4_000L  // don't fill right before a scripted line
        const val NODE_COUNT = 13
        const val HOLD_LEAD = 0.14f     // a hold starts this far before the stop's landmark...
        const val HOLD_TRAIL = 0.08f    // ...and drifts this far past it by the end of the hold

        /** Fallback menu if tour_script.json carries no "segments" array. */
        val DEFAULT_SEGMENTS = listOf(
            Segment("1.  THE THRESHOLD", 0L, 0),
            Segment("2.  THE AIRWAY", 150_000L, 1),
            Segment("3.  THE ALVEOLUS", 300_000L, 2),
            Segment("4.  THE BLOODSTREAM", 450_000L, 3),
            Segment("5.  THE HEART", 600_000L, 4),
            Segment("6.  THE SENTINEL", 750_000L, 5),
            Segment("7.  THE NEURON", 900_000L, 6),
            Segment("8.  THE MEMBRANE", 1_080_000L, 7),
            Segment("9.  THE MITOCHONDRION", 1_230_000L, 8),
            Segment("10. THE NUCLEUS", 1_380_000L, 9),
            Segment("11. THE RIBOSOME", 1_530_000L, 10),
            Segment("12. THE ATOM", 1_680_000L, 11),
            Segment("13. THE LOOK BACK", 1_830_000L, 12)
        )
    }

    private fun load() {
        if (loaded) return
        try {
            parse(context.assets.open(scriptAsset).bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            // Loud, and survivable: the Mote still descends the whole rail, silently.
            Log.e(TAG, "$scriptAsset missing or unreadable — running a silent fallback ride", e)
            kfTime = longArrayOf(0L, durationMs)
            kfProg = floatArrayOf(0f, (NODE_COUNT - 1).toFloat())
            cues = emptyList(); fillers = emptyList(); segments = DEFAULT_SEGMENTS
        }
        loaded = true
    }

    private fun parse(json: String) {
        val root = JSONObject(json)
        title = root.optString("title", title)
        durationMs = root.optLong("durationMs", 1_980_000L)
        val kf = root.getJSONArray("keyframes")
        kfTime = LongArray(kf.length()); kfProg = FloatArray(kf.length())
        for (i in 0 until kf.length()) {
            val pair = kf.getJSONArray(i)
            kfTime[i] = pair.getLong(0)
            kfProg[i] = pair.getDouble(1).toFloat()
        }
        val cs = root.getJSONArray("cues")
        val cl = ArrayList<Cue>(cs.length())
        for (i in 0 until cs.length()) {
            val o = cs.getJSONObject(i)
            cl.add(
                Cue(
                    t = o.getLong("t"),
                    view = if (o.has("view")) o.getInt("view") else -1,
                    role = if (o.has("role")) o.getString("role") else null,
                    clip = if (o.has("clip")) o.getString("clip") else null,
                    text = if (o.has("text")) o.getString("text") else null,
                    sfx = if (o.has("sfx")) o.getString("sfx") else null
                )
            )
        }
        cues = cl.sortedBy { it.t }
        root.optJSONArray("fillers")?.let { fs ->
            val fl = ArrayList<Filler>(fs.length())
            for (i in 0 until fs.length()) {
                val o = fs.getJSONObject(i)
                fl.add(Filler(o.getString("role"), o.getString("clip"), o.getString("text")))
            }
            fillers = fl
        }
        root.optJSONArray("segments")?.let { ss ->
            val sl = ArrayList<Segment>(ss.length())
            for (i in 0 until ss.length()) {
                val o = ss.getJSONObject(i)
                sl.add(Segment(o.getString("label"), o.getLong("startMs"), o.optInt("node", i)))
            }
            if (sl.isNotEmpty()) segments = sl.sortedBy { it.startMs }
        }
        Log.i(TAG, "Loaded '$title': ${cues.size} cues, ${fillers.size} fillers, ${segments.size} segments, ${durationMs / 1000}s")
    }

    /**
     * Rail position for a timeline instant. The script's keyframes hold flat at each stop for
     * minutes; a flat hold is rendered as a slow approach-and-pass of the stop's landmark
     * (from HOLD_LEAD before it to HOLD_TRAIL past it) so the world keeps moving under the
     * crew's voices, and the transits on either side are re-anchored to stay continuous.
     */
    private fun progressAt(elapsed: Long): Float {
        if (kfTime.isEmpty()) return 0f
        val maxP = kfProg.last()
        if (elapsed <= kfTime.first()) return (kfProg.first() - HOLD_LEAD).coerceIn(0f, maxP)
        if (elapsed >= kfTime.last()) return kfProg.last()
        var i = 1
        while (i < kfTime.size && kfTime[i] < elapsed) i++
        val t0 = kfTime[i - 1]; val t1 = kfTime[i]
        val f = if (t1 > t0) (elapsed - t0).toFloat() / (t1 - t0) else 0f
        val p0 = kfProg[i - 1]; val p1 = kfProg[i]
        if (p1 == p0) {
            val e = f * f * (3f - 2f * f)   // ease through the hold
            return (p0 - HOLD_LEAD + (HOLD_LEAD + HOLD_TRAIL) * e).coerceIn(0f, maxP)
        }
        val startsAfterHold = i - 1 > 0 && kfProg[i - 2] == p0
        val endsInHold = i + 1 < kfProg.size && kfProg[i + 1] == p1
        val a = if (startsAfterHold) p0 + HOLD_TRAIL else p0
        val b = if (endsInHold) p1 - HOLD_LEAD else p1
        return (a + (b - a) * f).coerceIn(0f, maxP)
    }

    /** Current position on the timeline in ms (for seamless pause/resume). */
    fun currentElapsedMs(): Long = elapsedNow

    /** Switch scripts (tour selection). Resets the timeline; the next startFrom / scaleJumpTo begins in the new tour. */
    fun setScript(asset: String) {
        if (asset == scriptAsset && loaded) return
        stop()
        scriptAsset = asset
        loaded = false
        segments = DEFAULT_SEGMENTS
        usedFillers.clear()
        elapsedNow = 0L; jumpToMs = 0L; lastNode = -1; nextCue = 0
        load()
    }

    /** Total tour length in ms (for the menu / clamping). */
    fun durationMs(): Long { load(); return durationMs }

    /** Start points for the menu (from the script, or the built-in default list). */
    fun segments(): List<Segment> { load(); return segments }

    fun title(): String { load(); return title }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val elapsed = SystemClock.uptimeMillis() - startAt
            elapsedNow = elapsed
            publishProgress(progressAt(elapsed))
            while (nextCue < cues.size && cues[nextCue].t <= elapsed) {
                fire(cues[nextCue]); nextCue++
            }
            maybeFillSilence(elapsed)
            if (elapsed < durationMs) handler.postDelayed(this, 100)
        }
    }

    private fun publishProgress(p: Float) {
        onProgress(p)
        val node = p.toInt().coerceIn(0, NODE_COUNT - 1)
        if (node != lastNode) { lastNode = node; onNode(node) }
    }

    private fun fire(cue: Cue) {
        cue.sfx?.let { onSfx(it) }
        if (cue.view in 0..3) onView(cue.view)
        val role = cue.role ?: return
        val clip = cue.clip ?: return
        val r = runCatching { CrewVoices.Role.valueOf(role) }.getOrNull() ?: return
        crewVoices.speak(r, cue.text.orEmpty(), clip)
        lastSpokeMs = SystemClock.uptimeMillis()
    }

    private fun maybeFillSilence(elapsed: Long) {
        if (fillers.isEmpty() || crewVoices.isSpeaking()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSpokeMs < SILENCE_MS) return
        if (elapsed > durationMs - 20_000) return                       // let the finale breathe
        if (nextCue < cues.size && cues[nextCue].t - elapsed < PROTECT_NEXT_MS) return
        val unused = fillers.indices.filter { it !in usedFillers }
        if (unused.isEmpty()) return                                    // banter exhausted: let the ambience carry it
        val idx = unused[(Math.random() * unused.size).toInt().coerceIn(0, unused.size - 1)]
        usedFillers.add(idx)
        lastFillerIdx = idx
        val f = fillers[idx]
        val r = runCatching { CrewVoices.Role.valueOf(f.role) }.getOrNull() ?: return
        crewVoices.speak(r, f.text, f.clip)
        lastSpokeMs = now
    }

    /** Restart the tour from the beginning. */
    fun start() = startFrom(0L)

    // ------------------------------------------------------- scale jump
    private var jumping = false
    private var jumpRunnable: Runnable? = null
    private var jumpToMs = 0L

    /**
     * SCALE JUMP: instead of teleporting, the Mote visibly races along the rail
     * from wherever it is now to the chosen segment — 5 s for a hop to a
     * neighbour, up to 20 s for the full descent — eased so it winds up and
     * brakes like a vessel rather than a slider. While in transit the script is
     * silent; on arrival the tour resumes exactly as startFrom() always did.
     * onJumpState tells the view layer to raise/drop the streak visuals.
     */
    fun scaleJumpTo(targetMs: Long, onJumpState: ((Boolean) -> Unit)? = null) {
        load()
        running = false
        handler.removeCallbacks(ticker)
        jumpRunnable?.let { handler.removeCallbacks(it) }
        // A jump already in flight is cancelled here; never leave its flag set, or a later stop()
        // would report the old destination as the resume point.
        if (jumping) { jumping = false; onJumpState?.invoke(false) }
        jumpRunnable = null
        crewVoices.silence()
        val target = targetMs.coerceIn(0L, (durationMs - 1000L).coerceAtLeast(0L))
        val fromMs = elapsedNow
        val fromProg = progressAt(fromMs)
        val toProg = progressAt(target)
        val span = (if (kfProg.isEmpty()) 1f else kfProg.last() - kfProg.first()).coerceAtLeast(0.001f)
        val dist = Math.abs(toProg - fromProg)
        if (dist < 0.01f) { startFrom(target); return }   // already there: no jump
        val durMs = (5000f + 15000f * (dist / span)).toLong().coerceIn(5000L, 20000L)
        jumping = true
        jumpToMs = target
        onJumpState?.invoke(true)
        onSfx("drive_engage")
        val t0 = SystemClock.uptimeMillis()
        val jump = object : Runnable {
            override fun run() {
                if (!jumping) { onJumpState?.invoke(false); return }
                val f = ((SystemClock.uptimeMillis() - t0).toFloat() / durMs).coerceIn(0f, 1f)
                val e = f * f * (3f - 2f * f)   // smoothstep: wind up, cruise, brake
                // Keep the timeline position honest mid-jump so a pause or double-tap lands where the Mote is.
                elapsedNow = fromMs + ((target - fromMs) * e).toLong()
                publishProgress(fromProg + (toProg - fromProg) * e)
                if (f >= 1f) {
                    jumping = false
                    jumpRunnable = null
                    onJumpState?.invoke(false)
                    onSfx("chime")
                    startFrom(target)
                } else {
                    handler.postDelayed(this, 16)
                }
            }
        }
        jumpRunnable = jump
        handler.post(jump)
        Log.i(TAG, "Scale jump: prog $fromProg -> $toProg in ${durMs}ms")
    }

    /**
     * Begin the tour at a chosen point on the timeline. Skips every cue that
     * already passed (so old lines don't replay), snaps the Mote to the right
     * depth instantly, and lets the script run forward from there — used by
     * the "SELECT A DEPTH" menu so a guest can start at, say, the Nucleus.
     */
    fun startFrom(elapsedMs: Long, resetView: Boolean = true) {
        load()
        crewVoices.silence()
        val begin = elapsedMs.coerceIn(0L, (durationMs - 1000L).coerceAtLeast(0L))
        running = true
        lastFillerIdx = -1
        if (begin == 0L) usedFillers.clear()      // a fresh ride from the top may use the banter again
        lastNode = -1
        nextCue = 0
        while (nextCue < cues.size && cues[nextCue].t <= begin) nextCue++
        startAt = SystemClock.uptimeMillis() - begin
        elapsedNow = begin
        lastSpokeMs = SystemClock.uptimeMillis()
        publishProgress(progressAt(begin))
        if (resetView) onView(1) // open on the external view so the Mote is seen in flight
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun stop() {
        running = false
        if (jumping) {
            // A stop mid-jump cancels the jump; the guest's pick still stands, so resume there.
            jumping = false
            elapsedNow = jumpToMs
        }
        jumpRunnable?.let { handler.removeCallbacks(it) }
        jumpRunnable = null
        handler.removeCallbacks(ticker)
    }
}
