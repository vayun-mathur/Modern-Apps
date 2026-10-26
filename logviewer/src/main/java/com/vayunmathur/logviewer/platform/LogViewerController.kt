package com.vayunmathur.logviewer.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.logviewer.R
import com.vayunmathur.logviewer.domain.LogDocument
import com.vayunmathur.logviewer.domain.LogLevel
import kotlinx.coroutines.launch

/**
 * The half of [LogViewerActions] that needs an Activity: the clipboard, the share sheet, and
 * starting the next screen.
 */
internal open class LogViewerController(
    private val activity: ComponentActivity,
    private val viewModel: LogViewerViewModel,
) : LogViewerActions {

    override fun copy() {
        val clip = viewModel.clipText() ?: return
        copyToClipboard(viewModel.state.value.title, clip.text)
        AppMessages.show(
            activity.getString(
                if (clip.truncated) R.string.copied_to_clipboard_truncated
                else R.string.copied_to_clipboard
            )
        )
    }

    override fun share() {
        val fileName = viewModel.state.value.snapshotFileName
        activity.lifecycleScope.launch {
            val uri = viewModel.shareUri() ?: return@launch
            val send = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                type = LogDocument.MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, fileName))
        }
    }

    /**
     * Copies first, then opens the tracker. Filing an issue means pasting the log into it, and a
     * browser opening over the report is the last moment the user could still have copied it.
     */
    override fun report() {
        copy()
        activity.startActivity(Intent(Intent.ACTION_VIEW, ISSUES_URL.toUri()))
    }

    override fun save(uri: Uri) = viewModel.save(uri)

    override fun zoom(factor: Float) = viewModel.zoom(factor)

    /** The filter and extra actions do nothing by default; each entry point overrides what it offers. */
    override fun setBuffers(buffers: List<String>) = Unit

    override fun setLevel(level: LogLevel) = Unit

    override fun setFilter(regex: String) = Unit

    override fun perform(action: ExtraAction) = Unit

    override fun dismissStackTrace() = viewModel.dismissStackTrace()

    override fun copyStackTrace() {
        val stackTrace = viewModel.state.value.stackTrace ?: return
        copyToClipboard(null, stackTrace)
    }

    protected fun start(intent: Intent) = activity.startActivity(intent)


    private fun copyToClipboard(label: CharSequence?, text: String) {
        activity.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private companion object {
        const val ISSUES_URL = "https://github.com/vayun-mathur/Modern-Apps/issues"
    }
}

