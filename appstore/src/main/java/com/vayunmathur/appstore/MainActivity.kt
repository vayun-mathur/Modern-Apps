package com.vayunmathur.appstore
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconProvider
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable
import com.vayunmathur.appstore.ui.AppRoot
import com.vayunmathur.appstore.platform.refreshInstalled

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.appstore.data.AppStoreDatabaseRepository
import com.vayunmathur.appstore.data.ExternalLinks
import com.vayunmathur.appstore.data.UpdateCheckWorker
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.appstore.platform.AppStoreViewModelFactory
import com.vayunmathur.library.image.fetchers.HttpImageFetcher
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.OfflineAware

/** Entry activity that wires the store UI, navigation and catalog startup. */
class MainActivity : MaActivity() {

    private var factoryState by mutableStateOf<AppStoreViewModelFactory?>(null)

    /** Package named by a `market://` or listing URL we were launched with. */
    private var externalPkg by mutableStateOf<String?>(null)

    private var vmRef: AppStoreViewModel? = null

    override fun onCreateBeforeContent() {
        // STANDARD includes GTS R1-R4 (needed for Play Store -> play.google.com / android.clients.google.com)
        // + ISRG X1/X2 (F-Droid) + DigiCert G2/G3 (GitHub). Covers all appstore hosts without full system ~140 roots.
        NetworkClient.init(this, TrustBundle.STANDARD)
        UpdateCheckWorker.schedule(this)
        // Listing artwork (iconUrl) loads over HTTP(S) through the shared image
        // pipeline; the fetch function is built here from NetworkClient so no
        // library-to-library edge is needed (#884).
        ImageLoader.setDefault(
            ImageLoader.Builder(this).remote { data, _ -> HttpImageFetcher.fetch(data, NetworkClient::getBytes) }.build(),
        )

        val repository = AppStoreDatabaseRepository.get(this)
        factoryState = AppStoreViewModelFactory(applicationContext, repository.database)
        handleIntentUrl(intent)
    }

    @Composable
    override fun Content() {
        OfflineAware {
            val factory = factoryState
            if (factory == null) {
                Box(Modifier.fillMaxSize())
            } else {
                val vm: AppStoreViewModel = viewModel(factory = factory)
                vmRef = vm
                AppRoot(vm, externalPkg) { externalPkg = null }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Catch installs and uninstalls that completed in the system dialog.
        vmRef?.refreshInstalled()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentUrl(intent)
    }

    private fun handleIntentUrl(intent: Intent?) {
        val pkg = ExternalLinks.parseExternalPackage(intent?.dataString)
        if (!pkg.isNullOrBlank()) externalPkg = pkg
    }
}

