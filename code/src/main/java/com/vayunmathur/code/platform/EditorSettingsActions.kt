package com.vayunmathur.code.platform
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.vayunmathur.code.data.EditorPrefs

/** [CodeSettingsActions] implementation, persisting through [EditorPrefs]. */
internal class EditorSettingsActions(private val vm: EditorViewModel) : CodeSettingsActions {

    override fun setFontSize(size: Int) {
        vm.fontSizeState.intValue = size
        vm.scope.launch { vm.prefs.setFontSize(size) }
    }

    override fun setTabWidth(width: Int) {
        vm.tabWidthState.intValue = width
        vm.scope.launch { vm.prefs.setTabWidth(width) }
    }

    override fun setThemeMode(mode: String) {
        vm.themeModeState.value = mode
        vm.scope.launch { vm.prefs.setThemeMode(mode) }
    }

    override fun setAutoIndent(enabled: Boolean) {
        vm.autoIndentState.value = enabled
        vm.scope.launch { vm.prefs.setAutoIndent(enabled) }
    }

    override fun setAutoCloseBrackets(enabled: Boolean) {
        vm.autoCloseBracketsState.value = enabled
        vm.scope.launch { vm.prefs.setAutoCloseBrackets(enabled) }
    }
}
