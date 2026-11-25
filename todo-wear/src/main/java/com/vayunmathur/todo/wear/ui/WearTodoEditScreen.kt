package com.vayunmathur.todo.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.todo.wear.R
import com.vayunmathur.todo.wear.data.WearTodoStore
import java.time.LocalDate
import java.time.ZoneId

/**
 * Watch-sized task edit: title (system IME, whose microphone is the
 * dictation path), due shortcuts (none/today/tomorrow/next week, sent as
 * local-midnight millis the phone stores as an all-day due date), and
 * priority. Save queues an EDIT op; the phone applies and republishes.
 */
@Composable
fun WearTodoEditScreen(
    store: WearTodoStore,
    syncId: String,
    onDone: () -> Unit,
) {
    val pagesMap by store.pages.collectAsState()
    val existing = store.pagesForAll().firstOrNull { it.syncId == syncId }
    WearTodoEditContent(
        initialTitle = existing?.title.orEmpty(),
        initialDueAt = existing?.dueAt,
        initialPriority = existing?.priority ?: 0,
        onSave = { title, dueAt, clearDue, priority -> store.edit(syncId = syncId, title = title, dueAt = dueAt, clearDue = clearDue, priority = priority) },
        onDone = onDone,
    )
}

/** Stateless edit behind [WearTodoEditScreen]: initial values/save sink are parameters for previews. */
@Composable
internal fun WearTodoEditContent(
    initialTitle: String,
    initialDueAt: Long?,
    initialPriority: Int,
    onSave: (String?, Long?, Boolean, Int) -> Unit,
    onDone: () -> Unit,
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var dueAt by remember(initialDueAt) { mutableStateOf(initialDueAt) }
    var clearDue by remember { mutableStateOf(false) }
    var priority by remember(initialPriority) { mutableStateOf(initialPriority) }
    val zone = ZoneId.systemDefault()
    fun startOfDay(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item {
            Text(
                stringResource(R.string.wear_title_hint),
                style = MaterialTheme.typography.caption1,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        item {
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                textStyle = TextStyle(color = MaterialTheme.colors.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colors.onSurface),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        val dueOptions: List<Pair<Int, () -> Unit>> = listOf(
            R.string.wear_due_none to { clearDue = true; dueAt = null },
            R.string.wear_due_today to {
                clearDue = false; dueAt = startOfDay(LocalDate.now(zone))
            },
            R.string.wear_due_tomorrow to {
                clearDue = false; dueAt = startOfDay(LocalDate.now(zone).plusDays(1))
            },
            R.string.wear_due_next_week to {
                clearDue = false; dueAt = startOfDay(LocalDate.now(zone).plusWeeks(1))
            },
        )
        dueOptions.forEach { (label, apply) ->
            item {
                Chip(
                    onClick = apply,
                    label = { Text(stringResource(label), maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        for (p in 0..4) {
            item {
                Chip(
                    onClick = { priority = p },
                    label = {
                        Text(
                            stringResource(
                                R.string.wear_priority,
                                if (p == 0) "none" else p.toString(),
                            ),
                            maxLines = 1,
                        )
                    },
                    colors = if (priority == p) {
                        ChipDefaults.primaryChipColors()
                    } else {
                        ChipDefaults.secondaryChipColors()
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        item {
            Chip(
                onClick = {
                    onSave(title.trim().takeIf { it.isNotEmpty() }, dueAt, clearDue, priority)
                    onDone()
                },
                label = { Text(stringResource(R.string.wear_save), maxLines = 1) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                onClick = onDone,
                label = { Text(stringResource(R.string.wear_back), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}

/** Every cached task across pages (edit/detail lookups are not page-scoped). */
internal fun WearTodoStore.pagesForAll() =
    pages.value.values.flatMap { it.tasks }.distinctBy { it.syncId }
