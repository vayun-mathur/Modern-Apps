package com.vayunmathur.logviewer.platform

import android.net.Uri
import androidx.annotation.StringRes
import com.vayunmathur.logviewer.domain.LogKind
import com.vayunmathur.logviewer.domain.LogLevel

/**
 * Everything the log screen renders, and everything it can ask for.
 *
 * Both entry points - a logcat dump and a crash report - share one screen, so the differences
 * between them are data here rather than two nearly identical composables: [logcat] is non-null
 * only when the filter controls apply, and [extraActions] carries the buttons that only make sense
 * for one of them.
 */
internal data class LogViewerUiState(
    val loading: Boolean = true,
    /** A filter change is re-reading the log; the previous one stays on screen meanwhile. */
    val refreshing: Boolean = false,
    /**
     * Set when the log could not be produced. A null [unavailableMessageRes] with [loading] false
     * means close the window silently; a non-null one means say why first.
     */
    val unavailable: Boolean = false,
    @param:StringRes val unavailableMessageRes: Int? = null,
    val title: String = "",
    /** The app the log is about, if any: what "Show log" opens. */
    val sourcePackage: String? = null,
    /** Header and body already flattened into the rows the list shows. */
    val lines: List<String> = emptyList(),
    val kind: LogKind = LogKind.ErrorReport,
    val fontSizeSp: Float = LogKind.ErrorReport.initialFontSizeSp,
    /** True for a report the sender wants filed: a Report button joins the bottom row. */
    val showReportButton: Boolean = false,
    val snapshotFileName: String = "",
    val logcat: LogcatFilters? = null,
    val extraActions: List<ExtraAction> = emptyList(),
    /** Non-null while the "unable to save" dialog is up; the text is the stack trace it shows. */
    val stackTrace: String? = null,
)

/** The active logcat filters, shown in the title and editable from the top bar. */
internal data class LogcatFilters(
    val buffers: List<String>,
    val level: LogLevel,
    val filterRegex: String,
)

/** The bottom buttons that exist on only one of the two screens. */
internal enum class ExtraAction {
    /** Error report: reload from the fuller text tombstone on disk. */
    MoreInfo,

    /** Error report: open the crashed app's log. */
    ShowAppLog,
}

/** What the log screen can do. Implemented by [LogViewerController]. */
internal interface LogViewerActions {
    fun copy()
    fun share()
    fun report()
    fun save(uri: Uri)

    /** Pinch-to-zoom; [factor] is the incremental scale since the last gesture event. */
    fun zoom(factor: Float)

    fun setBuffers(buffers: List<String>)
    fun setLevel(level: LogLevel)
    fun setFilter(regex: String)
    fun perform(action: ExtraAction)
    fun dismissStackTrace()
    fun copyStackTrace()
}
