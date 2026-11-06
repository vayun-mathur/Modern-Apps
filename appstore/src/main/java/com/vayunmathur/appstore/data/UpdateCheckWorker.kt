package com.vayunmathur.appstore.data

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import com.vayunmathur.library.log.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.appstore.MainActivity
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.accrescent.AccrescentRepository
import com.vayunmathur.appstore.data.accrescent.DeviceAttributesProvider
import com.vayunmathur.appstore.data.installer.InstallCoordinator
import com.vayunmathur.appstore.data.play.PlayRepository
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.appstore.tv.TvMainActivity
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Checks for updates in the background and says so once, quietly.
 *
 * The store used to require two manual taps — "Sync F-Droid", then "Check Play" — which
 * is not something anyone remembers to do, so an app installed here would sit on a stale
 * It refreshes the offline catalogues, asks Play about the
 * packages neither of them lists, and posts a notification only when the set of available
 * updates has actually changed since the last notification.
 *
 * If the user has opted in to unattended updates ([SettingsRepository.autoInstallUpdates]),
 * it also downloads and installs — as a foreground service — the updates it can apply
 * silently: packages this store is the installer or update owner of, on API 31+. Everything
 * it can't install without a prompt is left to the notification, exactly as before.
 * Auto-install is off by default.
 */
class UpdateCheckWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        // WorkManager can cold-start the process without MainActivity initializing TLS.
        NetworkClient.init(context, TrustBundle.STANDARD)
        val scope = CoroutineScope(SupervisorJob())
        try {
            runCheck(scope)
        } finally {
            scope.cancel()
        }
        Result.success()
    } catch (expected: java.io.IOException) {
        Log.status(TAG, "Update check failed", expected)
        Result.retry()
    } catch (expected: IllegalStateException) {
        Log.status(TAG, "Update check failed", expected)
        Result.retry()
    } catch (expected: SecurityException) {
        Log.status(TAG, "Update check failed", expected)
        Result.retry()
    }

    private data class CheckEnv(
        val scope: CoroutineScope,
        val db: AppDatabase,
        val catalog: CatalogRepository,
        val installedRepo: InstalledAppsRepository,
        val play: PlayRepository,
        val accrescent: AccrescentRepository,
        val settings: SettingsRepository,
    )

    private suspend fun runCheck(scope: CoroutineScope) {
        // Crash-orphaned APKs first: the worker can cold-start the process with
        // no activity alive, so it sweeps too rather than trusting the UI
        // entries to have run (#871).
        DownloadCache.sweepStale(context.cacheDir)
        val db = AppStoreDatabaseRepository.get(context).database
        val env = CheckEnv(
            scope = scope,
            db = db,
            catalog = CatalogRepository(context, db, scope),
            installedRepo = InstalledAppsRepository(context),
            play = PlayRepository(context),
            accrescent = AccrescentRepository(context, db),
            settings = SettingsRepository(context, scope),
        )
        val enabled = env.settings.readEnabledSources()

        // Drop disabled sources' cached rows before the index is read: the last sync
        // may predate the toggle, and nothing else purges on this path (#845).
        env.catalog.purgeDisabled(enabled)
        env.catalog.sync(enabled)
        env.installedRepo.refresh()
        if (AppSource.PLAYSTORE in enabled) env.play.restore()

        val updates = collectUpdates(env, enabled)
        reportUpdates(env, updates)

        env.accrescent.shutdown()
    }

    private suspend fun collectUpdates(
        env: CheckEnv,
        enabled: Set<AppSource>,
    ): List<UnifiedApp> {
        val installed = env.installedRepo.updatable.value
        val fromCatalog = env.catalog.updatesFor(installed)
        val fromPlay = playUpdates(env, enabled, installed)
        val fromAccrescent = accrescentUpdates(env, enabled, installed)

        // The surviving row's source decides which download-and-verify path the update takes,
        // so it has to be the same precedence the rest of the store uses.
        //
        // The TV-compat filter is the single choke for every update stream: the offline
        // catalogue and Play already filter at read time, but defence in depth keeps one
        // place where a missed hook cannot re-offer an incompatible update every 12h.
        // Accrescent needs no per-app filtering — its server already decides per-device —
        // and passes through untouched.
        val isTv = com.vayunmathur.appstore.data.accrescent.DeviceAttributesProvider(
            context.applicationContext
        ).isTv()
        return com.vayunmathur.appstore.data.AppProvider.filterTvCompatible(
            isTv,
            (fromCatalog + fromPlay + fromAccrescent)
                .sortedBy { it.source.priority }
                .distinctBy { it.packageName },
        )
    }

    private suspend fun playUpdates(
        env: CheckEnv,
        enabled: Set<AppSource>,
        installed: List<InstalledInfo>,
    ): List<UnifiedApp> {
        // Only Play can answer for packages the offline catalogues have never heard of.
        if (AppSource.PLAYSTORE !in enabled) return emptyList()
        // Direct DB read, not packageIndex.value: the worker's flow has no collector yet
        // on a cold start, so .value would be empty and Play would be asked about every
        // installed app — including ones the offline catalogue lists.
        val index = env.catalog.indexSnapshot()
        val unknown = installed
            .filter { it.packageName !in index }
            .map { it.packageName }
        // Null (lookup failed) reads as no Play contribution here rather than wiping:
        // the worker diffs the merged set against the last notification, so a failed
        // lookup returning rows would silently cancel a standing notification.
        val remote = env.play.detailsOrNull(unknown).orEmpty().associateBy { it.packageName }
        return installed.mapNotNull { inst ->
            remote[inst.packageName]?.takeIf { it.versionCode > inst.versionCode }
        }
    }

    private suspend fun accrescentUpdates(
        env: CheckEnv,
        enabled: Set<AppSource>,
        installed: List<InstalledInfo>,
    ): List<UnifiedApp> {
        // Accrescent: refresh its signed allowlist, then ask its API for a newer build of each
        // installed package it vouches for. Auto-install still routes through InstallCoordinator,
        // which re-verifies signer + min-version before committing.
        if (AppSource.ACCRESCENT !in enabled) return emptyList()
        env.accrescent.refreshRepoData()
        val accrescentIds = env.accrescent.appIds()
        return installed
            .filter { it.packageName in accrescentIds }
            .mapNotNull { inst ->
                val update = runCatching {
                    env.accrescent.updateInfo(inst.packageName, inst.versionCode)
                }.getOrNull() ?: return@mapNotNull null
                val details = env.accrescent.details(inst.packageName) ?: UnifiedApp(
                    packageName = inst.packageName,
                    source = AppSource.ACCRESCENT,
                    name = inst.packageName.substringAfterLast('.'),
                )
                details.copy(versionCode = update.versionCode, versionName = update.versionName)
            }
    }

    private suspend fun reportUpdates(env: CheckEnv, updates: List<UnifiedApp>) {
        val autoInstall = env.settings.readAutoInstallUpdates()

        if (!autoInstall || updates.isEmpty()) {
            notifyIfChanged(
                updates.map { it.packageName }.toSortedSet(),
                updates.size,
                needsConfirmation = false,
            )
            return
        }
        val (eligible, foreign) = updates.partition { canSilentlyUpdate(it.packageName) }
        if (eligible.isNotEmpty()) {
            autoInstall(eligible, env)
            // PackageInstaller commits asynchronously, so a just-committed install may
            // not have landed yet. Give it a moment before re-reading, or still-pending
            // updates read as stuck and get nagged about immediately (#898). Same wait
            // the foreground "update all" applies before its own re-read.
            delay(INSTALL_SETTLE_MS)
            env.installedRepo.refresh()
        }
        // Only nag about the updates we could not apply on our own: the ones we never
        // attempted plus the ones we attempted but that are still outstanding. The OS
        // may answer a "silent" commit with a confirmation prompt the background job
        // cannot usefully answer (see InstallStatusReceiver), so an attempted update
        // whose installed copy is still behind needs its tap after all (#898).
        val installedVersions = env.installedRepo.updatable.value
            .associate { it.packageName to it.versionCode }
        val stillOutstanding = eligible.filter { update ->
            (installedVersions[update.packageName] ?: 0L) < update.versionCode
        }
        val remaining = (foreign + stillOutstanding).distinctBy { it.packageName }
        notifyIfChanged(
            remaining.map { it.packageName }.toSortedSet(),
            remaining.size,
            needsConfirmation = true,
        )
    }

    private suspend fun autoInstall(
        eligible: List<UnifiedApp>,
        env: CheckEnv,
    ) {
        autoInstall(
            eligible,
            env.db,
            env.play,
            env.accrescent,
            env.scope,
        )
    }

    /**
     * Where an update notification lands: the TV entry on a TV, the phone pager elsewhere.
     *
     * "Am I a TV" is the same client-side fact the update streams filter on (see
     * [DeviceAttributesProvider.isTv]), so a notification about a TV-compatible update never
     * drops the user onto the phone pager on a device with no touch.
     */
    private fun openActivity(): Class<out Activity> =
        if (DeviceAttributesProvider(context.applicationContext).isTv()) TvMainActivity::class.java
        else MainActivity::class.java

    /**
     * Notify only when the set of updatable packages differs from last time.
     *
     * Without this, a daily check on a phone with one perpetually-stale app would post the
     * same notification every day until the user turned notifications off.
     *
     * [needsConfirmation] distinguishes updates left over from an auto-install run, which the OS
     * will not let us apply unattended, from an ordinary "updates are waiting" nudge. It is part
     * of the de-dupe signature so toggling the setting re-posts with the matching wording.
     */
    private fun notifyIfChanged(packages: Set<String>, count: Int, needsConfirmation: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val signature = packages.joinToString(",") + "|$needsConfirmation"
        if (prefs.getString(KEY_LAST_NOTIFIED, "") == signature) return
        prefs.edit { putString(KEY_LAST_NOTIFIED, signature) }

        if (count == 0) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        createChannel()
        val body = context.getString(
            if (needsConfirmation) R.string.updates_confirm_notification_body
            else R.string.updates_notification_body
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, openActivity())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(
                context.resources.getQuantityString(
                    if (needsConfirmation) R.plurals.updates_confirm_count
                    else R.plurals.updates_count,
                    count,
                    count,
                )
            )
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

/**
     * Download and silently install a set of updates, promoting the worker to a foreground
     * service for the duration so the OS doesn't kill it mid-install.
     *
     * [canSilentlyUpdate] has already confirmed each package will install without a prompt,
     * so nothing here can surface a dialog with the user absent.
     */
    private suspend fun autoInstall(
        apps: List<UnifiedApp>,
        db: AppDatabase,
        play: PlayRepository,
        accrescent: AccrescentRepository,
        scope: CoroutineScope,
    ) {
        runCatching { setForeground(installingForegroundInfo(apps.size)) }
        val installer = InstallCoordinator(
            context = context,
            db = db,
            play = play,
            accrescent = accrescent,
            scope = scope,
            ownSigningCertificates = { ApkCertificates.selfSigners(context) },
        )
        var installed = 0
        for (app in apps) {
            val outcome = runCatching { installer.install(app) }.getOrNull()
            if (outcome?.started == true) installed++
        }
        if (installed > 0) notifyInstalled(installed)
    }

    /**
     * Whether an update to [packageName] would install without a system dialog.
     *
     * Only true when this store is the package's installer of record — or, on API 34+, its
     * update owner. For anything else the OS would raise a confirmation the background job
     * can't usefully answer, so those are left to the notification instead.
     */
    private fun canSilentlyUpdate(packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            val info = context.packageManager.getInstallSourceInfo(packageName)
            val self = context.packageName
            val ownsUpdate = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                info.updateOwnerPackageName == self
            ownsUpdate || info.installingPackageName == self
        }.getOrDefault(false)
    }

    private fun installingForegroundInfo(count: Int): ForegroundInfo {
        createChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(
                context.resources.getQuantityString(R.plurals.updates_installing, count, count)
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                INSTALL_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(INSTALL_NOTIFICATION_ID, notification)
        }
    }

    private fun notifyInstalled(count: Int) {
        if (!canPostNotifications()) return
        createChannel()
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, openActivity())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(
                context.resources.getQuantityString(R.plurals.updates_installed, count, count)
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(INSTALLED_NOTIFICATION_ID, notification)
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.updates_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.updates_channel_description) }
        )
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
        private const val CHANNEL_ID = "appstore-updates"
        private const val NOTIFICATION_ID = 4201
        private const val INSTALL_NOTIFICATION_ID = 4202
        private const val INSTALLED_NOTIFICATION_ID = 4203
        private const val PREFS = "appstore-update-check"
        private const val KEY_LAST_NOTIFIED = "last_notified_packages"

        /**
         * How long to wait after committing installs before re-reading the device.
         *
         * PackageInstaller commits asynchronously — a just-committed install may not
         * have landed yet, and without the wait the re-read reports every update as
         * still outstanding. Same value as the foreground "update all" settle delay.
         */
        private const val INSTALL_SETTLE_MS = 1_500L

        /**
         * Twelve hours, and no immediate run.
         *
         * A sync pulls F-Droid's reproducibility feed and signed index — tens of megabytes
         * — so kicking one off every time the activity starts would be a lot of somebody's
         * data allowance. `KEEP` leaves an already-scheduled chain alone, and the periodic
         * request only fires on an unmetered connection.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        private const val WORK_NAME = "AppStoreUpdateCheck"
    }
}
