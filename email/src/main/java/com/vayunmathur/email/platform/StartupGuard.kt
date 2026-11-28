package com.vayunmathur.email.platform

import com.vayunmathur.library.log.Log
import kotlinx.coroutines.CancellationException

/**
 * Runs one best-effort startup step without letting its failure kill the
 * process (GitHub #939).
 *
 * Every step launched from `MainActivity.onCreateBeforeContent` — widget
 * previews (an Android 15+ only WorkManager enqueue), hourly-sync / outbox
 * scheduling, the IMAP IDLE foreground service, and the one-time backfills —
 * is fire-and-forget background work that must never gate or crash the first
 * screen. They used to run unguarded inside plain `launch` blocks, so any
 * failure in them (a WorkManager enqueue refusal, a foreground-service start
 * refusal, or — most importantly — a database that fails to open on this
 * device, e.g. its SQLCipher passphrase can no longer be decrypted by the
 * Android Keystore after an OS upgrade or a data restore) escaped as an
 * uncaught coroutine exception about a second after the Add Account gate
 * composed from the accounts flow's initial empty value, and the app exited
 * on every launch.
 *
 * Each step is therefore run in isolation: a failure is logged and reported
 * as `false`, and the remaining steps still run. [CancellationException] is
 * the one exception rethrown — it means the owning scope is going away, not
 * that the step failed.
 */
object StartupGuard {

    private const val TAG = "EmailStartup"

    /** Runs [step] in isolation; returns false (and logs) if it threw. */
    suspend fun runStep(name: String, step: suspend () -> Unit): Boolean {
        return try {
            step()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.status(TAG, "Startup step '$name' failed; continuing without it", failure)
            false
        }
    }
}
