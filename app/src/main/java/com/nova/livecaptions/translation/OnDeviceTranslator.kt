package com.nova.livecaptions.translation

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

object OnDeviceTranslator {
    suspend fun translate(text: String, sourceTag: String, targetTag: String): String {
        if (sourceTag == targetTag) return text
        val source = TranslateLanguage.fromLanguageTag(sourceTag) ?: return text
        val target = TranslateLanguage.fromLanguageTag(targetTag) ?: return text
        val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        return try {
            client.downloadModelIfNeeded().await()
            client.translate(text).await()
        } finally { client.close() }
    }
}
