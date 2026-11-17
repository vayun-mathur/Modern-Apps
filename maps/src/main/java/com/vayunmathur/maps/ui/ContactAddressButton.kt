package com.vayunmathur.maps.ui
import com.vayunmathur.maps.data.string
import com.vayunmathur.maps.platform.launchContactPicker
import com.vayunmathur.maps.platform.launchPostalPicker
import com.vayunmathur.maps.platform.newSystemContactPickerIntent
import com.vayunmathur.maps.platform.readAddress
import com.vayunmathur.maps.platform.readContactAddresses
import com.vayunmathur.maps.platform.readSessionAddresses

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconContacts
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.maps.R as MapsR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Contact-address shortcut (P17): a button (sat next to the P8 mic) that lets the
 * user grab a postal address from their contacts and drop it into the search box.
 *
 * P31 rework — pick a specific ADDRESS, never a whole contact, and never silently
 * collapse a contact's several addresses to the first.
 *
 * On Android 17 (API 37, [Build.VERSION_CODES.CINNAMON_BUN]) we use the new system
 * Contact Picker ([ContactsPickerSessionContract.ACTION_PICK_CONTACTS]). The full
 * contract exposes only these config extras — there is NO dedicated "show data
 * items instead of contacts" mode flag:
 *   • [ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS] —
 *     the mimetypes to surface; we pass only [StructuredPostal.CONTENT_ITEM_TYPE]
 *     so the picker filters to (and displays) postal-address data.
 *   • [ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_SELECTION_LIMIT] — the
 *     max number of items selectable; we set 1 to request native single-item
 *     selection of one address.
 *   • [ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_MATCH_ALL_DATA_FIELDS] —
 *     ANY-vs-ALL field matching (irrelevant with a single requested field).
 *   • [Intent.EXTRA_USE_SYSTEM_CONTACTS_PICKER] — force the system picker even when
 *     targeting a lower SDK on an Android 17 device.
 * We deliberately omit [Intent.EXTRA_ALLOW_MULTIPLE] (single-select).
 *
 * The picker returns a short-lived *session URI* over which we read exactly the
 * chosen row(s) — no READ_CONTACTS. Because the contract has no guaranteed
 * pre-selected-single-item guarantee across OEMs, we read *all* StructuredPostal
 * addresses in the session: exactly one → use it; several → present an in-app
 * chooser ([AddressChooserDialog]) so the user picks the specific address rather
 * than us taking the first.
 *
 * Older devices (and Android 17 images without the picker) fall through, and
 * nothing ever crashes:
 *  1. Fallback — the postal-row picker: [Intent.ACTION_PICK] typed to
 *     [StructuredPostal.CONTENT_TYPE] (`vnd.android.cursor.dir/postal-address_v2`).
 *     The system contacts app returns the chosen StructuredPostal *data row* URI
 *     with an implicit per-row read grant, so we read it with no READ_CONTACTS.
 *  2. Fallback — the AndroidX [ActivityResultContracts.PickContact] contract,
 *     which returns a whole-contact URI. We resolve its postal addresses (needs
 *     READ_CONTACTS, requested via the shared [rememberPermissionRequest]); if the
 *     contact has several, the same in-app chooser is shown.
 *
 * A cancel, a contact with no postal address, or an OEM with no picker at all is a
 * quiet no-op (with a brief toast only when literally nothing can handle the pick).
 * Each hit reads [StructuredPostal.FORMATTED_ADDRESS] (falling back to composing
 * street/city/region/postcode/country) and hands the string to [onAddress].
 */
