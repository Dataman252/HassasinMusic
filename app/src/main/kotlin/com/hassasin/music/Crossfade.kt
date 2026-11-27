package com.hassasin.music

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Fades between songs: the last seconds of a song fade out and the next song fades in, so tracks
 * flow into each other. Songs from the same album play straight on (gapless albums stay intact).
 * Done with the player's volume (a fade, not an overlap of two songs), so bit-perfect USB output,
 * which ignores that volume, is not touched.
 */
object Crossfade {
    private var fadingIn = false
    private var fadeInStart = 0L

    /** Called a few times a second by the player loop. */
    fun tick(player: ExoPlayer) {
        val sec = AppState.settings.value.crossfadeSec
        if (sec <= 0) { if (player.volume != 1f) player.volume = 1f; fadingIn = false; return }
        val fadeMs = sec * 1000L
        val now = System.currentTimeMillis()
        if (fadingIn) {
            val p = ((now - fadeInStart).toFloat() / fadeMs).coerceIn(0f, 1f)
            player.volume = p * p
            if (p >= 1f) fadingIn = false
            return
        }
        val dur = player.duration
        val pos = player.currentPosition
        if (!player.isPlaying || dur <= 0 || !player.hasNextMediaItem() || player.repeatMode == Player.REPEAT_MODE_ONE) {
            if (player.volume != 1f && !fadingIn) player.volume = 1f
            return
        }
        val left = dur - pos
        if (left in 1 until fadeMs && !sameAlbumNext(player)) {
            val p = (left.toFloat() / fadeMs).coerceIn(0f, 1f)
            player.volume = p * p
        } else if (player.volume != 1f) player.volume = 1f
    }

    /** A new song started: fade it in if the previous one faded out. */
    fun onNewSong(player: ExoPlayer, automatic: Boolean) {
        val sec = AppState.settings.value.crossfadeSec
        if (sec > 0 && automatic && player.volume < 0.99f) { fadingIn = true; fadeInStart = System.currentTimeMillis(); player.volume = 0f }
        else { fadingIn = false; player.volume = 1f }
    }

    private fun sameAlbumNext(player: ExoPlayer): Boolean {
        val q = Playback.state.value.queue
        val i = player.currentMediaItemIndex
        val a = q.getOrNull(i) ?: return false
        val b = q.getOrNull(player.nextMediaItemIndex) ?: return false
        return a.album.isNotBlank() && a.album == b.album && a.albumKey == b.albumKey
    }
}
