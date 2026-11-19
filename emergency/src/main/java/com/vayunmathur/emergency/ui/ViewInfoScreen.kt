package com.vayunmathur.emergency.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.emergency.R
import com.vayunmathur.emergency.data.AllergyCriticality
import com.vayunmathur.emergency.data.EmergencyInfo
import com.vayunmathur.emergency.data.ORGAN_DONOR_NO
import com.vayunmathur.emergency.data.ORGAN_DONOR_YES
import com.vayunmathur.emergency.data.normalizeBloodType
import com.vayunmathur.emergency.data.normalizeOrganDonor
import com.vayunmathur.emergency.platform.EmergencyUiState
import com.vayunmathur.emergency.platform.EmergencyViewModel
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.verticalShape

/** The read-only info + contacts view. Stateless; the activity wires the ViewModel. */
@Composable
fun ViewInfoScreen(state: EmergencyUiState) {
    // Resolved up front: the LazyListScope content below is not itself @Composable,
    // so stringResource() calls must happen here or inside item {} blocks.
    val ownerLabel = stringResource(R.string.group_owner)
    val contactLabel = stringResource(R.string.group_emergency_contact)
    val allergiesLabel = stringResource(R.string.section_allergies)
    val conditionsLabel = stringResource(R.string.section_conditions)
    val medicationsLabel = stringResource(R.string.section_current_medications)
    val allergyHigh = stringResource(R.string.allergy_high)
    val allergyLow = stringResource(R.string.allergy_low)
    val ownerRows = ownerRows(state.info)
    LazyListScaffold(
        // No title and no actions: the scaffold draws no bar, so the content starts
        // at the top. scrollBehavior is still required for the nested-scroll wiring.
    ) {
        if (state.loading) {
            item {
                Segment(value = stringResource(R.string.contacts_loading), index = 0, count = 1)
            }
        } else if (ownerRows.isEmpty() && state.info.contacts.isEmpty() &&
            state.health.allergies.isEmpty() && state.health.conditions.isEmpty() &&
            state.health.medications.isEmpty()
        ) {
            item {
                Segment(value = stringResource(R.string.no_info), index = 0, count = 1)
            }
        } else {
            if (ownerRows.isNotEmpty()) {
                group(ownerLabel, ownerRows)
            }
            for ((contactIndex, contact) in state.info.contacts.withIndex()) {
                val name = contact.displayName.ifBlank { contact.phoneNumber }
                val detail = if (contact.phoneType.isBlank()) {
                    contact.phoneNumber
                } else {
                    "${contact.phoneType} · ${contact.phoneNumber}"
                }
                val title = if (state.info.contacts.size == 1) {
                    contactLabel
                } else {
                    "$contactLabel ${contactIndex + 1}"
                }
                group(title, listOf(name to null, detail to null))
            }
            if (state.health.allergies.isNotEmpty()) {
                group(
                    allergiesLabel,
                    state.health.allergies.map { allergy ->
                        val severity = when (allergy.criticality) {
                            AllergyCriticality.High -> allergyHigh
                            AllergyCriticality.Low -> allergyLow
                            AllergyCriticality.Unknown -> null
                        }
                        allergy.displayName to listOfNotNull(allergy.reaction, severity)
                            .joinToString(" · ").ifBlank { null }
                    },
                )
            }
            if (state.health.conditions.isNotEmpty()) {
                group(
                    conditionsLabel,
                    state.health.conditions.map { it.displayName to null },
                )
            }
            if (state.health.medications.isNotEmpty()) {
                group(
                    medicationsLabel,
                    state.health.medications.map { it.displayName to null },
                )
            }
        }
    }
}

/**
 * One labelled group of segments: a small heading plus each value as a joined block.
 * Each entry is a value with an optional detail line underneath.
 */
private fun LazyListScope.group(title: String, rows: List<Pair<String, String?>>) {
    item(key = "header-$title") {
        Text(
            text = title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 4.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
    }
    rows.forEachIndexed { index, (value, detail) ->
        item(key = "$title-$index-$value") {
            Segment(value = value, detail = detail, index = index, count = rows.size)
        }
    }
}

/**
 * One datum as a full-width segment of its group: the value large and centered, an
 * optional detail smaller beneath it. Outer corners rounded, inner joins square, so
 * the group reads as one block.
 */
@Composable
private fun Segment(value: String, detail: String? = null, index: Int, count: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp),
        shape = verticalShape(index, count),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineSmall,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/**
 * Owner group rows: stored name/address when set. The group title already says
 * who this is, so rows carry just values (address keeps its detail line).
 * Blood type / organ donor join as extra cards under the identity when known;
 * unknown (blank) stays hidden so a responder never reads a noise row.
 */
@Composable
private fun ownerRows(info: EmergencyInfo): List<Pair<String, String?>> {
    val rows = mutableListOf<Pair<String, String?>>()
    if (info.name.isNotBlank()) rows += info.name to null
    val addressLabel = stringResource(R.string.field_address)
    if (info.address.isNotBlank()) rows += info.address to addressLabel
    val bloodLabel = stringResource(R.string.field_blood_type)
    val blood = normalizeBloodType(info.bloodType)
    if (blood.isNotBlank()) rows += blood to bloodLabel
    val donorLabel = stringResource(R.string.field_organ_donor)
    when (normalizeOrganDonor(info.organDonor)) {
        ORGAN_DONOR_YES -> rows += stringResource(R.string.organ_donor_yes) to donorLabel
        ORGAN_DONOR_NO -> rows += stringResource(R.string.organ_donor_no) to donorLabel
    }
    return rows
}
