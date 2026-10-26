package com.vayunmathur.logviewer.platform

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.logviewer.R
import com.vayunmathur.logviewer.domain.LogDocument
import com.vayunmathur.logviewer.provider.BlobProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.PrintStream

/**
 * Holds the loaded log and the one part of the screen the user changes: the font size.
 *
 * The app this replaces kept these in a process-wide `LruCache` keyed by a UUID it stashed in the
 * saved instance state, because a plain `Activity` loses its fields on rotation. A ViewModel is
 * what that cache was standing in for, so the cache is gone.
 *
 * Loading happens here, off the main thread. The original ran `logcat` inline in `onCreate`, which
 * is a multi-megabyte read and an exec on the UI thread; the output is identical either way, so
 * this is the one behavioural difference and it is in the user's favour.
 */
internal class LogViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(LogViewerUiState())
    val state: StateFlow<LogViewerUiState> = _state.asStateFlow()

    private var document: LogDocument? = null
    private var started = false
    private var loadJob: Job? = null

    /** The parts of the chrome that depend on what was actually loaded. */
    data class Chrome(
        val showReportButton: Boolean = false,
        val extraActions: List<ExtraAction> = emptyList(),
        val logcat: LogcatFilters? = null,
    )

    fun start(loader: suspend () -> LogLoadResult, chrome: suspend (LogDocument) -> Chrome) {
        if (started) return
        started = true
        load(loader, chrome)
    }

    /**
     * Re-reads logcat with new [filters] in place. The current log stays on screen under a progress
     * bar until the new one arrives, and a newer change cancels one still in flight.
     */
    fun reload(
        filters: LogcatFilters,
        loader: suspend () -> LogLoadResult,
        chrome: suspend (LogDocument) -> Chrome,
    ) {
        _state.update { it.copy(logcat = filters, refreshing = true) }
        load(loader, chrome)
    }

    private fun load(loader: suspend () -> LogLoadResult, chrome: suspend (LogDocument) -> Chrome) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Both run on IO: `chrome` decides whether "More info" applies, which means reading
            // /data/tombstones.
            val outcome = withContext(Dispatchers.IO) {
                when (val result = loader()) {
                    is LogLoadResult.Unavailable -> result to null
                    is LogLoadResult.Loaded -> result to chrome(result.document)
                }
            }
            when (val result = outcome.first) {
                is LogLoadResult.Unavailable -> _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        unavailable = true,
                        unavailableMessageRes = result.messageRes,
                    )
                }

                is LogLoadResult.Loaded -> {
                    val loaded = result.document
                    document = loaded
                    val resolved = outcome.second ?: Chrome()
                    val previous = _state.value
                    _state.value = LogViewerUiState(
                        loading = false,
                        title = loaded.title,
                        sourcePackage = loaded.sourcePackage,
                        lines = loaded.lines,
                        kind = loaded.kind,
                        // A reload keeps the size the user pinched to.
                        fontSizeSp =
                            if (previous.loading) loaded.kind.initialFontSizeSp else previous.fontSizeSp,
                        showReportButton = resolved.showReportButton,
                        snapshotFileName = LogDocument.snapshotFileName(loaded.title),
                        logcat = resolved.logcat,
                        extraActions = resolved.extraActions,
                        stackTrace = previous.stackTrace,
                    )
                }
            }
        }
    }

    fun zoom(factor: Float) {
        _state.update { it.copy(fontSizeSp = (it.fontSizeSp * factor).coerceIn(MIN_SP, MAX_SP)) }
    }

    fun dismissStackTrace() = _state.update { it.copy(stackTrace = null) }

    /** The clipboard text, or null if nothing is loaded. Truncation is reported by the caller. */
    fun clipText(): LogDocument.ClipText? = document?.clipText()

    /** Off the main thread: compressing a multi-megabyte log is long enough to drop frames. */
    suspend fun shareUri(): Uri? {
        val loaded = document ?: return null
        val fileName = _state.value.snapshotFileName
        return withContext(Dispatchers.Default) {
            BlobProvider.getUri(fileName) { out ->
                out.bufferedWriter(Charsets.UTF_8).use { loaded.writeSnapshot(it) }
            }
        }
    }

    /**
     * Writes the log to the document the user picked.
     *
     * Failures are shown as a dialog with the stack trace in it rather than a one-line message,
     * because the interesting part of "could not write" is which exception it was.
     */
    fun save(uri: Uri) {
        val loaded = document ?: return
        val fileName = _state.value.snapshotFileName
        val context = getApplication<Application>()
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                try {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: return@withContext OpenFailed
                    stream.bufferedWriter(Charsets.UTF_8).use { loaded.writeSnapshot(it) }
                    null
                } catch (e: IOException) {
                    e
                } catch (e: SecurityException) {
                    e
                }
            }
            when (failure) {
                null -> AppMessages.show(context.getString(R.string.toast_saved, fileName))
                OpenFailed -> AppMessages.show(context.getString(R.string.toast_unable_to_open_file))
                else -> _state.update { it.copy(stackTrace = stackTraceOf(failure)) }
            }
        }
    }

    private companion object {
        const val MIN_SP = 2f
        const val MAX_SP = 24f

        /** A `null` ParcelFileDescriptor is not an exception, but it is still a failure. */
        val OpenFailed = IOException("openOutputStream returned null")

        fun stackTraceOf(t: Throwable): String {
            val out = java.io.ByteArrayOutputStream(1000)
            t.printStackTrace(PrintStream(out))
            return out.toString()
        }
    }
}
