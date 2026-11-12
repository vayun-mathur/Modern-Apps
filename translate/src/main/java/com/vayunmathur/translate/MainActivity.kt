package com.vayunmathur.translate

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.library.downloadservice.InitialModelDownloadChecker
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.translate.platform.MadladModel
import com.vayunmathur.translate.platform.NllbModel
import com.vayunmathur.translate.platform.SharedImageIntent
import com.vayunmathur.translate.platform.TranslateViewModel

class MainActivity : MaActivity() {
    private val viewModel: TranslateViewModel by viewModels()
    private var initialText: String = ""

    override fun onCreateBeforeContent() {
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        initialText = processTextFromIntent(intent)
        // A recreated activity gets the same launch intent and the same ViewModel;
        // the image it carried was already opened the first time round.
        if (!viewModel.launchIntentHandled) {
            viewModel.launchIntentHandled = true
            handleImageIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImageIntent(intent)
    }

    @Composable
    override fun Content() {
        val ds = DataStoreUtils.getInstance(this)
        val sharedImage by viewModel.sharedImage.collectAsState()
        // NLLB only: MADLAD's files are skipped so the app does not download ~1 GB
        // it cannot yet use (Q2_K decode is too slow on-device — see the bring-up
        // notes). Re-add `+ MadladModel.FILES` when the engine selector lands.
        // A shared image waits in the ViewModel behind this same gate, exactly like
        // the in-app picker, which is only reachable once the model is installed.
        InitialModelDownloadChecker(ds, NllbModel.FILES) {
            Navigation(
                viewModel = viewModel,
                initialText = initialText,
                sharedImage = sharedImage,
                onSharedImageConsumed = viewModel::consumeSharedImage,
            )
        }
    }

    /** Share (SEND) / Open with (VIEW) an image: copy it in and open image translate (#846). */
    private fun handleImageIntent(intent: Intent?) {
        SharedImageIntent.imageUri(intent)?.let(viewModel::openSharedImage)
    }

    private fun processTextFromIntent(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_PROCESS_TEXT) return ""
        return intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
    }
}
