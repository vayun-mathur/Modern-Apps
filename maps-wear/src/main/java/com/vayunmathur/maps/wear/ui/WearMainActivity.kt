package com.vayunmathur.maps.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material.MaterialTheme
import com.vayunmathur.maps.wear.data.WearMapsStore
import com.vayunmathur.maps.wear.sync.WearLinkBridge

/**
 * Watch maps entry: the rendered map home, route detail and saved glance,
 * all from the locally stored snapshot so they work standalone (phone
 * absent, cold start) with stale/offline honesty.
 */
class WearMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.vayunmathur.library.wearlink.WearLinkPermissions.requestIfNeeded(this)
        val store = WearMapsStore.get(this)
        WearLinkBridge.get(this)
        WearLinkBridge.publishWatchState(this)
        setContent {
            MaterialTheme {
                var route by remember { mutableStateOf<WearRoute>(WearRoute.Map) }
                when (route) {
                    WearRoute.Map -> WearMapScreen(
                        store = store,
                        onOpenRoute = { route = WearRoute.RouteDetail },
                        onOpenSaved = { route = WearRoute.Saved },
                    )
                    WearRoute.RouteDetail -> WearRouteDetailScreen(
                        store = store,
                        onBack = { route = WearRoute.Map },
                    )
                    WearRoute.Saved -> WearSavedScreen(
                        store = store,
                        onBack = { route = WearRoute.Map },
                    )
                }
            }
        }
    }
}
