package com.vayunmathur.everysync

import android.app.Application
import com.vayunmathur.everysync.data.VaultStore
import com.vayunmathur.everysync.sync.SnapshotWorker
import com.vayunmathur.everysync.sync.SyncScheduler
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Ensures the periodic background sync is scheduled whenever the process starts —
 * including when WorkManager spins the app up in the background — so syncing
 * keeps running without the user reopening the UI.
 */
class EverySyncApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // FIRST_PARTY covers the relay host (same bundle the office twin uses).
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        SyncScheduler.schedulePeriodic(this)
        SnapshotWorker.scheduleDaily(this)
        // One-time: seal any plaintext vault keys stored before the Keystore
        // migration. Idempotent; runs on every process start, cheap when done.
        appScope.launch {
            runCatching { VaultStore.migrateKeysToKeystore(this@EverySyncApplication) }
        }
    }
}
