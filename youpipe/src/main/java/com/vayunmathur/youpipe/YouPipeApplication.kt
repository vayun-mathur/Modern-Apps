package com.vayunmathur.youpipe

import android.app.Application
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.youpipe.data.RemoteImageFetchers
import com.vayunmathur.youpipe.network.MyDownloader
import com.vayunmathur.youpipe.network.sabr.LocalDomPoTokenProvider
import com.vayunmathur.youpipe.platform.youtubeLocalization
import org.schabi.newpipe.extractor.NewPipe

/** You Pipe Application: a application implementation for you pipe application. */
class YouPipeApplication : Application() {
    /** Shared constants and helpers for YouPipeApplication. */
    companion object {
        @JvmStatic
        lateinit var appContext: android.content.Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Log.init(BuildConfig.DEV_BUILD)
        // Reduced CA set: FIRST_PARTY = ISRG X1/X2 + GTS R1-R4 covers api.maproj.org + YouTube (GTS)
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        // Video thumbnails and channel avatars load over HTTP(S) through the
        // shared image pipeline; the fetch function is built here from
        // NetworkClient so no library-to-library edge is needed (#884).
        ImageLoader.setDefault(
            ImageLoader.Builder(this).remote(RemoteImageFetchers::network).build(),
        )
        appContext = applicationContext
        val language = DataStoreUtils.getInstance(this).getString("youtube_language").orEmpty()
        NewPipe.init(MyDownloader(), youtubeLocalization(language))

        val poTokenProvider = LocalDomPoTokenProvider.shared(applicationContext)
        NewPipe.setYoutubeSessionPoTokenProvider(poTokenProvider)

        Thread({
            try {
                poTokenProvider.prewarmSessionPoToken()
            } catch (_: Throwable) {
                // Prewarm is best-effort; token minting will retry lazily on first use.
            }
        }, "YoutubeSessionPoTokenPrewarmKick").start()
    }
}
