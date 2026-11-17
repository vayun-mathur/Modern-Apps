package com.vayunmathur.share.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.share.platform.ShareViewModel
import com.vayunmathur.share.platform.ShareViewModelFactory

/**
 * The system share sheet's target: a half-height bottom sheet over whatever app shared the files.
 *
 * Opening the whole app for a share is a detour — the files are already chosen, so the only thing
 * left is picking a device, and that is all this shows. The window is transparent
 * (`Theme.SharePopup`) so the Compose sheet's own scrim is the only thing drawn over the app below.
 *
 * Nothing here stops the scan: [ShareViewModel.connectToDevice] stops it when a device is chosen
 * (the radios are needed for the transfer) and `onCleared` stops it when this activity goes away.
 */
class SharePopupActivity : ComponentActivity() {
    private val shareViewModel: ShareViewModel by viewModels { ShareViewModelFactory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh launch: the URIs live in the ViewModel, which survives recreation, and
        // re-staging shared text would rewrite the file a transfer may already be reading.
        if (savedInstanceState == null) shareViewModel.handleShareIntent(intent)
        setContent {
            DynamicTheme {
                SharePopupSheet(viewModel = shareViewModel, onDismiss = ::finish)
            }
        }
    }
}
