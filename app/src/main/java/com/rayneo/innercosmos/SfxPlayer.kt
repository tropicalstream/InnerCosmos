package com.rayneo.innercosmos

import android.content.Context
import android.media.MediaPlayer
import java.io.File

/**
 * Plays sound-effect clips from assets/sfx/<name>.wav as overlays (they can play
 * on top of dialog and on top of each other). Copies to cache first — atomically —
 * so build-compressed assets still work and a half-copied file is never replayed.
 * If a clip is missing, playback is a no-op and the caller falls back to a synth
 * cue from BodyAudioEngine.
 */
class SfxPlayer(private val context: Context) {
    private val active = ArrayList<MediaPlayer>()

    /** 0..1 gain from the audio-mix cycler. */
    @Volatile var volume = 1f

    fun play(name: String): Boolean {
        if (volume <= 0.001f) return true   // muted: swallow the cue, no synth fallback either
        val cache = File(context.cacheDir, "sfx_$name.wav")
        if (!cache.exists() || cache.length() == 0L) {
            val tmp = File(context.cacheDir, "sfx_$name.tmp")
            try {
                context.assets.open("sfx/$name.wav").use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (!tmp.renameTo(cache)) { tmp.delete(); return false }
            } catch (e: Exception) {
                tmp.delete()
                return false // no sfx file bundled
            }
        }
        val p = MediaPlayer()
        try {
            p.setDataSource(cache.absolutePath)
            p.setVolume(volume, volume)
            p.setOnCompletionListener { done(it) }
            p.setOnErrorListener { x, _, _ -> done(x); true }
            p.setOnPreparedListener { it.start() }
            p.prepareAsync()
        } catch (e: Exception) {
            runCatching { p.release() }
            runCatching { cache.delete() }
            return false
        }
        active.add(p)
        while (active.size > 4) done(active[0])   // cap overlapping cues; drop the oldest
        return true
    }

    private fun done(p: MediaPlayer) {
        active.remove(p)
        runCatching { p.release() }
    }

    fun release() {
        active.toList().forEach { done(it) }
    }
}
