package com.vayunmathur.vpn

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.vpn.platform.VpnViewModel
import com.vayunmathur.vpn.ui.VpnTabs

@Composable
fun Navigation(vm: VpnViewModel) {
    // Land on the Settings tab when opened from the system App Info page: the
    // deep-linked tab is the stack's start route, not an initial pager page.
    val activity = LocalActivity.current
    val startRoute: Route = if (activity?.intent?.action == Intent.ACTION_APPLICATION_PREFERENCES) {
        Route.Settings
    } else {
        Route.Tunnels
    }
    val backStack = rememberNavBackStack<Route>(startRoute)
    VpnTabs(backStack, vm)
}
