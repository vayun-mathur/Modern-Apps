package com.vayunmathur.fooddelivery.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Contextual notification ask for a confirmed checkout: only when an order is
 * confirmed and tracking starts, ask once and persist that we asked.
 */
@Composable
fun CheckoutNotificationPermissionEffect(orderSuccess: Boolean) {
    val context = LocalContext.current
    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(orderSuccess) {
        if (orderSuccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val prefs = context.getSharedPreferences("fooddelivery_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("has_asked_notifications", false)) {
                prefs.edit().putBoolean("has_asked_notifications", true).apply()
                if (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
