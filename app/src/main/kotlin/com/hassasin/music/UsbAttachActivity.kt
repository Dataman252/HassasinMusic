package com.hassasin.music

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Opened by Android when a USB DAC is plugged in and the user picks this app. Opening it is what
 * grants USB access to that DAC. It shows nothing and closes at once; the player notices the DAC
 * within a second and moves the sound to it.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("UsbAttach", "USB DAC plugged in; access granted by the plug-in offer")
        Playback.onDacMaybeChanged()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
