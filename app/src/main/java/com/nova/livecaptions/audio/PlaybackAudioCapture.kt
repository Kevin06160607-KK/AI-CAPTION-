package com.nova.livecaptions.audio

import android.media.*
import android.media.projection.MediaProjection

/** Captures only app playback explicitly allowed by Android; this never opens a microphone input. */
class PlaybackAudioCapture(private val projection: MediaProjection) {
    fun createRecorder(): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(CAPTURE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val minimum = AudioRecord.getMinBufferSize(CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "This device does not support playback audio capture." }
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum, CAPTURE_RATE * 2))
            .setAudioPlaybackCaptureConfig(config)
            .build().also { check(it.state == AudioRecord.STATE_INITIALIZED) { "Android could not open the permitted playback stream." } }
    }

    companion object { const val CAPTURE_RATE = 48_000; const val SPEECH_RATE = 16_000 }
}
