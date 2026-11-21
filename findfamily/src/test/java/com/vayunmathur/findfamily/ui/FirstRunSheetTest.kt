package com.vayunmathur.findfamily.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.data.FFDatabase
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.platform.FindFamilyViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Clock

/** Staged first-run sheet: pair first with no permission, location only after family exists. */
@RunWith(AndroidJUnit4::class)
class FirstRunSheetTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var repo: FindFamilyRepository
    private lateinit var vm: FindFamilyViewModel

    @BeforeTest
    fun setup() {
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        val db = Room.inMemoryDatabaseBuilder(app, FFDatabase::class.java).allowMainThreadQueries().build()
        repo = FindFamilyRepository(app, db)
        vm = FindFamilyViewModel(app, repo)
    }

    private fun stack(): NavBackStack<Route> = NavBackStack(arrayOf(Route.MainPage))

    @Test fun noFamilyShowsPairStageWithoutPermissionAsk() {
        rule.setContent { FirstRunSheet(backStack = stack(), ffViewModel = vm) }
        rule.onNodeWithText("Share location with family").assertExists()
        rule.onNodeWithText("Add family member").assertExists()
    }

    @Test fun familyShowsLocationStage() {
        runBlocking {
            repo.upsertUser(
            User(
                name = "Alice", photo = null, locationName = "Home", sendingEnabled = true,
                requestStatus = RequestStatus.MUTUAL_CONNECTION,
                lastLocationChangeTime = Clock.System.now(), id = 42,
            )
        )
        rule.setContent { FirstRunSheet(backStack = stack(), ffViewModel = vm) }
        rule.waitForIdle()
        rule.onNodeWithText("Turn on location sharing").assertIsDisplayed()
        rule.onNodeWithText("Allow location sharing").assertIsDisplayed()
        }
    }
}
