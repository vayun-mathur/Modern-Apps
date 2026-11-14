package com.vayunmathur.vpn

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.vpn.data.ConnectionLogDao
import com.vayunmathur.vpn.data.VpnConfigDao
import com.vayunmathur.vpn.data.VpnDatabase
import com.vayunmathur.vpn.platform.VpnViewModel
import com.vayunmathur.vpn.platform.VpnViewModelFactory
import com.vayunmathur.vpn.util.VpnNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main Activity: a ma activity implementation for main activity. */
class MainActivity : MaActivity() {
    private lateinit var configDao: VpnConfigDao
    private lateinit var logDao: ConnectionLogDao
    private val vm: VpnViewModel by viewModels {
        VpnViewModelFactory(application, configDao, logDao)
    }
    private var ready by mutableStateOf(false)

    override fun onCreateBeforeContent() {
        // SYSTEM: user-supplied VPN endpoint dynamic host, cannot pin
        NetworkClient.init(this, TrustBundle.SYSTEM)

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { VpnNative.init() }
            val db = VpnDatabase.get(this@MainActivity)
            configDao = db.vpnConfigDao()
            logDao = db.connectionLogDao()
            withContext(Dispatchers.Main) {
                ready = true
                handleIntent(intent)
            }
        }
    }

    @Composable
    override fun Content() {
        OfflineAware {
            if (ready) Navigation(vm)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Accept VIEW intents for .conf files from Files / Downloads etc.
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            vm.importFromUri(this, uri)
        }
    }
}
