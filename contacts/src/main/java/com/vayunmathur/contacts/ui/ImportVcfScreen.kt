package com.vayunmathur.contacts.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.util.ContactAccount
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.launch

enum class ImportMode { Existing, New }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportVcfScreen(
    viewModel: ContactViewModel,
    backStack: NavBackStack<Route>,
    uris: List<String>,
) {
    val uriList = remember(uris) { uris.map { Uri.parse(it) } }
    val parsedContacts by viewModel.parsedVcfContacts.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var importMode by remember { mutableStateOf(ImportMode.Existing) }
    var selectedAccount by remember { mutableStateOf<ContactAccount?>(null) }
    var newAccountName by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }

    // Kick off parsing
    LaunchedEffect(uriList) {
        viewModel.parseVcfUris(uriList)
    }

    // Clear on dispose
    DisposableEffect(Unit) {
        onDispose { viewModel.clearParsedVcf() }
    }

    // Auto-select first account
    LaunchedEffect(accounts) {
        if (selectedAccount == null) {
            selectedAccount = accounts.firstOrNull()
        }
    }

    val contacts = parsedContacts
    val canImport = when (importMode) {
        ImportMode.Existing -> selectedAccount != null && contacts?.isNotEmpty() == true && !isImporting
        ImportMode.New -> newAccountName.isNotBlank() && contacts?.isNotEmpty() == true && !isImporting
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_contacts)) },
                navigationIcon = { IconNavigation(backStack) }
            )
        },
        floatingActionButton = {
            if (canImport) {
                FloatingActionButton(onClick = {
                    val cnts = contacts ?: return@FloatingActionButton
                    isImporting = true
                    scope.launch {
                        when (importMode) {
                            ImportMode.Existing -> {
                                val account = selectedAccount ?: return@launch
                                viewModel.importVcfContacts(cnts, account.name, account.type) {
                                    isImporting = false
                                    backStack.pop()
                                }
                            }
                            ImportMode.New -> {
                                // Create account, then import
                                viewModel.createAccount(
                                    name = newAccountName,
                                    type = "com.vayunmathur.contacts.local",
                                    onComplete = {
                                        viewModel.importVcfContacts(cnts, newAccountName, "com.vayunmathur.contacts.local") {
                                            isImporting = false
                                            backStack.pop()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconSave()
                    }
                }
            }
        }
    ) { paddingValues ->
        when {
            contacts == null -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            contacts.isEmpty() -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.no_contacts_found))
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            stringResource(R.string.import_summary_contacts, contacts.size),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    // Mode selector
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { importMode = ImportMode.Existing }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = importMode == ImportMode.Existing,
                                        onClick = { importMode = ImportMode.Existing }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.import_to_existing_account))
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { importMode = ImportMode.New }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = importMode == ImportMode.New,
                                        onClick = { importMode = ImportMode.New }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.create_new_account_option))
                                }
                            }
                        }
                    }

                    // Mode-specific inputs
                    item {
                        when (importMode) {
                            ImportMode.Existing -> {
                                AccountSelectorDropdown(
                                    accounts = accounts,
                                    selectedAccount = selectedAccount,
                                    onSelect = { selectedAccount = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            ImportMode.New -> {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp)) {
                                        OutlinedTextField(
                                            value = newAccountName,
                                            onValueChange = { newAccountName = it },
                                            label = { Text(stringResource(R.string.account_name)) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Contacts list
                    items(contacts, key = { "${it.name.value}|${it.details.phoneNumbers.firstOrNull()?.number ?: ""}|${it.details.emails.firstOrNull()?.address ?: ""}" }) { contact ->
                        ContactCard(contact = contact)
                    }

                    item {
                        Spacer(Modifier.height(80.dp)) // FAB padding
                    }
                }
            }
        }
    }
}

@Composable
fun AccountSelectorDropdown(
    accounts: List<ContactAccount>,
    selectedAccount: ContactAccount?,
    modifier: Modifier = Modifier,
    onSelect: (ContactAccount) -> Unit,
) {
    var showDropdown by remember { mutableStateOf(false) }

    Box(modifier) {
        ListItem(
            content = { Text(selectedAccount?.name?.ifEmpty { stringResource(R.string.on_device) } ?: stringResource(R.string.select_account)) },
            trailingContent = { IconArrowDropDown() },
            modifier = Modifier.clickable { showDropdown = true },
        )
        DropdownMenu(expanded = showDropdown, onDismissRequest = { showDropdown = false }) {
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.name.ifEmpty { stringResource(R.string.on_device) }) },
                    onClick = {
                        onSelect(account)
                        showDropdown = false
                    },
                )
            }
        }
    }
}

@Composable
fun ContactCard(contact: Contact) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = contact.name.value.ifEmpty { stringResource(R.string.no_name) }, style = MaterialTheme.typography.titleMedium)
            contact.details.phoneNumbers.firstOrNull()?.let { phone ->
                Text(text = phone.number, style = MaterialTheme.typography.bodyMedium)
            }
            contact.details.emails.firstOrNull()?.let { email ->
                Text(text = email.address, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
