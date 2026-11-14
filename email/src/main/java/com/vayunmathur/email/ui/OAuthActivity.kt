package com.vayunmathur.email.ui

import android.content.Intent
import android.os.Bundle
import com.vayunmathur.library.log.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.email.MainActivity
import com.vayunmathur.email.R
import com.vayunmathur.email.data.OutlookOAuth
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.launch

/**
 * Microsoft OAuth redirect handler for `com.vayunmathur.email://oauth`.
 * Azure portal: Mobile and desktop applications — matches Thunderbird Desktop
 * `useExternalBrowser=true` pattern (RFC 8252 custom-scheme).
 *
 * Handles both onCreate and onNewIntent (singleTask launchMode) so second
 * login attempts land here even if activity already exists.
 */
class OAuthActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val raw = intent?.data
        if (raw == null) {
            Log.status(TAG, "OAuthActivity no data")
            AppMessages.show("Microsoft sign-in failed: no redirect data", duration = AppMessages.Duration.Indefinite)
            finish()
            return
        }
        Log.dev(TAG, "Redirect raw=$raw host=${raw.host} path=${raw.path} query=${raw.query}")

        lifecycleScope.launch {
            val result: OutlookOAuth.OAuthResult = try {
                OutlookOAuth.complete(applicationContext, raw)
            } catch (ignored: Exception) {
                Log.error(TAG, "complete threw", ignored)
                OutlookOAuth.OAuthResult.Failure(
                    reason = ignored.message ?: "${ignored.javaClass.simpleName} during sign-in",
                    error = ignored.javaClass.simpleName,
                    errorDescription = ignored.message
                )
            }

            val (msg, duration) = when (result) {
                is OutlookOAuth.OAuthResult.Success -> {
                    Log.dev(TAG, "OAuth success email=${result.email}")
                    getString(R.string.added, result.email) to AppMessages.Duration.Long
                }
                is OutlookOAuth.OAuthResult.Failure -> {
                    // Surface the actual callback error instead of hardcoded string
                    val parts = listOfNotNull(
                        result.reason.takeIf { it.isNotBlank() },
                        result.error?.takeIf { it.isNotBlank() },
                        result.errorDescription?.takeIf { it.isNotBlank() }
                    ).distinct()
                    val detailed = parts.joinToString(": ")
                    Log.error(TAG, "OAuth failure: $detailed raw=$raw")
                    // Show callback error (error_description from Azure) not generic check-redirect message
                    detailed to AppMessages.Duration.Indefinite
                }
            }
            AppMessages.show(msg, duration = duration)

            startActivity(
                Intent(this@OAuthActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
    }

    /** Holds the logging tag for the OAuth redirect activity. */
    companion object { private const val TAG = "OAuthActivity" }
}
