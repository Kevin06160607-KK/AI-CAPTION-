package com.nova.livecaptions.speech

interface SpeechEngine : AutoCloseable {
    fun transcribe(samples: FloatArray, language: String): String
}
