package com.vayunmathur.updater.platform

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.updater.domain.UpdateMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ViewModel for the updater screen: current build, check state and settings. */
class UpdaterViewModel(application: Application) : AndroidViewModel(application) {

    private val store = DataStoreUtils.getInstance(application)

    var state by mutableStateOf(UpdaterUiState())
        private set

    init {
        viewModelScope.launch {
            state = state.copy(
                currentBuild = SystemBuild.current()?.build.orEmpty(),
                autoInstall = store.getBooleanAwait(UpdaterPreferences.AUTO_INSTALL, default = true),
                meteredAllowed = store.getBooleanAwait(
                    UpdaterPreferences.METERED_ALLOWED,
                    default = false,
                ),
                lastCheckedMillis = store.getLongAwait(UpdaterPreferences.LAST_CHECKED) ?: 0L,
            )
        }
    }

    fun checkNow() {
        if (state.checking) return
        state = state.copy(checking = true, lastFailure = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.check() }
            val now = System.currentTimeMillis()
            store.setLong(UpdaterPreferences.LAST_CHECKED, now)
            state = when (result) {
                is UpdateChecker.Result.Available -> state.copy(
                    checking = false,
                    available = result.metadata,
                    lastCheckedMillis = now,
                )

                UpdateChecker.Result.UpToDate -> state.copy(
                    checking = false,
                    available = null,
                    lastCheckedMillis = now,
                )

                is UpdateChecker.Result.Failed -> state.copy(
                    checking = false,
                    lastFailure = result.reason,
                    lastCheckedMillis = now,
                )
            }
        }
    }

    fun setAutoInstall(enabled: Boolean) {
        state = state.copy(autoInstall = enabled)
        viewModelScope.launch { store.setBoolean(UpdaterPreferences.AUTO_INSTALL, enabled) }
    }

    fun setMeteredAllowed(allowed: Boolean) {
        state = state.copy(meteredAllowed = allowed)
        viewModelScope.launch { store.setBoolean(UpdaterPreferences.METERED_ALLOWED, allowed) }
    }
}

/** UI state for the updater screen, including the available update, if any. */
data class UpdaterUiState(
    /** `ro.build.version.incremental`, or empty when it could not be read. */
    val currentBuild: String = "",
    val checking: Boolean = false,
    /** Non-null when a newer build exists. */
    val available: UpdateMetadata? = null,
    /** Epoch millis, or 0 when never checked. */
    val lastCheckedMillis: Long = 0L,
    /**
     * Why the last check failed, or null. Shown verbatim rather than flattened to "try again":
     * "could not reach https://..." and "server did not return a metadata line" are different
     * problems and the second one is ours, not the network's.
     */
    val lastFailure: String? = null,
    val autoInstall: Boolean = true,
    val meteredAllowed: Boolean = false,
)
