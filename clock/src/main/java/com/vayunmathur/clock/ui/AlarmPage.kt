package com.vayunmathur.clock.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.clock.MAIN_PAGES
import com.vayunmathur.clock.Route
import com.vayunmathur.library.util.BottomNavBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmPage(backStack: NavBackStack<Route>) {
    Scaffold(topBar = {
        TopAppBar({Text("Alarm")})
    }, bottomBar = {
        BottomNavBar(backStack, MAIN_PAGES, Route.Alarm)
    }) { paddingValues ->
        Column(Modifier.padding(paddingValues)) {

        }
    }
}