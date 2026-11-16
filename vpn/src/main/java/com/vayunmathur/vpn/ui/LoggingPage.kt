package com.vayunmathur.vpn.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.vpn.platform.VpnViewModel

@Composable
fun LoggingPage(
    vm: VpnViewModel,
    initialTab: Int = 0,
    onTabChange: (Int) -> Unit = {},
) {
    val topApps by vm.topAppsFlow.collectAsState()
    val domainsByCount by vm.domainsByCountFlow.collectAsState()
    val domainsByBytes by vm.domainsByBytesFlow.collectAsState()

    LoggingContent(
        topApps = topApps,
        domainsByCount = domainsByCount,
        domainsByBytes = domainsByBytes,
        onDeleteAllLogs = { vm.deleteAllLogs() },
        initialTab = initialTab,
        onTabChange = onTabChange,
    )
}
