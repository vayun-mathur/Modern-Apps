package com.vayunmathur.setupwizard.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.setupwizard.platform.SetupIntents
import com.vayunmathur.setupwizard.platform.SetupUiState
import com.vayunmathur.setupwizard.platform.SetupViewModel

@Composable
internal fun WelcomeStep(
    viewModel: SetupViewModel,
    state: SetupUiState,
    onOpen: (Intent) -> Unit,
    onNextOemUnlock: () -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.onEnterWelcome() }
    val canCall = Build.VERSION.SDK_INT >= 33 &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING)
    WelcomeScreen(
        state = state,
        languages = viewModel::availableLanguages,
        onLanguageSelected = viewModel::setLanguage,
        onAccessibility = { onOpen(SetupIntents.accessibilitySettings()) },
        onEmergencyCall = if (canCall) {
            { onOpen(viewModel.system.emergencyDialerIntent()) }
        } else {
            null
        },
        onNext = {
            if (viewModel.welcomeLeadsToBootloaderWarning()) onNextOemUnlock() else onNext()
        },
    )
}
