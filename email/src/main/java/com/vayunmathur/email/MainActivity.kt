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
import com.vayunmathur.email.platform.StartupGuard
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
        //
        // Every step runs through StartupGuard in isolation (#939): none of
        // this background work may crash the process after the first frame.
        // An unguarded failure here (WorkManager / foreground-service start
        // refusal, or a database that will not open on this device) used to
        // escape this launch and exit the app ~1s after the Add Account
        // gate first composed.
        lifecycleScope.launch(Dispatchers.IO) {
            val context = this@MainActivity
            StartupGuard.runStep("widget previews") {
                context.updateWidgetPreviews(EmailWidgetReceiver::class)
            }
            StartupGuard.runStep("hourly sync schedule") {
                EmailSyncWorker.scheduleHourlyNonInboxSync(context)
            }
            StartupGuard.runStep("outbox flush") { OutboxSendWorker.runNow(context) }
            StartupGuard.runStep("IDLE start") { ImapIdleService.start(context) }
            StartupGuard.runStep("date backfill") {
                DateMillisBackfill.runIfNeeded(lifecycleScope, context)
            }
            StartupGuard.runStep("peek backfill") {
                PeekContentBackfill.runIfNeeded(lifecycleScope, context)
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
            // A database that will not open on this device (#939) must not
            // crash a share either: fall back to the plain composer route,
            // exactly as the no-account case below does.
            val account = try {
                EmailRepository.get(this@MainActivity).getAccounts().firstOrNull()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                IntentState.navigationRoute = Route.Composer(to = to, subject = subject, body = body)
                return@launch
            }
            val repository = EmailRepository.get(this@MainActivity)
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
