package com.vayunmathur.passwords.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.platform.PasswordsViewModel
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PagesComposeTest {

    @get:Rule val rule = createComposeRule()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private fun vm(): PasswordsViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return PasswordsViewModel(app, PasswordRepository.get(app))
    }

    @Test fun settingsPageShowsImportWarningAndButton() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Settings))
        rule.setContent { SettingsPage(backStack, vm()) }
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithText("Import Passwords").assertIsDisplayed()
    }

    @Test fun settingsImportMenuListsSources() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Settings))
        rule.setContent { SettingsPage(backStack, vm()) }
        rule.onNodeWithText("Import Passwords").performClick()
        rule.onNodeWithText("Bitwarden").assertIsDisplayed()
        rule.onNodeWithText("Firefox").assertIsDisplayed()
    }

    @Test fun autofillSettingsActivityFinishesImmediately() {
        val controller = org.robolectric.Robolectric.buildActivity(AutofillSettingsActivity::class.java)
        controller.create()
        assertEquals(true, controller.get().isFinishing)
    }
}
