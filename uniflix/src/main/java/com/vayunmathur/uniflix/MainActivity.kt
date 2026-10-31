package com.vayunmathur.uniflix

import androidx.compose.runtime.Composable
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.uniflix.data.RemoteImageFetchers

class MainActivity : MaActivity() {
    override fun onCreateBeforeContent() {
        // Catalog + artwork go through the self-hosted proxy; player streams
        // user-supplied addon URLs, so trust stays at STANDARD, never SYSTEM.
        NetworkClient.init(this, TrustBundle.STANDARD)
        // Catalog artwork loads over HTTP(S) through the shared image pipeline;
        // the fetch function is built here from NetworkClient so no
        // library-to-library edge is needed (#884).
        ImageLoader.setDefault(
            ImageLoader.Builder(this).remote(RemoteImageFetchers::network).build(),
        )
    }

    @Composable
    override fun Content() {
        Navigation()
    }
}
