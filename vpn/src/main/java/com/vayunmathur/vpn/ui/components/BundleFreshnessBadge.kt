package com.vayunmathur.vpn.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.Text
import com.vayunmathur.vpn.R
import com.vayunmathur.vpn.domain.BundleFreshnessState

/** One-line freshness label for a provider bundle; server rows stay unadorned. */
@Composable
fun BundleFreshnessBadge(state: BundleFreshnessState, updateRevision: Long? = null) {
    val text = when (state) {
        BundleFreshnessState.CURRENT -> stringResource(R.string.bundle_freshness_current)
        BundleFreshnessState.OUTDATED -> stringResource(R.string.bundle_freshness_outdated)
        BundleFreshnessState.UPDATE_AVAILABLE -> stringResource(
            R.string.bundle_freshness_update_available, updateRevision ?: 0,
        )
        BundleFreshnessState.UNKNOWN -> stringResource(R.string.bundle_freshness_unknown)
        BundleFreshnessState.CHECK_FAILED -> stringResource(R.string.bundle_freshness_check_failed)
    }
    Text(text, fontSize = 12.sp)
}
