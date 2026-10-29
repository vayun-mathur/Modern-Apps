package com.vayunmathur.findfamily

import android.Manifest
import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.ui.MainPage
import com.vayunmathur.findfamily.ui.UwbRangingScreen
import com.vayunmathur.findfamily.ui.dialogs.AddLinkDialog
import com.vayunmathur.findfamily.ui.dialogs.AddPersonDialog
import com.vayunmathur.findfamily.ui.dialogs.AddTrackerDialog
import com.vayunmathur.findfamily.ui.dialogs.decodeBase26
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.IconBluetooth
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.library.ui.dialog.DatePickerDialog
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import com.vayunmathur.findfamily.util.FindFamilyViewModel
import com.vayunmathur.findfamily.util.FindFamilyViewModelFactory
import com.vayunmathur.findfamily.util.Platform
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle

class MainActivity : MaActivity() {
    companion object {
        /** Extra key for launching directly into [Route.UwbRangingPage] via notification tap. */
        const val EXTRA_UWB_PEER_ID = "com.vayunmathur.findfamily.EXTRA_UWB_PEER_ID"
    }

    private val ffViewModel: FindFamilyViewModel by viewModels {
        FindFamilyViewModelFactory(application, FindFamilyRepository.get(application))
    }

    /**
     * Pending `findfamily://add/<base26>` invite (already decoded), set on cold
     * start and via [onNewIntent] for warm starts. Read from Compose so a tap routes
     * to the prefilled Add Person dialog; cleared once consumed.
     */
    private val deepLinkAddInvite = mutableStateOf<AddInvite?>(null)

    override fun onCreateBeforeContent() {
        deepLinkAddInvite.value = parseAddDeepLink(intent)
        // FIRST_PARTY: api.maproj.org + data.maproj.org (Cloudflare ISRG+GTS).
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
    }

