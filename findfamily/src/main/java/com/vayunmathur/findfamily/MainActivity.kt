package com.vayunmathur.findfamily

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.domain.AddInvite
import com.vayunmathur.findfamily.domain.FirstRun
import com.vayunmathur.findfamily.notifications.EXTRA_NOSHOW_USER_ID
import com.vayunmathur.findfamily.notifications.EXTRA_NOSHOW_WAYPOINT_ID
import com.vayunmathur.findfamily.platform.FindFamilyViewModel
import com.vayunmathur.findfamily.platform.FindFamilyViewModelFactory
import com.vayunmathur.findfamily.platform.Platform
import com.vayunmathur.findfamily.ui.dialogs.decodeBase26
import com.vayunmathur.findfamily.ui.findFamilyPermissionsSpec
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.OfflineAware

/**
 * FindFamily entry activity: initialises the first-party network client, parses invite
 * and no-show intents, and hosts the permission-gated navigation graph.
 */
class MainActivity : MaActivity() {
    /** Intent-extra keys for launching [MainActivity]. */
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

    /** Pending no-show notification target (watched person, expected place), set on
     * cold start and via [onNewIntent] for warm starts; cleared once routed. */
    private val noShowTarget = mutableStateOf<Pair<Long, Long?>?>(null)

    override fun onCreateBeforeContent() {
        deepLinkAddInvite.value = parseAddDeepLink(intent)
        noShowTarget.value = parseNoShowTarget(intent)
        // FIRST_PARTY: api.maproj.org + data.maproj.org (Cloudflare ISRG+GTS).
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
    }

    @Composable
    override fun Content() {
        val platform = Platform(this)
        val hasForeground by ffViewModel.hasForeground.collectAsState()
        val hasCoarse by ffViewModel.hasCoarse.collectAsState()
        val hasBackground by ffViewModel.hasBackground.collectAsState()
        val hasBluetooth by ffViewModel.hasBluetooth.collectAsState()
        val hasFamily by ffViewModel.hasFamilyMember.collectAsState()
        val firstRunCompleted by ffViewModel.firstRunCompleted.collectAsState()
        // Approximate-only needs the upgrade prompt even though the gate
        // itself only blocks on the missing set.
        val coarseOnly = hasCoarse && !hasForeground
        var showUpgradeDialog by remember { mutableStateOf(false) }
        LaunchedEffect(coarseOnly) {
            if (coarseOnly) showUpgradeDialog = true
        }

        // Staged first-run (census M): a brand-new user (no family, flow not
        // finished, not already granted) is NOT dropped into the all-at-once
        // permission wall. They go straight into the app with the staged
        // sheet over it — pair family first with no permission, location
        // only when enabling sharing, notifications at the first alert.
        // Returning users (family present, or the flow finished) and the
        // already-granted fast path keep the original gate exactly: the
        // gate passes granted users in one frame and walls the rest as before.
        val allGateGranted = hasForeground && hasBackground && hasBluetooth
        val isFirstRun = FirstRun.isFirstRun(hasFamily, firstRunCompleted, allGateGranted)

        val gatedContent: @Composable (Boolean) -> Unit = { showFirstRun ->
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
                noShowTarget = noShowTarget.value,
                onNoShowTargetConsumed = { noShowTarget.value = null },
                showFirstRun = showFirstRun,
                onFirstRunFinished = { ffViewModel.completeFirstRun() },
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

        OfflineAware {
            if (isFirstRun) {
                gatedContent(true)
            } else {
                AppPermissionsGate(spec = findFamilyPermissionsSpec()) {
                    gatedContent(false)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: warm-start links arrive here. Keep getIntent() in sync and
        // surface the invite so Compose routes to the prefilled dialog.
        setIntent(intent)
        parseAddDeepLink(intent)?.let { deepLinkAddInvite.value = it }
        parseNoShowTarget(intent)?.let { noShowTarget.value = it }
    }

    /** Reads the no-show notification extras into a (user, waypoint) target, or null. */
    private fun parseNoShowTarget(intent: Intent?): Pair<Long, Long?>? {
        val userId = intent?.takeIf { it.hasExtra(EXTRA_NOSHOW_USER_ID) }
            ?.getLongExtra(EXTRA_NOSHOW_USER_ID, -1L)?.takeIf { it != -1L } ?: return null
        val waypointId = intent.takeIf { it.hasExtra(EXTRA_NOSHOW_WAYPOINT_ID) }
            ?.getLongExtra(EXTRA_NOSHOW_WAYPOINT_ID, -1L)?.takeIf { it != -1L }
        return userId to waypointId
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
