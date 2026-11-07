package com.vayunmathur.backup

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.backup.data.BackupConfig
import com.vayunmathur.backup.platform.BackupViewModel
import com.vayunmathur.backup.platform.crypto.KeyManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NavigationAndRouteTest {
    @get:Rule val rule = createComposeRule()
    private val store = androidx.lifecycle.ViewModelStore()
    @After fun tearDown() { store.clear() }
    private fun vm(): BackupViewModel =
        androidx.lifecycle.ViewModelProvider(
            store,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    BackupViewModel(app) as T
            },
        )[BackupViewModel::class.java]

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = runBlocking<Unit> {
        FakeAndroidKeyStore.install(); FakeAndroidKeyStore.clear()
        app.getSharedPreferences("backup_secure_prefs", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        BackupConfig(app).clear(); runCatching { KeyManager(app).clear() }
    }

    @Test fun routesAreDistinctNavKeys() {
        assertEquals(Route.Onboarding, Route.Onboarding)
        assertEquals(Route.Dashboard, Route.Dashboard)
        assertNotEquals<Route>(Route.Onboarding, Route.Dashboard)
        assertTrue(Route.Onboarding.toString().isNotEmpty())
    }

    @Test fun navigationShowsOnboardingWhenNotOnboarded() {
        val vm = vm()
        rule.setContent { Navigation(vm) }
        rule.onNodeWithText("Set up Backup").assertIsDisplayed()
        rule.onNodeWithText("Recovery code").performScrollTo().assertIsDisplayed()
    }

    @Test fun mainActivityContentType() {
        // MainActivity is a MaActivity whose Content delegates to Navigation;
        // launching it under Robolectric exercises that wiring end to end.
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java)
        controller.create().start().resume()
        controller.pause().stop().destroy()
    }
}
