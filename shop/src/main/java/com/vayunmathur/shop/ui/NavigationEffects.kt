package com.vayunmathur.shop.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.shop.Route
import com.vayunmathur.shop.platform.ShopViewModel

/** Merchant links resolve in the ViewModel; a gated link stays unconsumed. */
@Composable
internal fun ShopLinkEffect(
    viewModel: ShopViewModel,
    backStack: NavBackStack<Route>,
    linkRequest: Pair<Int, String>?,
) {
    LaunchedEffect(linkRequest, backStack.backStack.lastOrNull()) {
        val url = linkRequest?.second ?: return@LaunchedEffect
        if (backStack.backStack.lastOrNull() == Route.ProfileSetup) return@LaunchedEffect
        viewModel.openLink(url) { route ->
            if (route is Route.ProductDetail || route == Route.Search) {
                applyShopPendingRoute(backStack, route, profileComplete = true)
            } else {
                backStack.reset(route)
            }
        }
    }
}

/** Asks for the notification permission when the ViewModel requests it. */
@Composable
internal fun ShopNotificationPermissionEffect(viewModel: ShopViewModel, context: android.content.Context) {
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(viewModel.notificationPermissionRequest) {
        if (viewModel.notificationPermissionRequest > 0 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
