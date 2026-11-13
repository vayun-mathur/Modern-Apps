package com.vayunmathur.weather

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.widgets.updateWidgetPreviews
import com.vayunmathur.weather.data.GeoDatabase
import com.vayunmathur.weather.data.WeatherRepository
import com.vayunmathur.weather.network.WxStatic
import com.vayunmathur.weather.platform.WeatherViewModel
import com.vayunmathur.weather.platform.WeatherViewModelFactory
import com.vayunmathur.weather.widget.glance.WeatherGlanceWidgetReceiver

class MainActivity : MaActivity() {
    private val viewModel: WeatherViewModel by viewModels {
        WeatherViewModelFactory(application, WeatherRepository.get(application))
    }

    override fun onCreateBeforeContent() {
        // FIRST_PARTY covers api.maproj.org (self-hosted weather bundle
        // fallback and tiles proxy) and static.maproj.org (static weather
        // tiles, R2 custom domain on the same Cloudflare roots); place
        // search is a committed on-device asset.
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        // Static weather tiles (static.maproj.org), cached under cacheDir.
        WxStatic.init(this)
        GeoDatabase.init(this)
        // Unpacking is a ~13 MB brotli decompression and disk write. Do it now,
        // in the background, rather than the first time the user opens search.
        GeoDatabase.warmUp(lifecycleScope)
        updateWidgetPreviews(WeatherGlanceWidgetReceiver::class)
    }

    @Composable
    override fun Content() {
        OfflineAware {
            Navigation(viewModel)
        }
    }
}
