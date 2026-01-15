package com.vayunmathur.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.DialogSceneStrategy
import com.vayunmathur.contacts.ui.ContactList
import com.vayunmathur.contacts.ui.ContactListPick
import com.vayunmathur.contacts.ui.dialog.EventDatePickerDialog
import com.vayunmathur.contacts.ui.dialog.EventDeleteConfirmDialog
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.pop
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val permissions = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            var hasPermissions by remember { mutableStateOf(permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) }
            DynamicTheme {
                if (!hasPermissions) {
                    NoPermissionsScreen(permissions) { hasPermissions = it }
                } else {
                    val viewModel: ContactViewModel = viewModel()
                    // If the app was launched with ACTION_PICK/GET_CONTENT, forward to the picker flow (same as before).
                    if (intent.action == Intent.ACTION_PICK || intent.action == Intent.ACTION_GET_CONTENT) {
                        var type = intent.type
                        if (intent.data?.toString()?.contains("phones") == true) {
                            type = CDKPhone.CONTENT_ITEM_TYPE
                        }
                        val contacts by viewModel.contacts.collectAsState()
                        ContactListPick(type, contacts) {
                            val resultIntent = Intent().apply {
                                data = it
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            setResult(RESULT_OK, resultIntent)
                            finish()
                        }
                    } else {
                        Navigation(viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun NoPermissionsScreen(permissions: Array<String>, setHasPermissions: (Boolean) -> Unit) {
    val permissionRequestor = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsResult ->
        setHasPermissions(permissionsResult.values.all { it })
    }
    LaunchedEffect(Unit) {
        permissionRequestor.launch(permissions)
    }
    Scaffold {
        Box(
            modifier = Modifier
                .padding(it)
                .fillMaxSize()
        ) {
            androidx.compose.material3.Button(
                {
                    permissionRequestor.launch(permissions)
                }, Modifier.align(Alignment.Center)
            ) {
                Text(text = "Please grant contacts permission")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun Navigation(viewModel: ContactViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.ContactsList)

    MainNavigation(backStack) {
        entry<Route.ContactsList>(metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("Select a contact to view details")
            }
        })) {
            ContactList(
                viewModel = viewModel,
                backStack = backStack,
                onContactClick = { contact ->
                    if(backStack.last() is Route.ContactDetail || backStack.last() is Route.EditContact) {
                        backStack[backStack.lastIndex] = Route.ContactDetail(contact.id)
                    } else {
                        backStack.add(Route.ContactDetail(contact.id))
                    }
                },
                onAddContactClick = {
                    if(backStack.last() is Route.ContactDetail) {
                        backStack.pop()
                    }
                    backStack.add(Route.EditContact(null))
                }
            )
        }
        entry<Route.ContactDetail>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
            ContactDetailsPage(
                viewModel = viewModel,
                contactId = key.contactId,
                onBack = { backStack.pop() },
                onEdit = { id -> backStack.add(Route.EditContact(id)) },
                onDelete = {
                    // Show the delete confirmation dialog using the contact id and name
                    val contact = viewModel.getContact(key.contactId)
                    backStack.add(Route.EventDeleteConfirmDialog(key.contactId, contact?.name?.value))
                },
                showBackButton = true
            )
        }
        entry<Route.EditContact>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
            EditContactPage(backStack, viewModel, key.contactId)
        }

        entry<Route.EventDatePickerDialog>(metadata = DialogSceneStrategy.dialog()) { key ->
            EventDatePickerDialog(key.id, key.initialDate) { backStack.pop() }
        }

        entry<Route.EventDeleteConfirmDialog>(metadata = DialogSceneStrategy.dialog()) { key ->
            EventDeleteConfirmDialog(key.contactId, key.contactName, viewModel, onConfirm = {
                // After confirming deletion, pop the dialog and the detail page
                backStack.pop()
                backStack.pop()
            }, onDismiss = {
                // Only close the dialog
                backStack.pop()
            })
        }
    }
}

sealed interface Route: NavKey {
    @Serializable
    object ContactsList : Route

    @Serializable
    data class ContactDetail(val contactId: Long) : Route

    @Serializable
    data class EditContact(val contactId: Long?) : Route

    @Serializable
    data class EventDatePickerDialog(val id: String, val initialDate: LocalDate?): Route

    @Serializable
    data class EventDeleteConfirmDialog(val contactId: Long, val contactName: String?): Route
}
