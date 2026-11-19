package com.vayunmathur.screentime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.screentime.R
import com.vayunmathur.screentime.platform.Coordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The launch-friction sheet: mindful nudge plus spent-timer notice.
 *
 * Shown when a usage observer fires for a watched app (nudge threshold) or when the user
 * opens a paused app's details from the suspended treatment (timer spent). Always
 * dismissible - self-managed means the user can always continue; the sheet just makes the
 * choice deliberate. `showWhenLocked` so it meets the launch, and `singleInstance` +
 * `excludeFromRecents` so it never stacks or lingers.
 */
class FrictionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_FRICTION_PACKAGE)
        val nudge = intent.getBooleanExtra(EXTRA_FRICTION_NUDGE, false)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            DynamicTheme {
                FrictionScreen(
                    appLabel = pkg?.let { labelFor(it) },
                    nudge = nudge,
                    onContinue = { finish() },
                    onTakeABreak = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            // A break spends the app immediately: the normal spent path pauses
                            // it until midnight, with no timer row left behind. A DB failure
                            // here must not crash the activity or leave it on screen: the
                            // receivers broad-catch the identical call for this reason.
                            try {
                                if (pkg != null) {
                                    Coordinator(this@FrictionActivity).onTimerReached(pkg)
                                }
                            } catch (_: Exception) {
                                // Enforcement is retried by the next coordinator pass.
                            } finally {
                                finish()
                            }
                        }
                    },
                )
            }
        }
    }

    private fun labelFor(packageName: String): String? =
        runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrNull()

    /** Intent extras for launching the friction screen. */
    companion object {
        const val EXTRA_FRICTION_PACKAGE = "com.vayunmathur.screentime.extra.FRICTION_PACKAGE"
        const val EXTRA_FRICTION_NUDGE = "com.vayunmathur.screentime.extra.FRICTION_NUDGE"

        fun intent(context: Context, packageName: String?, nudge: Boolean): Intent =
            Intent(context, FrictionActivity::class.java)
                .putExtra(EXTRA_FRICTION_PACKAGE, packageName)
                .putExtra(EXTRA_FRICTION_NUDGE, nudge)
    }
}

/** Actions the friction sheet offers. */
data class FrictionActions(
    val onContinue: () -> Unit,
    val onTakeABreak: () -> Unit,
)

@Composable
fun FrictionScreen(appLabel: String?, nudge: Boolean, onContinue: () -> Unit, onTakeABreak: () -> Unit) {
    var taken by remember { mutableStateOf(false) }

    AppScaffold(
        title = stringResource(
            if (nudge) R.string.friction_nudge_title else R.string.friction_spent_title,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            if (appLabel != null) Text(appLabel)
            Text(
                stringResource(
                    if (nudge) R.string.friction_nudge_hint else R.string.friction_spent_hint,
                ),
            )
            if (!taken) {
                TextButton(
                    onClick = {
                        taken = true
                        onTakeABreak()
                    },
                ) {
                    Text(stringResource(R.string.friction_break))
                }
            }
            TextButton(onClick = onContinue) {
                Text(
                    stringResource(
                        if (nudge) R.string.friction_continue else R.string.friction_open_anyway,
                    ),
                )
            }
        }
    }
}
