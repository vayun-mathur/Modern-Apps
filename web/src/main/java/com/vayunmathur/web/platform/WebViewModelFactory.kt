package com.vayunmathur.web.platform

import android.content.Context
import com.vayunmathur.web.data.ShieldSetting
import com.vayunmathur.web.data.WebStores

class WebViewModelFactory(
    private val repository: WebStores,
    private val context: Context,
    private val windowId: String = WebViewModel.DEFAULT_WINDOW_ID,
    private val incognito: Boolean = false,
    private val initialShieldSettings: List<ShieldSetting> = emptyList(),
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WebViewModel::class.java)) {
            return WebViewModel(repository, context, windowId, incognito, initialShieldSettings) as T
        }
        throw IllegalArgumentException("Unknown ViewModel $modelClass")
    }
}
