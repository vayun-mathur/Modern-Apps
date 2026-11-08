@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.vayunmathur.email.data

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailFolder
import com.vayunmathur.email.platform.EmailManager
import com.vayunmathur.email.R
import com.vayunmathur.email.network.imap.ImapAuthException
import com.vayunmathur.email.network.imap.ImapClient
import com.vayunmathur.email.network.imap.RawImapConnection
import com.vayunmathur.email.network.imap.TrustAll
import com.vayunmathur.email.platform.resolveAuth
import com.vayunmathur.email.platform.imapServer
import com.vayunmathur.email.platform.loginUser
import com.vayunmathur.email.platform.AppLifecycleTracker
import com.vayunmathur.email.platform.EmailNotifications
import com.vayunmathur.email.widget.EmailWidget
import com.vayunmathur.library.util.ensureNotificationChannel
import androidx.glance.appwidget.updateAll
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service — raw IMAP IDLE (no Jakarta), app-password + Outlook OAuth XOAUTH2.
 * Fix for BOOT_COMPLETED crash: start() returns Boolean, onStartCommand catches FGS exception.
 */
class ImapIdleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accountJobs = mutableMapOf<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildOngoingNotification(), foregroundServiceType())
        } catch (e: SecurityException) {
            return abortStart(e.message, e)
        } catch (e: IllegalStateException) {
            return abortStart(e.message, e)
        }
        scope.launch { startIdleLoops() }
        return START_STICKY
    }

    private fun abortStart(reason: String?, cause: Throwable): Int {
        Log.status(TAG, "startForeground failed: $reason", cause)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private suspend fun startIdleLoops() {
        val dao = EmailRepository.get(applicationContext).getDatabase().accountDao()
        val accounts = dao.getAccounts()
        if (accounts.isEmpty()) { stopSelf(); return }
        try { EmailSyncWorker.scheduleHourlyNonInboxSync(applicationContext) } catch (_: Exception) {}
        val current = accounts.map { it.email }.toSet()
        accountJobs.keys.filter { it !in current }.forEach { accountJobs[it]?.cancel(); accountJobs.remove(it) }
        for (account in accounts) {
            accountJobs[account.email]?.cancel()
            accountJobs[account.email] = scope.launch { idleLoop(account) }
        }
    }

    private suspend fun idleLoop(account: EmailAccount) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (scope.coroutineContext.isActive) {
            try {
                runIdleSessionRaw(account)
                backoffMs = INITIAL_BACKOFF_MS
                delay(RECONNECT_QUIET_MS)
            } catch (e: ImapAuthException) {
                Log.status(TAG, "IDLE auth failed for ${account.email}; stop", e)
                return
            } catch (e: IOException) {
                if (isAuthFailure(e)) {
                    Log.status(TAG, "IDLE auth fail ${account.email}")
                    return
                }
                Log.status(TAG, "IDLE err ${account.email}: ${e.javaClass.simpleName}: ${e.message}")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            } catch (_: Exception) {
                Log.status(TAG, "IDLE non-IO err ${account.email}; backing off")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    private fun isAuthFailure(e: IOException): Boolean {
        val msg = e.message?.lowercase() ?: ""
        return msg.contains("auth") && msg.contains("fail")
    }

    private suspend fun runIdleSessionRaw(account: EmailAccount) = withContext(Dispatchers.IO) {
        val server = account.imapServer()
        val user = account.loginUser()
        val auth = account.resolveAuth(applicationContext)
        val useTrustAll = !TrustAll.isKnownHost(server.host)
        val rawConn = RawImapConnection(server, trustAll = useTrustAll)
        rawConn.connect()
        var caps = rawConn.capability()
        // Fail closed on STARTTLS failure (see ImapClient.withConnection):
        // never fall through to a plaintext LOGIN with the password.
        if (!server.useSsl && caps.has("STARTTLS")) {
            rawConn.startTls()
            caps = rawConn.capability()
        }

        when (auth) {
            is EmailManager.AuthType.OAuth -> rawConn.authenticateXoauth2(user, auth.token)
            is EmailManager.AuthType.Password -> {
                try { rawConn.login(user, auth.value) } catch (e: IOException) {
                    if (caps.has("AUTH=PLAIN")) rawConn.authenticatePlain(user, auth.value) else throw e
                }
            }
        }

        val supportsIdle = caps.has("IDLE")
        Log.dev(TAG, "Raw IDLE supports $supportsIdle for ${account.email}")

        if (!supportsIdle) {
            try {
                while (scope.coroutineContext.isActive) {
                    delay(FALLBACK_NO_IDLE_POLL_MS)
                    pollInboxOnce(rawConn, account)
                }
            } finally {
                try { rawConn.close() } catch (_: IOException) {}
            }
            return@withContext
        }

        val db = EmailRepository.get(applicationContext).getDatabase()
        try {
            runIdleLoops(rawConn, db, account)
        } finally {
            try { rawConn.close() } catch (_: IOException) {}
        }
    }

    private suspend fun pollInboxOnce(rawConn: RawImapConnection, account: EmailAccount) {
        try {
            val dao = EmailRepository.get(applicationContext).getDatabase().messageDao()
            val known = dao.getKnownUids(account.email, "INBOX").toSet()
            val deleted = dao.getDeletedUids(account.email, "INBOX").toSet()
            val (msgs, atts) = ImapClient.fetchMessagesInConnection(
                rawConn,
                account.email,
                "INBOX",
                FETCH_PAGE_SIZE,
                0,
                false,
                known + deleted,
                applicationContext,
            )
            if (msgs.isNotEmpty()) {
                dao.insertMessages(msgs)
                if (atts.isNotEmpty()) dao.insertAttachments(atts)
                postNewMailNotification(account.email, msgs)
            }
            syncReadStatusPullRaw(applicationContext, account, known)
        } catch (_: Exception) { Log.status(TAG, "Raw poll fail for ${account.email}") }
    }

    private suspend fun fetchNewMail(rawConn: RawImapConnection, dao: EmailMessageDao, account: EmailAccount) {
        try {
            val known = dao.getKnownUids(account.email, "INBOX").toSet()
            val deleted = dao.getDeletedUids(account.email, "INBOX").toSet()
            val (msgs, atts) = ImapClient.fetchMessagesInConnection(
                rawConn,
                account.email,
                "INBOX",
                FETCH_PAGE_SIZE,
                0,
                false,
                known + deleted,
                applicationContext,
            )
            if (msgs.isNotEmpty()) {
                dao.insertMessages(msgs)
                if (atts.isNotEmpty()) {
                    dao.insertAttachments(atts)
                }
                postNewMailNotification(account.email, msgs)
            }
        } catch (_: Exception) { Log.status(TAG, "quick fetch fail for ${account.email}") }
    }

    private suspend fun runIdleLoops(
        rawConn: RawImapConnection,
        db: EmailDatabase,
        account: EmailAccount,
    ) {
        while (scope.coroutineContext.isActive) {
            val sel = rawConn.select("INBOX")
            Log.dev(TAG, "SELECT INBOX ${account.email} exists=${sel.exists}")
            discoverFolders(rawConn, db.accountDao(), account)

            val state = IdleWatchState()
            while (scope.coroutineContext.isActive && !state.needReopen) {
                watchOnce(rawConn, db, account, state)
            }
            if (shouldSettle(state)) delay(IDLE_SETTLE_MS)
        }
    }

    private fun shouldSettle(state: IdleWatchState): Boolean {
        if (!scope.coroutineContext.isActive) return false
        return !state.needReopen
    }

    private suspend fun discoverFolders(
        rawConn: RawImapConnection,
        accountDao: EmailAccountDao,
        account: EmailAccount,
    ) {
        try {
            val folders = rawConn.list("", "*").map { entry ->
                val delim = entry.delimiter ?: "/"
                // Persist under the canonical name (see
                // ImapClient.canonicalizeMailbox): storing the server's
                // raw "Inbox" here split Outlook folders/messages from
                // the "INBOX" bucket every other path uses.
                val fullName = ImapClient.canonicalizeMailbox(entry.mailbox, delim)
                val nm = if (entry.mailbox.contains(delim)) {
                    entry.mailbox.substringAfterLast(delim)
                } else {
                    entry.mailbox.substringAfterLast('/')
                }
                val parent = fullName.lastIndexOf(delim)
                    .let { if (it > 0) fullName.substring(0, it) else null }
                val holds = !entry.flags.any { f -> f.equals("\\Noselect", ignoreCase = true) }
                EmailFolder(account.email, fullName, nm.ifBlank { fullName }, parent, holds, delim)
            }
            accountDao.insertFolders(folders)
        } catch (_: Exception) { Log.status(TAG, "folder discovery fail for ${account.email}") }
    }

    private class IdleWatchState {
        var sawNewMail = false
        var sawExpunge = false
        var sawFlags = false
        val expungedSeqs = mutableListOf<Int>()
        var needReopen = false
        var isProactiveRefresh = false
    }

    private suspend fun watchOnce(
        rawConn: RawImapConnection,
        db: EmailDatabase,
        account: EmailAccount,
        state: IdleWatchState,
    ) {
        val idleTag = rawConn.sendIdle()
        Log.dev(TAG, "IDLE start ${account.email} tag=$idleTag")

        val watchdog = scope.launch {
            delay(IDLE_REFRESH_MS)
            if (isActive) {
                Log.dev(TAG, "proactive refresh ${account.email}")
                state.isProactiveRefresh = true
                try { rawConn.sendIdleDone() } catch (_: Exception) {}
            }
        }

        val idleEnded = drainIdleLines(rawConn, account, idleTag, state)

        if (!idleEnded) {
            try { rawConn.sendIdleDone() } catch (_: Exception) {}
            try { rawConn.readIdleResponseForTag(idleTag) } catch (_: Exception) {}
        }

        watchdog.cancel()

        if (state.isProactiveRefresh) {
            Log.dev(TAG, "24-min refresh ${account.email}")
            state.isProactiveRefresh = false
            state.needReopen = true
            delay(WATCHDOG_REFRESH_GRACE_MS)
            return
        }

        handleIdleSignals(rawConn, db, account, state)
    }

    private suspend fun drainIdleLines(
        rawConn: RawImapConnection,
        account: EmailAccount,
        idleTag: String,
        state: IdleWatchState,
    ): Boolean {
        var idleEnded = false
        var done = false
        while (!done && scope.coroutineContext.isActive) {
            val line = withContext(Dispatchers.IO) {
                try { rawConn.readIdleLine() } catch (_: IOException) { null }
            }
            done = handleIdleLine(rawConn, account, idleTag, state, line)
            idleEnded = idleEnded || (line != null && line.startsWith(idleTag))
        }
        return idleEnded
    }

    private fun handleIdleLine(
        rawConn: RawImapConnection,
        account: EmailAccount,
        idleTag: String,
        state: IdleWatchState,
        line: String?,
    ): Boolean {
        if (line == null) return true
        Log.dev(TAG, "IDLE line ${account.email}: $line")
        if (line.startsWith(idleTag)) return true
        classifyIdleLine(line, state)
        if (state.sawNewMail || state.sawExpunge || state.sawFlags) {
            try { rawConn.sendIdleDone() } catch (_: Exception) {}
        }
        return false
    }

    private fun classifyIdleLine(line: String, state: IdleWatchState) {
        when {
            Regex("""^\* (\d+) EXISTS""").containsMatchIn(line) -> state.sawNewMail = true
            Regex("""^\* (\d+) EXPUNGE""").containsMatchIn(line) -> {
                val seq = Regex("""^\* (\d+) EXPUNGE""")
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull() ?: -1
                if (seq != -1) {
                    state.expungedSeqs.add(seq)
                }
                state.sawExpunge = true
            }
            line.contains("FETCH") && line.contains("FLAGS") -> state.sawFlags = true
        }
    }

    private suspend fun handleIdleSignals(
        rawConn: RawImapConnection,
        db: EmailDatabase,
        account: EmailAccount,
        state: IdleWatchState,
    ) {
        if (state.sawNewMail && !state.needReopen) {
            state.sawNewMail = false
            fetchNewMail(rawConn, db.messageDao(), account)
        }

        if (state.sawExpunge && state.expungedSeqs.isNotEmpty() && !state.needReopen) {
            state.sawExpunge = false
            state.expungedSeqs.clear()
            try { EmailWidget().updateAll(applicationContext) } catch (_: Exception) {}
        }

        if (state.sawFlags && !state.needReopen) {
            state.sawFlags = false
            val known = db.messageDao().getKnownUids(account.email, "INBOX").toSet()
            try {
                syncReadStatusPullRaw(applicationContext, account, known)
            } catch (_: Exception) { Log.status(TAG, "flag sync fail for ${account.email}") }
        }
    }

    private suspend fun postNewMailNotification(
        accountEmail: String,
        messages: List<com.vayunmathur.email.data.EmailMessage>,
    ) {
        if (messages.isEmpty()) return
        val ctx = applicationContext ?: return
        val prefs = ctx.getSharedPreferences("email_notif_last_seen", Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong("$accountEmail::INBOX", NO_LAST_SEEN_UID)
        val notifiable = if (lastSeen == NO_LAST_SEEN_UID) messages else messages.filter { it.id > lastSeen }
        if (notifiable.isNotEmpty() && !AppLifecycleTracker.isAppInForeground) {
            EmailNotifications.postForNewMessages(ctx, accountEmail, notifiable)
        }
        val maxUid = messages.maxOfOrNull { it.id } ?: lastSeen
        if (maxUid > lastSeen) prefs.edit { putLong("$accountEmail::INBOX", maxUid) }
        try { EmailWidget().updateAll(ctx) } catch (_: Exception) { Log.status(TAG, "widget fail $accountEmail") }
    }

    private suspend fun syncReadStatusPullRaw(context: Context, account: EmailAccount, knownUids: Set<Long>) {
        if (knownUids.isEmpty()) return
        try {
            val dao = EmailRepository.get(context).getDatabase().messageDao()
            val uidsToCheck = knownUids.sortedDescending().take(READ_STATUS_CHECK_COUNT)
            val auth = account.resolveAuth(context)
            ImapClient.withConnection(account.imapServer(), account.loginUser(), auth) { conn ->
                conn.select("INBOX")
                val results = conn.fetch.uidFetchHeaders(uidsToCheck.joinToString(","))
                for (r in results) {
                    val isRead = r.flags.any { it.equals("\\Seen", ignoreCase = true) }
                    try { dao.updateReadStatus(account.email, "INBOX", r.uid, isRead) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun buildOngoingNotification(): Notification {
        ensureChannel(applicationContext)
        return androidx.core.app.NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_mail)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.listening_for_new_mail))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun foregroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
    }

    companion object {
        private const val TAG = "ImapIdle"
        private const val CHANNEL_ID = "imap_idle"
        private const val NOTIFICATION_ID = 9001
        const val IDLE_REFRESH_MS = 24L * 60 * 1000
        const val FALLBACK_NO_IDLE_POLL_MS = 5L * 60 * 1000
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val RECONNECT_QUIET_MS = 1_000L
        private const val WATCHDOG_REFRESH_GRACE_MS = 200L
        private const val IDLE_SETTLE_MS = 500L
        private const val FETCH_PAGE_SIZE = 50
        private const val READ_STATUS_CHECK_COUNT = 50
        private const val NO_LAST_SEEN_UID = -1L

        private fun ensureChannel(context: Context) {
            context.ensureNotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.email_sync_channel_name),
                NotificationManager.IMPORTANCE_LOW,
                context.getString(R.string.email_sync_channel_desc),
            ) {
                setShowBadge(false)
            }
        }

        fun start(context: Context): Boolean {
            return try {
                val intent = Intent(context, ImapIdleService::class.java)
                context.startForegroundService(intent)
                true
            } catch (e: SecurityException) {
                Log.status(TAG, "start failed: ${e.message}", e); false
            } catch (e: IllegalStateException) {
                Log.status(TAG, "start failed: ${e.message}", e); false
            }
        }

        fun stop(context: Context) { context.stopService(Intent(context, ImapIdleService::class.java)) }
    }
}
