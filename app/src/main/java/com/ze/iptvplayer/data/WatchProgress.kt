package com.ze.iptvplayer.data

import android.content.Context

/**
 * Remembers how far you got in each movie / episode (keyed by the entry's stream URL, like
 * favorites), so playback can resume and the app can show progress bars and "Continue watching".
 */
object WatchProgress {
    private const val PREFS_NAME = "watch_progress"
    private const val MAX_ENTRIES = 400
    private const val MIN_SAVE_MS = 10_000L

    data class Entry(val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
        val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * 0.95
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun parse(raw: Any?): Entry? {
        val parts = (raw as? String)?.split(',') ?: return null
        if (parts.size != 3) return null
        return Entry(
            parts[0].toLongOrNull() ?: return null,
            parts[1].toLongOrNull() ?: return null,
            parts[2].toLongOrNull() ?: return null
        )
    }

    fun get(context: Context, key: String): Entry? = parse(prefs(context).getString(key, null))

    fun all(context: Context): Map<String, Entry> {
        val result = HashMap<String, Entry>()
        for ((key, value) in prefs(context).all) parse(value)?.let { result[key] = it }
        return result
    }

    /** Unfinished entries, most recently watched first. */
    fun recent(context: Context): List<Pair<String, Entry>> =
        all(context).entries
            .filter { !it.value.finished }
            .sortedByDescending { it.value.updatedAt }
            .map { it.key to it.value }

    fun save(context: Context, key: String, positionMs: Long, durationMs: Long) {
        // Skip the first few seconds — nobody wants "resume at 0:03".
        if (durationMs <= 0 || positionMs < MIN_SAVE_MS) return
        prefs(context).edit().putString(key, "$positionMs,$durationMs,${System.currentTimeMillis()}").apply()
        if (prefs(context).all.size > MAX_ENTRIES) prune(context)
    }

    fun markWatched(context: Context, key: String) {
        prefs(context).edit().putString(key, "1,1,${System.currentTimeMillis()}").apply()
    }

    fun clear(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }

    private fun prune(context: Context) {
        val all = all(context)
        if (all.size <= MAX_ENTRIES) return
        val editor = prefs(context).edit()
        all.entries.sortedBy { it.value.updatedAt }.take(all.size - MAX_ENTRIES).forEach { editor.remove(it.key) }
        editor.apply()
    }
}
