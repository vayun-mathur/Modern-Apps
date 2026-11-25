package com.vayunmathur.findfamily.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material.MaterialTheme
import com.vayunmathur.findfamily.wear.data.WearFindFamilyStore
import com.vayunmathur.findfamily.wear.sync.WearLinkBridge

/**
 * Watch FindFamily entry: people home, per-person detail and the
 * all-people relative map, rendered only from the locally stored
 * snapshot so it works standalone (phone absent, cold start).
 */
class WearMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.vayunmathur.library.wearlink.WearLinkPermissions.requestIfNeeded(this)
        val store = WearFindFamilyStore.get(this)
        WearLinkBridge.get(this)
        WearLinkBridge.publishWatchState(this)
        setContent {
            MaterialTheme {
                var route by remember { mutableStateOf<WearRoute>(WearRoute.People) }
                when (val current = route) {
                    WearRoute.People -> WearPeopleScreen(
                        store = store,
                        onOpenPerson = { route = WearRoute.Person(it) },
                        onOpenMap = { route = WearRoute.AllMap },
                    )
                    is WearRoute.Person -> WearPersonScreen(
                        store = store,
                        personId = current.personId,
                        onBack = { route = WearRoute.People },
                    )
                    WearRoute.AllMap -> WearAllMapScreen(
                        store = store,
                        onOpenPerson = { route = WearRoute.Person(it) },
                        onBack = { route = WearRoute.People },
                    )
                }
            }
        }
    }
}
