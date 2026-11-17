package com.vayunmathur.updater

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.updater.notifications.UpdateNotifications
import com.vayunmathur.updater.platform.UpdateCheckWorker
import com.vayunmathur.updater.platform.UpdaterViewModel

/** Updater host activity; schedules periodic checks and shows the updater UI. */
class MainActivity : MaActivity() {
    private val viewModel: UpdaterViewModel by viewModels()

    override fun onCreateBeforeContent() {
        // Scheduling here as well as from the boot receiver covers a first launch, where the
        // app has never been through a boot in a non-stopped state and so has never received
        // BOOT_COMPLETED. Both paths use ExistingPeriodicWorkPolicy.KEEP, so this is a no-op
        // once the work is already enqueued.
        UpdateNotifications.ensureChannels(this)
        UpdateCheckWorker.schedule(this)
    }

    @Composable
    override fun Content() {
        Navigation(viewModel)
    }
}
