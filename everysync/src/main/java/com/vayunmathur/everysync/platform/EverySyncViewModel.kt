package com.vayunmathur.everysync.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.everysync.data.Settings
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.AccountStore
import com.vayunmathur.everysync.data.VaultStore
import com.vayunmathur.everysync.data.sink.CalendarSink
import com.vayunmathur.everysync.data.sink.ContactsSink
import com.vayunmathur.everysync.network.remote.RelayRoles
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.provider.ProviderRegistry
import com.vayunmathur.everysync.provider.impl.CH_LOCAL_READ_FAILED
import com.vayunmathur.everysync.provider.impl.CH_PULL_TRANSPORT
import com.vayunmathur.everysync.provider.impl.CH_RELAY_UNAVAILABLE
import com.vayunmathur.everysync.provider.impl.CH_UNDECRYPTABLE_LOG
import com.vayunmathur.everysync.provider.impl.E2eeProvider
import com.vayunmathur.everysync.provider.impl.E2eeProvider.Companion.relayType
import com.vayunmathur.everysync.sync.SnapshotStore
import com.vayunmathur.everysync.sync.SnapshotStore.SnapshotMeta
import com.vayunmathur.everysync.sync.SyncScheduler
import com.vayunmathur.everysync.sync.SyncStatus
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ViewModel backing EverySync account, sync and vault screens. */
class EverySyncViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AccountStore.getInstance(app)

    /** Personal E2EE vault sharing (create-vault + invites + join requests). EverySync-only. */
    val vaults = EverySyncVaults(this)

    /** Holds shared vault data-type constants for the ViewModel. */
    companion object {
        /** Vault data type whose invites never offer viewer: read-only still discloses every secret. */
        const val PASSWORDS_DATA_TYPE = "passwords"
    }

    val accounts = store.accountsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Account names with a sync currently in progress. */
    val syncing = SyncStatus.syncing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val interval = Settings.intervalFlow(app)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60L)

    val wifiOnly = Settings.wifiOnlyFlow(app)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun addRelayAccount(providerId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val provider = ProviderRegistry.get(providerId) ?: return@launch
            val accountName = provider.displayName
            if (store.get(accountName) == null) {
                store.upsert(
                    AccountConfig(
                        accountName = accountName,
                        providerId = providerId,
                        enabledTypes = provider.capabilities,
                    ),
                )
            }
            SyncScheduler.syncNow(app, accountName)
            onDone()
        }
    }

    fun syncNow(accountName: String) {
        SyncScheduler.syncNow(getApplication(), accountName)
    }

    /**
     * Snapshot-now entry point for the UI (e.g. a "back up now" affordance).
     * Same-day vaults are skipped by the store, so this is idempotent.
     */
    fun snapshotNow() {
        viewModelScope.launch(Dispatchers.IO) {
            SnapshotStore.snapshotAll(getApplication())
            loadSnapshots()
        }
    }

    fun toggleType(accountName: String, type: DataType, enabled: Boolean) {
        viewModelScope.launch {
            val config = store.get(accountName) ?: return@launch
            val app = getApplication<Application>()
            if (!enabled) {
                // Snapshot BEFORE removing the type: all purge paths below
                // are account- or vault-wide and the merge never infers
                // deletion from absence, so the snapshot is the way back.
                // Best-effort: purge is explicit user intent either way.
                // (IPC types snapshot again inside purgeType — same-day
                // snapshots are skipped, so the duplicate is a no-op.)
                withContext(Dispatchers.IO) {
                    runCatching {
                        for (meta in VaultStore.getAll(app).filter {
                            it.dataType == type.relayType()
                        }) {
                            SnapshotStore.snapshotVault(app, meta.vaultId, meta.dataType)
                        }
                    }
                }
            }
            val types = if (enabled) config.enabledTypes + type else config.enabledTypes - type
            store.upsert(config.copy(enabledTypes = types))
            if (enabled) {
                // Re-sync so the just-enabled type is repopulated on-device.
                SyncScheduler.syncNow(app, accountName)
            } else {
                // Remove the disabled type's data from the on-device provider so it
                // doesn't linger; it will be pulled again if re-enabled.
                withContext(Dispatchers.IO) {
                    when (type) {
                        DataType.CONTACTS -> ContactsSink.purge(app, accountName)
                        DataType.CALENDAR -> CalendarSink.purge(app, accountName)
                        DataType.PASSWORDS -> E2eeProvider.purgeType(app, accountName, type)
                        DataType.NOTES -> E2eeProvider.purgeType(app, accountName, type)
                        DataType.TODOS -> E2eeProvider.purgeType(app, accountName, type)
                    }
                }
            }
        }
    }

    fun setInterval(minutes: Long) {
        viewModelScope.launch {
            Settings.setIntervalMinutes(getApplication(), minutes)
            SyncScheduler.schedulePeriodic(getApplication())
        }
    }

    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch {
            Settings.setWifiOnly(getApplication(), value)
            SyncScheduler.schedulePeriodic(getApplication())
        }
    }

    fun setConflictPolicy(policy: String) {
        viewModelScope.launch { Settings.setConflictPolicy(getApplication(), policy) }
    }

    fun removeAccount(accountName: String) {
        viewModelScope.launch { store.remove(accountName) }
    }

    // --- Sync health + snapshots (observability + last-resort recovery) ---

    /**
     * Human-readable explanation of an account's `lastSyncError`, mapping the
     * stable abort codes (see `VaultSyncAbort`) to user-facing strings. Null
     * when there is no error. Never includes key material or record contents.
     */
    fun syncErrorText(accountName: String): String? {
        val config = store.get(accountName) ?: return null
        val error = config.lastSyncError ?: return null
        val app = getApplication<Application>()
        return when {
            CH_PULL_TRANSPORT in error -> app.getString(R.string.sync_error_pull_failed)
            CH_UNDECRYPTABLE_LOG in error -> app.getString(R.string.sync_error_undecryptable)
            CH_LOCAL_READ_FAILED in error -> app.getString(R.string.sync_error_local_read)
            CH_RELAY_UNAVAILABLE in error -> app.getString(R.string.sync_error_relay_unavailable)
            else -> error
        }
    }

    private val _snapshots = MutableStateFlow<List<SnapshotMeta>>(emptyList())
    val snapshots: StateFlow<List<SnapshotMeta>> = _snapshots.asStateFlow()

    private val _restoring = MutableStateFlow(false)
    val restoring: StateFlow<Boolean> = _restoring.asStateFlow()

    fun loadSnapshots() {
        viewModelScope.launch(Dispatchers.IO) {
            _snapshots.value = SnapshotStore.listAll(getApplication())
        }
    }

    fun restoreSnapshot(snapshot: SnapshotMeta) {
        viewModelScope.launch(Dispatchers.IO) {
            _restoring.value = true
            try {
                val count = SnapshotStore.restore(getApplication(), snapshot)
                withContext(Dispatchers.Main) {
                    announce(
                        if (count != null) {
                            getApplication<Application>().resources
                                .getQuantityString(R.plurals.snapshot_restored, count, count)
                        } else {
                            getApplication<Application>().getString(R.string.snapshot_restore_failed)
                        },
                    )
                }
                if (count != null) {
                    // Reconcile the restored rows against the relay immediately.
                    SyncScheduler.syncAllNow(getApplication())
                }
            } finally {
                _restoring.value = false
            }
        }
    }

    // --- Vault sharing helpers (EverySync-only; see EverySyncVaults) ---

    private val _safetyCode = MutableStateFlow<String?>(null)
    val safetyCode: StateFlow<String?> = _safetyCode.asStateFlow()

    private val _computingCode = MutableStateFlow(false)
    val computingCode: StateFlow<Boolean> = _computingCode.asStateFlow()

    fun computeSafetyCode(recipientId: String) {
        _computingCode.value = true
        _safetyCode.value = null
        vaults.safetyCode(recipientId) { code ->
            _safetyCode.value = code
            _computingCode.value = false
            if (code == null) announce(R.string.safety_code_failed)
        }
    }

    /** Roles the share dialog offers for a vault data type. */
    fun offerableRoles(dataType: String): List<String> =
        if (dataType == PASSWORDS_DATA_TYPE) {
            listOf(RelayRoles.OWNER, RelayRoles.EDITOR)
        } else {
            listOf(RelayRoles.OWNER, RelayRoles.EDITOR, RelayRoles.VIEWER)
        }

    fun announce(messageRes: Int) {
        AppMessages.show(getApplication<Application>().getString(messageRes))
    }

    fun announce(message: String) {
        AppMessages.show(message)
    }
}
