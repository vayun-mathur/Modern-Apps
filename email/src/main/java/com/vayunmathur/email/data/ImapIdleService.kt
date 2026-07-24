package com.vayunmathur.email.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.updateAll
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import com.vayunmathur.email.EmailManager
import com.vayunmathur.email.R
import com.vayunmathur.email.resolveAuth
import com.vayunmathur.email.imapServer
import com.vayunmathur.email.loginUser
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.UIDFolder
import javax.mail.event.MessageChangedEvent
import javax.mail.event.MessageChangedListener
import javax.mail.event.MessageCountAdapter
import javax.mail.event.MessageCountEvent
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
 * Foreground service that keeps one IMAP IDLE connection open per account.
 *
 * IDLE push model:
 * - INBOX is watched via `IMAPFolder.idle(true)` for instant push (1 RTT).
 *   A watchdog triggers a graceful refresh at ~24 min to beat the server's
 *   ~29 min IDLE timeout — avoids BYE + full TLS re-handshake by closing
 *   INBOX and reopening it on the same STORE.
 * - Folder list refreshed on every store connect.
 * - Read/unread via [MessageChangedListener] (FLAGS_CHANGED).
 * - Deletions via `messagesRemoved` (EXPUNGE).
 *
 * Non-INBOX: INBOX is live push; other folders hourly via
 * [EmailSyncWorker.scheduleHourlyNonInboxSync] + on-demand pull-to-refresh.
 */
class ImapIdleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accountJobs = mutableMapOf<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildOngoingNotification(), foregroundServiceType())
        scope.launch { startIdleLoops() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startIdleLoops() {
        val dao = EmailDatabase.getInstance(applicationContext).emailDao()
        val accounts = dao.getAccounts()
        if (accounts.isEmpty()) {
            stopSelf()
            return
        }
        try {
            EmailSyncWorker.scheduleHourlyNonInboxSync(applicationContext)
        } catch (_: Throwable) {}
        val currentEmails = accounts.map { it.email }.toSet()
        accountJobs.keys.filter { it !in currentEmails }.forEach { email ->
            accountJobs[email]?.cancel()
            accountJobs.remove(email)
        }
        for (account in accounts) {
            accountJobs[account.email]?.cancel()
            accountJobs[account.email] = scope.launch { idleLoop(account) }
        }
    }

    private suspend fun idleLoop(account: com.vayunmathur.email.EmailAccount) {
        var backoffMs = 2_000L
        val maxBackoffMs = 60_000L
        while (scope.coroutineContext.isActive) {
            try {
                runIdleSession(account)
                backoffMs = 2_000L
                delay(1_000L)
            } catch (e: javax.mail.AuthenticationFailedException) {
                Log.w(TAG, "IDLE auth failed for ${account.email}; stopping retries")
                return
            } catch (e: Exception) {
                Log.w(TAG, "IDLE error for ${account.email}: ${e.javaClass.simpleName}: ${e.message}")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
            }
        }
    }

    private suspend fun runIdleSession(account: com.vayunmathur.email.EmailAccount) {
        withContext(Dispatchers.IO) {
            val mgr = EmailManager()
            mgr.withStore(
                server = account.imapServer(),
                user = account.loginUser(),
                auth = account.resolveAuth(applicationContext),
            ) { store ->
                val dao = EmailDatabase.getInstance(applicationContext).emailDao()
                try {
                    val folders = mgr.fetchFoldersInStore(store, account.email)
                    dao.insertFolders(folders)
                    Log.d(TAG, "Folder discovery for ${account.email}: ${folders.size} folders")
                } catch (t: Throwable) {
                    Log.w(TAG, "Folder discovery failed for ${account.email}: ${t.message}")
                }

                val imapStore = store as? IMAPStore
                val supportsIdle = try {
                    imapStore?.hasCapability("IDLE") ?: true
                } catch (_: Throwable) { true }
                if (!supportsIdle) {
                    Log.w(TAG, "Server ${account.imapServer().host} no IDLE cap; poll fallback")
                }

                while (scope.coroutineContext.isActive) {
                    val folder = store.getFolder("INBOX") as IMAPFolder
                    folder.open(Folder.READ_ONLY)

                    val sawNewMail = AtomicBoolean(false)
                    val sawFlagsChanged = AtomicBoolean(false)
                    val flaggedUids = Collections.synchronizedSet(mutableSetOf<Long>())
                    val removedUids = Collections.synchronizedSet(mutableSetOf<Long>())
                    val isProactiveRefresh = AtomicBoolean(false)

                    folder.addMessageCountListener(object : MessageCountAdapter() {
                        override fun messagesAdded(e: MessageCountEvent) {
                            Log.d(TAG, "EXISTS for ${account.email}: ${e.messages.size} new")
                            sawNewMail.set(true)
                        }
                        override fun messagesRemoved(e: MessageCountEvent) {
                            for (msg in e.messages) {
                                try {
                                    val uid = (folder as UIDFolder).getUID(msg)
                                    if (uid != -1L) removedUids.add(uid)
                                } catch (_: Throwable) {}
                            }
                            if (e.messages.isNotEmpty()) {
                                Log.d(TAG, "EXPUNGE for ${account.email}: ${e.messages.size} removed, uids=$removedUids")
                            }
                        }
                    })

                    folder.addMessageChangedListener(object : MessageChangedListener {
                        override fun messageChanged(e: MessageChangedEvent) {
                            if (e.messageChangeType == MessageChangedEvent.FLAGS_CHANGED) {
                                sawFlagsChanged.set(true)
                                try {
                                    val uid = (folder as UIDFolder).getUID(e.message)
                                    if (uid != -1L) flaggedUids.add(uid)
                                } catch (_: Throwable) {}
                                Log.d(TAG, "FLAGS_CHANGED for ${account.email}: uid in $flaggedUids")
                            }
                        }
                    })

                    var folderNeedsReopen = false
                    try {
                        Log.d(TAG, "Entering IDLE loop for ${account.email} (cap=$supportsIdle)")
                        while (scope.coroutineContext.isActive && folder.isOpen && !folderNeedsReopen) {
                            val watchdog = if (supportsIdle) {
                                scope.launch {
                                    delay(IDLE_REFRESH_MS)
                                    if (isActive && folder.isOpen) {
                                        Log.d(TAG, "Proactive IDLE refresh for ${account.email} (24 min)")
                                        isProactiveRefresh.set(true)
                                        try { folder.close(false) } catch (_: Throwable) {}
                                    }
                                }
                            } else null

                            try {
                                if (supportsIdle) {
                                    folder.idle(true)
                                } else {
                                    delay(FALLBACK_NO_IDLE_POLL_MS)
                                    sawNewMail.set(true)
                                    sawFlagsChanged.set(true)
                                }
                            } catch (e: Exception) {
                                if (isProactiveRefresh.getAndSet(false)) {
                                    Log.d(TAG, "IDLE 24-min refresh — reopening INBOX on same store for ${account.email}")
                                    folderNeedsReopen = true
                                } else {
                                    throw e
                                }
                            } finally {
                                watchdog?.cancel()
                            }

                            if (folderNeedsReopen) break

                            if (sawNewMail.getAndSet(false)) {
                                try {
                                    if (folder.isOpen) quickInboxFetch(folder, account.email)
                                } catch (t: Throwable) {
                                    Log.w(TAG, "Inline INBOX fetch failed for ${account.email}: ${t.message}")
                                }
                            }

                            if (removedUids.isNotEmpty()) {
                                val toDelete = synchronized(removedUids) { removedUids.toList().also { removedUids.clear() } }
                                try { handleExpunged(account.email, toDelete) } catch (t: Throwable) {
                                    Log.w(TAG, "Expunge handling failed: ${t.message}")
                                }
                            }

                            if (sawFlagsChanged.getAndSet(false) || flaggedUids.isNotEmpty()) {
                                val flaggedSnapshot = synchronized(flaggedUids) { flaggedUids.toList().also { flaggedUids.clear() } }
                                try {
                                    if (folder.isOpen) handleFlagChanges(folder, account.email, flaggedSnapshot)
                                } catch (t: Throwable) {
                                    Log.w(TAG, "Flag handling failed: ${t.message}")
                                }
                            }
                        }
                    } finally {
                        try { folder.close(false) } catch (_: Throwable) {}
                    }

                    if (folderNeedsReopen || isProactiveRefresh.get()) {
                        isProactiveRefresh.set(false)
                        delay(200L)
                        continue
                    }
                    if (!scope.coroutineContext.isActive) break
                    Log.d(TAG, "INBOX session ended for ${account.email} — reopening on same store")
                    delay(500L)
                }
            }
        }
    }

    private suspend fun handleExpunged(accountEmail: String, uids: List<Long>) {
        if (uids.isEmpty()) return
        val dao = EmailDatabase.getInstance(applicationContext).emailDao()
        for (uid in uids) {
            try { dao.deleteMessageRow(accountEmail, "INBOX", uid) } catch (_: Throwable) {}
        }
        Log.d(TAG, "Expunge cleaned $uids for $accountEmail")
        try { com.vayunmathur.email.widget.EmailWidget().updateAll(applicationContext) } catch (_: Throwable) {}
    }

    private suspend fun handleFlagChanges(folder: IMAPFolder, accountEmail: String, flaggedUids: List<Long>) {
        val dao = EmailDatabase.getInstance(applicationContext).emailDao()
        if (flaggedUids.isNotEmpty()) {
            val uidFolder = folder as? UIDFolder
            for (uid in flaggedUids) {
                try {
                    val msg = uidFolder?.getMessageByUID(uid) ?: continue
                    val isRead = msg.isSet(Flags.Flag.SEEN)
                    dao.updateReadStatus(accountEmail, "INBOX", uid, isRead)
                } catch (_: Throwable) {}
            }
        } else {
            try {
                val known = dao.getKnownUids(accountEmail, "INBOX").sortedDescending().take(50)
                val uidFolder = folder as? UIDFolder ?: return
                for (uid in known) {
                    try {
                        val msg = uidFolder.getMessageByUID(uid) ?: continue
                        dao.updateReadStatus(accountEmail, "INBOX", uid, msg.isSet(Flags.Flag.SEEN))
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
    }

    private suspend fun quickInboxFetch(folder: IMAPFolder, accountEmail: String) {
        val dao = EmailDatabase.getInstance(applicationContext).emailDao()
        val known = dao.getKnownUids(accountEmail, "INBOX").toSet()
        val deleted = dao.getDeletedUids(accountEmail, "INBOX").toSet()
        val (messages, attachments) = EmailManager().fetchMessagesFromOpenFolder(
            folder = folder, user = accountEmail, folderName = "INBOX",
            limit = 50, offset = 0, fetchBodies = false, skipUids = known + deleted,
        )
        if (messages.isEmpty()) {
            Log.d(TAG, "Inline INBOX fetch: nothing new for $accountEmail")
            return
        }
        dao.insertMessages(messages)
        if (attachments.isNotEmpty()) dao.insertAttachments(attachments)
        Log.d(TAG, "Inline INBOX fetch: ${messages.size} new for $accountEmail")

        val prefs = applicationContext.getSharedPreferences("email_notif_last_seen", Context.MODE_PRIVATE)
        val prefKey = "$accountEmail::INBOX"
        val lastSeen = prefs.getLong(prefKey, -1L)
        val notifiable = if (lastSeen == -1L) messages else messages.filter { it.id > lastSeen }
        if (notifiable.isNotEmpty() && !com.vayunmathur.email.util.AppLifecycleTracker.isAppInForeground) {
            com.vayunmathur.email.util.EmailNotifications.postForNewMessages(applicationContext, accountEmail, notifiable)
        }
        val maxUid = messages.maxOf { it.id }
        if (maxUid > lastSeen) prefs.edit().putLong(prefKey, maxUid).apply()

        try { com.vayunmathur.email.widget.EmailWidget().updateAll(applicationContext) } catch (t: Throwable) {
            Log.w(TAG, "Widget update failed: ${t.message}")
        }
    }

    private fun buildOngoingNotification(): Notification {
        ensureChannel(applicationContext)
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_mail)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.listening_for_new_mail))
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
        private const val IDLE_REFRESH_MS = 24L * 60 * 1000
        private const val FALLBACK_NO_IDLE_POLL_MS = 5L * 60 * 1000

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Background email sync", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps an IMAP connection open so new mail arrives instantly."
                    setShowBadge(false)
                }
            )
        }

        fun start(context: Context) {
            val intent = Intent(context, ImapIdleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ImapIdleService::class.java))
        }
    }
}
