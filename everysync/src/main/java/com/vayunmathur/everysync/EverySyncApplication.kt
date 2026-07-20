package com.vayunmathur.everysync

import android.app.Application
import com.vayunmathur.everysync.sync.SyncScheduler

/**
 * Ensures the periodic background sync is scheduled whenever the process starts —
 * including when WorkManager or the sync framework spins the app up in the
 * background — so syncing keeps running without the user reopening the UI.
 */
class EverySyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncScheduler.schedulePeriodic(this)
    }
}
