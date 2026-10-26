package com.vayunmathur.logviewer.intents

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.logviewer.platform.ErrorReportReader
import com.vayunmathur.logviewer.platform.ExtraAction
import com.vayunmathur.logviewer.platform.LogViewerController
import com.vayunmathur.logviewer.platform.LogViewerViewModel
import com.vayunmathur.logviewer.ui.LogViewerPage

/**
 * Shows a crash, ANR or battery report.
 *
 * Two producers. `ACTION_ERROR_REPORT` is the platform's, sent by `SystemErrorNotification`,
 * `TombstoneHandler` and `DropBoxMonitor` through `LogViewerApp.createBaseErrorReportIntent()` -
 * which is why the extra names in [LogViewerIntents] have to match that file exactly.
 * `ACTION_APP_ERROR` is the AOSP report object, sent by the crash dialog.
 *
 * Guarded by `com.vayunmathur.logviewer.SHOW_ERROR_REPORT`, which is signature|preinstalled rather
 * than privileged: an ordinary preinstalled app should be able to show the user its own crash.
 */
class ErrorReportActivity : ComponentActivity() {

    private val viewModel: LogViewerViewModel by viewModels()

    // One instance, not one per recomposition of setContent's lambda.
    private val actions by lazy { Actions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val reportIntent = intent
        val context: Context = applicationContext

        viewModel.start(
            loader = { ErrorReportReader.read(context, reportIntent) },
            chrome = { document ->
                LogViewerViewModel.Chrome(
                    showReportButton = ErrorReportReader.showReportButton(reportIntent),
                    extraActions = buildList {
                        // Reads /data/tombstones, which is why chrome is resolved off the main
                        // thread with the document.
                        if (ErrorReportReader.hasMoreInfo(reportIntent)) add(ExtraAction.MoreInfo)
                        if (document.sourcePackage != null) add(ExtraAction.ShowAppLog)
                    },
                )
            },
        )

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

    private inner class Actions : LogViewerController(this@ErrorReportActivity, viewModel) {
        override fun perform(action: ExtraAction) {
            when (action) {
                // The same intent again, but asking for the text tombstone this time. A new
                // activity rather than a reload, so Back returns to the shorter report.
                ExtraAction.MoreInfo -> start(
                    Intent(this@ErrorReportActivity.intent)
                        .putExtra(LogViewerIntents.EXTRA_PREFER_TEXT_TOMBSTONE, true)
                )

                ExtraAction.ShowAppLog -> {
                    val sourcePackage = viewModel.state.value.sourcePackage ?: return
                    start(
                        Intent(this@ErrorReportActivity, LogcatActivity::class.java)
                            .putExtra(Intent.EXTRA_PACKAGE_NAME, sourcePackage)
                    )
                }
            }
        }
    }
}
