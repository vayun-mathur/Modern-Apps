package com.vayunmathur.clock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.clock.R
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.ui.components.AlarmCard
import com.vayunmathur.clock.platform.AlarmActions
import com.vayunmathur.clock.platform.AlarmUiState
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack

/**
 * The alarm list, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmScreen(
    backStack: NavBackStack<Route>,
    state: AlarmUiState,
    actions: AlarmActions,
    initialExpandedAlarmId: Long? = null,
) {
    val alarms = state.alarms
    LazyListScaffold(
        actions = {
            IconButton(onClick = { backStack.add(Route.AlarmSettings) }) { IconSettings() }
        },
        floatingActionButton = {
            if (alarms.isNotEmpty()) {
                FloatingActionButton({
                    backStack.add(Route.NewAlarmDialog())
                }) {
                    IconAdd()
                }
            }
        },
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (alarms.isEmpty()) {
            item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = { backStack.add(Route.NewAlarmDialog()) }) {
                        IconAdd()
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.set_an_alarm))
                    }
                }
            }
        } else {
            items(alarms, key = { it.id }) { alarm ->
                AlarmCard(
                    backStack = backStack,
                    alarm = alarm,
                    is24Hour = state.is24Hour,
                    actions = actions,
                    initialExpanded = alarm.id == initialExpandedAlarmId,
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}
