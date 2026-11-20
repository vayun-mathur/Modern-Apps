package com.vayunmathur.email

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.email.data.DateMillisBackfill
import com.vayunmathur.email.data.DraftEntry
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.data.PeekContentBackfill
import com.vayunmathur.email.data.EmailSyncWorker
import com.vayunmathur.email.data.ImapIdleService
import com.vayunmathur.email.data.OutboxSendWorker
import com.vayunmathur.email.platform.AppLifecycleTracker
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.platform.IntentState
import com.vayunmathur.email.ui.MainContent
import com.vayunmathur.email.widget.EmailWidgetReceiver
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.widgets.updateWidgetPreviews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Entry activity that wires the email UI, navigation and background sync startup. */
class MainActivity : MaActivity() {

    /** Holds activity-level constants such as the notification permission request code. */
    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 7331
    }

    override fun onCreateBeforeContent() {
        NetworkClient.init(this, TrustBundle.SYSTEM)
        handleIntent(intent)
        // Single deferred startup block: every worker/service enqueue below
        // is thread-safe (WorkManager enqueues, startForegroundService) and
        // none gates the first frame, so they leave the main thread together
        // instead of several main-thread calls. The two backfills already
        // launch on Dispatchers.IO themselves; calling them from here keeps
        // one startup site. Order preserved: hourly sync, outbox, IDLE,
        // then the one-time backfills.
        lifecycleScope.launch(Dispatchers.IO) {
            this@MainActivity.updateWidgetPreviews(EmailWidgetReceiver::class)
            EmailSyncWorker.scheduleHourlyNonInboxSync(this@MainActivity)
            OutboxSendWorker.runNow(this@MainActivity)
            ImapIdleService.start(this@MainActivity)
            DateMillisBackfill.runIfNeeded(lifecycleScope, this@MainActivity)
            PeekContentBackfill.runIfNeeded(lifecycleScope, this@MainActivity)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            val granted = android.content.pm.PackageManager.PERMISSION_GRANTED
            if (checkSelfPermission(permission) != granted) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_POST_NOTIFICATIONS,
                )
            }
        }
    }

    @Composable
    override fun Content() {
        OfflineAware {
            val viewModel: EmailViewModel = viewModel()
            MainContent(viewModel = viewModel)
        }
    }

    override fun onStart() {
        super.onStart()
        AppLifecycleTracker.isAppInForeground = true
        AppLifecycleTracker.tryStartIdleIfForeground(this)
    }

    override fun onResume() {
        super.onResume()
        AppLifecycleTracker.tryStartIdleIfForeground(this)
    }

    override fun onStop() {
        super.onStop()
        AppLifecycleTracker.isAppInForeground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (tryHandleEmlView(intent)) return
        handleDeepLinkOrShare(intent)
    }

    private fun tryHandleEmlView(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_VIEW) return false
        val dataUri = intent.data ?: return false
        if (!looksLikeEml(intent, dataUri)) return false
        IntentState.navigationRoute = Route.EmlViewer(dataUri.toString())
        return true
    }

    private fun looksLikeEml(intent: Intent, dataUri: android.net.Uri): Boolean {
        val lowerMime = intent.type?.lowercase() ?: ""
        if (lowerMime.contains("rfc822") || lowerMime.contains("mbox")) return true
        val lastSeg = dataUri.lastPathSegment ?: ""
        if (lastSeg.endsWith(".eml", ignoreCase = true)) return true
        if (dataUri.scheme != "content") return false
        return queryDisplayName(dataUri)?.endsWith(".eml", ignoreCase = true) == true
    }

    private fun queryDisplayName(dataUri: android.net.Uri): String? {
        return try {
            contentResolver.query(
                dataUri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) { /* best-effort */ null }
    }

    private fun handleDeepLinkOrShare(intent: Intent) {
        val accountEmail = intent.getStringExtra("accountEmail")
        val threadId = intent.getStringExtra("threadId")
        when {
            accountEmail != null && threadId != null ->
                IntentState.navigationRoute = Route.MessageThread(accountEmail, threadId)
            intent.getBooleanExtra("compose", false) ->
                IntentState.navigationRoute = Route.Composer()
            intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SENDTO ->
                handleShareIntent(intent)
        }
    }

    private fun handleShareIntent(intent: Intent) {
        val to = if (intent.action == Intent.ACTION_SENDTO) {
            intent.data?.schemeSpecificPart ?: ""
        } else {
            ""
        }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
        val body = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        if (to.isBlank() && subject.isBlank() && body.isBlank()) {
            IntentState.navigationRoute = Route.Composer()
            return
        }
        // Persist the shared text as a draft first, so the nav route carries
        // only its id: large EXTRA_TEXT is no longer retained in the back
        // stack (and lost/stale on process death), and the composer resumes
        // it through the same draft machinery as a normal compose (whose
        // autosave creates/updates this same row). The account's signature
        // is appended here, as the composer would for a fresh compose,
        // because a draft-resume does not append it again.
        lifecycleScope.launch(Dispatchers.IO) {
            val repository = EmailRepository.get(this@MainActivity)
            val account = repository.getAccounts().firstOrNull()
            if (account == null) {
                // No account to own a draft yet: keep the pre-existing flow
                // (the add-account gate runs first, then this route is read).
                IntentState.navigationRoute = Route.Composer(to = to, subject = subject, body = body)
                return@launch
            }
            val draftId = repository.outboxDao().insertDraft(
                DraftEntry(
                    accountEmail = account.email,
                    to = to,
                    subject = subject,
                    body = body + com.vayunmathur.email.ui.signatureBlockHtml(account),
                ),
            )
            IntentState.navigationRoute = Route.Composer(draftId = draftId)
        }
    }
}
