package com.vayunmathur.library.ui

import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Attractions
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Church
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hardware
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.LocalPostOffice
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Museum
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Toys
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Work
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Slice 3/3 round 11: panorama sphere renderer look-direction math + camera state. */
@RunWith(RobolectricTestRunner::class)
class Slice3Round11Test {

    @get:org.junit.Rule
    val composeRule = androidx.compose.ui.test.junit4.createComposeRule()

    @Test fun bundledCustomIconsRenderWithDescriptions() {
        composeRule.setContent {
            androidx.compose.foundation.layout.Column {
                IconBodySystem()
                IconToolsLevel()
            }
        }
        composeRule.onNodeWithContentDescription("Body").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Level").assertIsDisplayed()
    }

    @Test fun poiVectorMappingIsExhaustiveForDocumentedTypes() {
        val f = androidx.compose.material.icons.Icons.Filled
        val expected: Map<Int, androidx.compose.ui.graphics.vector.ImageVector> = mapOf(
            0 to f.Restaurant, 1 to f.LocalCafe, 2 to f.Restaurant, 3 to f.LocalBar,
            4 to f.ShoppingBag, 5 to f.LocalGroceryStore, 6 to f.LocalGasStation,
            7 to f.LocalPharmacy, 8 to f.Hotel, 9 to f.AccountBalance,
            10 to f.LocalHospital, 11 to f.School, 12 to f.Park, 13 to f.FitnessCenter,
            14 to f.Church, 15 to f.Attractions, 16 to f.LocalParking, 17 to f.Theaters,
            18 to f.Theaters, 19 to f.LocalLibrary, 20 to f.LocalPostOffice,
            21 to f.LocalPolice, 22 to f.LocalFireDepartment, 23 to f.AccountBalance,
            24 to f.Checkroom, 25 to f.Devices, 26 to f.Hardware, 27 to f.ContentCut,
            28 to f.DirectionsCar, 29 to f.BakeryDining, 30 to f.MenuBook, 31 to f.Chair,
            32 to f.SportsSoccer, 33 to f.Storefront, 34 to f.MedicalServices,
            35 to f.MedicalServices, 36 to f.Pets, 37 to f.EvStation, 38 to f.Museum,
            39 to f.Work, 40 to f.Attractions, 41 to f.LocalFlorist, 42 to f.Diamond,
            43 to f.Visibility, 44 to f.LocalLaundryService, 45 to f.Pets, 46 to f.LocalBar,
            47 to f.Toys, 48 to f.CardGiftcard, 49 to f.Storefront, 50 to f.Train,
        )
        assertEquals(51, expected.size)
        for ((type, vector) in expected) {
            assertEquals(vector, MapsPoiVectors.of(type), "POI type $type")
        }
        // "Other" and every out-of-range type fall back to the place marker.
        for (type in listOf(51, 100, 255, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(f.Place, MapsPoiVectors.of(type), "fallback for $type")
        }
    }

    // ---- AppPermissionsGate rows / runtime labels ----

    private fun setGate(requirements: List<PermissionRequirement>) {
        composeRule.setContent {
            AppPermissionsGate(
                spec = AppPermissionsSpec(title = "T", requirements = requirements),
            ) {
                androidx.compose.material3.Text("GATED-CONTENT")
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertWallShows(name: String, purpose: String? = null) {
        composeRule.onNodeWithText("Grant Required Permissions").assertIsDisplayed()
        composeRule.onNodeWithText(name).assertIsDisplayed()
        if (purpose != null) composeRule.onNodeWithText(purpose).assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("GATED-CONTENT").fetchSemanticsNodes().size,
        )
    }

    @Test fun gateRuntimeCameraMicrophoneLabels() {
        setGate(
            listOf(
                PermissionRequirement.Runtime(
                    arrayOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
                ),
            ),
        )
        assertWallShows("Camera, Microphone", "Photos and video, Audio recording")
        composeRule.onNodeWithText("Grant").assertIsDisplayed()
    }

    @Test fun gateRuntimeGroupedLabelsJoinInOrder() {
        setGate(
            listOf(
                PermissionRequirement.Runtime(
                    arrayOf(
                        "android.permission.ACCESS_FINE_LOCATION",
                        "android.permission.ACCESS_BACKGROUND_LOCATION",
                        "android.permission.BLUETOOTH_SCAN",
                        "android.permission.NEARBY_WIFI_DEVICES",
                        "android.permission.ACCESS_LOCAL_NETWORK",
                        "android.permission.POST_NOTIFICATIONS",
                        "android.permission.READ_MEDIA_IMAGES",
                        "android.permission.ACCESS_MEDIA_LOCATION",
                    ),
                ),
            ),
        )
        assertWallShows(
            "Location, Background location, Bluetooth, Nearby Wi-Fi, Local network, " +
                "Notifications, Photos and videos",
            "Current position, Position while the app is closed, Nearby Bluetooth devices, " +
                "Nearby Wi-Fi devices, Devices on the local network, Alerts and updates, " +
                "Images, video, and their locations",
        )
    }

    @Test fun gateRuntimeContactsPhoneCalendarAudioStorageLabels() {
        setGate(
            listOf(
                PermissionRequirement.Runtime(
                    arrayOf(
                        "android.permission.READ_CONTACTS",
                        "android.permission.READ_PHONE_STATE",
                        "android.permission.WRITE_CALENDAR",
                        "android.permission.READ_MEDIA_AUDIO",
                        "android.permission.READ_EXTERNAL_STORAGE",
                    ),
                ),
            ),
        )
        assertWallShows(
            "Contacts, Phone, Calendar, Audio files, Files",
            "Contact names and numbers, Placing calls, Calendar events, " +
                "Music on this device, Files on this device",
        )
    }

    @Test fun gateRuntimeMediaLocationOnlyLabels() {
        setGate(
            listOf(
                PermissionRequirement.Runtime(
                    arrayOf("android.permission.ACCESS_MEDIA_LOCATION"),
                ),
            ),
        )
        assertWallShows("Photo locations", "Locations saved with photos")
    }

    @Test fun gateRuntimePhotosWithoutLocationLabels() {
        setGate(
            listOf(
                PermissionRequirement.Runtime(
                    arrayOf("android.permission.READ_MEDIA_VIDEO"),
                ),
            ),
        )
        assertWallShows("Photos and videos", "Images and video on this device")
    }

    @Test fun gateRuntimeUnknownPermissionFallsBackToOther() {
        setGate(
            listOf(
                PermissionRequirement.Runtime(arrayOf("com.example.permission.CUSTOM_THING")),
            ),
        )
        assertWallShows("Permissions", "App features")
    }

    @Test fun gateRuntimeKnownPlusUnknownAddsOtherLabel() {
        setGate(
            listOf(
                PermissionRequirement.Runtime(
                    arrayOf("android.permission.CAMERA", "com.example.permission.CUSTOM_THING"),
                ),
            ),
        )
        assertWallShows("Camera, Permissions", "Photos and video, App features")
    }

    @Test fun gateExactAlarmsRow() {
        setGate(listOf(PermissionRequirement.ExactAlarms))
        assertWallShows("Exact alarms", "Reminders at precise times")
    }

    // No gateAllFilesRow / gateManageMediaRow: their isGranted checks go through
    // Environment.isExternalStorageManager() / MediaStore.canManageMedia(), whose
    // Robolectric shadows are order-dependent (AIOOBE on empty external dirs /
    // granted-by-default), so the wall may never render in a full-suite JVM.
    // Their GateRow rendering path is covered by the other special-access rows.

    @Test fun gateNotificationListenerRow() {
        setGate(listOf(PermissionRequirement.NotificationListener))
        assertWallShows("Notification access", "Notifications from other apps")
    }

    @Test fun gateFullScreenIntentRow() {
        setGate(listOf(PermissionRequirement.FullScreenIntent))
        assertWallShows("Full-screen alerts", "Alarms over the lock screen")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun gateHealthConnectRowWhenUnprobed() {
        val previous = HealthConnectGrantCache.granted
        try {
            HealthConnectGrantCache.granted = null
            setGate(
                listOf(
                    PermissionRequirement.HealthConnect(setOf("hc.permission.READ_STEPS")),
                ),
            )
            // The gate first probes Health Connect asynchronously and shows a
            // loading state until that completes; wait for the row itself
            // rather than assuming waitForIdle outlasts the probe.
            composeRule.waitUntilExactlyOneExists(
                androidx.compose.ui.test.hasText("Health data"),
                timeoutMillis = 10_000,
            )
            assertWallShows("Health data", "Read and write health records")
        } finally {
            HealthConnectGrantCache.granted = previous
        }
    }

    // ---- rememberPermissionRequest granted short-circuit ----

    @Test fun permissionRequestReportsGrantedWithoutLaunching() {
        org.robolectric.Shadows.shadowOf(
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<android.app.Application>(),
        ).grantPermissions("android.permission.CAMERA")

        val results = mutableListOf<Boolean>()
        var request: (() -> Unit)? = null
        composeRule.setContent {
            request = rememberPermissionRequest("android.permission.CAMERA") { results += it }
            androidx.compose.material3.Text("perm-host")
        }
        composeRule.runOnIdle { request!!.invoke() }
        assertEquals(listOf(true), results)
    }

    @Test fun multiplePermissionRequestGrantsOnlyWhenAllGranted() {
        val shadow = org.robolectric.Shadows.shadowOf(
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<android.app.Application>(),
        )
        shadow.grantPermissions("android.permission.CAMERA", "android.permission.RECORD_AUDIO")

        val results = mutableListOf<Boolean>()
        var request: (() -> Unit)? = null
        composeRule.setContent {
            request = rememberMultiplePermissionRequest(
                arrayOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
            ) { results += it }
            androidx.compose.material3.Text("perm-host-2")
        }
        composeRule.runOnIdle { request!!.invoke() }
        assertEquals(listOf(true), results)
    }

    @Test fun multiplePermissionRequestEmptyBatchIsGranted() {
        val results = mutableListOf<Boolean>()
        var request: (() -> Unit)? = null
        composeRule.setContent {
            request = rememberMultiplePermissionRequest(emptyArray()) { results += it }
            androidx.compose.material3.Text("perm-host-3")
        }
        composeRule.runOnIdle { request!!.invoke() }
        assertEquals(listOf(true), results)
    }

    private fun renderer(crop: PanoramaCrop, initialYaw: Float? = null) =
        SphereRenderer(crop, initialYaw) { null }

    @Test fun fullSphereCentresYawAndPitch() {
        val r = renderer(PanoramaCrop.full(3600, 1800))
        // Centre of the full image is u=v=0.5: yaw=-pi (mesh negates u), pitch=0.
        assertEquals((-PI).toFloat(), r.yaw, 1e-5f)
        assertEquals(0f, r.pitch, 1e-5f)
        assertEquals(PanoramaCameraState.DEFAULT_FOV_DEG, r.fov)
    }

    @Test fun partialCropCentresOnCoveredBand() {
        // Left half, top half of a full sphere: centre u=0.25, v=0.25.
        val crop = PanoramaCrop(4000, 2000, 2000, 1000, croppedLeft = 0, croppedTop = 0)
        val r = renderer(crop)
        assertEquals((-PI / 2).toFloat(), r.yaw, 1e-5f)
        assertEquals((PI / 4).toFloat(), r.pitch, 1e-5f)
    }

    @Test fun bottomBandLooksDown() {
        val crop = PanoramaCrop(4000, 2000, 4000, 1000, croppedLeft = 0, croppedTop = 1000)
        val r = renderer(crop)
        assertEquals((-PI / 4).toFloat(), r.pitch, 1e-5f)
    }

    @Test fun initialYawOverridesCropCentreButPitchStillFromCrop() {
        val crop = PanoramaCrop(4000, 2000, 4000, 1000, croppedLeft = 0, croppedTop = 1000)
        val r = renderer(crop, initialYaw = 1.25f)
        assertEquals(1.25f, r.yaw)
        assertEquals((-PI / 4).toFloat(), r.pitch, 1e-5f)
    }

    @Test fun degenerateCropSizesDoNotCrashAndClampPitch() {
        val r = renderer(PanoramaCrop(0, 0, 0, 0, 0, 0))
        // Sizes coerced to >=1: centre u=v=0, pitch=pi/2 clamped to just under pi/2.
        assertEquals(0f, r.yaw, 1e-5f)
        assertTrue(r.pitch in 1.49f..1.51f, "pitch clamped near 1.5, was ${r.pitch}")
    }

    @Test fun addYawAccumulatesAndAddPitchClamps() {
        val r = renderer(PanoramaCrop.full(100, 100))
        val startYaw = r.yaw
        r.addYaw(0.5f)
        r.addYaw(-0.2f)
        assertEquals(startYaw + 0.3f, r.yaw, 1e-6f)

        r.addPitch(10f)
        assertTrue(r.pitch <= 1.5f && r.pitch > 1.49f, "pitch clamped high, was ${r.pitch}")
        r.addPitch(-20f)
        assertTrue(r.pitch >= -1.5f && r.pitch < -1.49f, "pitch clamped low, was ${r.pitch}")
        r.addPitch(0.25f)
        assertEquals(-1.25f, r.pitch, 1e-6f)
    }

    // ---- PermissionRequirement value / grant logic ----

    @Test fun runtimeRequirementEqualityIsByPermissionContent() {
        val a = PermissionRequirement.Runtime(arrayOf("perm.A", "perm.B"))
        val b = PermissionRequirement.Runtime(arrayOf("perm.A", "perm.B"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != PermissionRequirement.Runtime(arrayOf("perm.A")))
        assertTrue(a != PermissionRequirement.Runtime(arrayOf("perm.B", "perm.A")))
    }

    @Test fun notificationsRequirementMatchesPlatform() {
        val req = PermissionRequirement.notifications()
        // Robolectric default SDK is 34 (>= Tiramisu): POST_NOTIFICATIONS is required.
        assertEquals(1, req.permissions.size)
        assertEquals(android.Manifest.permission.POST_NOTIFICATIONS, req.permissions[0])
    }

    @Test fun emptyRuntimeAndHealthConnectGrantLogic() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        assertTrue(PermissionRequirement.Runtime(emptyArray()).isGranted(context))
        assertTrue(
            !PermissionRequirement.Runtime(arrayOf("perm.NOT_GRANTED_XYZ")).isGranted(context)
        )

        val health = PermissionRequirement.HealthConnect(setOf("hc.READ", "hc.WRITE"))
        val previous = HealthConnectGrantCache.granted
        try {
            HealthConnectGrantCache.granted = null
            assertTrue(!health.isGranted(context), "unprobed HealthConnect must not be granted")
            HealthConnectGrantCache.granted = setOf("hc.READ")
            assertTrue(!health.isGranted(context), "partial grant must not satisfy")
            HealthConnectGrantCache.granted = setOf("hc.READ", "hc.WRITE", "hc.EXTRA")
            assertTrue(health.isGranted(context), "superset grant must satisfy")
            assertTrue(
                PermissionRequirement.HealthConnect(emptySet()).isGranted(context),
                "empty HealthConnect requirement is trivially satisfied once probed",
            )
        } finally {
            HealthConnectGrantCache.granted = previous
        }
    }

    @Test fun permissionsSpecDefaults() {
        val spec = AppPermissionsSpec(title = "T", requirements = emptyList())
        assertEquals("T", spec.title)
        assertEquals(null, spec.subtitle)
        assertEquals(null, spec.icon)
    }

    // ---- SpecialAccess request intents ----

    @Test fun specialAccessRequestsLaunchRightSettingsPages() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val shadow = org.robolectric.Shadows.shadowOf(
            context as android.app.Application,
        )

        SpecialAccess.requestAllFilesAccess(context)
        var intent = shadow.nextStartedActivity
        assertEquals(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            intent.action,
        )
        assertEquals("package:${context.packageName}", intent.data?.toString())
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        SpecialAccess.requestExactAlarms(context)
        intent = shadow.nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.action)
        assertEquals("package:${context.packageName}", intent.data?.toString())

        SpecialAccess.requestNotificationListener(context)
        intent = shadow.nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, intent.action)

        SpecialAccess.openVpnSettings(context)
        intent = shadow.nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_VPN_SETTINGS, intent.action)

        SpecialAccess.requestManageMedia(context)
        intent = shadow.nextStartedActivity
        assertEquals("android.settings.REQUEST_MANAGE_MEDIA", intent.action)

        SpecialAccess.requestFullScreenIntent(context)
        intent = shadow.nextStartedActivity
        assertEquals(
            android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            intent.action,
        )
        assertEquals("package:${context.packageName}", intent.data?.toString())
    }

