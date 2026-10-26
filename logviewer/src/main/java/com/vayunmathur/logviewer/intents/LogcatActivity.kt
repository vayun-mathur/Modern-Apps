package com.vayunmathur.logviewer.intents

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.logviewer.domain.LogDocument
import com.vayunmathur.logviewer.domain.LogLevel
import com.vayunmathur.logviewer.platform.LogLoadResult
import com.vayunmathur.logviewer.platform.LogViewerController
import com.vayunmathur.logviewer.platform.LogViewerViewModel
import com.vayunmathur.logviewer.platform.LogcatFilters
import com.vayunmathur.logviewer.platform.LogcatReader
import com.vayunmathur.logviewer.ui.LogViewerPage

/**
 * Shows the device log, or one app's slice of it.
 *
 * Reached through `ACTION_LOGCAT` and `ACTION_PKG_LOGCAT`, both guarded by
 * `com.vayunmathur.logviewer.SHOW_LOGCAT` (signature|privileged), so only another privileged app
 * signed with the same key can open it. There is no launcher entry: the log is something another
 * system surface sends the user to, not something to browse to.
 *
 * Changing a filter re-reads the log in this same window; the active filters live in the
 * ViewModel, so they survive rotation.
 */
class LogcatActivity : ComponentActivity() {

    private val viewModel: LogViewerViewModel by viewModels()

    // One instance, not one per recomposition of setContent's lambda.
    private val actions by lazy { Actions() }

    private var targetPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set by the framework for ACTION_PKG_LOGCAT, and by the error report's "Show log" button.
        targetPackage = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)

        val initial = LogcatFilters(LogcatReader.DEFAULT_BUFFERS, LogLevel.Verbose, filterRegex = "")
        viewModel.start(loader = loader(initial), chrome = chrome(initial))

        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                LogViewerPage(
                    viewModel = viewModel,
                    actions = actions,
                    onFinish = { finishAndRemoveTask() },
                )
            }
        }
    }

    private fun loader(filters: LogcatFilters): suspend () -> LogLoadResult {
        val context: Context = applicationContext
        val request = LogcatReader.Request(
            targetPackage = targetPackage,
            buffers = filters.buffers,
            level = filters.level,
            filterRegex = filters.filterRegex,
        )
        return { LogcatReader.read(context, request) }
    }

    private fun chrome(filters: LogcatFilters): suspend (LogDocument) -> LogViewerViewModel.Chrome = {
        LogViewerViewModel.Chrome(logcat = filters)
    }

    private fun reload(edit: LogcatFilters.() -> LogcatFilters) {
        val current = viewModel.state.value.logcat ?: return
        val next = current.edit()
        if (next == current) return
        viewModel.reload(next, loader(next), chrome(next))
    }

    private inner class Actions : LogViewerController(this@LogcatActivity, viewModel) {
        override fun setBuffers(buffers: List<String>) = reload { copy(buffers = buffers) }

        override fun setLevel(level: LogLevel) = reload { copy(level = level) }

        override fun setFilter(regex: String) = reload { copy(filterRegex = regex) }
    }
}
