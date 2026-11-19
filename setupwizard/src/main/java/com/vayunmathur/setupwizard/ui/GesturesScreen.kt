package com.vayunmathur.setupwizard.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconSwipe
import com.vayunmathur.library.ui.SetupAction
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.setupwizard.R

/** Offers the launcher's gesture-navigation tutorial. */
@Composable
fun GesturesScreen(onTryIt: () -> Unit, onSkip: () -> Unit) {
    SetupScaffold(
        title = stringResource(R.string.swipe_gestures_title),
        subtitle = stringResource(R.string.swipe_gestures_desc),
        icon = { IconSwipe() },
        primaryAction = SetupAction(stringResource(R.string.try_it), onTryIt),
        secondaryAction = SetupAction(stringResource(R.string.skip), onSkip),
    )
}
