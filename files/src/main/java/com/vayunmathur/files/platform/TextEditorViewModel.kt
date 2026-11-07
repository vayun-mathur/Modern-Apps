package com.vayunmathur.files.platform

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the standalone TextEditorActivity.
 *
 * Owns the asynchronous read/write of the file content. The composable continues to manage
 * its own [androidx.compose.foundation.text.input.TextFieldState] (cursor/selection) and the
 * edit/preview toggle UI flag, since those are pure UI state.
 */
class TextEditorViewModel(application: Application) : AndroidViewModel(application) {

    /** Last persisted file content; `null` while still loading. */
    private val _initialContent = MutableStateFlow<String?>(null)
    val initialContent: StateFlow<String?> = _initialContent.asStateFlow()

    /**
     * True when the last load failed. Kept separate from [initialContent]
     * because a failed load must not look like an empty file: presenting it
     * as one invites the user to type into the "empty" document and save
     * over the original, destroying it.
     */
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** True when the last save failed (nothing was written). */
    private val _saveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    fun load(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader().readText()
                } ?: throw java.io.IOException("Could not open $uri")
                _loadFailed.value = false
                _initialContent.value = text
            } catch (_: Exception) {
                _loadFailed.value = true
                _initialContent.value = ""
            }
        }
    }

    fun save(uri: Uri, content: String) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val out = ctx.contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("Could not open $uri")
                out.use {
                    it.writer().use { w -> w.write(content) }
                }
            } catch (_: Exception) {
                _saveFailed.value = true
                return@launch
            }
            _saveFailed.value = false
            _initialContent.value = content
        }
    }
}
