package com.vayunmathur.health.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.platform.HealthViewModel
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.library.ui.IconBodySystem
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.IconFire
import com.vayunmathur.library.ui.IconMedicalServices
import com.vayunmathur.library.ui.dialog.DatePickerDialog
import com.vayunmathur.library.ui.dialog.TimePickerDialogContent
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.SiblingPage

/** The four sibling tabs along the bottom of the health host. */
@Composable
internal fun HealthBottomBar(backStack: NavBackStack<Route>) {
    BottomNavBar(
        backStack = backStack,
        pages = listOf(
            BottomBarItem(
                stringResource(R.string.nav_today),
                Route.Today,
            ) { IconFavorite() },
            BottomBarItem(
                stringResource(R.string.nav_nutrition),
                Route.NutritionDetails,
            ) { IconFire() },
            BottomBarItem(
                stringResource(R.string.nav_body),
                Route.Body,
            ) { IconBodySystem() },
            BottomBarItem(
                stringResource(R.string.nav_medical),
                Route.Medical,
            ) { IconMedicalServices() },
        ),
        currentPage = backStack.last(),
    )
}

/** Fitness and nutrition destinations. */
internal fun EntryProviderScope<Route>.fitnessEntries(
    backStack: NavBackStack<Route>,
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
        SleepDetailsPage(backStack, viewModel)
    }
    entry<Route.ExerciseDetails>(metadata = MorphPage()) {
        ExerciseDetailsPage(backStack, viewModel)
    }
}

/** Medical records destinations. */
internal fun EntryProviderScope<Route>.medicalEntries(
    backStack: NavBackStack<Route>,
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
