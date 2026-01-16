package com.vayunmathur.contacts

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconSave

class ImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data ?: return

        val contacts = contentResolver.openInputStream(uri)?.use { stream ->
            VcfUtils.parseContacts(stream)
        } ?: emptyList()

        setContent {
            DynamicTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ImportScreen(contacts) {
                        contacts.forEach { it.save(this@ImportActivity, it.details, ContactDetails.empty()) }
                        startActivity(Intent(this, MainActivity::class.java))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
fun ImportScreen(contacts: List<Contact>, onImport: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar({Text("Import Contacts")}) },
        floatingActionButton = {
            FloatingActionButton(onClick = onImport) {
                IconSave()
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(contacts) { contact ->
                Card(modifier = Modifier.padding(4.dp).fillMaxSize()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = contact.name.value, style = MaterialTheme.typography.titleMedium)
                        if (contact.details.phoneNumbers.isNotEmpty()) {
                            Text(text = contact.details.phoneNumbers.joinToString(", ") { it.number }, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (contact.details.emails.isNotEmpty()) {
                            Text(text = contact.details.emails.joinToString(", ") { it.address }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
