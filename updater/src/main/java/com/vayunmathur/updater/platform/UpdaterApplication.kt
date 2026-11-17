package com.vayunmathur.updater.platform

import android.app.Application
import androidx.work.Configuration

/**
 * Reserves a JobScheduler id range for WorkManager that cannot collide with
 * [IdleReboot]'s direct JobScheduler use (job id 3, well below this range).
 *
 * WorkManager's default range covers the whole id space, so a direct
 * `JobInfo.Builder` job risks being cancelled or replaced by a WorkManager
 * job that happens to pick the same id. Pinning the range here — the
 * Application is the only place WorkManager reads its configuration from —
 * keeps the idle-reboot job and the periodic update check disjoint.
 */
class UpdaterApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setJobSchedulerJobIdRange(MIN_JOB_SCHEDULER_ID, MAX_JOB_SCHEDULER_ID)
            .build()

    private companion object {
        const val MIN_JOB_SCHEDULER_ID = 1000
        const val MAX_JOB_SCHEDULER_ID = 11000
    }
}
