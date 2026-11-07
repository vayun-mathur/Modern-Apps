package com.vayunmathur.education.ui

import androidx.compose.ui.platform.LocalContext
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DatePicker
import com.vayunmathur.library.ui.DatePickerDialog
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.content.Band
import com.vayunmathur.education.content.Grades
import com.vayunmathur.education.content.ModuleType
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.vayunmathur.education.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentPage(backStack: NavBackStack<Route>, viewModel: EducationViewModel) {
    val learner by viewModel.learner.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    // Must be collected, not just read via deadlineFor(): the deadlines
    // StateFlow is WhileSubscribed, so without a collector on this screen it
    // freezes at whatever it last held and deadline edits made here would
    // never show up (Set date would keep reading "Set date").
    val deadlines by viewModel.deadlines.collectAsStateWithLifecycle()
    val content = viewModel.content
    val l = learner ?: return

    // Which unit (if any) is currently having its deadline picked.
    var pendingDeadlineUnitId by remember { mutableStateOf<String?>(null) }

    AppScaffold(
        title = stringResource(R.string.parent_settings),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // --- Learner ---
            SectionHeader(stringResource(R.string.learner))
            var name by remember { mutableStateOf(l.name) }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; viewModel.setName(it) },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.PersonName),
                modifier = Modifier.fillMaxWidth(),
            )

            GradeDropdown(current = l.gradeLevel, onSelect = viewModel::setGrade)
            BandOverrideDropdown(current = l.bandOverride, onSelect = viewModel::setBandOverride)
            Text(
                stringResource(R.string.active_band, bandLabel(viewModel.bandOf(l))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            DailyGoalRow(goal = l.dailyGoal, onChange = viewModel::setDailyGoal)

            HorizontalDivider()

            // --- Progress ---
            SectionHeader(stringResource(R.string.progress))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StreakChip(l.streakCount)
                StarsChip(l.totalStars)
            }
            val practiced = progress.values.count { it.stars > 0 }
            Text(
                pluralStringResource(R.plurals.skill_stars_earned, practiced, practiced),
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()

            // --- Deadlines ---
            SectionHeader(stringResource(R.string.module_deadlines))
            Text(
                stringResource(R.string.set_a_target_date_for_a_unit_it_shows_in),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content.courses.forEach { course ->
                Text(
                    course.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                course.units.forEach { unit ->
                    val deadline = deadlines.firstOrNull {
                        it.moduleType == ModuleType.UNIT.name && it.moduleId == unit.id
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(unit.title, style = MaterialTheme.typography.bodyLarge)
                            deadline?.let {
                                Text(
                                    stringResource(R.string.due, formatEpochDay(LocalContext.current, it.dueEpochDay)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (deadline != null) {
                            IconButton(onClick = { viewModel.removeDeadline(deadline) }) { IconDelete() }
                        }
                        OutlinedButton(onClick = { pendingDeadlineUnitId = unit.id }) {
                            Text(if (deadline == null) stringResource(R.string.set_date) else stringResource(R.string.change))
                        }
                    }
                }
            }

            HorizontalDivider()

            // --- Security ---
            SectionHeader(stringResource(R.string.security))
            ChangePinRow(onSetPin = viewModel::setPin)
        }
    }

    pendingDeadlineUnitId?.let { unitId ->
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { pendingDeadlineUnitId = null },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        state.selectedDateMillis?.let { ms ->
                            val day = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date.toEpochDays()
                            viewModel.setDeadline(ModuleType.UNIT, unitId, day)
                        }
                        pendingDeadlineUnitId = null
                    },
                ) { Text(stringResource(UiR.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeadlineUnitId = null }) { Text(stringResource(UiR.string.cancel)) }
            },
        ) {
            DatePicker(state)
        }
    }
}

@Composable
private fun gradeLabel(grade: Int): String = when {
    grade <= 0 -> stringResource(R.string.grade_kindergarten)
    else -> stringResource(R.string.grade_number, grade)
}

@Composable
private fun GradeDropdown(current: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = gradeLabel(current),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.grade)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    IconArrowDropDown()
                }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Grades.all.forEach { g ->
                SelectableDropdownMenuItem(
                    selected = g == current,
                    onClick = { onSelect(g); expanded = false },
                    text = { Text(gradeLabel(g)) },
                    selectedLeadingIcon = { IconCheck() },
                )
            }
        }
        Box(Modifier.matchParentSize().clickable { expanded = true })
    }
}

@Composable
private fun BandOverrideDropdown(current: String?, onSelect: (Band?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = current?.let { runCatching { Band.valueOf(it) }.getOrNull() }
        ?.let { bandLabel(it) } ?: "Automatic (by grade)"
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.band_override)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    IconArrowDropDown()
                }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SelectableDropdownMenuItem(
                selected = current == null,
                onClick = { onSelect(null); expanded = false },
                text = { Text(stringResource(R.string.automatic_by_grade)) },
                selectedLeadingIcon = { IconCheck() },
            )
            Band.entries.forEach { band ->
                SelectableDropdownMenuItem(
                    selected = current == band.name,
                    onClick = { onSelect(band); expanded = false },
                    text = { Text(bandLabel(band)) },
                    selectedLeadingIcon = { IconCheck() },
                )
            }
        }
        Box(Modifier.matchParentSize().clickable { expanded = true })
    }
}

@Composable
private fun DailyGoalRow(goal: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(pluralStringResource(R.plurals.daily_goal_activities, goal, goal), Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((goal - 1).coerceAtLeast(1)) }) { Text("-") }
        Text("  $goal  ", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { onChange(goal + 1) }) { Text("+") }
    }
}

@Composable
private fun ChangePinRow(onSetPin: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) { pin = it; saved = false } },
            label = { Text(stringResource(R.string.new_pin)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { onSetPin(pin); pin = ""; saved = true },
            enabled = pin.length >= 4,
            modifier = Modifier.padding(start = 8.dp),
        ) { Text(if (saved) stringResource(R.string.saved) else stringResource(UiR.string.save)) }
    }
}
