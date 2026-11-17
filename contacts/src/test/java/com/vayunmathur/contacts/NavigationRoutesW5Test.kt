package com.vayunmathur.contacts

import android.Manifest
import android.app.Application
import android.provider.ContactsContract
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.SimProviderPass2Test
import com.vayunmathur.contacts.platform.ContactViewModel
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver

/** W5: every Navigation entry rendered through the real NavGraph with a fake provider. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NavigationRoutesW5Test {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
        )
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
        ShadowContentResolver.registerProviderInternal("icc", SimProviderPass2Test.FakeIccProvider())
    }

    private fun render(route: Route?) {
        val vm = ContactViewModel(app)
        rule.setContent { Navigation(vm, initialRoute = route) }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty(), "route $route should render")
    }

    @Test fun contactsListRoute() = render(Route.ContactsList)

    @Test
    fun tabSelectSwapsToGroupsTab() {
        val vm = ContactViewModel(app)
        rule.setContent { Navigation(vm, initialRoute = Route.ContactsList) }
        rule.waitForIdle()
        rule.onNodeWithText("Groups").performClick()
        rule.waitForIdle()
        // The Groups sibling destination renders its groups.
        rule.onNodeWithText("Family").assertIsDisplayed()
    }
    @Test fun settingsRoute() = render(Route.Settings)
    @Test fun groupsRoute() = render(Route.GroupsList(11L))
    @Test fun contactDetailRoute() = render(Route.ContactDetail(7L))
    @Test fun editContactRoute() = render(Route.EditContact(7L))
    @Test fun newContactRoute() = render(Route.EditContact(null))
    @Test fun insertOrEditRoute() = render(Route.InsertOrEditContact())
    @Test fun cropPhotoRoute() = render(Route.CropPhoto("file:///tmp/none.jpg"))
    @Test fun importVcfRoute() = render(Route.ImportVcf(emptyList()))
}
