@file:OptIn(androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi::class)

package com.vayunmathur.emergency.platform

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MedicalResource
import androidx.health.connect.client.request.ReadMedicalResourcesInitialRequest
import androidx.health.connect.client.request.ReadMedicalResourcesPageRequest
import com.vayunmathur.emergency.data.ImportedAllergy
import com.vayunmathur.emergency.data.ImportedCondition
import com.vayunmathur.emergency.data.ImportedMedication
import com.vayunmathur.emergency.domain.FhirMedicalParse

private const val TAG = "EmergencyHealthConnect"

/**
 * Read-only window onto Health Connect's Personal Health Record store, for the two things an
 * emergency card wants that the free-text fields cannot keep current: the patient's allergies
 * and what they are taking right now.
 *
 * Mirrors the health app's `PersonalHealthRecords`, pared to reads: PHR needs Android 15 with an
 * updated Health Connect module, so [isAvailable] gates everything and every method degrades to
 * empty rather than throwing on older devices or when the permission is refused. The emergency
 * app therefore always works from its own stored fields, with this layered on when present.
 *
 * Reads span every data source, not just any this app owns, because the whole point is to show
 * records a hospital or pharmacy synced in - which by definition live elsewhere.
 */
class HealthConnectMedical(context: Context) {

    private val appContext = context.applicationContext

    private val client: HealthConnectClient? by lazy {
        runCatching {
            if (HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE) {
                HealthConnectClient.getOrCreate(appContext)
            } else {
                null
            }
        }.getOrElse {
            Log.status(TAG, "Health Connect client unavailable", it)
            null
        }
    }

    /** True when the device's Health Connect module exposes the Personal Health Record feature. */
    fun isAvailable(): Boolean {
        val client = client ?: return false
        return runCatching {
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_PERSONAL_HEALTH_RECORD,
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }.getOrElse {
            Log.status(TAG, "Could not query the Personal Health Record feature status", it)
            false
        }
    }

    /** Whether both medical read permissions this feature needs are currently granted. */
    suspend fun hasPermissions(): Boolean {
        val client = client ?: return false
        return runCatching {
            client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
        }.getOrDefault(false)
    }

    /** Active allergies, most critical first; empty when unavailable, denied, or none recorded. */
    suspend fun readAllergies(): List<ImportedAllergy> {
        val parsed = readCategory(MedicalResource.MEDICAL_RESOURCE_TYPE_ALLERGIES_INTOLERANCES)
            .mapNotNull { FhirMedicalParse.parseAllergy(it) }
        return FhirMedicalParse.sortAllergies(parsed).distinctBy { it.displayName }
    }

    /** Current medications; empty when unavailable, denied, or none recorded. */
    suspend fun readCurrentMedications(): List<ImportedMedication> =
        readCategory(MedicalResource.MEDICAL_RESOURCE_TYPE_MEDICATIONS)
            .mapNotNull { FhirMedicalParse.parseCurrentMedication(it) }
            .distinctBy { it.displayName }

    /** Active conditions; empty when unavailable, denied, or none recorded. */
    suspend fun readConditions(): List<ImportedCondition> =
        readCategory(MedicalResource.MEDICAL_RESOURCE_TYPE_CONDITIONS)
            .mapNotNull { FhirMedicalParse.parseCondition(it) }
            .distinctBy { it.displayName }

    /** Every FHIR resource of [medicalResourceType] as raw JSON, paging until exhausted. */
    private suspend fun readCategory(medicalResourceType: Int): List<String> {
        if (!isAvailable()) return emptyList()
        val client = client ?: return emptyList()
        return runCatching {
            val out = mutableListOf<String>()
            var response = client.readMedicalResources(
                ReadMedicalResourcesInitialRequest(medicalResourceType, emptySet(), PAGE_SIZE),
            )
            out += response.medicalResources.map { it.fhirResource.data }
            var token = response.nextPageToken
            while (token != null) {
                response = client.readMedicalResources(ReadMedicalResourcesPageRequest(token, PAGE_SIZE))
                out += response.medicalResources.map { it.fhirResource.data }
                token = response.nextPageToken
            }
            out
        }.getOrElse {
            // Reads can fail when the device is locked (medical data is credential-encrypted) or
            // the permission was revoked; the card just falls back to its own fields.
            Log.status(TAG, "Could not read medical resources of type $medicalResourceType", it)
            emptyList()
        }
    }

    /** Health Connect permissions and paging constants for [HealthConnectMedical]. */
    companion object {
        /** The read permissions the emergency card needs. Requested from the edit screen. */
        val PERMISSIONS = setOf(
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_ALLERGIES_INTOLERANCES,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_MEDICATIONS,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_CONDITIONS,
        )

        private const val PAGE_SIZE = 500
    }
}
