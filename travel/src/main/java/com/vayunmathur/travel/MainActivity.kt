package com.vayunmathur.travel

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.travel.data.TravelRepository
import com.vayunmathur.library.image.fetchers.HttpImageFetcher
import com.vayunmathur.travel.platform.TravelViewModel
import com.vayunmathur.travel.platform.TravelViewModelFactory
import com.vayunmathur.travel.platform.pendingTripIds
import com.vayunmathur.travel.platform.pollTrip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle

/** Main Activity: a ma activity implementation for main activity. */
class MainActivity : MaActivity() {
    private lateinit var repository: TravelRepository

    private val viewModel: TravelViewModel by viewModels {
        TravelViewModelFactory(application, repository)
    }
    private var ready by mutableStateOf(false)

    override fun onCreateBeforeContent() {
        // Hardened trust: api.maproj.org + data.maproj.org are Cloudflare
        // fronted, covered by ISRG X1/X2 + GTS R1-R4 in FIRST_PARTY bundle.
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        // Destination and stay photos load over HTTP(S) through the shared image
        // pipeline; the fetch function is built here from NetworkClient so no
        // library-to-library edge is needed (#884).
        ImageLoader.setDefault(
            ImageLoader.Builder(this).remote { data, _ -> HttpImageFetcher.fetch(data, NetworkClient::getBytes) }.build(),
        )

        lifecycleScope.launch(Dispatchers.IO) {
            repository = TravelRepository.get(this@MainActivity)
            withContext(Dispatchers.Main) { ready = true }
        }
    }

    override fun onResume() {
        super.onResume()
        // Resume polling any trip that is still awaiting payment / processing.
        if (!::repository.isInitialized) return
        lifecycleScope.launch {
            viewModel.pendingTripIds().forEach { viewModel.pollTrip(it) }
        }
    }

    @Composable
    override fun Content() {
        if (ready) Navigation(viewModel)
    }
}
