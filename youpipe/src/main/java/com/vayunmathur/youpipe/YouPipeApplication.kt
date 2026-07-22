package com.vayunmathur.youpipe

import android.app.Application
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.youpipe.util.MyDownloader
import com.vayunmathur.youpipe.util.sabr.LocalDomPoTokenProvider
import com.vayunmathur.youpipe.util.youtubeLocalization
import org.schabi.newpipe.extractor.NewPipe

class YouPipeApplication : Application() {
    companion object {
        @JvmStatic
        lateinit var appContext: android.content.Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        val language = DataStoreUtils.getInstance(this).getString("youtube_language").orEmpty()
        NewPipe.init(MyDownloader(), youtubeLocalization(language))

        val poTokenProvider = LocalDomPoTokenProvider.shared(applicationContext)
        NewPipe.setYoutubeSessionPoTokenProvider(poTokenProvider)

        Thread({
            try {
                poTokenProvider.prewarmSessionPoToken(
                    NewPipe.getPreferredLocalization(),
                    NewPipe.getPreferredContentCountry(),
                    false,
                )
            } catch (_: Throwable) {
                // Prewarm is best-effort; token minting will retry lazily on first use.
            }
        }, "YoutubeSessionPoTokenPrewarmKick").start()
    }
}
