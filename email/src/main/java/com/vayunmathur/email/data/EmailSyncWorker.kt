package com.vayunmathur.email.data

import android.content.Context
import android.util.Log
import androidx.work.*
import com.vayunmathur.email.EmailManager
import com.vayunmathur.email.resolveAuth
import com.vayunmathur.email.imapServer
import com.vayunmathur.email.loginUser
import com.vayunmathur.email.widget.EmailWidget
import androidx.glance.appwidget.updateAll
import java.util.concurrent.TimeUnit

class EmailSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val nonInboxOnly = inputData.getBoolean(KEY_NON_INBOX_ONLY, false)
        val db = EmailDatabase.getInstance(applicationContext)
        val dao = db.emailDao()
        val accounts = dao.getAccounts()

        if (accounts.isEmpty()) {
            Log.d("EmailSync", "No accounts to sync")
            return Result.success()
        }

        EmailSyncState.start()
        val manager = EmailManager()
        var hasErrors = false
        var accountsProcessed = 0

        for (account in accounts) {
            try {
                Log.d("EmailSync", ">>> Starting sync for account: ${account.email} (nonInboxOnly=$nonInboxOnly)")
                val auth = account.resolveAuth(applicationContext)

                manager.withStore(account.imapServer(), account.loginUser(), auth) { store ->
                    Log.d("EmailSync", "Fetching folders for ${account.email}...")
                    val folders = manager.fetchFoldersInStore(store, account.email)
                    dao.insertFolders(folders)
                    Log.d("EmailSync", "Synced ${folders.size} folders.")

                    val skipSet = if (account.provider == PROVIDER_GMAIL) {
                        GMAIL_VIRTUAL_FOLDERS
                    } else emptySet()
                    val messageFolders = if (nonInboxOnly) {
                        folders.filter { folder ->
                            folder.holdsMessages && folder.fullName !in skipSet && folder.fullName != "INBOX"
                        }
                    } else {
                        folders.filter { folder ->
                            folder.holdsMessages && folder.fullName !in skipSet
                        }
                    }
                    val totalUnits = (accounts.size * messageFolders.size).coerceAtLeast(1)

                    for ((index, folder) in messageFolders.withIndex()) {
                        try {
                            val knownUids = dao.getKnownUids(account.email, folder.fullName).toSet()
                            val deletedUids = dao.getDeletedUids(account.email, folder.fullName).toSet()
                            val (messages, attachments) = manager.fetchMessagesInStore(
                                store = store,
                                user = account.loginUser(),
                                folderName = folder.fullName,
                                limit = 50,
                                fetchBodies = false,
                                skipUids = knownUids + deletedUids,
                            )
                            if (messages.isNotEmpty()) dao.insertMessages(messages)
                            if (attachments.isNotEmpty()) dao.insertAttachments(attachments)

                            // INBOX-only: read-status sync + foreground notification (skip when nonInboxOnly)
                            if (!nonInboxOnly) {
                                if (knownUids.isNotEmpty() && folder.fullName == "INBOX") {
                                    syncReadStatus(store, account.email, folder.fullName, knownUids)
                                }

                                if (folder.fullName == "INBOX" && messages.isNotEmpty()) {
                                    val lastSeen = lastSeenPrefs(applicationContext)
                                        .getLong(lastSeenKey(account.email, folder.fullName), -1L)
                                    if (lastSeen >= 0L && !com.vayunmathur.email.util.AppLifecycleTracker.isAppInForeground) {
                                        val notifiable = messages.filter { it.id > lastSeen }
                                        com.vayunmathur.email.util.EmailNotifications.postForNewMessages(
                                            applicationContext, account.email, notifiable,
                                        )
                                    }
                                    val maxUid = messages.maxOf { it.id }
                                    if (maxUid > lastSeen) {
                                        lastSeenPrefs(applicationContext).edit()
                                            .putLong(lastSeenKey(account.email, folder.fullName), maxUid)
                                            .apply()
                                    }
                                }
                            }

                            Log.d("EmailSync", "[${index + 1}/${messageFolders.size}] ${folder.fullName}: ${messages.size} new (skipped ${knownUids.size}).")
                        } catch (e: Exception) {
                            Log.e("EmailSync", "   x Failed folder ${folder.fullName}", e)
                        }
                        val unitsDone = accountsProcessed * messageFolders.size + (index + 1)
                        EmailSyncState.setProgress(unitsDone.toFloat() / totalUnits)
                    }

                    // Body backfill only for full sync (on-demand / initial) — skip for hourly non-INBOX to save battery.
                    if (!nonInboxOnly) {
                        val missing = dao.getMessagesWithoutBody(account.email, BACKFILL_LIMIT)
                        if (missing.isNotEmpty()) {
                            Log.d("EmailSync", "Body backfill: ${missing.size} message(s)")
                            EmailSyncState.setProgress(0f)
                            for ((idx, msg) in missing.withIndex()) {
                                if (isStopped) {
                                    Log.d("EmailSync", "Backfill stopped at ${idx}/${missing.size}")
                                    break
                                }
                                try {
                                    val current = dao.getMessage(msg.accountEmail, msg.folderName, msg.id) ?: continue
                                    if (current.body != null) continue
                                    val (body, isHtml, attachments) = manager.fetchMessageBodyInStore(
                                        store = store,
                                        user = account.loginUser(),
                                        folderName = msg.folderName,
                                        uid = msg.id,
                                    )
                                    if (body != null || attachments.isNotEmpty()) {
                                        dao.insertMessages(listOf(current.copy(
                                            body = body,
                                            isHtml = isHtml,
                                            hasAttachments = attachments.isNotEmpty(),
                                        )))
                                        if (attachments.isNotEmpty()) dao.insertAttachments(attachments)
                                    }
                                } catch (e: Exception) {
                                    Log.w("EmailSync", "   x Backfill failed for UID ${msg.id}: ${e.message}")
                                }
                                EmailSyncState.setProgress((idx + 1f) / missing.size)
                            }
                            Log.d("EmailSync", "Backfill done for ${account.email}")
                        }
                    }
                }

                Log.d("EmailSync", "<<< Completed sync for account: ${account.email}")
            } catch (e: Exception) {
                Log.e("EmailSync", "Failed to sync account ${account.email}", e)
                hasErrors = true
            }
            accountsProcessed++
        }

        if (!nonInboxOnly) {
            EmailWidget().updateAll(applicationContext)
        }
        EmailSyncState.finish()

        return if (hasErrors) Result.retry() else Result.success()
    }

    companion object {
        private const val SYNC_WORK_NAME = "EmailSyncWorker"
        private const val HOURLY_NON_INBOX_WORK_NAME = "EmailNonInboxHourlySync"
        private const val KEY_NON_INBOX_ONLY = "non_inbox_only"

        /**
         * Sync read/unread flags from the IMAP server for messages we already
         * have locally. Runs over the most recent messages in the folder to
         * keep the local read status in sync with other clients.
         */
        private suspend fun EmailSyncWorker.syncReadStatus(
            store: javax.mail.Store,
            accountEmail: String,
            folderName: String,
            knownUids: Set<Long>,
        ) {
            val db = EmailDatabase.getInstance(applicationContext)
            val dao = db.emailDao()
            val folder = store.getFolder(folderName)
            if ((folder.type and javax.mail.Folder.HOLDS_MESSAGES) == 0) return
            folder.open(javax.mail.Folder.READ_ONLY)
            try {
                val uidFolder = folder as? javax.mail.UIDFolder ?: return
                // Check read status for the 50 most recent known UIDs
                val uidsToCheck = knownUids.sortedDescending().take(50)
                for (uid in uidsToCheck) {
                    try {
                        val msg = uidFolder.getMessageByUID(uid) ?: continue
                        val serverIsRead = msg.isSet(javax.mail.Flags.Flag.SEEN)
                        dao.updateReadStatus(accountEmail, folderName, uid, serverIsRead)
                    } catch (_: Exception) {}
                }
            } finally {
                try { folder.close(false) } catch (_: Throwable) {}
            }
        }

        /**
         * Gmail's IMAP exposes several "virtual" labels that mirror INBOX (and
         * other folders) — syncing them downloads everything twice. Skipping
         * them is a major sync-time win.
         */
        private val GMAIL_VIRTUAL_FOLDERS = setOf(
            "[Gmail]/All Mail",
            "[Gmail]/Important",
            "[Gmail]/Starred",
            "[Gmail]/Chats",
        )

        /** How many missing-body messages to backfill per worker run. */
        private const val BACKFILL_LIMIT = 200

        // ---- Notification baseline ----

        private fun lastSeenPrefs(context: Context) =
            context.getSharedPreferences("email_notif_last_seen", Context.MODE_PRIVATE)

        private fun lastSeenKey(accountEmail: String, folderName: String) =
            "$accountEmail::$folderName"

        /**
         * Legacy 15-min periodic polling — removed in IDLE-only model.
         * Kept as a no-op that cancels any previously scheduled periodic work
         * so upgrades from older APKs stop polling.
         */
        @Deprecated("IDLE-only push: periodic polling removed. This now cancels legacy periodic work.", ReplaceWith("cancelSync(context)"))
        fun schedulePeriodicSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(HOURLY_NON_INBOX_WORK_NAME)
        }

        /**
         * Hourly non-INBOX sync: polls only non-INBOX folders (e.g. Sent, custom labels)
         * once per hour via WorkManager. INBOX stays IDLE-only push — no polling.
         * Cheap: skips body backfill, skip SEEN sync, skips notifications.
         */
        fun scheduleHourlyNonInboxSync(context: Context) {
            // Purge legacy 15-min full polling work that may still be scheduled.
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val data = Data.Builder().putBoolean(KEY_NON_INBOX_ONLY, true).build()
            val req = PeriodicWorkRequestBuilder<EmailSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                HOURLY_NON_INBOX_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }

        /**
         * One-off catch-up: folder discovery + all-folder header fetch + body backfill.
         * Used for initial sync after account add, manual pull-to-refresh, boot recovery, and
         * non-INBOX folder catch-up. Not periodic — INBOX live push is handled by [ImapIdleService].
         */
        fun runOneOffSync(context: Context) {
            val syncRequest = OneTimeWorkRequestBuilder<EmailSyncWorker>()
                .setInputData(Data.Builder().putBoolean(KEY_NON_INBOX_ONLY, false).build())
                .build()
            WorkManager.getInstance(context).enqueue(syncRequest)
        }

        fun cancelSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(HOURLY_NON_INBOX_WORK_NAME)
        }
    }
}
