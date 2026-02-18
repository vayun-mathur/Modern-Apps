package com.vayunmathur.findfamily.ui.dialog

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.findfamily.Networking
import com.vayunmathur.findfamily.Platform
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.pop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.time.Clock

@Composable
fun AddPersonDialog(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, platform: Platform, id: Long?) {
    val users by viewModel.data<User>().collectAsState()
    val usersByID = users.associateBy { it.id }

    var userid: String by remember { mutableStateOf(id?.encodeBase26() ?: "") }
    var contactName: String? by remember { mutableStateOf(null) }
    var contactPhoto by remember { mutableStateOf<String?>(null) }
    val requestPickContact2 = platform.requestPickContact { name, photo ->
        CoroutineScope(Dispatchers.Main).launch {
            contactName = name
            contactPhoto = photo
        }
    }

    val userStatus = usersByID[userid.decodeBase26()]?.requestStatus

    Dialog({backStack.pop()}) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Add Person", style = MaterialTheme.typography.headlineMedium)

                OutlinedTextField(
                    Networking.userid.encodeBase26(),
                    {},
                    interactionSource = interactionSourceClickable {
                        platform.copy(Networking.userid.encodeBase26())
                    },
                    label = { Text("Your FindFamily ID") },
                    trailingIcon = {
                        IconCopy()
                    },
                    readOnly = true
                )

                OutlinedTextField(
                    userid,
                    { userid = it },
                    readOnly = id != null,
                    label = { Text("Contact's FindFamily ID") },
                    isError = userStatus == RequestStatus.MUTUAL_CONNECTION || userStatus == RequestStatus.AWAITING_RESPONSE,
                    supportingText =
                        when (userStatus) {
                            RequestStatus.AWAITING_REQUEST -> { @Composable {Text("This person has requested your location")}}
                            RequestStatus.MUTUAL_CONNECTION -> {
                                if (userid == Networking.userid.encodeBase26())
                                    {@Composable {Text("Cannot share your location with yourself")}}
                                else {@Composable {Text("Already sharing location with this person")}}
                            }
                            RequestStatus.AWAITING_RESPONSE -> {@Composable {Text("Already requested to share with this person") } }
                            else -> null
                        }
                    )

                OutlinedTextField(contactName ?: "", {}, interactionSource = interactionSourceClickable {
                    requestPickContact2()
                }, label = { Text("Contact's Name") }, readOnly = true)

                Button(
                    {
                        val userToAdd = User(
                            contactName!!,
                            contactPhoto,
                            "Unknown Location",
                            true,
                            if (userStatus == RequestStatus.AWAITING_REQUEST) RequestStatus.MUTUAL_CONNECTION else RequestStatus.AWAITING_RESPONSE,
                            Clock.System.now(),
                            null,
                            userid.decodeBase26()
                        )
                        viewModel.upsert(userToAdd, {
                            backStack.pop()
                        })
                    },
                    enabled = userid.isNotBlank() && contactName != null && !(userStatus == RequestStatus.MUTUAL_CONNECTION || userStatus == RequestStatus.AWAITING_RESPONSE)
                ) {
                    if (userStatus == RequestStatus.AWAITING_REQUEST) {
                        Text("Accept Location Request")
                    } else {
                        Text("Request Location")
                    }
                }
            }
        }
    }
}

fun String.decodeBase26(): Long {
    var value = 0uL
    for(i in this.indices)
        value += (this[i].code - 65).toULong() * 26.0.pow(this.length - i - 1).toULong()
    return value.toLong()
}

fun Long.encodeBase26(): String {
    var result = ""
    var remaining = this.toULong()
    while(remaining > 0uL) {
        result = ((remaining % 26uL) + 65uL).toInt().toChar() + result
        remaining /= 26uL
    }
    return result
}

@Composable
fun interactionSourceClickable(onClick: () -> Unit): MutableInteractionSource {
    return remember { MutableInteractionSource() }
        .also { interactionSource ->
            LaunchedEffect(interactionSource) {
                interactionSource.interactions.collect {
                    if (it is PressInteraction.Release) {
                        onClick()
                    }
                }
            }
        }
}