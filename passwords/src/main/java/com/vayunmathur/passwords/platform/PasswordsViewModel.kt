package com.vayunmathur.passwords.platform

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.domain.ImportSource
import com.vayunmathur.passwords.domain.PasskeyLink
import com.vayunmathur.passwords.domain.toColumn
import com.vayunmathur.passwords.sync.KdbxSyncScheduler
import com.vayunmathur.passwords.sync.KdbxSyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Passwords app.
 *
 * Owns:
 *  - Central TOTP ticker as a single shared [StateFlow] (replaces per-row
 *    [androidx.compose.runtime.LaunchedEffect] that ticked once per row).
 *  - Bitwarden-style CSV import (content-resolver read + parse + per-row upsert).
 *  - Edit-form draft state for [Password] (decoupled from composable lifetime).
 *  - Copy-to-clipboard actions, with a [SharedFlow] for one-shot "copied" events.
 *
 * Uses [PasswordRepository] for all persistence. Exposes the password list
 * as a [StateFlow] and provides Composable helpers for per-row reads and
 * editable bindings.
 */
class PasswordsViewModel(
    application: Application,
    private val repository: PasswordRepository,
) : AndroidViewModel(application), PasswordsActions {

    /** Exposed so SettingsPage can build KdbxBackupFormat without exposing DAOs. */
    fun buildBackupFormat(): com.vayunmathur.library.util.BackupFormat =
        KdbxBackupFormat(repository)

    // -- Data -------------------------------------------------------------

    /**
     * False until the vault's first rows have arrived, so the list can tell "still opening the
     * database" apart from "you have no passwords" - which look identical when both are an empty
     * list, and the difference is several seconds of blank screen on launch.
     */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    // `flow { emitAll(...) }` rather than `repository.passwords` directly: the property getter
    // touches the lazy database, and evaluating it here would build it - loading the SQLCipher
    // native library, unwrapping the Keystore passphrase and deriving the page key - on whichever
    // thread constructed this ViewModel, which is the main thread during composition. Wrapping it
    // defers that to first collection, and flowOn puts that collection on IO.
    val passwords: StateFlow<List<Password>> = flow { emitAll(repository.passwords) }
        .onEach { _loaded.value = true }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    val passkeys: StateFlow<List<Passkey>> = flow { emitAll(repository.passkeys) }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    fun deletePasskey(passkey: Passkey) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deletePasskey(passkey)
            requestSync()
        }
    }

    override fun setPasskeyLink(passkey: Passkey, link: PasskeyLink) {
        viewModelScope.launch(Dispatchers.IO) {
            // upsertPasskey, not upsertPasskeyRaw: the sync only notices the change if updatedAt
            // moves.
            repository.upsertPasskey(passkey.copy(linkedPasswordSyncId = link.toColumn()))
            requestSync()
        }
    }

    fun upsert(password: Password, onSaved: ((Long) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val newId = repository.upsertPassword(password)
            onSaved?.invoke(newId)
            requestSync()
        }
    }

    override fun delete(password: Password) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deletePassword(password)
            requestSync()
        }
    }

    private suspend fun requestSync() {
        val ctx = getApplication<Application>()
        if (KdbxSyncSettings.enabled(ctx)) KdbxSyncScheduler.scheduleDebounced(ctx)
    }

    /**
     * Returns a [State] tracking the password with [initialId]. If not yet
     * loaded (or absent), returns [default]. Recomposes when the underlying
     * list changes.
     */
    @Composable
    fun passwordState(initialId: Long, default: () -> Password = { Password() }): State<Password> {
        val list by passwords.collectAsState()
        return remember(initialId) {
            derivedStateOf { list.firstOrNull { it.id == initialId } ?: default() }
        }
    }

    // -- TOTP ticker ------------------------------------------------------

    /**
     * Wall-clock millis, ticked once per second. The flow is shared across
     * every TOTP row so we don't allocate a coroutine per row. It is
     * stopped via [SharingStarted.WhileSubscribed] when no composable is
     * observing, matching the previous per-row `LaunchedEffect` behavior
     * which cancelled on leaving composition.
     */
    val tickerFlow: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(1000),
        System.currentTimeMillis(),
    )

    // -- Clipboard --------------------------------------------------------

    private val _copyEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Emits a short label (e.g. "Password copied") for snackbar feedback. */
    override val copyEvents: SharedFlow<String> = _copyEvents.asSharedFlow()

    // Default arguments live on the PasswordsActions declaration; an override may not
    // repeat them.
    override fun copyToClipboard(label: String, text: String, feedback: String?) {
        val ctx = getApplication<Application>()
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        // Tells the system UI (and any keyboard with a clipboard history) not to show this
        // in the clear. Android 13+; older releases have no way to say it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        if (feedback != null) {
            viewModelScope.launch { _copyEvents.emit(feedback) }
        }
    }

    // -- Edit-form draft --------------------------------------------------

    private val _draft = MutableStateFlow<Password?>(null)
    /** Currently-edited password draft, or null if no edit in progress. */
    val draft: StateFlow<Password?> = _draft.asStateFlow()

    /**
     * Initialize the draft from the persisted [seed] the first time the edit
     * page is shown for a given id. Subsequent calls with the same id are
     * ignored so user edits are not clobbered.
     */
    fun initDraft(seed: Password) {
        val current = _draft.value
        if (current == null || current.id != seed.id) {
            _draft.value = seed
        }
    }

    override fun updateDraft(transform: (Password) -> Password) {
        _draft.value = _draft.value?.let(transform)
    }

    fun clearDraft() {
        _draft.value = null
    }

    /**
     * Persists the current draft. For new rows, the assigned id is reported
     * via [onSaved]. Clears the draft once enqueued.
     */
    override fun saveDraft(onSaved: ((Long) -> Unit)?) {
        val current = _draft.value ?: return
        upsert(current, onSaved)
        _draft.value = null
    }

    // -- CSV import -------------------------------------------------------

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    fun importCsv(uri: Uri, source: ImportSource = ImportSource.BITWARDEN) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            _importing.value = true
            _importMessage.value = null
            // Best-effort: persist read access for potential re-reads. Not
            // required for this one-shot import, so failure must not abort it.
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {}
            try {
                val result = withContext(Dispatchers.IO) {
                    importCsvFromUri(ctx.contentResolver, uri, source)
                }
                _importMessage.value =
                    "Imported ${result.inserted} rows, skipped ${result.skipped} rows"
            } catch (expected: IllegalStateException) {
                _importMessage.value = "Import failed: ${expected.message}"
            } finally {
                _importing.value = false
            }
        }
    }

    private data class ImportResult(val inserted: Int, val skipped: Int)

    // Visible for testing (return type of [resolveColumns]).
    internal data class ImportColumns(
        val name: Int,
        val username: Int,
        val password: Int,
        val url: Int,
        val totp: Int,
        val email: Int,
        val note: Int,
        val type: Int,
    )

    // internal (not private) so unit tests can pin the header mapping: an
    // end-to-end import cannot run under unit tests (the SQLCipher native
    // library is unavailable there), and the BOM regression lives in this mapping.
    internal fun resolveColumns(header: List<String>, source: ImportSource): ImportColumns {
        // A UTF-8 BOM on the file lands on the first header cell, and trim()
        // does not remove it — so strip it before matching, or the first
        // column of BOM'd exports silently maps to nothing.
        val clean = header.map { it.removePrefix("\uFEFF") }
        fun findCol(vararg names: String): Int =
            names.firstNotNullOfOrNull { n -> clean.indexOf(n).takeIf { it >= 0 } } ?: -1
        return ImportColumns(
            name = findCol(*source.nameHeaders),
            username = findCol(*source.usernameHeaders),
            password = findCol(*source.passwordHeaders),
            url = findCol(*source.urlHeaders),
            totp = findCol(*source.totpHeaders),
            email = findCol(*source.emailHeaders),
            note = findCol(*source.noteHeaders),
            type = findCol(*source.typeHeaders),
        )
    }

    private suspend fun importCsvFromUri(
        contentResolver: ContentResolver,
        uri: Uri,
        source: ImportSource,
    ): ImportResult {
        val text = contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader().readText()
        } ?: throw IllegalStateException("Unable to open selected file")

        val rows = parseCsv(text)
        if (rows.isEmpty()) return ImportResult(0, 0)

        val header = rows.first().map { it.trim().lowercase() }
        val columns = resolveColumns(header, source)

        var inserted = 0
        var skipped = 0

        for (row in rows.drop(1)) {
            if (row.all { it.isBlank() }) continue
            if (importRow(row, columns, source)) inserted++ else skipped++
        }

        return ImportResult(inserted, skipped)
    }

    private suspend fun importRow(row: List<String>, columns: ImportColumns, source: ImportSource): Boolean {
        return try {
            repository.upsertPassword(buildPassword(row, columns, source))
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun buildPassword(row: List<String>, columns: ImportColumns, source: ImportSource): Password {
        fun col(idx: Int) = if (idx in row.indices) row[idx] else ""
        val rawUrl = col(columns.url)
        val name = prefixedName(col(columns.name), rawUrl, col(columns.type))
        return Password(
            name = name,
            username = col(columns.username),
            email = col(columns.email),
            password = col(columns.password),
            note = col(columns.note),
            totpSecret = extractTotpSecret(col(columns.totp)),
            websites = splitWebsites(rawUrl, source),
        )
    }

    private fun prefixedName(rawName: String, rawUrl: String, type: String): String {
        var name = rawName.ifEmpty { rawUrl }
        // Prefix the entry name with its type for non-login entries
        // (e.g. "sshKey: Tyche"), so specialized items stay identifiable.
        val trimmed = type.trim()
        if (trimmed.isNotEmpty() && !trimmed.equals("login", ignoreCase = true)) {
            name = if (name.isEmpty()) trimmed else "$trimmed: $name"
        }
        return name
    }

    private fun extractTotpSecret(raw: String): String? {
        val totp = raw.takeIf { it.isNotEmpty() } ?: return null
        if (!totp.startsWith("otpauth://")) return totp
        val match = Regex("[?&]secret=([^&]+)").find(totp)
        return match?.groupValues?.get(1) ?: totp
    }

    private fun splitWebsites(rawUrl: String, source: ImportSource): List<String> {
        val urlSeparators =
            if (source.splitUrlsOnComma) charArrayOf(',', ';', '\n', '\r')
            else charArrayOf(';', '\n', '\r')
        return rawUrl.split(*urlSeparators)
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
    }

    /**
     * Parses CSV text into rows of fields. Respects text qualifiers (double
     * quotes), escaped quotes (""), and quoted fields spanning multiple lines
     * (e.g. JSON stored in a note column). Handles both LF and CRLF line endings.
     */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val state = CsvState()
        var i = 0
        while (i < text.length) {
            i = parseCsvChar(text, i, state, rows)
        }
        // Flush a trailing field/row when the file does not end with a newline.
        if (state.current.isNotEmpty() || state.row.isNotEmpty()) {
            state.row.add(state.current.toString())
            rows.add(state.row)
        }
        return rows
    }

    private class CsvState {
        var row = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
    }

    private fun parseCsvChar(text: String, index: Int, state: CsvState, rows: MutableList<List<String>>): Int {
        var i = index
        val c = text[i]
        if (state.inQuotes) {
            i = parseQuotedChar(text, i, c, state)
        } else {
            parseBareChar(c, state, rows)
        }
        return i + 1
    }

    private fun parseQuotedChar(text: String, index: Int, c: Char, state: CsvState): Int {
        var i = index
        when {
            c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                state.current.append('"')
                i++
            }
            c == '"' -> state.inQuotes = false
            else -> state.current.append(c)
        }
        return i
    }

    private fun parseBareChar(c: Char, state: CsvState, rows: MutableList<List<String>>) {
        when (c) {
            '"' -> state.inQuotes = true
            ',' -> {
                state.row.add(state.current.toString())
                state.current.setLength(0)
            }
            '\r' -> {} // handled together with '\n'
            '\n' -> {
                state.row.add(state.current.toString())
                state.current.setLength(0)
                rows.add(state.row)
                state.row = mutableListOf()
            }
            else -> state.current.append(c)
        }
    }
}

/** Factory for constructing [PasswordsViewModel] with a [PasswordRepository]. */
class PasswordsViewModelFactory(
    private val application: Application,
    private val repository: PasswordRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PasswordsViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return PasswordsViewModel(application, repository) as T
    }
}
