package com.vayunmathur.email.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.Navigation
import com.vayunmathur.email.Route
import com.vayunmathur.email.data.EmailSettings
import com.vayunmathur.email.platform.addAccountEntries
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.platform.IntentState
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun MainContent(viewModel: EmailViewModel) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle(emptyList())
    // Contextual notification ask (todo/files pattern: system request only at
    // first use, never on first frame): once a first account exists, mail
    // notifications become relevant, so ask once then and persist that we asked.
    val context = LocalContext.current
    val settings = remember(context) { EmailSettings.get(context) }
    val hasAskedNotifications by settings.hasAskedNotifications.collectAsStateWithLifecycle()
    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(accounts.isNotEmpty(), hasAskedNotifications) {
        if (accounts.isNotEmpty() && !hasAskedNotifications &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            settings.setHasAskedNotifications()
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    val navigationRoute = IntentState.navigationRoute
    val isEmlViewer = navigationRoute is Route.EmlViewer

    if (accounts.isEmpty() && !isEmlViewer) {
        // First-run gate: no account yet, so there is no main graph to
        // push the add-account form onto. Host the same two destinations
        // on a gate-local stack instead; saving an account fills
        // `accounts` and swaps this for the main Navigation, so the
        // form's onAccountAdded has nothing to do here. The picker is
        // the gate root and keeps its no-back presentation.
        val gateStack = rememberNavBackStack<Route>(Route.AddAccount)
        MainNavigation(gateStack) {
            addAccountEntries(gateStack, onPickBack = null, onAccountAdded = {})
        }
    } else {
        Navigation(viewModel = viewModel)
    }
}
