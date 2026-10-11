package com.ze.iptvplayer.util

/**
 * Pulls a clean title / year / season / episode out of the raw entry names providers use, e.g.
 * "O Último Farol (2021)" or "Rota Noturna S01E02 - O Mapa Rasgado". Display only: what is a
 * movie and what is a series episode is decided by ContentTypeClassifier, with the same patterns.
 */
object MediaTitles {

    data class Movie(val title: String, val year: String?)

    data class Episode(
        val showTitle: String,
        val showYear: String?,
        val season: Int,
        val number: Int,
        val title: String
    )

    private val yearRegex = Regex("\\s*[(\\[]((?:19|20)\\d{2})[)\\]]\\s*$")
    private val episodeRegex = Regex(
        "\\b(?:s(\\d{1,2})\\s?e(\\d{1,3})|t(\\d{1,2})\\s?e(\\d{1,3})|(\\d{1,2})x(\\d{1,3}))\\b",
        RegexOption.IGNORE_CASE
    )
    private val separators = charArrayOf('-', '–', '—', ':', '|', '.', ' ')

    fun movie(name: String): Movie {
        val trimmed = name.trim()
        val match = yearRegex.find(trimmed) ?: return Movie(trimmed, null)
        val title = trimmed.substring(0, match.range.first).trim(*separators).ifEmpty { trimmed }
        return Movie(title, match.groupValues[1])
    }

    /** Null when [name] has no season/episode tag. */
    fun episode(name: String): Episode? {
        val trimmed = name.trim()
        val match = episodeRegex.find(trimmed) ?: return null
        val g = match.groupValues
        val season = (g[1].ifEmpty { g[3].ifEmpty { g[5] } }).toIntOrNull() ?: return null
        val number = (g[2].ifEmpty { g[4].ifEmpty { g[6] } }).toIntOrNull() ?: return null

        var showPart = trimmed.substring(0, match.range.first).trim(*separators)
        var year: String? = null
        yearRegex.find(showPart)?.let {
            year = it.groupValues[1]
            showPart = showPart.substring(0, it.range.first).trim(*separators)
        }
        val episodeTitle = trimmed.substring(match.range.last + 1).trim(*separators)
        return Episode(showPart.ifEmpty { trimmed }, year, season, number, episodeTitle)
    }

    fun episodeCode(season: Int, number: Int): String =
        String.format(java.util.Locale.US, "S%02dE%02d", season, number)
}
