package com.vayunmathur.library.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.library.ui.R as LibraryUiR

/**
 * The single consolidated initial-permission gate.
 *
 * Takes the app's navigation [content] as a parameter, performs all permission
 * checks itself, and shows either one permission screen or [content]. All
 * requirements are blocking: until every [PermissionRequirement] in
 * [spec.requirements] is granted, [content] is not composed.
 *
 * The wall is a titled list: one [Card] per missing requirement, each a named
 * [ListItem] (headline names the permission, supporting text states its
 * purpose) with a Grant button. The top bar always reads "Grant Required
 * Permissions"; [spec]'s title, subtitle, and icon are ignored.
 *
 * Behaviour:
 * - Synchronous requirements (runtime permissions, [SpecialAccess] entries,
 *   manage-media) are checked on launch and on every ON_RESUME (covers grants
 *   made in system Settings, which produce no result callback).
 * - [PermissionRequirement.HealthConnect] is probed async via
 *   `getGrantedPermissions`; until the first probe completes a loading state
 *   is shown rather than flashing the wall.
 * - Every request fires only from its row's Grant button tap. Special-access
 *   intents and the Health Connect contract require an OS gesture anyway, and
 *   runtime batches deliberately do not auto-launch — no system dialog appears
 *   until the user taps.
 * - Permanent denial recovers via the shared
 *   [rememberMultiplePermissionRequest] path, which opens app settings when a
 *   re-request would silently do nothing.
 */
@Composable
fun AppPermissionsGate(
    spec: AppPermissionsSpec,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var resumeEpoch by remember { mutableIntStateOf(0) }
    var runtimeEpoch by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeEpoch++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val healthRequirements = remember(spec) {
        spec.requirements.filterIsInstance<PermissionRequirement.HealthConnect>()
    }
    var healthProbed by remember(spec) { mutableStateOf(healthRequirements.isEmpty()) }
    LaunchedEffect(spec, resumeEpoch) {
        if (healthRequirements.isNotEmpty()) {
            healthProbed = try {
                val client = HealthConnectClient.getOrCreate(context)
                HealthConnectGrantCache.granted =
                    client.permissionController.getGrantedPermissions()
                true
            } catch (_: Exception) {
                // Health Connect missing/broken: keep the last known grants (usually
                // null, i.e. not granted) so the wall explains the state instead of
                // crashing or silently passing.
                true
            }
        }
    }

    // Snapshot so a grant flapping mid-frame cannot show content and wall together.
    val missing = remember(spec, resumeEpoch, runtimeEpoch, healthProbed) {
        if (!healthProbed) emptyList() else spec.requirements.filterNot { it.isGranted(context) }
    }
    if (!healthProbed) {
        AppScaffold(
            title = stringResource(LibraryUiR.string.permission_gate_title),
            modifier = modifier,
        ) { pad ->
            Box(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator()
            }
        }
        return
    }
    if (missing.isEmpty()) {
        content()
        return
    }

    GateBody(missing = missing, onRuntimeResult = { runtimeEpoch++ })
}

@Composable
private fun GateBody(
    missing: List<PermissionRequirement>,
    onRuntimeResult: () -> Unit,
) {
    LazyListScaffold(
        title = stringResource(LibraryUiR.string.permission_gate_title),
        horizontalPadding = Spacing.lg,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        items(missing.size) { index ->
            RequirementRow(
                requirement = missing[index],
                onRuntimeResult = onRuntimeResult,
            )
        }
    }
}

