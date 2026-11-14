package com.vayunmathur.games.hub.ui.yuu

import com.vayunmathur.games.hub.data.YuuUiModel

/**
 * UI contract for the Yuu plaza tab. Lives under `ui` (not `util`) since `util/` is
 * pending migration — same state+actions shape as `util/HubUiContract.kt` otherwise.
 */
data class YuuListUiState(
    val yuus: List<YuuUiModel> = emptyList(),
    val profileYuuId: Long? = null,
)

/** Contract for yuu list actions. */
interface YuuListActions {
    fun onCreate() {}
    fun onEdit(yuu: YuuUiModel) {}
    fun onDuplicate(yuu: YuuUiModel) {}
    fun onDelete(yuu: YuuUiModel) {}
    fun onSetAsProfile(yuu: YuuUiModel) {}
    fun onClearProfile() {}

    /** Shared constants and helpers for YuuListActions. */
    companion object {
        val Noop: YuuListActions = object : YuuListActions {}
    }
}
