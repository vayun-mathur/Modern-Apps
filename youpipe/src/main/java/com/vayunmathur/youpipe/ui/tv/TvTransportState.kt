package com.vayunmathur.youpipe.ui.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Whether the player controls are on screen.
 *
 * Owned here rather than in a composable so the activity-level [TvDpadInput] key map can read
 * and reveal it without a composition: any press brings the controls up and re-arms auto-hide.
 */
object TvTransportState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _visible = MutableStateFlow(true)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private var hideJob: Job? = null

    /** True while the user has asked controls to stay up (accessibility keep-visible). */
    var pinned: Boolean = false

    val controlsHidden: Boolean get() = !_visible.value && !pinned

    /** Bring the controls up and re-arm the auto-hide timer. */
    fun reveal() {
        _visible.value = true
        hideJob?.cancel()
        hideJob = null
        if (pinned) return
        hideJob = scope.launch {
            delay(AUTO_HIDE_MS)
            // A scrub in progress is an interaction, however long the key has been held.
            if (TvDpadInput.scrubPreviewMs == null) _visible.value = false
        }
    }

    /** Put the controls away now (BACK, or the player leaving). Cancels any pending scrub. */
    fun hide() {
        hideJob?.cancel()
        hideJob = null
        TvDpadInput.cancelScrub()
        TvDpadInput.seekFocused = false
        _visible.value = false
    }

    private const val AUTO_HIDE_MS = 5_000L
}
