package com.vayunmathur.emergency.platform

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.emergency.data.EmergencyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** ViewModel backing the emergency info screens, combining stored info, grants and Health Connect state. */
class EmergencyViewModel(app: Application) : AndroidViewModel(app), EmergencyActions {

    private val repository = EmergencyRepository.get(app)
    private val healthConnect = HealthConnectMedical(app)
    private val loaded = MutableStateFlow(false)
    private val hasContactsAccess = MutableStateFlow(false)
    private val addFailed = MutableStateFlow(false)
    private val health = MutableStateFlow(HealthConnectMedicalState())
    private val ownerCandidates = MutableStateFlow<OwnerPickCandidates?>(null)
    private val ownerPickFailed = MutableStateFlow(false)

    // Contact add/remove run as separate IO launches; without serialization
    // a slow add can publish its outcome after a later one, leaving
    // addFailed (and the stored list) describing the wrong action.
    private val contactMutex = Mutex()

    val state: StateFlow<EmergencyUiState> = combine(
        repository.info,
        loaded,
        hasContactsAccess,
        addFailed,
        health,
        ownerCandidates,
        ownerPickFailed,
    ) { flows ->
        @Suppress("UNCHECKED_CAST")
        val info = flows[0] as com.vayunmathur.emergency.data.EmergencyInfo
        EmergencyUiState(
            info = info,
            loading = !(flows[1] as Boolean),
            hasContactsAccess = flows[2] as Boolean,
            addFailed = flows[3] as Boolean,
            health = flows[4] as HealthConnectMedicalState,
            ownerCandidates = flows[5] as OwnerPickCandidates?,
            ownerPickFailed = flows[6] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EmergencyUiState())

    init {
        refresh()
    }

    override fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            hasContactsAccess.value = checkContactsGrant()
            repository.load()
            loaded.value = true
        }
        refreshHealthConnect()
    }

    override fun refreshHealthConnect() {
        viewModelScope.launch(Dispatchers.IO) {
            val available = healthConnect.isAvailable()
            if (!available) {
                health.value = HealthConnectMedicalState(available = false)
                return@launch
            }
            val granted = healthConnect.hasPermissions()
            if (!granted) {
                health.value = HealthConnectMedicalState(available = true, granted = false)
                return@launch
            }
            health.value = HealthConnectMedicalState(
                available = true,
                granted = true,
                allergies = healthConnect.readAllergies(),
                medications = healthConnect.readCurrentMedications(),
                conditions = healthConnect.readConditions(),
            )
        }
    }

    override fun saveMedicalInfo(bloodType: String, organDonor: String) {
        viewModelScope.launch {
            repository.saveMedicalInfo(bloodType, organDonor)
        }
    }

    override fun pickOwner(contactUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val identity = OwnerIdentityReader(getApplication()).read(contactUri)
            if (identity == null || identity.name.isBlank()) {
                ownerPickFailed.value = true
                return@launch
            }
            when {
                identity.addresses.isEmpty() ->
                    repository.saveOwnerIdentity(identity.name, "")
                identity.addresses.size == 1 ->
                    repository.saveOwnerIdentity(identity.name, identity.addresses[0])
                else -> ownerCandidates.value =
                    OwnerPickCandidates(identity.name, identity.addresses)
            }
        }
    }

    override fun confirmOwnerAddress(address: String) {
        val candidates = ownerCandidates.value ?: return
        ownerCandidates.value = null
        viewModelScope.launch {
            repository.saveOwnerIdentity(candidates.name, address)
        }
    }

    override fun dismissOwnerPick() {
        ownerCandidates.value = null
    }

    override fun clearOwnerPickFailed() {
        ownerPickFailed.value = false
    }

    override fun addContact(phoneUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            contactMutex.withLock {
                addFailed.value = !repository.addContact(phoneUri)
            }
        }
    }

    override fun removeContact(phoneUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            contactMutex.withLock {
                repository.removeContact(phoneUri)
            }
        }
    }

    override fun clearAddFailed() {
        addFailed.value = false
    }

    private fun checkContactsGrant(): Boolean =
        getApplication<Application>().checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
