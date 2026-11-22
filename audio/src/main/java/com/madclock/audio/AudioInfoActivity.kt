package com.madclock.audio

import android.app.Activity
import android.os.Bundle

/** Public launcher screen. No Intent extras, playback controls, or bound services. */
class AudioInfoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_info)
    }
}
