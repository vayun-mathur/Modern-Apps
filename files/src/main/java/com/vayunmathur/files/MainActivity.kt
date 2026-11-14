package com.vayunmathur.files

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.core.content.IntentCompat
import com.vayunmathur.files.data.saf.DocumentPickerActivity
import com.vayunmathur.files.platform.FilesViewModel
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ui.MaActivity

/**
 * Entry activity for the file browser: enables the SAF picker when privileged
 * and routes files shared into the app toward the browser.
 */

class MainActivity : MaActivity() {
    private val viewModel: FilesViewModel by viewModels()

    override fun onCreateBeforeContent() {
        ensureDocumentPickerEnabled()
        handleIntent(intent)
    }

    @Composable
    override fun Content() {
        Navigation(viewModel)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.takeIf { it.type != null } ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { viewModel.setIncomingUris(listOf(it)) }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                intent,
                Intent.EXTRA_STREAM,
                Uri::class.java,
            )?.let { viewModel.setIncomingUris(it) }
        }
    }

    /**
     * Enables the SAF [DocumentPickerActivity] when Files is the privileged system documents UI
     * (holds MANAGE_DOCUMENTS). The component ships disabled so sideloaded installs never
     * intercept other apps' file pickers; but without this call nothing on the device handles
     * CREATE_DOCUMENT/OPEN_DOCUMENT and those callers crash with ActivityNotFoundException.
     * Idempotent and persisted by PackageManager, so one successful call sticks.
     */
    private fun ensureDocumentPickerEnabled() {
        if (checkSelfPermission(android.Manifest.permission.MANAGE_DOCUMENTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val picker = ComponentName(this, DocumentPickerActivity::class.java)
        // Broad catch is deliberate: enablement crosses PackageManager and must
        // never take down launch.
        @Suppress("TooGenericExceptionCaught")
        try {
            if (packageManager.getComponentEnabledSetting(picker) !=
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            ) {
                packageManager.setComponentEnabledSetting(
                    picker,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        } catch (e: Exception) {
            Log.error("Files", "Failed to enable document picker", e)
        }
    }
}
