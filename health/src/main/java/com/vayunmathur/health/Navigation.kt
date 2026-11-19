package com.vayunmathur.health

import androidx.compose.runtime.Composable
import com.vayunmathur.health.platform.HealthViewModel
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.ui.HealthBottomBar
import com.vayunmathur.health.ui.fitnessEntries
import com.vayunmathur.health.ui.medicalEntries
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: HealthViewModel, medicalViewModel: MedicalViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Today)
    MainNavigation(
        backStack = backStack,
        bottomBar = { HealthBottomBar(backStack) },
    ) {
        fitnessEntries(backStack, viewModel)
        medicalEntries(backStack, medicalViewModel)
    }
}

