package com.vayunmathur.health

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.health.platform.HealthViewModel
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.ui.AddAllergyPage
import com.vayunmathur.health.ui.AddConditionPage
import com.vayunmathur.health.ui.AddLabResultPage
import com.vayunmathur.health.ui.AddMedicationPage
import com.vayunmathur.health.ui.AddVaccinationPage
import com.vayunmathur.health.ui.BarChartDetails
import com.vayunmathur.health.ui.BodyPage
import com.vayunmathur.health.ui.CatalogPickerPage
import com.vayunmathur.health.ui.ExerciseDetailsPage
import com.vayunmathur.health.ui.MedicalPage
import com.vayunmathur.health.ui.NutritionDetailsPage
import com.vayunmathur.health.ui.NutritionPage
import com.vayunmathur.health.ui.RecipeEditorPage
import com.vayunmathur.health.ui.RecipeManagementPage
import com.vayunmathur.health.ui.TodayPage
import com.vayunmathur.library.ui.IconBodySystem
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.IconFire
import com.vayunmathur.library.ui.IconMedicalServices
import com.vayunmathur.library.ui.dialog.DatePickerDialog
import com.vayunmathur.library.ui.dialog.TimePickerDialogContent
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.SiblingPage
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

/** The four sibling tabs along the bottom of the health host. */
@Composable
private fun HealthBottomBar(backStack: com.vayunmathur.library.util.NavBackStack<Route>) {
    com.vayunmathur.library.util.BottomNavBar(
        backStack = backStack,
        pages = listOf(
            com.vayunmathur.library.util.BottomBarItem(
                stringResource(R.string.nav_today),
                Route.Today,
            ) { IconFavorite() },
            com.vayunmathur.library.util.BottomBarItem(
                stringResource(R.string.nav_nutrition),
                Route.NutritionDetails,
            ) { IconFire() },
            com.vayunmathur.library.util.BottomBarItem(
                stringResource(R.string.nav_body),
                Route.Body,
            ) { IconBodySystem() },
            com.vayunmathur.library.util.BottomBarItem(
                stringResource(R.string.nav_medical),
                Route.Medical,
            ) { IconMedicalServices() },
        ),
        currentPage = backStack.last(),
    )
}

/** Fitness and nutrition destinations. */
private fun com.vayunmathur.library.util.EntryProviderScope<Route>.fitnessEntries(
    backStack: com.vayunmathur.library.util.NavBackStack<Route>,
    viewModel: HealthViewModel,
) {
    entry<Route.Today>(metadata = SiblingPage()) {
        TodayPage(backStack, viewModel)
    }
    entry<Route.Body>(metadata = SiblingPage()) {
        BodyPage(backStack, viewModel)
    }
    entry<Route.NutritionDetails>(metadata = SiblingPage()) {
        NutritionPage(backStack, viewModel)
    }
    entry<Route.NutritionFullBreakdown> {
        NutritionDetailsPage(backStack, viewModel)
    }
    entry<Route.RecipeManagement> {
        RecipeManagementPage(backStack, viewModel)
    }
    entry<Route.RecipeEditor> {
        RecipeEditorPage(backStack, viewModel, it.recipeId)
    }
    entry<Route.BarChartDetails>(metadata = MorphPage()) {
        BarChartDetails(backStack, viewModel, it.healthMetric)
    }
    entry<Route.SleepDetails>(metadata = MorphPage()) {
        com.vayunmathur.health.ui.SleepDetailsPage(backStack, viewModel)
    }
    entry<Route.ExerciseDetails>(metadata = MorphPage()) {
        ExerciseDetailsPage(backStack, viewModel)
    }
}

/** Medical records destinations. */
private fun com.vayunmathur.library.util.EntryProviderScope<Route>.medicalEntries(
    backStack: com.vayunmathur.library.util.NavBackStack<Route>,
    medicalViewModel: MedicalViewModel,
) {
    entry<Route.Medical>(metadata = SiblingPage() + ListPage()) {
        MedicalPage(backStack, medicalViewModel)
    }
    entry<Route.EditVaccination>(metadata = ListDetailPage()) {
        AddVaccinationPage(backStack, medicalViewModel)
    }
    entry<Route.EditMedication>(metadata = ListDetailPage()) {
        AddMedicationPage(backStack, medicalViewModel)
    }
    entry<Route.EditAllergy>(metadata = ListDetailPage()) {
        AddAllergyPage(backStack, medicalViewModel)
    }
    entry<Route.EditCondition>(metadata = ListDetailPage()) {
        AddConditionPage(backStack, medicalViewModel)
    }
    entry<Route.EditLabResult>(metadata = ListDetailPage()) {
        AddLabResultPage(backStack, medicalViewModel)
    }
    entry<Route.CatalogPicker>(metadata = ListDetailPage()) {
        CatalogPickerPage(backStack, medicalViewModel, it)
    }
    entry<Route.MedicalDatePicker>(metadata = DialogPage()) {
        DatePickerDialog(backStack, it.key, it.initialDate, allowClear = it.allowClear)
    }
    entry<Route.MedicalTimePicker>(metadata = DialogPage()) {
        TimePickerDialogContent(backStack, it.key, it.initialTime)
    }
}
