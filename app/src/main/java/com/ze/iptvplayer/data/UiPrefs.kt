package com.ze.iptvplayer.data

import android.content.Context

enum class ChannelViewMode { LIST, GRID }
enum class LongPressAction { SHOW_MENU, QUICK_FAVORITE }

/** Persists the user's preferred default channel view (list/grid) and long-press behavior. */
object UiPrefs {
    private const val PREFS_NAME = "iptv_ui_prefs"
    private const val KEY_VIEW_MODE = "view_mode"
    private const val KEY_THEME = "theme"
    private const val KEY_TUTORIAL = "fullscreen_tutorial_seen"
    private const val KEY_BANNER = "vod_banner"
    private const val KEY_PINNED = "pinned_player"
    private const val KEY_LONG_PRESS_ACTION = "long_press_action"

    fun getDefaultViewMode(context: Context): ChannelViewMode {
        val raw = prefs(context).getString(KEY_VIEW_MODE, null)
        return runCatching { ChannelViewMode.valueOf(raw ?: "") }.getOrDefault(ChannelViewMode.LIST)
    }

    fun setDefaultViewMode(context: Context, mode: ChannelViewMode) {
        prefs(context).edit().putString(KEY_VIEW_MODE, mode.name).apply()
    }

    fun getLongPressAction(context: Context): LongPressAction {
        val raw = prefs(context).getString(KEY_LONG_PRESS_ACTION, null)
        return runCatching { LongPressAction.valueOf(raw ?: "") }.getOrDefault(LongPressAction.SHOW_MENU)
    }

    fun setLongPressAction(context: Context, action: LongPressAction) {
        prefs(context).edit().putString(KEY_LONG_PRESS_ACTION, action.name).apply()
    }

    fun getTheme(context: Context): String = prefs(context).getString(KEY_THEME, null) ?: "classic"

    fun setTheme(context: Context, id: String) {
        prefs(context).edit().putString(KEY_THEME, id).apply()
    }

    fun getBanner(context: Context): Boolean = prefs(context).getBoolean(KEY_BANNER, false)
    fun setBanner(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY_BANNER, on).apply() }

    fun getPinnedPlayer(context: Context): Boolean = prefs(context).getBoolean(KEY_PINNED, false)
    fun setPinnedPlayer(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY_PINNED, on).apply() }

    fun getTutorialSeen(context: Context): Boolean = prefs(context).getBoolean(KEY_TUTORIAL, false)
    fun setTutorialSeen(context: Context, seen: Boolean) { prefs(context).edit().putBoolean(KEY_TUTORIAL, seen).apply() }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
