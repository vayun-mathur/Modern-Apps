package com.vayunmathur.networklocation.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.vayunmathur.networklocation.provider.LocationProviderImpl

/**
 * The bound service the framework's LocationManager connects to for network
 * location. Declared in the manifest with the
 * `com.android.location.service.v3.NetworkLocationProvider` action so the platform
 * discovers it; becomes THE network provider once MAOS points
 * `config_networkLocationProviderPackageName` at this package.
 */
class NetworkLocationService : Service() {
    private var provider: LocationProviderImpl? = null

    override fun onCreate() {
        super.onCreate()
        provider = LocationProviderImpl(this)
    }

    override fun onBind(intent: Intent?): IBinder? = provider?.binder

    override fun onDestroy() {
        provider?.shutdown()
        provider = null
        super.onDestroy()
    }
}
