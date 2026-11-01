package com.nova.livecaptions.transliteration

import android.icu.text.Transliterator

object TamilTransliterator {
    fun toThanglish(text: String): String = runCatching {
        Transliterator.getInstance("Tamil-Latin").transliterate(text).replace(Regex("\\s+"), " ").trim()
    }.getOrDefault(text)
}
