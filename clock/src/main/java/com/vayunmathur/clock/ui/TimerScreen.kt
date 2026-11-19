package com.vayunmathur.clock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.clock.ui.components.TimerCard
import com.vayunmathur.clock.ui.components.TimerKeypadContent
import com.vayunmathur.clock.platform.TimerActions
import com.vayunmathur.clock.platform.TimerUiState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.isExpandedWidth

/**
 * The timer tab — keypad while there is nothing to show, countdown cards otherwise — with
 * no dependency on the ViewModel so it can be rendered from a `@Preview`. See
 * `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerScreen(
    state: TimerUiState,
    actions: TimerActions,
    initialAddingTimer: Boolean = false,
    initialKeypadInput: String = "",
) {
    val timers = state.timers
    var isAddingTimer by remember { mutableStateOf(initialAddingTimer) }
    val showKeypad = timers.isEmpty() || isAddingTimer
    val expanded = isExpandedWidth()
    LazyListScaffold(
        floatingActionButton = {
            if (!showKeypad) {
                FloatingActionButton({ isAddingTimer = true }) { IconAdd() }
            }
        },
        horizontalPadding = if (showKeypad) 0.dp else 16.dp,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showKeypad) {
            item {
                Box(Modifier.fillParentMaxSize()) {
                    TimerKeypadContent(
                        paddingValues = PaddingValues(0.dp),
                        onStart = { duration, name -> actions.start(duration, name); isAddingTimer = false },
                        onCancel = { if (timers.isNotEmpty()) isAddingTimer = false },
                        showCancel = timers.isNotEmpty(),
                        initialInput = initialKeypadInput
                    )
                }
            }
        } else {
            // On expanded widths running timers pair up in fractional columns so
            // a single card doesn't stretch across the window; compact stays a
            // single column. The keypad case above is untouched.
            if (expanded && timers.size > 1) {
                items(timers.chunked(2), key = { row -> row.first().id }) { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        row.forEach { timer ->
                            Box(modifier = Modifier.weight(1f)) {
                                TimerCard(timer, state.now(), actions)
                            }
                        }
                        // Keep a lone card in a pair at half width so it doesn't
                        // stretch to fill the row.
                        if (row.size == 1) {
                            Box(modifier = Modifier.weight(1f)) {}
                        }
                    }
                }
            } else {
                items(timers, key = { it.id }) { timer -> TimerCard(timer, state.now(), actions) }
            }
        }
    }
}
