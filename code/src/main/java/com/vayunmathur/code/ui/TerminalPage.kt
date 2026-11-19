package com.vayunmathur.code.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.code.R
import com.vayunmathur.code.Route
import com.vayunmathur.code.platform.EditorViewModel
import com.vayunmathur.code.platform.clearTerminal
import com.vayunmathur.code.platform.startTerminal
import com.vayunmathur.code.platform.terminalInterrupt
import com.vayunmathur.code.platform.terminalSend
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconSend
import com.vayunmathur.library.ui.IconStop
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack

/**
 * A line-based terminal scoped to the open project. Scrollback above, a monospace input with
 * Run/Stop below. Not a PTY: no ANSI colours or full-screen apps, system `sh`/toybox only, and
 * only the granted external-storage tree is reachable (see [com.vayunmathur.code.platform.TerminalSession]).
 */
@Composable
fun TerminalPage(viewModel: EditorViewModel, backStack: NavBackStack<Route>) {
    val hasFolder = viewModel.rootName != null

    LaunchedEffect(Unit) { if (hasFolder) viewModel.startTerminal() }

    AppScaffold(
        title = stringResource(R.string.terminal),
        backStack = backStack,
        actions = {
            IconButton(onClick = { viewModel.terminalInterrupt() }, enabled = hasFolder) { IconStop() }
            IconButton(onClick = { viewModel.clearTerminal() }) { IconDelete() }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!hasFolder) {
                Text(
                    stringResource(R.string.terminal_needs_folder),
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            val scroll = rememberScrollState()
            LaunchedEffect(viewModel.terminalLines.size) { scroll.animateScrollTo(scroll.maxValue) }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                viewModel.terminalLines.forEach { line ->
                    Text(
                        text = line,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            TerminalInput(onRun = { viewModel.terminalSend(it) })
        }
    }
}

@Composable
private fun TerminalInput(onRun: (String) -> Unit) {
    var command by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.terminal_hint)) },
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        )
        IconButton(
            onClick = {
                if (command.isNotEmpty()) {
                    onRun(command)
                    command = ""
                }
            },
        ) { IconSend() }
    }
}
