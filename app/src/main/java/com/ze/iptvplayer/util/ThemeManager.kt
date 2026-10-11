package com.ze.iptvplayer.util

import android.app.Activity
import android.content.Context
import android.graphics.Color
import androidx.annotation.AttrRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.google.android.material.color.MaterialColors
import com.ze.iptvplayer.R
import com.ze.iptvplayer.data.UiPrefs

/**
 * The app's colour themes. Every screen applies the chosen theme in onCreate (before super), and
 * all colours come from theme attributes (z*), so switching is just "save + recreate".
 * [preview] is bg, surface, accent — only used to paint the little cards in More → Theme.
 */
object ThemeManager {

    class AppTheme(
        val id: String,
        @StringRes val nameRes: Int,
        @StyleRes val style: Int,
        @StyleRes val playerStyle: Int,
        val previewBg: Int,
        val previewSurface: Int,
        val previewAccent: Int
    )

    val all: List<AppTheme> = listOf(
        AppTheme("classic", R.string.theme_classic, R.style.Theme_ZecaTV, R.style.Theme_ZecaTV_Player, 0xFF111318.toInt(), 0xFF1A1D24.toInt(), 0xFF1565C0.toInt()),
        AppTheme("cinema", R.string.theme_cinema, R.style.Theme_ZecaTVCinema, R.style.Theme_ZecaTVCinemaPlayer, 0xFF0B0D12.toInt(), 0xFF151922.toInt(), 0xFFFF5A4E.toInt()),
        AppTheme("clean", R.string.theme_clean_light, R.style.Theme_ZecaTVCleanLight, R.style.Theme_ZecaTVCleanLightPlayer, 0xFFF2F5F9.toInt(), 0xFFFFFFFF.toInt(), 0xFF2F5BFF.toInt()),
        AppTheme("neon", R.string.theme_neon, R.style.Theme_ZecaTVNeon, R.style.Theme_ZecaTVNeonPlayer, 0xFF000000.toInt(), 0xFF0E0E0E.toInt(), 0xFFC6FF3D.toInt()),
        AppTheme("amoled", R.string.theme_amoled, R.style.Theme_ZecaTVAmoled, R.style.Theme_ZecaTVAmoledPlayer, 0xFF000000.toInt(), 0xFF0C0C0C.toInt(), 0xFF3B82F6.toInt()),
        AppTheme("midnight", R.string.theme_midnight, R.style.Theme_ZecaTVMidnight, R.style.Theme_ZecaTVMidnightPlayer, 0xFF0E0B1A.toInt(), 0xFF17132A.toInt(), 0xFF8B5CF6.toInt()),
        AppTheme("forest", R.string.theme_forest, R.style.Theme_ZecaTVForest, R.style.Theme_ZecaTVForestPlayer, 0xFF0C1411.toInt(), 0xFF131E1A.toInt(), 0xFF2FA36B.toInt()),
        AppTheme("amber", R.string.theme_amber, R.style.Theme_ZecaTVAmber, R.style.Theme_ZecaTVAmberPlayer, 0xFF14100C.toInt(), 0xFF1E1812.toInt(), 0xFFF59E0B.toInt())
    )

    fun current(context: Context): AppTheme {
        val id = UiPrefs.getTheme(context)
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    fun set(context: Context, id: String) = UiPrefs.setTheme(context, id)

    /** Call at the very top of onCreate, before super.onCreate(). The player screen always uses
     *  dark surfaces (it sits on video) but keeps the chosen accent. */
    fun apply(activity: Activity, player: Boolean = false) {
        val theme = current(activity)
        activity.setTheme(if (player) theme.playerStyle else theme.style)
    }

    fun color(context: Context, @AttrRes attr: Int): Int = MaterialColors.getColor(context, attr, Color.MAGENTA)
}
