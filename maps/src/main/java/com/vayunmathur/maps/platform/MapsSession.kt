package com.vayunmathur.maps.platform

import android.Manifest
import android.content.Intent
import com.vayunmathur.library.log.Log
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.NavigationVoiceAssistantCapabilities
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.vayunmathur.maps.service.NavigationService
import com.vayunmathur.maps.platform.NavigationSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The Car App Library [Session] for the maps navigation app (P12).
 *
 * Owns the single [NavMapScreen] and bridges the existing process-global
 * [NavigationSessionManager] to the car host's [NavigationManager]:
 *  - reports navigation start/stop to the host (so the car shows we're the
 *    active nav app and can surface a "stop" affordance);
 *  - handles the host's "stop navigation" request by tearing down the existing
 *    foreground [NavigationService], which stops the session everywhere (phone
 *    + car share the same singleton).
 */
@androidx.car.app.annotations.ExperimentalCarApi
class MapsSession : Session() {

    private var navRunning = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @androidx.car.app.annotations.ExperimentalCarApi
    override fun onCreateScreen(intent: Intent): Screen {
        NavigationSessionManager.init(carContext)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })

        // Location is needed both to draw the puck and to route from the current
        // position. Best-effort request; the screen still renders without it.
        runCatching {
            carContext.requestPermissions(
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            ) { _, _ -> }
        }

        val navigationManager = carContext.getCarService(NavigationManager::class.java)
        navigationManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() {
                // Host asked us to stop (e.g. another nav app took over). Route
                // it through the existing service so all observers reset.
                stopExistingNavigation()
            }
        })

        // API 9 (experimental, AAOS only): declare voice-assistant capabilities
        // so the host can route voice actions to us. Guarded — old hosts and
        // non-automotive hosts throw/ignore.
        if (carContext.getCarAppApiLevel() >= CAR_API_VOICE_ASSISTANT) {
            runCatching {
                if (navigationManager.canSetVoiceAssistantCapabilities()) {
                    navigationManager.setVoiceAssistantCapabilities(
                        NavigationVoiceAssistantCapabilities.Builder()
                            .setVoiceAssistantConsentGranted(true)
                            .addSupportedAction(
                                NavigationVoiceAssistantCapabilities.ACTION_EXIT_NAVIGATION
                            )
                            .addSupportedAction(
                                NavigationVoiceAssistantCapabilities.ACTION_MUTE_AND_UNMUTE
                            )
                            .addSupportedAction(
                                NavigationVoiceAssistantCapabilities.ACTION_ROUTE_OVERVIEW
                            )
                            .addSupportedAction(
                                NavigationVoiceAssistantCapabilities.ACTION_SHOW_ALTERNATES
                            )
                            .addSupportedAction(
                                NavigationVoiceAssistantCapabilities.ACTION_SHOW_DIRECTIONS_LIST
                            )
                            .addSupportedAction(
                                NavigationVoiceAssistantCapabilities.ACTION_SHOW_TRAFFIC
                            )
                            .build()
                    )
                }
            }.onFailure { Log.status(TAG, "setVoiceAssistantCapabilities failed", it) }
        }

        // Mirror the existing session state into the host's NavigationManager.
        scope.launch {
            NavigationSessionManager.state.collect { state ->
                val active = state !is NavigationSessionManager.NavState.Idle &&
                    state !is NavigationSessionManager.NavState.Failed
                if (active && !navRunning) {
                    runCatching { navigationManager.navigationStarted() }
                    navRunning = true
                } else if (!active && navRunning) {
                    runCatching { navigationManager.navigationEnded() }
                    navRunning = false
                }
            }
        }

        return NavMapScreen(carContext)
    }

    private fun stopExistingNavigation() {
        runCatching {
            val intent = Intent(carContext, NavigationService::class.java)
                .apply { action = NavigationService.ACTION_STOP }
            carContext.startService(intent)
        }.onFailure { Log.status(TAG, "stopExistingNavigation failed", it) }
    }

    private companion object {
        const val TAG = "MapsSession"
        /** Car API level for voice-assistant capabilities (experimental, AAOS only). */
        const val CAR_API_VOICE_ASSISTANT = 9
    }
}
