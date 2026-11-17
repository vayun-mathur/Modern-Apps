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


/** The editor: picked identity, medical fields, a Health Connect prompt, contacts add/remove. */
@Composable
fun EditInfoScreen(
    state: EmergencyUiState,
    actions: EmergencyActions,
    onPickContact: () -> Unit,
    onPickOwner: () -> Unit = {},
    onConnectHealth: () -> Unit = {},
) {
    // Local copies so typing does not wait for a repository round-trip; saved on each keystroke
    // (SharedPreferences writes are cheap) so leaving mid-edit loses nothing.
    var bloodType by remember(state.info.bloodType) { mutableStateOf(state.info.bloodType) }
    var organDonor by remember(state.info.organDonor) { mutableStateOf(state.info.organDonor) }
    val save = {
        actions.saveMedicalInfo(bloodType, organDonor)
    }

    LazyListScaffold(
        title = stringResource(R.string.app_name),
        horizontalPadding = 0.dp,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        item {
            SettingsSection(title = stringResource(R.string.section_owner_identity)) {
                val name = state.info.name
                if (name.isBlank()) {
                    SettingsRow(
                        title = stringResource(R.string.owner_identity_empty),
                        onClick = onPickOwner,
                    )
                } else {
                    val address = state.info.address
                    SettingsRow(
                        title = name,
                        supportingText = address.ifBlank { null },
                        onClick = onPickOwner,
                    )
                }
                SettingsRow(
                    title = stringResource(R.string.choose_owner_contact),
                    onClick = onPickOwner,
                )
                if (state.ownerPickFailed) {
                    SettingsRow(
                        title = stringResource(R.string.fail_pick_owner),
                        onClick = actions::clearOwnerPickFailed,
                    )
                }
            }
        }
        item {
            MedicalDropdown(
                label = stringResource(R.string.field_blood_type),
                unknownLabel = stringResource(R.string.organ_donor_unknown),
                value = bloodType,
                options = BLOOD_TYPE_OPTIONS,
                optionLabel = { it },
                onChange = { bloodType = it; save() },
            )
        }
        item {
            // Resolved here (composable scope): optionLabel below is a plain
            // lambda, so it can only capture strings, not call stringResource.
            val donorYes = stringResource(R.string.organ_donor_yes)
            val donorNo = stringResource(R.string.organ_donor_no)
            MedicalDropdown(
                label = stringResource(R.string.field_organ_donor),
                unknownLabel = stringResource(R.string.organ_donor_unknown),
                value = organDonor,
                options = listOf(ORGAN_DONOR_YES, ORGAN_DONOR_NO),
                optionLabel = { if (it == ORGAN_DONOR_YES) donorYes else donorNo },
                onChange = { organDonor = it; save() },
            )
        }

        if (state.health.available) {
            item {
                SettingsSection(title = stringResource(R.string.section_medical_info)) {
                    if (!state.health.granted) {
                        SettingsRow(
                            title = stringResource(R.string.health_connect_connect),
                            supportingText = stringResource(R.string.health_connect_hint),
                            onClick = onConnectHealth,
                        )
                    } else {
                        SettingsRow(
                            title = stringResource(R.string.health_connect_connected),
                            supportingText = stringResource(R.string.health_connect_hint),
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.section_emergency_contacts)) {
                if (!state.hasContactsAccess) {
                    SettingsRow(
                        title = stringResource(R.string.contacts_permission_title),
                        supportingText = stringResource(R.string.contacts_permission_hint),
                        onClick = onPickContact,
                    )
                } else if (state.loading) {
                    SettingsRow(title = stringResource(R.string.contacts_loading))
                } else {
                    SettingsRow(
                        title = stringResource(R.string.add_emergency_contact),
                        onClick = onPickContact,
                    )
                }
                if (state.addFailed) {
                    SettingsRow(
                        title = stringResource(R.string.fail_add_contact),
                        onClick = actions::clearAddFailed,
                    )
                }
            }
        }

        for (contact in state.info.contacts) {
            item(key = contact.phoneUri.toString()) {
                SettingsRow(
                    title = contact.displayName.ifBlank { contact.phoneNumber },
                    supportingText = contactSubtitle(contact.phoneType, contact.phoneNumber),
                    trailingContent = {
                        TextButton(onClick = { actions.removeContact(contact.phoneUri) }) {
                            Text(stringResource(R.string.remove_contact))
                        }
                    },
                )
            }
        }
    }

    val candidates = state.ownerCandidates
    if (candidates != null) {
        OwnerAddressDialog(
            name = candidates.name,
            addresses = candidates.addresses,
            onConfirm = actions::confirmOwnerAddress,
            onDismiss = actions::dismissOwnerPick,
        )
    }
}

/** One medical field: a titled section holding a dropdown with an unknown option. */
@Composable
private fun MedicalDropdown(
    label: String,
    unknownLabel: String,
    value: String,
    options: List<String>,
    optionLabel: (String) -> String,
    onChange: (String) -> Unit,
) {
    // A legacy free-text value outside the options is kept as a fallback entry
    // so switching to dropdowns never silently drops stored data.
    val allOptions = remember(value, options) {
        if (value.isBlank() || value in options) listOf("") + options
        else listOf("") + options + value
    }
    FormSection(title = label) {
        SettingsExposedSelectRow(
            label = label,
            selected = value,
            options = allOptions,
            itemLabel = { if (it.isBlank()) unknownLabel else optionLabel(it) },
            onSelect = onChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}

private fun contactSubtitle(type: String, number: String): String =
    if (type.isBlank()) number else "$type · $number"
