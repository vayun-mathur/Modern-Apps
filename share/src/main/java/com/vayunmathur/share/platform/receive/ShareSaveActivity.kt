package com.vayunmathur.share.platform.receive

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.vayunmathur.library.log.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.share.R
import com.vayunmathur.share.platform.ReceivedFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ShareSave"
private const val STATE_INDEX = "index"
private const val STATE_SAVED = "saved"

/**
 * Transparent trampoline hosting the "save a copy" picker for a Done notification's Save.
 *
 * An `ActivityResultLauncher` needs an Activity, and there is no receive screen left to host one,
 * so this exists only to run the picker, copy, and finish. It carries URIs rather than a session
 * handle because by the time Save is tappable the session is gone.
 *
 * Uses `ACTION_CREATE_DOCUMENT`, not `OpenDocumentTree`. Tree access hands the app read/write over
 * a whole directory and its children forever, which is why the system refuses the ones people
 * actually want — Downloads and the storage root both answer "Can't use this folder / To protect
 * your privacy, choose another folder". `ACTION_CREATE_DOCUMENT` grants write access to exactly
 * the one file the user just named, so nothing is blocked and nothing lingering is granted.
 *
 * The cost is one picker per file, so they are run in sequence.
 */
class ShareSaveActivity : ComponentActivity() {

    private var index = 0
    private var saved = 0

    private val creator = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val source = uris().getOrNull(index)
        val destination = result.data?.data
        if (result.resultCode == RESULT_OK && destination != null && source != null) {
            lifecycleScope.launch {
                if (withContext(Dispatchers.IO) { copy(source, destination) }) saved++
                index++
                advance()
            }
        } else {
            // Cancelled for this file. Skipping to the next is friendlier than abandoning the
            // whole batch, and cancelling the first is indistinguishable from changing your mind.
            index++
            advance()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = savedInstanceState?.getInt(STATE_INDEX) ?: 0
        saved = savedInstanceState?.getInt(STATE_SAVED) ?: 0
        if (uris().isEmpty()) {
            Log.status(TAG, "no URIs to save")
            finish()
            return
        }
        // Only on a fresh launch: `registerForActivityResult` restores the pending request across
        // recreation, so launching again would stack a second picker on top of it.
        if (savedInstanceState == null) advance()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_INDEX, index)
        outState.putInt(STATE_SAVED, saved)
    }

    /** Ask for the next destination, or report and finish once every file has had its turn. */
    private fun advance() {
        val targets = uris()
        val next = targets.getOrNull(index)
        if (next == null) {
            // TOAST EXCEPTION: no UI to host a snackbar.
            // A Toast, not `AppMessages`: that is collected by the app's scaffold, and this
            // trampoline has no UI and may well be the only thing the user has open.
            Toast.makeText(
                this,
                if (saved == targets.size) getString(R.string.share_save_succeeded, saved)
                else getString(R.string.share_save_failed, targets.size - saved),
                Toast.LENGTH_SHORT,
            ).show()
            finish()
            return
        }
        val name = displayName(next)
        creator.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // Per file, from its own name: a batch can mix a photo and a PDF, and the picker
                // uses the type to choose a default location and a sensible extension.
                type = ReceivedFileStore.mimeTypeOf(name)
                putExtra(Intent.EXTRA_TITLE, name)
            }
        )
    }

    /** The staged file's name. These are our own `FileProvider` URIs, so it is the last segment. */
    private fun displayName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "received_file" }

    private fun uris(): List<Uri> {
        val extras = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(EXTRA_FILE_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(EXTRA_FILE_URIS)
        }
        return extras.orEmpty()
    }

    // Broad catch is deliberate: content-resolver copies throw undocumented
    // RuntimeExceptions (not just IOException) on bad providers, which read as
    // "save failed" rather than a crash.
    @Suppress("TooGenericExceptionCaught")
    private fun copy(source: Uri, destination: Uri): Boolean = try {
        contentResolver.openInputStream(source)?.use { input ->
            contentResolver.openOutputStream(destination)?.use { output -> input.copyTo(output) }
        } != null
    } catch (e: Exception) {
        Log.status(TAG, "save failed for $source", e)
        false
    }

    /** Shared constants and helpers for ShareSaveActivity. */
    companion object {
        const val EXTRA_FILE_URIS = "com.vayunmathur.share.EXTRA_FILE_URIS"
    }
}
