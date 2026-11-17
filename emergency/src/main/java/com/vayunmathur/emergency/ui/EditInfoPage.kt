package com.vayunmathur.emergency.ui
import android.Manifest
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.emergency.R
import com.vayunmathur.emergency.data.BLOOD_TYPE_OPTIONS
import com.vayunmathur.emergency.data.ORGAN_DONOR_NO
import com.vayunmathur.emergency.data.ORGAN_DONOR_YES
import com.vayunmathur.emergency.platform.EmergencyActions
import com.vayunmathur.emergency.platform.EmergencyUiState
import com.vayunmathur.emergency.platform.EmergencyViewModel
import com.vayunmathur.emergency.platform.HealthConnectMedical
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.FormSection
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.SettingsExposedSelectRow
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior

/**
 * Edits the owner's medical details and emergency contacts.
 *
 * Answers `android.settings.EDIT_EMERGENCY_INFO` like GrapheneOS's EditInfoActivity.
 * The owner's identity (name, and address when available) is picked from a contact
 * card with `ACTION_PICK` against `Contacts.CONTENT_URI` and snapshotted into
 * storage — never typed here. Emergency contacts are picked separately with
 * `ACTION_PICK` against `Phone.CONTENT_URI` so the stored value is the per-number
 * URI GrapheneOS persists (not the aggregate contact URI). Picking requires
 * `READ_CONTACTS`, requested here. Allergies and medications are never typed here -
 * they are read from Health Connect, which this screen only offers to connect.
 */
/** Binds the ViewModel to the stateless screen, owning the pick/permission launchers. */
@Composable
fun EditInfoPage(viewModel: EmergencyViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pickContact = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.data?.let { viewModel.addContact(it) }
    }
    val pickOwner = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.data?.let { viewModel.pickOwner(it) }
    }
    val requestContacts = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.refresh()
    }
    // Health Connect has its own permission contract, distinct from the runtime one above.
    // Refusal is fine - the card just keeps showing whatever is already granted.
    val requestHealth = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { viewModel.refreshHealthConnect() }

    val gatePick = { open: () -> Unit ->
        if (state.hasContactsAccess) {
            open()
        } else {
            requestContacts.launch(Manifest.permission.READ_CONTACTS)
        }
    }
    EditInfoScreen(
        state = state,
        actions = viewModel,
        onPickContact = {
            gatePick {
                pickContact.launch(
                    Intent(
                        Intent.ACTION_PICK,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    ),
                )
            }
        },
        onPickOwner = {
            gatePick {
                pickOwner.launch(
                    Intent(
                        Intent.ACTION_PICK,
                        ContactsContract.Contacts.CONTENT_URI,
                    ),
                )
            }
        },
        onConnectHealth = { requestHealth.launch(HealthConnectMedical.PERMISSIONS) },
    )
}