@Composable
private fun RequirementRow(
    requirement: PermissionRequirement,
    onRuntimeResult: () -> Unit,
) {
    val context = LocalContext.current
    when (requirement) {
        is PermissionRequirement.Runtime -> {
            // Empty batch (e.g. notifications() pre-Tiramisu) always reports
            // granted, so it never reaches here as missing; no empty guard needed.
            val request = rememberMultiplePermissionRequest(requirement.permissions) {
                onRuntimeResult()
            }
            val (name, purpose) = runtimeLabels(requirement.permissions)
            GateRow(name = name, purpose = purpose, onRequest = request)
        }
        PermissionRequirement.ExactAlarms -> {
            GateRow(
                nameRes = LibraryUiR.string.permission_name_exact_alarms,
                purposeRes = LibraryUiR.string.permission_purpose_exact_alarms,
                onRequest = { SpecialAccess.requestExactAlarms(context) },
            )
        }
        PermissionRequirement.AllFiles -> {
            GateRow(
                nameRes = LibraryUiR.string.permission_name_all_files,
                purposeRes = LibraryUiR.string.permission_purpose_all_files,
                onRequest = { SpecialAccess.requestAllFilesAccess(context) },
            )
        }
        PermissionRequirement.NotificationListener -> {
            GateRow(
                nameRes = LibraryUiR.string.permission_name_notification_listener,
                purposeRes = LibraryUiR.string.permission_purpose_notification_listener,
                onRequest = { SpecialAccess.requestNotificationListener(context) },
            )
        }
        PermissionRequirement.Vpn -> {
            GateRow(
                nameRes = LibraryUiR.string.permission_name_vpn,
                purposeRes = LibraryUiR.string.permission_purpose_vpn,
                onRequest = { SpecialAccess.requestVpnConsent(context) },
            )
        }
        PermissionRequirement.FullScreenIntent -> {
            GateRow(
                nameRes = LibraryUiR.string.permission_name_full_screen_intent,
                purposeRes = LibraryUiR.string.permission_purpose_full_screen_intent,
                onRequest = { SpecialAccess.requestFullScreenIntent(context) },
            )
        }
        PermissionRequirement.ManageMedia -> {
            GateRow(
                nameRes = LibraryUiR.string.permission_name_manage_media,
                purposeRes = LibraryUiR.string.permission_purpose_manage_media,
                onRequest = { SpecialAccess.requestManageMedia(context) },
            )
        }
        is PermissionRequirement.HealthConnect -> {
            val launcher = rememberLauncherForActivityResult(
                contract = PermissionController.createRequestPermissionResultContract(),
            ) { granted ->
                if (granted.containsAll(requirement.permissions)) {
                    HealthConnectGrantCache.granted = granted
                }
                // Refusals surface on the next resume probe; nothing cached here.
            }
            GateRow(
                nameRes = LibraryUiR.string.permission_name_health_connect,
                purposeRes = LibraryUiR.string.permission_purpose_health_connect,
                onRequest = { launcher.launch(requirement.permissions) },
            )
        }
    }
}

@Composable
private fun GateRow(nameRes: Int, purposeRes: Int, onRequest: () -> Unit) {
    GateRow(
        name = stringResource(nameRes),
        purpose = stringResource(purposeRes),
        onRequest = onRequest,
    )
}

@Composable
private fun GateRow(name: String, purpose: String, onRequest: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(name) },
            supportingContent = { Text(purpose) },
            trailingContent = {
                Button(onClick = onRequest) {
                    Text(stringResource(LibraryUiR.string.permission_grant))
                }
            },
        )
    }
}

/**
 * Names a runtime batch by grouping its permissions, so multi-permission rows
 * (e.g. camera + mic, location + notifications, share's BLE/Wi-Fi bundle)
 * read as one joined label rather than a nameless card.
 */
