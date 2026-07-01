package com.vayunmathur.messages.ui.setup

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.messages.Route
import com.vayunmathur.messages.meta.MetaAuthData
import com.vayunmathur.messages.meta.MetaWebViewLogin

@Composable
fun InstagramLoginScreen(backStack: NavBackStack<Route>) {
    MetaWebViewLogin(
        platform = MetaAuthData.Platform.INSTAGRAM,
        modifier = Modifier.fillMaxSize(),
        onSuccess = { backStack.pop() },
    )
}
