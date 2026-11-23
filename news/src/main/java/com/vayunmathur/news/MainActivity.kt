package com.vayunmathur.news

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.library.image.fetchers.HttpImageFetcher
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.news.data.NewsRepository
import com.vayunmathur.news.platform.NewsViewModel
import com.vayunmathur.news.platform.NewsViewModelFactory

/** Entry activity hosting the news feed, reader, sources and saved screens. */
class MainActivity : MaActivity() {
    private val viewModel: NewsViewModel by viewModels {
        NewsViewModelFactory(application, NewsRepository.get(application))
    }

    override fun onCreateBeforeContent() {
        // Publisher hosts are arbitrary public sites, so SYSTEM (platform
        // trust) is the narrowest TrustBundle that can reach them; the
        // reduced bundles only cover our own / well-known roots.
        NetworkClient.init(this, TrustBundle.SYSTEM)
        ImageLoader.setDefault(
            ImageLoader.Builder(this)
                .remote { data, _ -> HttpImageFetcher.fetch(data, NetworkClient::getBytes) }
                .build(),
        )
    }

    @Composable
    override fun Content() {
        OfflineAware {
            Navigation(viewModel)
        }
    }
}
