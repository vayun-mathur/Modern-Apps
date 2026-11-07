package com.vayunmathur.email.data

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.email.network.imap.ImapClient
import com.vayunmathur.email.platform.EmailManager
import com.vayunmathur.email.platform.imapServer
import com.vayunmathur.email.platform.loginUser
import com.vayunmathur.email.platform.resolveAuth
import com.vayunmathur.email.widget.EmailWidget
import androidx.glance.appwidget.updateAll
import java.util.concurrent.TimeUnit

/**
 * Sync worker — now uses raw IMAP client only (no Jakarta dependency).
 * - Non-INBOX hourly via WorkManager
 * - Full one-off for folder discovery + INBOX + body backfill
 * - Notification baseline per account/folder
 */
class EmailSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val nonInboxOnly = inputData.getBoolean(KEY_NON_INBOX_ONLY, false)
        val db = EmailRepository.get(applicationContext).getDatabase()
        val accounts = db.accountDao().getAccounts()

        if (accounts.isEmpty()) {
            Log.debug("EmailSync", "No accounts to sync")
            return Result.success()
        }

        EmailSyncState.start()
        var hasErrors = false
        var accountsProcessed = 0

        for (account in accounts) {
            val ok = try {
                syncAccount(db, account, nonInboxOnly, accounts.size, accountsProcessed)
                true
            } catch (_: Exception) {
                Log.error("EmailSync", "Failed to sync account ${account.email}")
                false
            }
            if (!ok) hasErrors = true
            accountsProcessed++
        }

        if (!nonInboxOnly) EmailWidget().updateAll(applicationContext)
        EmailSyncState.finish()
        return if (hasErrors) Result.retry() else Result.success()
    }

    private suspend fun syncAccount(
        db: EmailDatabase,
        account: EmailAccount,
        nonInboxOnly: Boolean,
        accountCount: Int,
        accountsProcessed: Int,
    ) {
        Log.dev("EmailSync", ">>> RAW Starting sync for ${account.email} (nonInboxOnly=$nonInboxOnly)")
        val auth = account.resolveAuth(applicationContext)

        val folders = ImapClient.fetchFolders(account.imapServer(), account.loginUser(), auth, account.email)
        db.accountDao().insertFolders(folders)
        Log.dev("EmailSync", "Synced ${folders.size} folders.")

        val skipSet = if (account.provider == PROVIDER_GMAIL) ImapClient.GMAIL_VIRTUAL_FOLDERS else emptySet()
        val messageFolders = if (nonInboxOnly) {
            folders.filter { it.holdsMessages && it.fullName !in skipSet && it.fullName != ImapClient.INBOX }
        } else {
            folders.filter { it.holdsMessages && it.fullName !in skipSet }
        }
        val totalUnits = (accountCount * messageFolders.size).coerceAtLeast(1)

        for ((index, folder) in messageFolders.withIndex()) {
            syncFolder(db, account, auth, folder, nonInboxOnly, index, messageFolders.size)
            val unitsDone = accountsProcessed * messageFolders.size + (index + 1)
            EmailSyncState.setProgress(unitsDone.toFloat() / totalUnits)
        }

        if (!nonInboxOnly) {
            backfillBodies(db, account, auth)
        }

        Log.dev("EmailSync", "<<< Completed RAW sync for ${account.email}")
    }

    private suspend fun syncFolder(
        db: EmailDatabase,
        account: EmailAccount,
        auth: EmailManager.AuthType,
        folder: EmailFolder,
        nonInboxOnly: Boolean,
        index: Int,
        folderCount: Int,
    ) {
        val messagesDao = db.messageDao()
        try {
            val knownUids = messagesDao.getKnownUids(account.email, folder.fullName).toSet()
            val deletedUids = messagesDao.getDeletedUids(account.email, folder.fullName).toSet()
            val (messages, attachments) = ImapClient.fetchMessages(
                server = account.imapServer(),
                user = account.loginUser(),
                auth = auth,
                folderName = folder.fullName,
                limit = SYNC_PAGE_SIZE,
                fetchBodies = false,
                skipUids = knownUids + deletedUids,
                context = applicationContext,
                accountEmail = account.email,
            )
            if (messages.isNotEmpty()) messagesDao.insertMessages(messages)
            if (attachments.isNotEmpty()) messagesDao.insertAttachments(attachments)

            if (!nonInboxOnly) {
                handleInboxExtras(account, folder, messages, knownUids)
            }

            Log.dev(
                "EmailSync",
                "[${index + 1}/$folderCount] ${folder.fullName}: " +
                    "${messages.size} new (skipped ${knownUids.size}).",
            )
        } catch (_: Exception) {
            Log.error("EmailSync", "   x Failed folder ${folder.fullName}")
        }
    }

    private suspend fun handleInboxExtras(
        account: EmailAccount,
        folder: EmailFolder,
        messages: List<EmailMessage>,
        knownUids: Set<Long>,
    ) {
        if (knownUids.isNotEmpty() && folder.fullName == ImapClient.INBOX) {
            syncReadStatusRaw(applicationContext, account, folder.fullName, knownUids)
        }

        if (folder.fullName == ImapClient.INBOX && messages.isNotEmpty()) {
            val lastSeen = lastSeenPrefs(applicationContext)
                .getLong(lastSeenKey(account.email, folder.fullName), NO_LAST_SEEN_UID)
            if (lastSeen >= 0L && !com.vayunmathur.email.platform.AppLifecycleTracker.isAppInForeground) {
                val notifiable = messages.filter { it.id > lastSeen }
                com.vayunmathur.email.platform.EmailNotifications.postForNewMessages(
                    applicationContext, account.email, notifiable,
                )
            }
            val maxUid = messages.maxOf { it.id }
            if (maxUid > lastSeen) {
                lastSeenPrefs(applicationContext).edit {
                    putLong(lastSeenKey(account.email, folder.fullName), maxUid)
                }
            }
        }
    }

    private suspend fun backfillBodies(
        db: EmailDatabase,
        account: EmailAccount,
        auth: EmailManager.AuthType,
    ) {
        val messagesDao = db.messageDao()
        val missing = messagesDao.getMessagesWithoutBody(account.email, BACKFILL_LIMIT)
        if (missing.isEmpty()) return
        Log.dev("EmailSync", "Body backfill: ${missing.size} message(s)")
        EmailSyncState.setProgress(0f)
        for ((idx, msg) in missing.withIndex()) {
            if (isStopped) {
                Log.dev("EmailSync", "Backfill stopped at ${idx}/${missing.size}")
                break
            }
            backfillOne(messagesDao, account, auth, msg)
            EmailSyncState.setProgress((idx + 1f) / missing.size)
        }
        Log.dev("EmailSync", "Backfill done for ${account.email}")
    }

    private suspend fun backfillOne(
        messagesDao: EmailMessageDao,
        account: EmailAccount,
        auth: EmailManager.AuthType,
        msg: EmailMessage,
    ) {
        try {
            val current = messagesDao.getMessage(msg.accountEmail, msg.folderName, msg.id) ?: return
            if (current.body != null) return
            val (body, isHtml, attachments) = ImapClient.fetchMessageBody(
                server = account.imapServer(),
                user = account.loginUser(),
                auth = auth,
                folderName = msg.folderName,
                uid = msg.id,
                context = applicationContext,
                accountEmail = account.email,
            )
            if (body != null || attachments.isNotEmpty()) {
                messagesDao.insertMessages(
                    listOf(
                        current.copy(
                            body = body,
                            isHtml = isHtml,
                            hasAttachments = attachments.isNotEmpty(),
                        ),
                    ),
                )
                if (attachments.isNotEmpty()) messagesDao.insertAttachments(attachments)
            }
        } catch (_: Exception) {
            Log.status("EmailSync", "   x Backfill failed for UID ${msg.id}")
        }
    }

    companion object {
        private const val SYNC_WORK_NAME = "EmailSyncWorker"
        private const val HOURLY_NON_INBOX_WORK_NAME = "EmailNonInboxHourlySync"
        private const val KEY_NON_INBOX_ONLY = "non_inbox_only"
        private const val SYNC_PAGE_SIZE = 50
        private const val READ_STATUS_CHECK_COUNT = 50
        private const val NO_LAST_SEEN_UID = -1L

        /** Sync read status via raw FETCH FLAGS */
        private suspend fun syncReadStatusRaw(
            context: Context,
            account: com.vayunmathur.email.data.EmailAccount,
            folderName: String,
            knownUids: Set<Long>,
        ) {
            try {
                val dao = EmailRepository.get(context).getDatabase().messageDao()
                val uidsToCheck = knownUids.sortedDescending().take(READ_STATUS_CHECK_COUNT)
                if (uidsToCheck.isEmpty()) return
                val auth = account.resolveAuth(context)
                val server = account.imapServer()
                val user = account.loginUser()

                ImapClient.withConnection(server, user, auth) { conn ->
                    conn.select(folderName)
                    val uidSet = uidsToCheck.joinToString(",")
                    val results = conn.fetch.uidFetchHeaders(uidSet)
                    for (r in results) {
                        val isRead = r.flags.any { it.equals("\\Seen", ignoreCase = true) }
                        try { dao.updateReadStatus(account.email, folderName, r.uid, isRead) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        private val GMAIL_VIRTUAL_FOLDERS = setOf(
            "[Gmail]/All Mail",
            "[Gmail]/Important",
            "[Gmail]/Starred",
            "[Gmail]/Chats",
        )

        private const val BACKFILL_LIMIT = 200

        private fun lastSeenPrefs(context: Context) =
            context.getSharedPreferences("email_notif_last_seen", Context.MODE_PRIVATE)

        private fun lastSeenKey(accountEmail: String, folderName: String) =
            "$accountEmail::$folderName"

        @Deprecated("IDLE-only push: periodic polling removed.")
        fun schedulePeriodicSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(HOURLY_NON_INBOX_WORK_NAME)
        }

        fun scheduleHourlyNonInboxSync(context: Context) {
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
