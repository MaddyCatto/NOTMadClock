package com.madclock.audio

import android.content.res.Resources
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.madclock.ipc.SleepSound
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The supplied loops are 30s, stereo PCM16 at 48kHz, with a canonical WAV header. */
internal object SleepPcm {
    private const val SAMPLE_RATE = 48_000
    private const val FRAME_BYTES = 4
    const val FRAMES = SAMPLE_RATE * 30
    private const val SAMPLE_BYTES = FRAMES * FRAME_BYTES
    private val wavHeader = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(SAMPLE_BYTES + 36)
        .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(2)
        .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * FRAME_BYTES).putShort(FRAME_BYTES.toShort()).putShort(16)
        .put("data".toByteArray(Charsets.US_ASCII)).putInt(SAMPLE_BYTES).array()

    fun resource(sound: SleepSound) = when (sound) {
        SleepSound.WHITE -> R.raw.sleep_01_white_30s_seamless
        SleepSound.PINK -> R.raw.sleep_02_pink_30s_seamless
        SleepSound.BROWN -> R.raw.sleep_03_brown_30s_seamless
        SleepSound.BLUE -> R.raw.sleep_04_blue_30s_seamless
        SleepSound.VIOLET -> R.raw.sleep_05_violet_30s_seamless
        SleepSound.GRAY -> R.raw.sleep_06_gray_approx_30s_seamless
        SleepSound.GREEN -> R.raw.sleep_07_green_midrange_30s_seamless
        SleepSound.SOFT_WHITE -> R.raw.sleep_08_soft_white_30s_seamless
        SleepSound.WARM_PINK -> R.raw.sleep_09_warm_pink_30s_seamless
        SleepSound.DEEP_BROWN -> R.raw.sleep_10_deep_brown_30s_seamless
    }

    fun read(resources: Resources, sound: SleepSound): ByteArray =
        DataInputStream(resources.openRawResource(resource(sound))).use { input ->
            val header = ByteArray(wavHeader.size).also(input::readFully)
            check(header.contentEquals(wavHeader))
            ByteArray(SAMPLE_BYTES).also(input::readFully).also { check(input.read() == -1) }
        }

    fun track(samples: ByteArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size)
            .build()
        try {
            check(track.write(ByteBuffer.wrap(samples), samples.size, AudioTrack.WRITE_BLOCKING) == samples.size)
            // AudioFlinger wraps the buffer at its last frame without a decoder seek or a Java refill.
            check(track.setLoopPoints(0, FRAMES, -1) == AudioTrack.SUCCESS)
            return track
        } catch (e: RuntimeException) {
            track.release()
            throw e
        }
    }
}
