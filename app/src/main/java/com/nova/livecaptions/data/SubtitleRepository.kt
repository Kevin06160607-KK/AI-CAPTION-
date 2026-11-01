package com.nova.livecaptions.data

import android.content.Context
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

}
