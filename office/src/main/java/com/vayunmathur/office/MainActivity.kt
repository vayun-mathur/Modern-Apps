package com.vayunmathur.office

import android.content.Intent
import android.net.Uri
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.core.content.IntentCompat
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.office.R
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.requestToJoin
import com.vayunmathur.office.ui.OfficeAppTheme

import com.vayunmathur.office.data.odf.DocumentImporter
/** Entry activity hosting Office home, join-link handling and document editing. */
class MainActivity : MaActivity() {
    private val viewModel: OfficeViewModel by viewModels()
    private var intentDocumentUri: Uri? = null

    override fun onCreateBeforeContent() {
        // FIRST_PARTY: api.maproj.org + data.maproj.org -> ISRG+GTS
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        viewModel.loadSettings(this)

        // office://join links request access to someone else's doc — handle them here and keep
        // them out of the document-loading path (their scheme isn't content/file).
        val joinLink = parseJoinLink(intent)
        if (joinLink != null) handleJoinLink(joinLink)
        intentDocumentUri = if (joinLink == null) extractDocumentUri(intent) else null
    }

    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        OfficeAppTheme(content = content)
    }

    @Composable
    override fun Content() {
        Navigation(viewModel, this, intentDocumentUri)
    }

    /**
     * Resolves a human-readable file name (with extension) for an opened uri. Content
     * uris from other apps often expose an opaque document id as their last path
     * segment with no extension; DocumentImporter routes by extension, so query
     * OpenableColumns.DISPLAY_NAME first to keep extension-based routing working for
     * files opened via intents (falling back to the last path segment).
     */
    internal fun resolveDisplayName(context: android.content.Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    /** MIME types accepted by the document picker. */
    internal val odfMimeTypes = arrayOf(
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
        "application/vnd.oasis.opendocument.graphics",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/csv",
        "text/comma-separated-values",
        "text/tab-separated-values",
        "text/markdown",
        "text/plain",
        "text/xml",
        "application/xml"
    )

    /**
     * Holds on to the picker's grant so Save can still write back to the chosen document after the
     * transient grant expires. Falls back to a read-only grant for providers that refuse write.
     */
    internal fun persistUriGrant(uri: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (runCatching { contentResolver.takePersistableUriPermission(uri, readWrite) }.isFailure) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: warm-start join links arrive here rather than through a fresh onCreate.
        setIntent(intent)
        parseJoinLink(intent)?.let { handleJoinLink(it) }
    }

    /** Fires a sealed join request to the owner and confirms with a toast. */
    private fun handleJoinLink(link: Pair<String, String>) {
        val (docId, ownerId) = link
        AppMessages.show(getString(R.string.join_request_sending))
        viewModel.requestToJoin(docId, ownerId) { ok ->
            AppMessages.show(
                getString(if (ok) R.string.join_request_sent else R.string.join_request_failed),
                duration = AppMessages.Duration.Long,
            )
        }
    }

    /** Parses `office://join/<docId>?owner=<ownerDeviceId>`; null for any other intent. */
    private fun parseJoinLink(intent: Intent?): Pair<String, String>? {
        val data = intent?.data ?: return null
        if (data.scheme != "office" || data.host != "join") return null
        val docId = data.lastPathSegment?.takeIf { it.isNotBlank() } ?: return null
        val ownerId = data.getQueryParameter("owner")?.takeIf { it.isNotBlank() } ?: return null
        return docId to ownerId
    }

    /**
     * Resolves the document uri to open from an intent. VIEW/edit intents carry it
     * in [Intent.getData]; SEND intents (files shared from other apps) carry it in
     * [Intent.EXTRA_STREAM].
     */
    private fun extractDocumentUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> intent.data
    }
}
