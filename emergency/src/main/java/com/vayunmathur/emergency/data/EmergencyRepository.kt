package com.vayunmathur.emergency.data

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.UserManager
import com.vayunmathur.library.log.Log
import androidx.core.content.edit
import com.vayunmathur.emergency.platform.ContactReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val TAG = "EmergencyRepository"

/**
 * Stores the owner's medical info and emergency contacts.
 *
 * Shape mirrors GrapheneOS: default SharedPreferences (not device-protected storage - the
 * direct-boot note below explains why), `|`-serialised contact URIs, re-validation of stored
 * URIs on every load with dead contacts pruned and re-persisted, and the Settings suggestion
 * alias disabled once anything is set (`PreferenceUtils.updateSettingsSuggestionState`).
 *
 * Direct-boot guard: contacts are never persisted while the user is locked
 * (`EmergencyContactsPreference.persistEmergencyContacts`), because contact resolution needs
 * the credential-encrypted contacts provider. Reads pre-unlock return whatever is already
 * stored without pruning.
 */
class EmergencyRepository private constructor(private val app: Context) {

    private val prefs: SharedPreferences by lazy {
        // Device-protected storage so the lock-screen view can read post-reboot pre-unlock;
        // contact URIs stored here are only *resolved* once unlocked (see load below).
        val device = app.createDeviceProtectedStorageContext()
        device.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val mutable = MutableStateFlow(EmergencyInfo())
    val info: StateFlow<EmergencyInfo> = mutable.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val info = readStored()
        val resolved = if (isUnlocked()) resolveAndPrune(info) else info
        mutable.value = resolved
    }

    /**
     * Snapshots the picked owner identity. Writes only the identity keys so a
     * medical keystroke-save elsewhere can never clobber a just-picked name.
     */
    suspend fun saveOwnerIdentity(name: String, address: String) = withContext(Dispatchers.IO) {
        prefs.edit {
            putString(EmergencyKeys.NAME, name)
            putString(EmergencyKeys.ADDRESS, address)
        }
        load()
        updateSuggestionState()
    }

    /**
     * Persists the dropdown-selected medical fields. Writes only those keys so it
     * can never clobber the picked owner identity.
     */
    suspend fun saveMedicalInfo(bloodType: String, organDonor: String) =
        withContext(Dispatchers.IO) {
            prefs.edit {
                putString(EmergencyKeys.BLOOD_TYPE, bloodType)
                putString(EmergencyKeys.ORGAN_DONOR, organDonor)
            }
            load()
            updateSuggestionState()
        }

    /** Adds a contact URI after validating it still resolves; returns false when rejected. */
    suspend fun addContact(phoneUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val reader = ContactReader(app)
        val contact = reader.read(phoneUri) ?: return@withContext false
        val current = mutable.value.contacts
        if (current.any { it.phoneUri == phoneUri }) return@withContext true
        persistUris(current.map { it.phoneUri } + phoneUri)
        mutable.value = mutable.value.copy(contacts = current + contact)
        updateSuggestionState()
        true
    }

    suspend fun removeContact(phoneUri: Uri) = withContext(Dispatchers.IO) {
        val current = mutable.value.contacts
        val next = current.filterNot { it.phoneUri == phoneUri }
        if (next.size == current.size) return@withContext
        persistUris(next.map { it.phoneUri })
        mutable.value = mutable.value.copy(contacts = next)
        updateSuggestionState()
    }

    private fun readStored(): EmergencyInfo {
        // Legacy guard (b/28194605): contacts were once a string set; a ClassCastException
        // means the stored value predates the `|` format and is ignored.
        val serialized = runCatching { prefs.getString(EmergencyKeys.CONTACTS, "") }
            .getOrElse {
                Log.status(TAG, "ignoring legacy contact storage", it)
                ""
            }.orEmpty()
        val uris = parseContactUris(serialized)
        // Pre-unlock the contacts cannot be resolved; carry the URIs with blank snapshots.
        val contacts = if (isUnlocked()) {
            ContactReader(app).readAll(uris)
        } else {
            uris.map { EmergencyContact(it, "", "", "") }
        }
        return EmergencyInfo(
            name = prefs.getString(EmergencyKeys.NAME, "").orEmpty(),
            address = prefs.getString(EmergencyKeys.ADDRESS, "").orEmpty(),
            // Legacy free-text values are normalized to the dropdown's canonical form.
            bloodType = normalizeBloodType(prefs.getString(EmergencyKeys.BLOOD_TYPE, "").orEmpty()),
            organDonor = normalizeOrganDonor(prefs.getString(EmergencyKeys.ORGAN_DONOR, "").orEmpty()),
            contacts = contacts,
        )
    }

    /**
     * Re-validates stored URIs, dropping contacts deleted from the address book and
     * re-persisting when anything was pruned - GrapheneOS's `deserializeAndFilter`.
     */
    private fun resolveAndPrune(info: EmergencyInfo): EmergencyInfo {
        val uris = info.contacts.map { it.phoneUri }
        val resolved = ContactReader(app).readAll(uris)
        if (resolved.size != uris.size) {
            persistUris(resolved.map { it.phoneUri })
            return info.copy(contacts = resolved)
        }
        return info
    }

    private fun persistUris(uris: List<Uri>) {
        // Never persist pre-unlock: same guard as GrapheneOS.
        if (!isUnlocked()) return
        prefs.edit { putString(EmergencyKeys.CONTACTS, serializeContacts(uris)) }
    }

    private fun isUnlocked(): Boolean =
        app.getSystemService(UserManager::class.java)?.isUserUnlocked != false

    /**
     * Disables the Settings first-run suggestion once anything is set, re-enables when the
     * last detail is removed - GrapheneOS's `updateSettingsSuggestionState`.
     */
    private fun updateSuggestionState() {
        val state = if (mutable.value.hasAnythingSet()) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        runCatching {
            app.packageManager.setComponentEnabledSetting(
                ComponentName(app, SUGGESTION_CLASS),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }.onFailure { Log.status(TAG, "could not update the settings suggestion state", it) }
    }

    /** Singleton access and preference-file constants for [EmergencyRepository]. */
    companion object {
        private const val PREFS = "emergency_info"
        private const val SUGGESTION_CLASS = "com.vayunmathur.emergency.ui.EditInfoSuggestion"

        @Volatile
        private var instance: EmergencyRepository? = null

        fun get(context: Context): EmergencyRepository =
            instance ?: synchronized(this) {
                instance ?: EmergencyRepository(context.applicationContext).also { instance = it }
            }
    }
}
