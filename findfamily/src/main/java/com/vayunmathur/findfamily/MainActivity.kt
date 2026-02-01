package com.vayunmathur.findfamily

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.calendar.ui.dialog.DatePickerDialog
import com.vayunmathur.findfamily.data.FFDatabase
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.ui.MainPage
import com.vayunmathur.findfamily.ui.UserPage
import com.vayunmathur.findfamily.ui.WaypointEditPage
import com.vayunmathur.findfamily.ui.dialog.AddPersonDialog
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Clock

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = buildDatabase<FFDatabase>()
        val viewModel = DatabaseViewModel(User::class to db.userDao(), Waypoint::class to db.waypointDao(), LocationValue::class to db.locationValueDao())
        val dataStoreUtils = DataStoreUtils(this)
        val platform = Platform(this)
        setContent {
            LaunchedEffect(Unit) {
                Networking.init(viewModel, dataStoreUtils)
            }
            DynamicTheme {
                Navigation(platform, viewModel)
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object MainPage: Route

    @Serializable
    data class UserPage(val id: Long): Route

    @Serializable
    data class UserPageHistoryDatePicker(val initialDate: LocalDate): Route

    @Serializable
    data class WaypointEditPage(val id: Long): Route

    @Serializable
    data object AddPersonDialog: Route
}

@Composable
fun Navigation(platform: Platform, viewModel: DatabaseViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.MainPage)
    MainNavigation(backStack) {
        entry<Route.MainPage> {
            MainPage(platform, backStack, viewModel)
        }
        entry<Route.UserPage> {
            UserPage(platform, backStack, viewModel, it.id)
        }
        entry<Route.WaypointEditPage> {
            WaypointEditPage(backStack, viewModel, it.id)
        }
        entry<Route.UserPageHistoryDatePicker>(metadata = DialogPage()) {
            DatePickerDialog(backStack, "HistoryDatePicker", it.initialDate, maxDate = Clock.System.now().toLocalDateTime(
                TimeZone.currentSystemDefault()).date)
        }
        entry<Route.AddPersonDialog>(metadata = DialogPage()) {
            AddPersonDialog(backStack, viewModel, platform)
        }
    }
}