    @Test fun openAppSettingsLaunchesDetailsPageForOwnPackage() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val shadow = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        openAppSettings(context)
        val intent = shadow.nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.data?.toString())
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun specialAccessChecksReturnWithoutCrashing() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        // Robolectric shadows provide deterministic defaults: no listener granted
        // (package not in the (empty) secure setting), VPN prepare has no consent.
        assertTrue(!SpecialAccess.hasNotificationListener(context))
        SpecialAccess.hasExactAlarms(context)
        SpecialAccess.hasManageMedia(context)
        SpecialAccess.hasFullScreenIntent(context)
        // hasAllFilesAccess() omitted: Environment.isExternalStorageManager()
        // throws AIOOBE under Robolectric when external dirs are unconfigured.
    }

    // ---- MaFragmentActivity shell ----

    class FragProbeActivity : MaFragmentActivity() {
        var beforeContentCalls = 0

        @androidx.compose.runtime.Composable
        override fun Content() {
            androidx.compose.material3.Text("frag-probe-content")
        }

        override fun onCreateBeforeContent() {
            beforeContentCalls++
        }
    }

    class FragDeferredActivity : MaFragmentActivity() {
        override val autoSetContent: Boolean = false
        var beforeContentCalls = 0

        @androidx.compose.runtime.Composable
        override fun Content() {
            androidx.compose.material3.Text("frag-deferred-content")
        }

        override fun onCreateBeforeContent() {
            beforeContentCalls++
        }

        fun showNow() = showContent()
    }

    @Test fun fragmentActivityAutoContentRunsHookAndResumes() {
        val controller = org.robolectric.Robolectric.buildActivity(FragProbeActivity::class.java)
        val activity = controller.create().get()
        assertEquals(1, activity.beforeContentCalls)
        controller.start().resume()
        controller.visible()
        assertEquals(
            androidx.lifecycle.Lifecycle.State.RESUMED,
            activity.lifecycle.currentState,
        )
        controller.pause().stop().destroy()
    }

    @Test fun fragmentActivityDeferredContentShowsOnDemand() {
        val controller =
            org.robolectric.Robolectric.buildActivity(FragDeferredActivity::class.java)
        val activity = controller.create().get()
        assertEquals(1, activity.beforeContentCalls)
        // Deferred: creating must not crash without content, and an explicit
        // showContent() (the biometric-unlock path) composes it afterwards.
        activity.showNow()
        controller.start().resume()
        controller.visible()
        assertEquals(
            androidx.lifecycle.Lifecycle.State.RESUMED,
            activity.lifecycle.currentState,
        )
        controller.pause().stop().destroy()
    }

    @Test fun cameraStateDefaultsAndPublish() {
        val state = PanoramaCameraState()
        assertEquals(0f, state.yaw)
        assertEquals(0f, state.pitch)
        assertEquals(75f, state.fovDeg)
        assertEquals(75f, PanoramaCameraState.DEFAULT_FOV_DEG)

        state.publish(yaw = 0.75f, pitch = -0.5f, fovDeg = 42f)
        assertEquals(0.75f, state.yaw)
        assertEquals(-0.5f, state.pitch)
        assertEquals(42f, state.fovDeg)

        state.publish(yaw = -1f, pitch = 1f, fovDeg = 90f)
        assertEquals(-1f, state.yaw)
        assertEquals(90f, state.fovDeg)
    }
}
