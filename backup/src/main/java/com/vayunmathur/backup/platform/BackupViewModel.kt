package com.vayunmathur.backup.platform

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.backup.data.backend.BackendFactory
import com.vayunmathur.backup.data.backend.BackupRepository
import com.vayunmathur.backup.domain.crypto.Bip39
import com.vayunmathur.backup.domain.crypto.Crypto
import com.vayunmathur.backup.platform.crypto.KeyManager
import com.vayunmathur.backup.data.BackupConfig
import com.vayunmathur.backup.data.BackupSettings
import com.vayunmathur.backup.data.files.FileBackupManager
import com.vayunmathur.backup.platform.FileBackupWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Aggregate UI state for the Backup app. */
data class BackupUiState(
    val loading: Boolean = false,
    val settings: BackupSettings = BackupSettings(),
    val hasKey: Boolean = false,
    /** A freshly generated recovery code awaiting user confirmation. */
    val generatedCode: List<String> = emptyList(),
    val message: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
) {
    val onboarded: Boolean get() = hasKey && settings.isConfigured
}

/** ViewModel holding backup configuration, key state and one-shot UI messages. */
class BackupViewModel(app: Application) : AndroidViewModel(app) {
    private val config = BackupConfig(app)
    private val keyManager = KeyManager(app)

    var state by mutableStateOf(BackupUiState(hasKey = keyManager.hasMasterKey()))
        private set

    init {
        config.settings
            .onEach { state = state.copy(settings = it) }
            .launchIn(viewModelScope)
    }

    // --- Recovery code ---

    fun generateRecoveryCode() {
        state = state.copy(generatedCode = Bip39.generate(), error = null, message = null)
    }

    /** Confirms the generated code (or a re-typed one) and derives + stores the master key. */
    fun confirmNewCode(words: List<String>) = storeCode(words, "Recovery code saved. Backups are now encrypted.")

    /** Restores a master key from an existing 12-word code. */
    fun restoreWithCode(words: List<String>) =
        storeCode(words, "Recovery code accepted. You can now restore your backups.")

    private fun storeCode(words: List<String>, successMessage: String) {
        val cleaned = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (!Bip39.isValid(cleaned)) {
            state = state.copy(error = "That is not a valid 12-word recovery code.")
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                keyManager.storeSeed(Bip39.mnemonicToSeed(cleaned))
            }
            state = state.copy(hasKey = true, generatedCode = emptyList(), error = null, message = successMessage)
        }
    }

    // --- Backend selection ---

    fun setSafBackend(treeUri: String) {
        viewModelScope.launch {
            config.setSafBackend(treeUri)
            state = state.copy(message = "Backup folder selected.", error = null)
        }
    }

    fun setWebDavBackend(url: String, user: String, password: String) {
        if (url.isBlank()) {
            state = state.copy(error = "Enter a WebDAV URL.")
            return
        }
        viewModelScope.launch {
            config.setWebDavBackend(url.trim(), user.trim(), password)
            state = state.copy(message = "WebDAV destination saved.", error = null)
        }
    }

    fun setAppBackupEnabled(enabled: Boolean) {
        viewModelScope.launch { config.setAppBackupEnabled(enabled) }
    }

    fun setFileBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            config.setFileBackupEnabled(enabled)
            if (enabled) FileBackupWorker.schedule(getApplication())
        }
    }

    // --- File/media backup + restore ---

    fun backupFilesNow() = withRepo("Backing up files…") { repo ->
        val count = FileBackupManager(getApplication(), repo).backupAll()
        config.setLastRun(System.currentTimeMillis())
        "Backed up $count files."
    }

    fun restoreFilesNow() = withRepo("Restoring files…") { repo ->
        val count = FileBackupManager(getApplication(), repo).restoreAll()
        "Restored $count files to the app's storage folder."
    }

    private fun withRepo(busyMessage: String, block: suspend (BackupRepository) -> String) {
        state = state.copy(busy = true, error = null, message = busyMessage)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val settings = state.settings
                    val backend = BackendFactory.create(getApplication(), settings)
                        ?: error("No backup destination configured.")
                    if (!keyManager.hasMasterKey()) error("No recovery code set.")
                    block(BackupRepository(backend, Crypto(keyManager.getMasterKey())))
                }
            }
            state = outcome.fold(
                onSuccess = { state.copy(busy = false, message = it, error = null) },
                onFailure = { state.copy(busy = false, message = null, error = it.message ?: "Operation failed") },
            )
        }
    }

    fun dismissMessages() {
        state = state.copy(message = null, error = null)
    }
}
