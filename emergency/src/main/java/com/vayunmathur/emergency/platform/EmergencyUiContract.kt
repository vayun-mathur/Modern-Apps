package com.vayunmathur.emergency.platform

import android.net.Uri
import com.vayunmathur.emergency.data.EmergencyContact
import com.vayunmathur.emergency.data.EmergencyInfo
import com.vayunmathur.emergency.data.ImportedAllergy
import com.vayunmathur.emergency.data.ImportedCondition
import com.vayunmathur.emergency.data.ImportedMedication

/**
 * The UI contract between [EmergencyViewModel]/[SosCoordinator] and the screens in `ui`.
 *
 * Pages take a state value plus an actions interface rather than the ViewModel itself, so
 * they can be rendered by a `@Preview` - which is what the store listing images are
 * generated from (see `src/screenshotTest`). It lives beside the ViewModel rather than in
 * `ui` so the dependency runs one way.
 */

/**
 * Allergies and current medications read from Health Connect, shown alongside the stored
 * fields. All read-only; empty and [available] false on devices without the PHR feature.
 */
data class HealthConnectMedicalState(
    /** The device's Health Connect module exposes Personal Health Records. */
    val available: Boolean = false,
    /** Both medical read permissions are granted. */
    val granted: Boolean = false,
    val allergies: List<ImportedAllergy> = emptyList(),
    val medications: List<ImportedMedication> = emptyList(),
    val conditions: List<ImportedCondition> = emptyList(),
)

/** Everything the view/edit screens draw. */
data class EmergencyUiState(
    val info: EmergencyInfo = EmergencyInfo(),
    /** True while the stored info is still being loaded. */
    val loading: Boolean = true,
    /** False until READ_CONTACTS is granted; the contact list must prompt, not show empty. */
    val hasContactsAccess: Boolean = false,
    /** Last add-contact failure, for a one-shot message; null when none. */
    val addFailed: Boolean = false,
    /** Allergies and current medications from Health Connect, when available and granted. */
    val health: HealthConnectMedicalState = HealthConnectMedicalState(),
    /** Pending owner pick with several addresses; null except while choosing. */
    val ownerCandidates: OwnerPickCandidates? = null,
    /** Last owner-pick failure, for a one-shot message; false when none. */
    val ownerPickFailed: Boolean = false,
)

/**
 * A picked owner whose contact has several postal addresses: the name is fixed
 * and the user must choose which address to snapshot.
 */
data class OwnerPickCandidates(
    val name: String,
    val addresses: List<String>,
)

/** Callbacks the emergency info screens use to persist edits and re-read stored or Health Connect data. */
interface EmergencyActions {
    /** Persists the dropdown-selected medical fields (identity comes from a pick). */
    fun saveMedicalInfo(bloodType: String, organDonor: String) {}

    /**
     * Snapshots the picked owner contact's identity; opens the address chooser
     * when several addresses are available, saves directly otherwise. Sets
     * [EmergencyUiState.ownerPickFailed] when the contact cannot be read.
     */
    fun pickOwner(contactUri: Uri) {}

    /** Saves the picked name with the chosen address and closes the chooser. */
    fun confirmOwnerAddress(address: String) {}

    /** Closes the address chooser without saving. */
    fun dismissOwnerPick() {}

    /** Clears the one-shot owner-pick failure flag after it has been shown. */
    fun clearOwnerPickFailed() {}

    /** Adds the picked contact URI; sets [EmergencyUiState.addFailed] when rejected. */
    fun addContact(phoneUri: Uri) {}

    fun removeContact(phoneUri: Uri) {}

    /** Re-reads stored info and re-checks the contacts grant. */
    fun refresh() {}

    /** Clears the one-shot add-failure flag after it has been shown. */
    fun clearAddFailed() {}

    /** Re-reads Health Connect allergies/medications after a permission grant. */
    fun refreshHealthConnect() {}

    /** No-op [EmergencyActions] for previews and tests. */
    companion object {
        val Noop: EmergencyActions = object : EmergencyActions {}
    }
}

/** Everything the SOS countdown screen draws. */
data class SosUiState(
    /** The number about to be dialled (override-aware). */
    val number: String = EmergencyNumberLookup.FALLBACK_NUMBER,
    /** Milliseconds left when the screen opened; the screen counts down from here. */
    val totalMillis: Long = 0,
    /** False when the gesture is off - the screen must exit, not count down. */
    val gestureEnabled: Boolean = false,
    /** True once the state above has been read. */
    val loaded: Boolean = false,
)

/** Callbacks the SOS countdown screen uses to continue in the background or cancel the countdown. */
interface SosActions {
    /** Hands the remaining countdown to the foreground service and finishes the screen. */
    fun continueInBackground(remainingMillis: Long) {}

    /** Cancels the countdown entirely (alarm + service + sound). */
    fun cancel() {}

    /** No-op [SosActions] for previews and tests. */
    companion object {
        val Noop: SosActions = object : SosActions {}
    }
}

/** Preview/sample contact for screenshots. */
fun sampleContact(): EmergencyContact = EmergencyContact(
    phoneUri = Uri.parse("content://com.android.contacts/data/1"),
    displayName = "Alex Rivera",
    phoneNumber = "(555) 010-2030",
    phoneType = "Mobile",
)

/** Preview/sample Health Connect data for screenshots. */
fun sampleHealthConnect(): HealthConnectMedicalState = HealthConnectMedicalState(
    available = true,
    granted = true,
    allergies = listOf(
        ImportedAllergy("Penicillin", com.vayunmathur.emergency.data.AllergyCriticality.High, "Anaphylaxis"),
        ImportedAllergy("Peanuts", com.vayunmathur.emergency.data.AllergyCriticality.High, "Hives"),
        ImportedAllergy("Pollen", com.vayunmathur.emergency.data.AllergyCriticality.Low, null),
    ),
    medications = listOf(
        ImportedMedication("Salbutamol 100mcg inhaler"),
        ImportedMedication("Lisinopril 10mg"),
    ),
    conditions = listOf(
        ImportedCondition("Asthma"),
        ImportedCondition("Hypertension"),
    ),
)