    @Composable
    override fun Content() {
        val platform = Platform(this)
        val hasForeground by ffViewModel.hasForeground.collectAsState()
        val hasCoarse by ffViewModel.hasCoarse.collectAsState()
        // Approximate-only needs the upgrade prompt even though the gate
        // itself only blocks on the missing set.
        val coarseOnly = hasCoarse && !hasForeground
        var showUpgradeDialog by remember { mutableStateOf(false) }
        LaunchedEffect(coarseOnly) {
            if (coarseOnly) showUpgradeDialog = true
        }

        AppPermissionsGate(spec = findFamilyPermissionsSpec()) {
            val deepLinkPeerId = remember {
                intent?.takeIf { it.hasExtra(EXTRA_UWB_PEER_ID) }
                    ?.getLongExtra(EXTRA_UWB_PEER_ID, -1L)
                    ?.takeIf { it != -1L }
            }
            Navigation(
                platform,
                ffViewModel,
                ffViewModel.missingFeatures,
                deepLinkPeerId,
                deepLinkAddInvite.value,
                onDeepLinkAddConsumed = { deepLinkAddInvite.value = null },
            )

            // Approximate-only: explain and offer a reliable way to
            // re-open the upgrade prompt after it has been dismissed.
            if (coarseOnly && showUpgradeDialog) {
                ConfirmDialog(
                    title = stringResource(R.string.permission_upgrade_dialog_title),
                    message = stringResource(R.string.permission_upgrade_dialog_message),
                    confirmLabel = stringResource(R.string.permission_upgrade_dialog_confirm),
                    dismissLabel = stringResource(R.string.permission_upgrade_dialog_dismiss),
                    onConfirm = { showUpgradeDialog = false },
                    onDismiss = { showUpgradeDialog = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: warm-start links arrive here. Keep getIntent() in sync and
        // surface the invite so Compose routes to the prefilled dialog.
        setIntent(intent)
        parseAddDeepLink(intent)?.let { deepLinkAddInvite.value = it }
    }

    /**
     * Decodes a `findfamily://add/<base26Userid>[?k=<fingerprint>]` invite, or null for any
     * other intent. `k` is a truncated hash of the sender's public bundle, which the connect
     * flow checks the relay's key against; links from builds before it existed have no `k`.
     */
    private fun parseAddDeepLink(intent: Intent?): AddInvite? {
        val data = intent?.data ?: return null
        if (data.scheme != "findfamily" || data.host != "add") return null
        val segment = data.lastPathSegment?.trim()?.uppercase() ?: return null
        if (segment.isEmpty() || segment.any { it !in 'A'..'Z' }) return null
        val id = runCatching { segment.decodeBase26() }.getOrNull() ?: return null
        val fingerprint = runCatching { data.getQueryParameter("k")?.trim() }.getOrNull()
            ?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
        return AddInvite(id, fingerprint)
    }
}

/** A tapped invite link: the sender's id, plus the bundle fingerprint it vouched for. */
data class AddInvite(val id: Long, val fingerprint: String?)

/** The foreground/background-location + BT-scan gate every FindFamily surface sits behind. */
@Composable
private fun findFamilyPermissionsSpec() = AppPermissionsSpec(
    title = stringResource(R.string.permission_grant_fine_location),
    icon = { IconBluetooth() },
    requirements = listOf(
        // Request fine AND coarse together: on Android 12+
        // this surfaces the Precise/Approximate choice;
        // requesting fine alone is ignored by the system.
        PermissionRequirement.Runtime(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        ),
        PermissionRequirement.Runtime(
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ),
        // The finder half of powered-off finding is always
        // on, so the scanner needs BLUETOOTH_SCAN from the
        // start; without it it starts and immediately closes.
        PermissionRequirement.Runtime(
            arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        ),
    )
)

@Composable
fun MissingFeaturesDialog(backStack: NavBackStack<Route>) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.missing_features_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.missing_features_explanation),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { backStack.pop() }) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data class MainPage(val selectedUserId: Long? = null, val selectedWaypointId: Long? = null): Route

    @Serializable
    data class UserPageHistoryDatePicker(val initialDate: LocalDate): Route

    @Serializable
    data class AddPersonDialog(val id: Long? = null, val fingerprint: String? = null): Route

    @Serializable
    data object AddLinkDialog: Route

    /** Dev-only (DEV_BUILD): bind a new custom UWB tracker. */
    @Serializable
    data object AddTrackerDialog: Route

    @Serializable
    data object MissingFeaturesDialog: Route

    /** UWB Find Nearby (UWB) screen for the given peer. Full screen (no DialogPage metadata). */
    @Serializable
    data class UwbRangingPage(val userId: Long): Route
}

@Composable
fun Navigation(
    platform: Platform,
    ffViewModel: FindFamilyViewModel,
    showMissingFeatures: Boolean,
    deepLinkUwbPeerId: Long? = null,
    deepLinkAddInvite: AddInvite? = null,
    onDeepLinkAddConsumed: () -> Unit = {},
) {
    val backStack = rememberNavBackStack<Route>(Route.MainPage())

    LaunchedEffect(showMissingFeatures) {
        if (showMissingFeatures) {
            backStack.add(Route.MissingFeaturesDialog)
        }
    }

    LaunchedEffect(deepLinkUwbPeerId) {
        if (deepLinkUwbPeerId != null) {
            backStack.add(Route.UwbRangingPage(deepLinkUwbPeerId))
        }
    }

    // A tapped invite prefills the Add Person dialog; the user still explicitly
    // accepts, so the connection/crypto flow is unchanged.
    LaunchedEffect(deepLinkAddInvite) {
        if (deepLinkAddInvite != null) {
            backStack.add(Route.AddPersonDialog(deepLinkAddInvite.id, deepLinkAddInvite.fingerprint))
            onDeepLinkAddConsumed()
        }
    }

    MainNavigation(backStack) {
        entry<Route.MainPage> {
            MainPage(platform, backStack, ffViewModel, it.selectedUserId, it.selectedWaypointId)
        }
        entry<Route.UserPageHistoryDatePicker>(metadata = DialogPage()) {
            DatePickerDialog(
                backStack,
                "HistoryDatePicker",
                it.initialDate,
                maxDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            )
        }
        entry<Route.AddPersonDialog>(metadata = DialogPage()) {
            AddPersonDialog(backStack, ffViewModel, platform, it.id, it.fingerprint)
        }
        entry<Route.AddLinkDialog>(metadata = DialogPage()) {
            AddLinkDialog(backStack, ffViewModel)
        }
        entry<Route.AddTrackerDialog>(metadata = DialogPage()) {
            AddTrackerDialog(backStack)
        }
        entry<Route.MissingFeaturesDialog>(metadata = DialogPage()) {
            MissingFeaturesDialog(backStack)
        }
        entry<Route.UwbRangingPage> {
            UwbRangingScreen(backStack, ffViewModel, it.userId)
        }
    }
}
