package com.vayunmathur.health

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.health.data.FoodDatabase
import com.vayunmathur.health.data.HealthAPI
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.PERMISSIONS
import com.vayunmathur.health.data.ReferenceCatalog
import com.vayunmathur.health.platform.HealthConnectAvailability
import com.vayunmathur.health.platform.HealthConnectSetup
import com.vayunmathur.health.platform.HealthViewModel
import com.vayunmathur.health.platform.HealthViewModelFactory
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.platform.MedicalViewModelFactory
import com.vayunmathur.health.platform.PersonalHealthRecords
import com.vayunmathur.health.sync.HealthSyncWorker
import com.vayunmathur.health.ui.HealthConnectRequiredPage
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.PermissionRequirement

/** Main Activity: a ma activity implementation for main activity. */
class MainActivity : MaActivity() {
    private val healthViewModel: HealthViewModel by viewModels {
        HealthViewModelFactory(application, HealthRepository.get(this))
    }
    private val medicalViewModel: MedicalViewModel by viewModels {
        MedicalViewModelFactory(application, HealthRepository.get(this))
    }
    private lateinit var healthConnectClient: HealthConnectClient

    /**
     * Creates the Health Connect client when the provider is usable, wiring it into HealthAPI
     * and PersonalHealthRecords.
     *
     * Idempotent: called from `onCreateBeforeContent` and again on every resume, because the
     * user can install the provider while this activity is alive. When the provider is missing
     * or too old this leaves HealthAPI in local-only mode and PersonalHealthRecords
     * uninitialised (its `isAvailable` reports false with no client).
     */
    private fun ensureHealthConnect() {
        if (HealthConnectSetup.current(this) != HealthConnectAvailability.AVAILABLE) {
            HealthAPI.initLocal(this, HealthRepository.get(this))
            return
        }
        if (!::healthConnectClient.isInitialized) {
            healthConnectClient = HealthConnectClient.getOrCreate(this)
        }
        val repository = HealthRepository.get(this)
        HealthAPI.init(healthConnectClient, this, repository)
        PersonalHealthRecords.init(this, healthConnectClient)
    }

    override fun onCreateBeforeContent() {
        // Health Connect is a separate APK on API 33 and below and getOrCreate throws when it is
        // missing or too old, which crashed launch outright. Its setup is gated on the SDK status;
        // Room, the food database and the reference catalog work without it, and the composable
        // below shows the install screen instead of the app until it is usable.
        ensureHealthConnect()
        FoodDatabase.init(this)
        ReferenceCatalog.init(this)
        // Unpacking is half a second of decompression and disk write. Do it now, in the background,
        // rather than the first time the user opens a picker and waits for it.
        ReferenceCatalog.warmUp(lifecycleScope)
    }

    @Composable
    override fun Content() {
        // Re-checked on every resume: returning from the Play Store install clears this screen
        // without a relaunch. The status call is a cached PackageManager lookup, not IPC.
        var availability by remember {
            mutableStateOf(HealthConnectSetup.current(this@MainActivity))
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    // The user may be returning from the Play Store install. Set up the client
                    // first so the gate and the screens below never see an uninitialised one.
                    ensureHealthConnect()
                    availability = HealthConnectSetup.current(this@MainActivity)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        if (availability != HealthConnectAvailability.AVAILABLE) {
            // Health Connect missing or too old: show the installer screen instead of the app.
            // Composing the permission gate here would launch its own getOrCreate probe and,
            // worse, a Grant button whose contract has no provider to answer it.
            HealthConnectRequiredPage(availability) {
                HealthConnectSetup.openInstaller(this@MainActivity)
            }
            return
        }

        AppPermissionsGate(
            spec = AppPermissionsSpec(
                title = stringResource(R.string.grant_permissions),
                subtitle = stringResource(R.string.grant_permissions_rationale),
                icon = { IconFavorite() },
                requirements = listOf(
                    // Dose reminders post notifications; without this grant they would silently
                    // never appear. No-op pre-Tiramisu, where notifications are not runtime-gated.
                    PermissionRequirement.notifications(),
                    PermissionRequirement.HealthConnect(PERMISSIONS),
                )
            )
        ) {
            LaunchedEffect(Unit) {
                HealthSyncWorker.enqueue(this@MainActivity)
            }
            // FHIR (PHR) record access is requested separately from the gate above and is
            // allowed to fail: it only exists on Android 15 with an updated Health Connect
            // module, so folding it into PERMISSIONS would gate the whole app on something
            // most supported devices cannot grant. It is requested only from the explicit
            // tap on the Medical tab's permission card (see MedicalPage), never auto-fired
            // here — entering the app must not pop the system dialog with no rationale.
            Navigation(healthViewModel, medicalViewModel)
        }
    }
}
