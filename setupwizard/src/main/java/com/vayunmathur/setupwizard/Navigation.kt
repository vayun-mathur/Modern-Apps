package com.vayunmathur.setupwizard

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.setupwizard.platform.SetupFlow
import com.vayunmathur.setupwizard.platform.SetupViewModel
import com.vayunmathur.setupwizard.ui.FinishStep
import com.vayunmathur.setupwizard.ui.GesturesStep
import com.vayunmathur.setupwizard.ui.LocationStep
import com.vayunmathur.setupwizard.ui.MigrationStep
import com.vayunmathur.setupwizard.ui.OemUnlockStep
import com.vayunmathur.setupwizard.ui.SecurityStep
import com.vayunmathur.setupwizard.ui.WelcomeStep
import com.vayunmathur.setupwizard.ui.WifiStep

@Composable
fun Navigation(viewModel: SetupViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Welcome)
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state = viewModel.state

    /** Moves on from a step the user can come back to. */
    fun advance(from: Route) {
        SetupFlow.next(state.isPrimaryUser, from)?.let(backStack::add)
    }

    /**
     * Moves on from a step that is finished with: the entry is replaced rather than pushed, so
     * a step that handed off to another app and came back does not sit on the stack waiting to
     * hand off a second time when the user presses back.
     */
    fun advanceReplacing(from: Route) {
        SetupFlow.next(state.isPrimaryUser, from)?.let(backStack::setLast)
    }

    /** Opens something outside the wizard that the flow does not wait on. */
    fun open(intent: Intent) {
        try {
            (activity ?: context).startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.status("SetupNavigation", "nothing handled ${intent.action}", e)
        }
    }

    // Setup is not a place the user can back out of: there is no other HOME activity while the
    // device is unprovisioned, so letting back finish this Activity leaves a black screen.
    BackHandler(enabled = backStack.backStack.size <= 1) {}

    MainNavigation(backStack) {
        entry<Route.Welcome> {
            WelcomeStep(
                viewModel = viewModel,
                state = state,
                onOpen = { open(it) },
                onNextOemUnlock = { backStack.add(Route.OemUnlock) },
                onNext = { advance(Route.Welcome) },
            )
        }
        entry<Route.OemUnlock> {
            OemUnlockStep(viewModel, state) { advanceReplacing(Route.Welcome) }
        }
        entry<Route.Wifi> {
            WifiStep(onCancelled = backStack::pop, onDone = { advanceReplacing(Route.Wifi) })
        }
        entry<Route.Location> {
            LocationStep(viewModel, state) { advance(Route.Location) }
        }
        entry<Route.Security> {
            SecurityStep(
                viewModel = viewModel,
                state = state,
                onCancelled = backStack::pop,
                onDone = { advanceReplacing(Route.Security) },
            )
        }
        entry<Route.Migration> {
            MigrationStep(
                context,
                onAdvance = { advance(it) },
                onAdvanceReplacing = { advanceReplacing(it) },
            )
        }
        entry<Route.Gestures> {
            GesturesStep(onDone = { advance(it) })
        }
        entry<Route.Finish> {
            FinishStep(viewModel, state, activity)
        }
    }
}








