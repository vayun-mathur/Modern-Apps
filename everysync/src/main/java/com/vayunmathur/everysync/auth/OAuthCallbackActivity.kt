package com.vayunmathur.everysync.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.everysync.MainActivity
import com.vayunmathur.everysync.sync.SyncScheduler
import kotlinx.coroutines.launch

/**
 * Receives the OAuth2 redirect (`com.vayunmathur.everysync:/oauth`), completes the
 * PKCE token exchange, then returns to [MainActivity] and kicks off a first sync.
 */
class OAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val accountName = OAuthManager.complete(applicationContext, data)
            if (accountName != null) {
                SyncScheduler.syncNow(applicationContext, accountName)
            }
            startActivity(
                Intent(this@OAuthCallbackActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
    }
}
