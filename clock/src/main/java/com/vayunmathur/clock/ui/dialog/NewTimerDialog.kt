package com.vayunmathur.clock.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.ui.sendTimerNotification
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.pop
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Composable
fun NewTimerDialog(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    var name by remember { mutableStateOf("") }
    var minutes: Int? by remember { mutableStateOf(null) }
    var seconds: Int? by remember { mutableStateOf(null) }
    val context = LocalContext.current
    Dialog({ backStack.pop() }) {
        Card {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(name, {name = it}, label = { Text("Label") })
                OutlinedTextField(minutes?.toString()?:"", {minutes = it.toUIntOrNull()?.toInt()}, label = { Text("Minutes") })
                OutlinedTextField(seconds?.toString()?:"", {seconds = it.toUIntOrNull()?.toInt()}, label = { Text("Seconds") })
                Row {
                    Button({
                        backStack.pop()
                    }) {
                        Text("Cancel")
                    }
                    Button({
                        val timer = Timer(true, name, Clock.System.now(), minutes!!.minutes + seconds!!.seconds, minutes!!.minutes + seconds!!.seconds)
                        viewModel.upsert(timer) {
                            sendTimerNotification(context, timer.copy(id = it), true)
                        }
                        backStack.pop()
                    }, enabled = minutes != null && seconds != null) {
                        Text("Save")
                    }
                }
            }
        }
    }
}