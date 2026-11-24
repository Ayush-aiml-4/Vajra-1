package com.vajra.companion.identity

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists genuine local security & command execution events (never fabricated/mock data)
 * for display on the VAJRA Companion dashboard.
 */
object DeviceActivityLog {

    private const val PREFS_NAME = "vajra_activity_log"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 25

    data class Entry(
        val timestampMs: Long,
        val category: String,
        val status: String,
        val title: String,
        val detail: String
    )

    @Synchronized
    fun record(
        context: Context,
        category: String,
        status: String,
        title: String,
        detail: String
    ) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existingRaw = prefs.getString(KEY_ENTRIES, "[]") ?: "[]"
            val arr = try {
                JSONArray(existingRaw)
            } catch (_: Exception) {
                JSONArray()
            }

            val updated = JSONArray()
            updated.put(
                JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("category", category)
                    .put("status", status)
                    .put("title", title)
                    .put("detail", detail)
            )
            val keepCount = minOf(arr.length(), MAX_ENTRIES - 1)
            for (i in 0 until keepCount) {
                updated.put(arr.get(i))
            }
            prefs.edit().putString(KEY_ENTRIES, updated.toString()).apply()
        } catch (_: Exception) {
            // Never crash worker/UI over telemetry log persistence
        }
    }

    @Synchronized
    fun list(context: Context, limit: Int = 8): List<Entry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingRaw = prefs.getString(KEY_ENTRIES, "[]") ?: "[]"
        val arr = try {
            JSONArray(existingRaw)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<Entry>(minOf(limit, arr.length()))
        val max = minOf(limit, arr.length())
        for (i in 0 until max) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(
                Entry(
                    timestampMs = obj.optLong("ts", 0L),
                    category = obj.optString("category", "SECURITY"),
                    status = obj.optString("status", "INFO"),
                    title = obj.optString("title", ""),
                    detail = obj.optString("detail", "")
                )
            )
        }
        return out
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
