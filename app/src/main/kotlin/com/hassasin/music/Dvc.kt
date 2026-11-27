package com.hassasin.music

import android.content.Context
import android.media.AudioManager
import kotlin.math.pow

/**
 * Direct Volume Control: the volume is applied by the app, in 64-bit, and Android's own media volume
 * stays at its maximum. The phone's volume keys and slider move the app's volume (through the media
 * session, also with the screen off). Benefits: one clean digital volume stage, headroom for the EQ,
 * and a working volume on outputs that ignore Android's volume (the phone's direct output) — the cause
 * of "plays at max volume" in other players. Not used for the USB DAC, which has its own volume.
 */
object Dvc {
    const val STEPS = 30
    const val DB_PER_STEP = 2.0

    fun on(): Boolean = AppState.settings.value.dvc && !(UsbLive.active && UsbLive.kind == "usb")

    fun step(): Int = AppState.settings.value.dvcStep.coerceIn(0, STEPS)

    /** The app volume in dB: 0 at the top step, −60 dB at step 1, silence at 0. */
    fun db(step: Int = step()): Double = if (step <= 0) -120.0 else -(STEPS - step) * DB_PER_STEP

    /** Linear gain the player applies (1 when DVC is off). */
    fun gain(): Float = if (!on()) 1f else 10.0.pow(db() / 20.0).toFloat()

    fun setStep(s: Int) {
        val v = s.coerceIn(0, STEPS)
        if (v == step()) return
        AppState.update { it.copy(dvcStep = v) }
        applyNow()
    }

    /** Pushes the volume to the player right away (crossfade keeps doing it on every tick). */
    fun applyNow() {
        if (Playback.isReady()) Playback.main { Crossfade.apply(Playback.player) }
        Playback.onDvcChanged()
    }

    /** Turning DVC on: Android's media volume goes to its maximum and the app volume starts at a safe level. */
    fun enable(context: Context, on: Boolean) {
        if (on) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0) }
            AppState.update { it.copy(dvc = true, dvcStep = minOf(it.dvcStep, 20)) }
        } else AppState.update { it.copy(dvc = false) }
        applyNow()
    }

    fun label(): String = if (step() <= 0) "Muted" else "%.0f dB".format(db())
}
