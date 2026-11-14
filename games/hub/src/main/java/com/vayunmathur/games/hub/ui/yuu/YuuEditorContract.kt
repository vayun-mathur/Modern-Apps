package com.vayunmathur.games.hub.ui.yuu

import com.vayunmathur.games.hub.data.entities.YuuFaceConfig

/** Wii-style creator categories, in rail order. */
enum class YuuCategory {
    Face,
    Body,
    Hair,
    Eyes,
    Brows,
    Nose,
    Mouth,
    Extras,
}

/**
 * UI contract for the Yuu creator. The screen holds draft [config] + [name] locally
 * while editing; [onSave] persists via the ViewModel and [onDismiss] closes.
 */
data class YuuEditorUiState(
    val editingId: Long? = null,
    val initialName: String = "",
    val initialConfig: YuuFaceConfig = YuuFaceConfig.DEFAULT,
)

/** Contract for yuu editor actions. */
interface YuuEditorActions {
    fun onSave(name: String, config: YuuFaceConfig) {}
    fun onDismiss() {}

    /** Shared constants and helpers for YuuEditorActions. */
    companion object {
        val Noop: YuuEditorActions = object : YuuEditorActions {}
    }
}
