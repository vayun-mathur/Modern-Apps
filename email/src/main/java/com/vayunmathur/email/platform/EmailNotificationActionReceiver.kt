package com.vayunmathur.email.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationManagerCompat
import com.vayunmathur.email.platform.EmailManager
import com.vayunmathur.email.platform.resolveAuth
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.platform.imapServer
import com.vayunmathur.email.platform.loginUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles "Mark read" / "Delete" action buttons on new-mail notifications.
 * Performs the local DB change and syncs to the IMAP server off the main thread.
 */
class EmailNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val accountEmail = intent.getStringExtra(EXTRA_ACCOUNT) ?: return
        val folderName = intent.getStringExtra(EXTRA_FOLDER) ?: return
        val uid = intent.getLongExtra(EXTRA_UID, -1L)
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        if (uid < 0) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = EmailRepository.get(context).getDatabase()
                val accountDao = db.accountDao()
                val messageDao = db.messageDao()
                val account = accountDao.getAccountByEmail(accountEmail)
                val manager = EmailManager()
                when (action) {
                    ACTION_MARK_READ -> {
                        messageDao.updateReadStatus(accountEmail, folderName, uid, true)
                        account?.let {
                            manager.setSeenFlag(
                                it.imapServer(),
                                it.loginUser(),
                                it.resolveAuth(context),
                                folderName,
                                uid,
                                true,
                            )
                        }
                    }
                    ACTION_DELETE -> {
                        // Tombstone like EmailViewModel.deleteMessage: without
                        // it, a failed/lagging server expunge lets the next
                        // sync re-insert the message the user just deleted.
                        messageDao.deleteMessageRow(accountEmail, folderName, uid, tombstone = true)
                        account?.let {
                            manager.deleteMessage(
                                it.imapServer(),
                                it.loginUser(),
                                it.resolveAuth(context),
                                folderName,
                                uid,
                            )
                        }
                    }
                }
                NotificationManagerCompat.from(context).cancel(notifId)
            } catch (_: Exception) {
                Log.status("EmailNotifAction", "Action $action failed")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MARK_READ = "com.vayunmathur.email.MARK_READ"
        const val ACTION_DELETE = "com.vayunmathur.email.DELETE"
        const val EXTRA_ACTION = "action"
        const val EXTRA_ACCOUNT = "accountEmail"
        const val EXTRA_FOLDER = "folderName"
        const val EXTRA_UID = "uid"
        const val EXTRA_NOTIF_ID = "notifId"

        fun intent(
            context: Context,
            action: String,
            accountEmail: String,
            folderName: String,
            uid: Long,
            notifId: Int,
        ): Intent =
            Intent(context, EmailNotificationActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_ACTION, action)
                putExtra(EXTRA_ACCOUNT, accountEmail)
                putExtra(EXTRA_FOLDER, folderName)
                putExtra(EXTRA_UID, uid)
                putExtra(EXTRA_NOTIF_ID, notifId)
            }
    }
}
