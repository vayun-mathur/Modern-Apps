package com.vayunmathur.contacts.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ui.R as UiR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OverflowMenu
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.isCompactHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vayunmathur.contacts.data.isDefaultLocalAccount
import com.vayunmathur.contacts.data.isLocalAccountType
import com.vayunmathur.contacts.util.ContactAccount
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.util.setAccountVisibility
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.ui.dialogs.DeleteAccountDialog
import com.vayunmathur.contacts.ui.dialogs.RenameAccountDialog
import com.vayunmathur.contacts.util.VcfUtils
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(viewModel: ContactViewModel, backStack: NavBackStack<Route>) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val hiddenAccounts by viewModel.hiddenAccounts.collectAsStateWithLifecycle()
    val isCalendarSyncEnabled by viewModel.isCalendarSyncEnabled.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val allContactsForExport by viewModel.allContactsForExport.collectAsStateWithLifecycle()
    val simLabels by viewModel.simAccountLabels.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var renameTarget by remember { mutableStateOf<ContactAccount?>(null) }
    var deleteTarget by remember { mutableStateOf<ContactAccount?>(null) }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            viewModel.setCalendarSyncEnabled(true)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/vcard"),
        onResult = { uri ->
            uri?.let {
                coroutineScope.launch {
                    // Broad catch is deliberate: export crosses provider, VCF
                    // encoding and document writes, and must log rather than
                    // crash the settings screen.
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        val exportList = allContactsForExport.ifEmpty { contacts }
                        context.contentResolver.openOutputStream(it)?.use { outputStream ->
                            VcfUtils.exportContacts(exportList, outputStream)
                        }
                    } catch (e: java.io.FileNotFoundException) {
                        Log.error("SettingsPage", "Error exporting contacts", e)
                    } catch (e: SecurityException) {
                        Log.error("SettingsPage", "Error exporting contacts", e)
                    } catch (e: java.io.IOException) {
                        Log.error("SettingsPage", "Error exporting contacts", e)
                    } catch (e: Exception) {
                        Log.error("SettingsPage", "Error exporting contacts", e)
                    }
                }
            }
        }
    )

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            backStack.add(Route.ImportVcf(uris.map { it.toString() }))
        }
    }

    LazyListScaffold(
        horizontalPadding = 16.dp,
        floatingActionButton = {
            FloatingActionButton(onClick = { backStack.add(Route.AddAccountDialog) }) {
                IconAdd()
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) {
            item {
                Text(
                    text = stringResource(R.string.calendar_sync),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                val hasCalendarPermissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                    .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

                // #537 RU overflow: Button was in trailingContent (horizontal Row) and
                // clipped for long translations. Move it below the description in a
                // vertical supportingContent Column so it wraps in any locale; Switch
                // stays in trailingContent only when permission is granted.
                ListItem(
                    content = { Text(stringResource(R.string.sync_contacts_calendar)) },
                    supportingContent = if (!hasCalendarPermissions) {
                        {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Button(onClick = {
                                    calendarPermissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                                }) {
                                    Text(stringResource(R.string.grant_calendar_permissions))
                                }
                            }
                        }
                    } else null,
                    trailingContent = if (hasCalendarPermissions) {
                        {
                            Switch(
                                checked = isCalendarSyncEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.setCalendarSyncEnabled(enabled)
                                }
                            )
                        }
                    } else null
                )
                HorizontalDivider()
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.display),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                val showAccountLabels by viewModel.showAccountLabels.collectAsStateWithLifecycle()
                ListItem(
                    content = { Text(stringResource(R.string.show_account_labels)) },
                    trailingContent = {
                        Switch(
                            checked = showAccountLabels,
                            onCheckedChange = { viewModel.setShowAccountLabels(it) }
                        )
                    }
                )
                HorizontalDivider()
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.backup_and_export),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                ListItem(
                    content = { Text(stringResource(R.string.export_contacts)) },
                    trailingContent = {
                        IconButton(onClick = { exportLauncher.launch("contacts.vcf") }) {
                            IconDownload()
                        }
                    }
                )
                HorizontalDivider()
                ListItem(
                    content = { Text(stringResource(R.string.import_vcf_file)) },
                    supportingContent = { Text(stringResource(R.string.import_contacts_from_vcf_files)) },
                    modifier = Modifier.clickable {
                        importLauncher.launch(arrayOf(
                            "text/vcard",
                            "text/x-vcard",
                            "text/directory",
                            "text/x-vcard",
                            "application/vcard",
                            "*/*"
                        ))
                    },
                    trailingContent = {
                        IconArrowDropDown()
                    }
                )
                HorizontalDivider()
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.visible_accounts),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(accounts, key = { "${it.type}|${it.name}" }) { account ->
                val key = "${account.type}|${account.name}"
                val legacyKey = account.name
                val isHidden = key in hiddenAccounts || legacyKey in hiddenAccounts
                val isVisible = !isHidden
                val onDevice = stringResource(R.string.on_device)
                val simLabel = simLabels[key]
                val displayName = simLabel ?: account.name.ifEmpty { onDevice }
                ListItem(
                    content = { Text(displayName) },
                    // The on-device account has no type string: emitting a blank supporting
                    // line leaves a gap and pushes the checkbox out of alignment (#800).
                    supportingContent = if (account.type.isNotBlank()) {
                        { Text(account.type) }
                    } else {
                        null
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isLocalAccountType(account.type) &&
                                !isDefaultLocalAccount(account.name, account.type)
                            ) {
                                OverflowMenu(icon = { IconMoreVert() }) {
                                    Item(
                                        text = stringResource(UiR.string.rename),
                                        leadingIcon = { IconEdit() },
                                        onClick = { renameTarget = account }
                                    )
                                    Item(
                                        text = stringResource(UiR.string.delete),
                                        leadingIcon = { IconDelete() },
                                        onClick = { deleteTarget = account }
                                    )
                                }
                            }
                            Checkbox(
                                checked = isVisible,
                                onCheckedChange = { viewModel.setAccountVisibility(account, it) }
                            )
                        }
                    }
                )
                HorizontalDivider()
            }
            item {
                // The tall FAB clearance is a portrait affordance; on short (landscape)
                // windows the scaffold's own bottom inset already clears the button.
                Spacer(modifier = Modifier.height(if (isCompactHeight()) 16.dp else 80.dp))
            }
    }

        renameTarget?.let { target ->
            RenameAccountDialog(
                viewModel = viewModel,
                target = target,
                onDismiss = { renameTarget = null }
            )
        }

        deleteTarget?.let { target ->
            DeleteAccountDialog(
                viewModel = viewModel,
                target = target,
                onDismiss = { deleteTarget = null }
            )
        }
}
