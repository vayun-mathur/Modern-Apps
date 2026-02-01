package com.vayunmathur.findfamily

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.calendar.ui.dialog.DatePickerDialog
import com.vayunmathur.findfamily.data.FFDatabase
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.ui.MainPage
import com.vayunmathur.findfamily.ui.UserPage
import com.vayunmathur.library.ui.DynamicTheme
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
        setContent {
            DynamicTheme {
                Navigation(viewModel)
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
    data class WaypointPage(val id: Long): Route
}

@Composable
fun Navigation(viewModel: DatabaseViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.MainPage)
    MainNavigation(backStack) {
        entry<Route.MainPage> {
            MainPage(backStack, viewModel)
        }
        entry<Route.UserPage> {
            UserPage(backStack, viewModel, it.id)
        }
        entry<Route.UserPageHistoryDatePicker>(metadata = DialogPage()) {
            DatePickerDialog(backStack, "HistoryDatePicker", it.initialDate, maxDate = Clock.System.now().toLocalDateTime(
                TimeZone.currentSystemDefault()).date)
        }
    }
}