@Composable
private fun runtimeLabels(permissions: Array<String>): Pair<String, String> {
    val set = permissions.toSet()
    val names = mutableListOf<String>()
    val purposes = mutableListOf<String>()
    @Composable
    fun add(nameRes: Int, purposeRes: Int) {
        names.add(stringResource(nameRes))
        purposes.add(stringResource(purposeRes))
    }
    if (set.contains(Manifest.permission.ACCESS_FINE_LOCATION) ||
        set.contains(Manifest.permission.ACCESS_COARSE_LOCATION)
    ) {
        add(
            LibraryUiR.string.permission_name_location,
            LibraryUiR.string.permission_purpose_location,
        )
    }
    if (set.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
        add(
            LibraryUiR.string.permission_name_background_location,
            LibraryUiR.string.permission_purpose_background_location,
        )
    }
    if (set.intersect(
            setOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        ).isNotEmpty()
    ) {
        add(
            LibraryUiR.string.permission_name_bluetooth,
            LibraryUiR.string.permission_purpose_bluetooth,
        )
    }
    if (set.contains(Manifest.permission.NEARBY_WIFI_DEVICES)) {
        add(
            LibraryUiR.string.permission_name_wifi,
            LibraryUiR.string.permission_purpose_wifi,
        )
    }
    if (set.contains(Manifest.permission.ACCESS_LOCAL_NETWORK)) {
        add(
            LibraryUiR.string.permission_name_local_network,
            LibraryUiR.string.permission_purpose_local_network,
        )
    }
    if (set.contains(Manifest.permission.CAMERA)) {
        add(
            LibraryUiR.string.permission_name_camera,
            LibraryUiR.string.permission_purpose_camera,
        )
    }
    if (set.contains(Manifest.permission.RECORD_AUDIO)) {
        add(
            LibraryUiR.string.permission_name_microphone,
            LibraryUiR.string.permission_purpose_microphone,
        )
    }
    if (set.contains(Manifest.permission.READ_CONTACTS) ||
        set.contains(Manifest.permission.WRITE_CONTACTS)
    ) {
        add(
            LibraryUiR.string.permission_name_contacts,
            LibraryUiR.string.permission_purpose_contacts,
        )
    }
    if (set.contains(Manifest.permission.CALL_PHONE) ||
        set.contains(Manifest.permission.READ_PHONE_STATE) ||
        set.contains(Manifest.permission.READ_PHONE_NUMBERS)
    ) {
        add(
            LibraryUiR.string.permission_name_phone,
            LibraryUiR.string.permission_purpose_phone,
        )
    }
    if (set.contains(Manifest.permission.READ_CALENDAR) ||
        set.contains(Manifest.permission.WRITE_CALENDAR)
    ) {
        add(
            LibraryUiR.string.permission_name_calendar,
            LibraryUiR.string.permission_purpose_calendar,
        )
    }
    if (set.contains(Manifest.permission.POST_NOTIFICATIONS)) {
        add(
            LibraryUiR.string.permission_name_notifications,
            LibraryUiR.string.permission_purpose_notifications,
        )
    }
    if (set.contains(Manifest.permission.READ_MEDIA_AUDIO)) {
        add(
            LibraryUiR.string.permission_name_audio,
            LibraryUiR.string.permission_purpose_audio,
        )
    }
    // Literal strings for API 33/34 permissions: the Manifest constants are inlined
    // string values, and referencing the fields trips InlinedApi on minSdk 31 even
    // though comparing permission-name strings is safe on every API level.
    val hasImagesOrVideo = set.contains("android.permission.READ_MEDIA_IMAGES") ||
        set.contains("android.permission.READ_MEDIA_VIDEO") ||
        set.contains("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
    val hasMediaLocation = set.contains(Manifest.permission.ACCESS_MEDIA_LOCATION)
    if (hasImagesOrVideo) {
        names.add(stringResource(LibraryUiR.string.permission_name_photos))
        purposes.add(
            stringResource(
                if (hasMediaLocation) {
                    LibraryUiR.string.permission_purpose_photos_with_location
                } else {
                    LibraryUiR.string.permission_purpose_photos
                }
            )
        )
    } else if (hasMediaLocation) {
        add(
            LibraryUiR.string.permission_name_photo_locations,
            LibraryUiR.string.permission_purpose_photo_locations,
        )
    }
    if (set.contains(Manifest.permission.READ_EXTERNAL_STORAGE) ||
        set.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    ) {
        add(
            LibraryUiR.string.permission_name_storage,
            LibraryUiR.string.permission_purpose_storage,
        )
    }
    val known = setOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        "android.permission.NEARBY_WIFI_DEVICES",
        "android.permission.ACCESS_LOCAL_NETWORK",
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        Manifest.permission.ACCESS_MEDIA_LOCATION,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )
    if (names.isEmpty() || (set - known).isNotEmpty()) {
        add(
            LibraryUiR.string.permission_name_other,
            LibraryUiR.string.permission_purpose_other,
        )
    }
    return names.joinToString(", ") to purposes.joinToString(", ")
}
