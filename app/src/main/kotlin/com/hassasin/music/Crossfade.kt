package com.hassasin.music

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Fades between songs: the last seconds of a song fade out and the next song fades in, so tracks
 * flow into each other. Songs from the same album play straight on (gapless albums stay intact).
 * Done with the player's volume (a fade, not an overlap of two songs), so bit-perfect USB output,
 * which ignores that volume, is not touched. The fade multiplies the app volume (DVC).
 */
object Crossfade {
    private var fadingIn = false
    private var fadeInStart = 0L
    /** The fade's own factor, 0..1; the player's volume is this times the app volume. */
    private var fade = 1f

    /** Called a few times a second by the player loop. */
    fun tick(player: ExoPlayer) {
        val sec = AppState.settings.value.crossfadeSec
        fade = when {
            sec <= 0 -> { fadingIn = false; 1f }
            fadingIn -> {
                val p = ((System.currentTimeMillis() - fadeInStart).toFloat() / (sec * 1000L)).coerceIn(0f, 1f)
                if (p >= 1f) fadingIn = false
                p * p
            }
            else -> fadeOutFactor(player, sec * 1000L)
        }
        apply(player)
    }

    private fun fadeOutFactor(player: ExoPlayer, fadeMs: Long): Float {
        val dur = player.duration
        if (!player.isPlaying || dur <= 0 || !player.hasNextMediaItem() || player.repeatMode == Player.REPEAT_MODE_ONE) return 1f
        val left = dur - player.currentPosition
        if (left !in 1 until fadeMs || sameAlbumNext(player)) return 1f
        val p = (left.toFloat() / fadeMs).coerceIn(0f, 1f)
        return p * p
    }

    /** Sets the player's volume from the fade and the app volume. */
    fun apply(player: ExoPlayer) {
        val v = (fade * Dvc.gain() * ReplayGain.gain() * SleepTimer.fade()).coerceIn(0f, 1f)
        if (kotlin.math.abs(player.volume - v) > 1e-4f) player.volume = v
    }

    /** A new song started: fade it in if the previous one faded out. */
    fun onNewSong(player: ExoPlayer, automatic: Boolean) {
        val sec = AppState.settings.value.crossfadeSec
        if (sec > 0 && automatic && fade < 0.99f) { fadingIn = true; fadeInStart = System.currentTimeMillis(); fade = 0f }
        else { fadingIn = false; fade = 1f }
        apply(player)
    }

    private fun sameAlbumNext(player: ExoPlayer): Boolean {
        val q = Playback.state.value.queue
        val a = q.getOrNull(player.currentMediaItemIndex) ?: return false
        val b = q.getOrNull(player.nextMediaItemIndex) ?: return false
        return a.album.isNotBlank() && a.album == b.album && a.albumKey == b.albumKey
    }
}
