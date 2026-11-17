package com.vayunmathur.keyboard.platform.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.vayunmathur.keyboard.R
import com.vayunmathur.keyboard.data.ClipboardStore
import com.vayunmathur.keyboard.data.ClipItem
import com.vayunmathur.keyboard.data.KeyboardSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Clipboard history and image-paste split from KeyboardService
 * (LargeClass/TooManyFunctions). Behaviour identical.
 */

/** How long a freshly copied clip is offered in the strip before the chip gives up the slot. */
private const val CLIP_CHIP_MS = 60_000L

/** How long a strip notice (e.g. image paste refused) stays up before clearing itself. */
private const val NOTICE_MS = 4_000L

/**
 * One-time move off the old DataStore-backed history (see [KeyboardSettings.Keys.CLIPS]):
 * decode the legacy string into the file store, persist it, then blank the key. The
 * wipe watcher keys off CLIPS_WIPE, so blanking here is not mistaken for a request.
 * Runs on IO from onCreate.
 */
internal suspend fun KeyboardService.migrateLegacyClips() {
    // Await, not snapshot-read: the DataStore mirror may not be hydrated yet this
    // early in onCreate, and a null here would skip the one-time migration forever.
    val stored = ds.getStringAwait(KeyboardSettings.Keys.CLIPS) ?: return
    if (stored.isBlank()) return
    clipboard.seed(ClipboardStore.decode(stored))
    clipboard.persistState()
    ds.setString(KeyboardSettings.Keys.CLIPS, "")
}

/** A short-lived strip notice; the IME has no snackbar host, so the strip is the channel. */
internal fun KeyboardService.showNotice(text: String) {
    noticeJob?.cancel()
    kbState.notice = text
    noticeJob = scope.launch {
        delay(NOTICE_MS)
        if (kbState.notice == text) kbState.notice = null
    }
}

/**
 * Watch the system clipboard. An IME may read it while it is the active input method,
 * which is what makes this possible on Android 10+ — but the callback only fires while
 * we are bound, so [onStartInputView] also sweeps the current clip to catch copies made
 * while the keyboard was hidden.
 */
internal fun KeyboardService.observeClipboard() {
    val manager = getSystemService(ClipboardManager::class.java) ?: return
    val listener = ClipboardManager.OnPrimaryClipChangedListener {
        captureCurrentClip(inPasswordField = kbState.passwordField)
    }
    manager.addPrimaryClipChangedListener(listener)
    clipListener = listener
}

internal fun KeyboardService.captureCurrentClip(inPasswordField: Boolean) {
    if (!kbState.settings.clipboardEnabled) return
    val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return
    scope.launch {
        val item = clipboard.capture(this@captureCurrentClip, clip, inPasswordField)
            ?: return@launch
        val fresh = clipboard.add(item)
        kbState.clips = clipboard.items
        persistClips()
        if (fresh != null) offerClip(fresh)
    }
}

/** Show the chip for a newly copied clip, and take it back down after a while. */
internal fun KeyboardService.offerClip(item: ClipItem) {
    chipClipId = item.id
    kbState.clipSuggestion = item
    scope.launch {
        delay(CLIP_CHIP_MS)
        if (chipClipId == item.id) dismissClipSuggestion()
    }
}

internal fun KeyboardService.persistClips() {
    // File I/O, never on the main thread; encode() already excludes sensitive clips.
    scope.launch(Dispatchers.IO) { runCatching { clipboard.persistState() } }
}

/** Drop everything, in memory and on disk — what turning the setting off has to mean. */
internal fun KeyboardService.forgetClips() {
    clipboard.clear()
    kbState.clips = emptyList()
    dismissClipSuggestion()
    persistClips()
}

internal fun KeyboardService.pasteClip(item: ClipItem) {
    feedback()
    val ic = currentInputConnection ?: return
    dismissClipSuggestion()
    if (item.isImage) {
        commitImage(ic, item)
    } else {
        finishComposition(ic)
        commitCurrentWord(ic, autoCorrect = false)
        commit(ic, item.text)
        kbState.suggestions = emptyList()
    }
    updateAutoCapShift()
}

/**
 * Hand an image clip to the field via `commitContent`. Most fields cannot take one, so
 * the editor's accepted MIME types are checked first; every refusal explains itself in
 * the strip rather than leaving the tap visibly dead.
 */
internal fun KeyboardService.commitImage(ic: InputConnection, item: ClipItem) {
    val file = item.imageFile
    val mime = item.mimeType
    val editor = currentInputEditorInfo
    if (file == null || mime == null || editor == null) {
        showNotice(getString(R.string.clipboard_image_unavailable))
        return
    }
    if (!file.exists()) {
        showNotice(getString(R.string.clipboard_image_unavailable))
        return
    }
    val accepted = EditorInfoCompat.getContentMimeTypes(editor)
    if (accepted.none { ClipDescription.compareMimeTypes(mime, it) }) {
        showNotice(getString(R.string.clipboard_image_not_supported))
        return
    }
    val uri = runCatching {
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }.getOrNull()
    if (uri == null) {
        showNotice(getString(R.string.clipboard_image_unavailable))
        return
    }
    val content = InputContentInfoCompat(uri, ClipDescription(item.preview, arrayOf(mime)), null)
    InputConnectionCompat.commitContent(
        ic,
        editor,
        content,
        InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
        null,
    )
    // How the field represents an image — if it takes it at all — is up to the app.
    before.invalidate()
}

/**
 * Delete a clip. Deleting the newest one also empties the system clipboard: "delete what
 * I just copied" has to mean the password is gone, not merely hidden from our own list.
 */
internal fun KeyboardService.deleteClip(item: ClipItem) {
    feedback()
    val newest = clipboard.items.firstOrNull()?.id == item.id
    clipboard.delete(item)
    kbState.clips = clipboard.items
    if (kbState.clipSuggestion?.id == item.id) dismissClipSuggestion()
    if (newest) {
        runCatching { getSystemService(ClipboardManager::class.java)?.clearPrimaryClip() }
    }
    persistClips()
}

internal fun KeyboardService.clearClips() {
    feedback()
    forgetClips()
    runCatching { getSystemService(ClipboardManager::class.java)?.clearPrimaryClip() }
}

internal fun KeyboardService.dismissClipSuggestion() {
    chipClipId = 0L
    kbState.clipSuggestion = null
}