@Composable
fun ContactAddressButton(onAddress: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messenger = rememberMessenger()
    val noPickerMessage = stringResource(MapsR.string.no_contact_picker)
    val reportNoPicker = { messenger.show(noPickerMessage) }

    // A whole-contact URI whose postal read was refused for lack of READ_CONTACTS;
    // retried once the permission is granted. Null while nothing is pending.
    var pendingContact by remember { mutableStateOf<Uri?>(null) }

    // When a pick yields several addresses we can't disambiguate, we show a chooser.
    var addressChoices by remember { mutableStateOf<List<String>>(emptyList()) }

    // Deliver the resolved address(es): none → no-op; one → use it; several → let
    // the user pick the specific one (never collapse to the first).
    fun deliver(addresses: List<String>) {
        when {
            addresses.isEmpty() -> Unit
            addresses.size == 1 -> onAddress(addresses.first())
            else -> addressChoices = addresses
        }
    }

    val requestContacts =
        rememberPermissionRequest(Manifest.permission.READ_CONTACTS) { granted ->
            val contact = pendingContact
            pendingContact = null
            if (granted && contact != null) {
                deliver(runCatching { readContactAddresses(context, contact) }.getOrDefault(emptyList()))
            }
        }

    // Fallback #2: pick a whole contact, then resolve its postal address(es)
    // (needs READ_CONTACTS). Cancel → null → no-op.
    val contactPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickContact(),
    ) { contact ->
        if (contact == null) return@rememberLauncherForActivityResult
        try {
            deliver(readContactAddresses(context, contact))
        } catch (_: SecurityException) {
            // No implicit grant on a whole-contact pick — get READ_CONTACTS and retry.
            pendingContact = contact
            requestContacts()
        }
    }

    // Fallback #1: the system postal-address picker returns a specific
    // StructuredPostal data row with an implicit per-row read grant.
    val postalPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Cancel (or no data) → no-op.
        val row = result.data?.data ?: return@rememberLauncherForActivityResult
        try {
            val address = readAddress(context, row)
            if (address != null) {
                onAddress(address)
            } else {
                // Row carried no address (or wasn't a postal row) — fall back to
                // the whole-contact picker.
                launchContactPicker(contactPicker, reportNoPicker)
            }
        } catch (_: SecurityException) {
            // The implicit per-row grant didn't cover the query on this OEM —
            // fall back to the whole-contact picker + READ_CONTACTS path.
            launchContactPicker(contactPicker, reportNoPicker)
        }
    }

    // Primary (Android 17+): the new system Contact Picker returns a session URI
    // scoped to the user's chosen postal address(es). We read it off the main thread.
    val addressPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val sessionUri = result.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            val addresses = withContext(Dispatchers.IO) {
                runCatching { readSessionAddresses(context, sessionUri) }.getOrDefault(emptyList())
            }
            deliver(addresses)
        }
    }

    IconButton(
        onClick = {
            // Read the address purely from the picker's returned URI (temporary
            // per-URI read grant) — no READ_CONTACTS. On Android 17 the system
            // Contact Picker returns a session URI we query directly.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                try {
                    addressPicker.launch(newSystemContactPickerIntent())
                } catch (_: ActivityNotFoundException) {
                    reportNoPicker()
                }
            } else {
                // Older devices: the legacy postal-row ACTION_PICK also returns a
                // row URI with an implicit read grant (no READ_CONTACTS).
                launchPostalPicker(postalPicker, contactPicker, reportNoPicker)
            }
        },
        modifier = modifier,
    ) {
        IconContacts()
    }

    if (addressChoices.isNotEmpty()) {
        AddressChooserDialog(
            addresses = addressChoices,
            onPick = { picked ->
                addressChoices = emptyList()
                onAddress(picked)
            },
            onDismiss = { addressChoices = emptyList() },
        )
    }
}

/** In-app chooser for when a pick resolves to several postal addresses: lists each
 *  [FORMATTED_ADDRESS] (or composed) string; tapping one selects it. */
@Composable
private fun AddressChooserDialog(
    addresses: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(com.vayunmathur.maps.R.string.choose_address)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                items(addresses) { address ->
                    ListItem(
                        content = { Text(address) },
                        modifier = Modifier.clickable { onPick(address) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )
}

