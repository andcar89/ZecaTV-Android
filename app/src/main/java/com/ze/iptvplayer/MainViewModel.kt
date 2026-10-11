package com.ze.iptvplayer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class LoadState {
    object Idle : LoadState()
    object Loading : LoadState()

    /** [generation] changes every time channel data was (re)loaded, so screens know to rebuild. */
    data class Ready(val generation: Int) : LoadState()
    data class Error(val message: String) : LoadState()
    object NoPlaylist : LoadState()
}

/**
 * Lets AddSourceActivity (which loads a playlist itself, to validate it) tell the main screen
 * which playlist the in-memory [PlaylistRepository] currently holds, so it doesn't load it twice.
 */
object ActivePlaylistTracker {
    @Volatile
    var loadedId: String? = null

    /** Set when the loaded data was replaced behind the main screen's back (playlist added/edited). */
    @Volatile
    var dirty: Boolean = false
}

/** Owns playlist loading for the whole main screen — shared by every tab. */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<LoadState>(LoadState.Idle)
    val state: StateFlow<LoadState> = _state.asStateFlow()

    /** Live-list scroll position, kept here so it survives the screen being rebuilt (e.g. after the
     *  landscape fullscreen player rotates the phone back). Only valid for [liveScrollGeneration]. */
    var liveScrollGeneration = -1
    var liveScrollPos = 0
    var liveScrollOffset = 0

    private var generation = 0
    private var failedId: String? = null

    /**
     * Brings the loaded data in line with whatever playlist is active in [Prefs]. Cheap when
     * nothing changed; called whenever the main screen comes to the foreground.
     */
    fun syncWithPrefs() {
        val ctx = getApplication<Application>()
        val active = Prefs.activePlaylist(ctx)
        if (active == null) {
            _state.value = LoadState.NoPlaylist
            return
        }
        val current = _state.value
        if (current is LoadState.Loading) return

        val loaded = ActivePlaylistTracker.loadedId == active.id && PlaylistRepository.channels.isNotEmpty()
        if (!loaded) {
            // Don't hammer a failing source every time the screen resumes — retry is explicit.
            if (current is LoadState.Error && failedId == active.id && !ActivePlaylistTracker.dirty) return
            reload()
            return
        }
        if (ActivePlaylistTracker.dirty || current !is LoadState.Ready) {
            ActivePlaylistTracker.dirty = false
            generation++
            _state.value = LoadState.Ready(generation)
        }
    }

    /** (Re)loads the active playlist from its source and EPG. */
    fun reload() {
        val ctx = getApplication<Application>()
        val playlist = Prefs.activePlaylist(ctx)
        if (playlist == null) {
            _state.value = LoadState.NoPlaylist
            return
        }
        _state.value = LoadState.Loading
        viewModelScope.launch {
            val result = PlaylistRepository.load(ctx, playlist.source)
            result.onSuccess {
                ActivePlaylistTracker.loadedId = playlist.id
                ActivePlaylistTracker.dirty = false
                failedId = null
                generation++
                _state.value = LoadState.Ready(generation)
            }.onFailure { e ->
                failedId = playlist.id
                _state.value = LoadState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun switchPlaylist(id: String) {
        val ctx = getApplication<Application>()
        Prefs.setActivePlaylistId(ctx, id)
        reload()
    }
}
