package com.aitamil.livesubtitles

import android.content.Context
import android.icu.text.Transliterator
import com.google.mlkit.nl.translate.*
import kotlinx.coroutines.tasks.await
import org.json.JSONArray

enum class SubtitleMode(val title: String) {
    TAMIL("\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD"), THANGLISH("Thanglish"), ENGLISH("English")
}

data class SubtitleLine(val original: String, val tamil: String, val english: String, val time: Long = System.currentTimeMillis())

object SubtitleRepository {
    private val prefsName = "subtitle_history"

    fun save(context: Context, line: SubtitleLine) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val items = JSONArray(prefs.getString("items", "[]"))
        items.put(org.json.JSONObject().put("original", line.original).put("tamil", line.tamil).put("english", line.english).put("time", line.time))
        while (items.length() > 300) items.remove(0)
        prefs.edit().putString("items", items.toString()).apply()
    }

    fun history(context: Context): List<SubtitleLine> {
        val arr = JSONArray(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString("items", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SubtitleLine(o.optString("original"), o.optString("tamil"), o.optString("english"), o.optLong("time"))
        }.reversed()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().remove("items").apply()
    }

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

    fun thanglish(tamil: String): String = try {
        Transliterator.getInstance("Tamil-Latin").transliterate(tamil).replace(Regex("\\s+"), " ").trim()
    } catch (_: Exception) { tamil }
}
