package com.vayunmathur.youpipe.ui

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionCategory
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: subscriptions-list and settings-root navigation rows. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SubscriptionsSettingsNavPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel
    private lateinit var backStack: NavBackStack<Route>

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java)
            .allowMainThreadQueries().build()
        vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
        backStack = NavBackStack(arrayOf<Route>(Route.Main(1)))
        runBlocking {
            val subId = db.subscriptionDao().upsert(Subscription(name = "Seeded Chan", channelID = "UCseed", avatarURL = ""))
            db.subscriptionCategoryDao().upsertAll(listOf(SubscriptionCategory(subId, "News")))
        }
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun subscriptions_allCategoryAndChannelRowsNavigate() {
        rule.setContent { SubscriptionsPage(backStack, vm) }
        rule.waitUntilExactlyOneExists(hasText("Seeded Chan"), timeoutMillis = 10_000)
        rule.onNodeWithText(app.getString(R.string.label_all_subscriptions)).performClick()
        assertEquals(Route.SubscriptionVideosPage(null), backStack.backStack.last())
        rule.onNodeWithText("News").performClick()
        assertEquals(Route.SubscriptionVideosPage("News"), backStack.backStack.last())
        rule.onNodeWithText("Seeded Chan").performClick()
        assertEquals(Route.ChannelPage("UCseed"), backStack.backStack.last())
    }

    @Test
    fun subscriptions_addAndEditCategoryButtonsNavigate() {
        rule.setContent { SubscriptionsPage(backStack, vm) }
        rule.waitUntilExactlyOneExists(hasText("News"), timeoutMillis = 10_000)
        rule.onNodeWithContentDescription("Add").performClick()
        assertEquals(Route.CreateSubscriptionCategory(null), backStack.backStack.last())
        rule.onNodeWithContentDescription("Edit").performClick()
        assertEquals(Route.CreateSubscriptionCategory("News"), backStack.backStack.last())
    }

    @Test
    fun settingsRoot_everyRowNavigates() {
        val stack = NavBackStack(arrayOf<Route>(Route.Main(4)))
        rule.setContent { SettingsPage(stack, vm) }
        rule.onNodeWithText(app.getString(R.string.settings_general_content), substring = true).performClick()
        assertEquals(Route.SettingsGeneral, stack.backStack.last())
        rule.onNodeWithText(app.getString(R.string.settings_sponsorblock), substring = true).performClick()
        assertEquals(Route.SettingsSponsorBlock, stack.backStack.last())
        rule.onNodeWithText(app.getString(R.string.label_recommendations), substring = true).performClick()
        assertEquals(Route.SettingsRecommendations, stack.backStack.last())
        rule.onNodeWithText(app.getString(R.string.settings_data), substring = true).performClick()
        assertEquals(Route.SettingsData, stack.backStack.last())
    }
}
