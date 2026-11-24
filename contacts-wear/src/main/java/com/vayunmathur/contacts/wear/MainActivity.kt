package com.vayunmathur.contacts.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.contacts.wear.data.WearContactsStore
import com.vayunmathur.contacts.wear.platform.WearDeviceCapabilities
import com.vayunmathur.contacts.wear.platform.WearDeviceCapabilitiesProbe
import com.vayunmathur.contacts.wear.sync.WearWatchStatePublisher
import com.vayunmathur.contacts.wear.ui.WearContactsApp
import kotlinx.coroutines.launch

/**
 * Wear Contacts entry: renders the locally synced copy (offline-capable) and,
 * on an empty first launch, asks the phone to republish its snapshot.
 */
class MainActivity : ComponentActivity() {

    private lateinit var store: WearContactsStore
    private var capabilities by mutableStateOf(WearDeviceCapabilities())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WearContactsStore(applicationContext)
        capabilities = WearDeviceCapabilitiesProbe.probeHandlers(this)
        setContent {
            val snapshot by store.snapshot.collectAsState()
            WearContactsApp(snapshot, capabilities)
        }
        lifecycleScope.launch {
            capabilities = WearDeviceCapabilitiesProbe.probe(this@MainActivity)
            if (store.snapshot.value.contacts.isEmpty()) {
                WearWatchStatePublisher.requestSnapshot(applicationContext)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) store.reload()
        capabilities = WearDeviceCapabilitiesProbe.probeHandlers(this)
        lifecycleScope.launch {
            capabilities = WearDeviceCapabilitiesProbe.probe(this@MainActivity)
        }
    }
}
