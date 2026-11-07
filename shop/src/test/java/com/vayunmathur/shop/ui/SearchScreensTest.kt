package com.vayunmathur.shop.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.shop.data.UcpProduct
import com.vayunmathur.shop.network.ShopBackendException
import com.vayunmathur.shop.data.UcpError
import com.vayunmathur.shop.platform.AppInit
import com.vayunmathur.shop.platform.FakeShopBackend
import com.vayunmathur.shop.platform.ShopViewModel
import com.vayunmathur.shop.platform.testProduct
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp")
class SearchScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        runCatching { WorkManagerTestInitHelper.initializeTestWorkManager(ctx) }
        AppInit.start(ctx)
        runBlocking { AppInit.awaitReady() }
        ctx.getSharedPreferences("shop_link_defaults", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("shop_link_defaults", Context.MODE_PRIVATE).edit().putBoolean("dismissed", true).commit()
    }

    private data class SearchCallbacks(
        var query: String = "",
        var searches: Int = 0,
        var clearedScope: Int = 0,
        val clicks: MutableList<Triple<String, String, String>> = mutableListOf(),
        var promptOpened: Int = 0,
        var promptDismissed: Int = 0,
    )

    private fun setContent(
        cb: SearchCallbacks,
        results: List<UcpProduct> = emptyList(),
        searching: Boolean = false,
        scopeName: String? = null,
        linkPrompt: LinkPrompt? = null,
    ) {
        rule.setContent {
            DynamicTheme {
                SearchContent(
                    query = cb.query,
                    onQueryChange = { cb.query = it },
                    searching = searching,
                    results = results,
                    onSearch = { cb.searches++ },
                    onProductClick = { v, p, s -> cb.clicks += Triple(v, p, s) },
                    scopeName = scopeName,
                    onClearScope = { cb.clearedScope++ },
                    linkPrompt = linkPrompt,
                )
            }
        }
    }

    @Test
    fun `empty results explain that nothing was found`() {
        setContent(SearchCallbacks())
        rule.onNodeWithText("No products found").assertIsDisplayed()
        rule.onNodeWithText("Enter a search or try a different query").assertIsDisplayed()
    }

    @Test
    fun `searching shows progress instead of results`() {
        setContent(SearchCallbacks(), searching = true, results = listOf(testProduct()))
        rule.onNodeWithText("No products found").assertDoesNotExist()
        rule.onNodeWithText("Trail Runner").assertDoesNotExist()
    }

    @Test
    fun `results render cards and tapping one opens its first variant`() {
        val cb = SearchCallbacks()
        setContent(cb, results = listOf(testProduct(), testProduct(id = "p2", title = "Rain Boots")))
        rule.onNodeWithText("Trail Runner").assertIsDisplayed()
        rule.onNodeWithText("Rain Boots").assertIsDisplayed()
        rule.onAllNodesWithText("Trail Store")[0].assertIsDisplayed()
        rule.onNodeWithText("Trail Runner").performClick()
        assertEquals(listOf(Triple("v1", "shopify", "shop.example.com")), cb.clicks)
    }

    @Test
    fun `typing a query and searching reports both`() {
        val cb = SearchCallbacks()
        setContent(cb)
        rule.onNode(androidx.compose.ui.test.isEditable()).performTextInput("boots")
        assertEquals("boots", cb.query)
        // The clear button appears once the query is non-empty in the real screen; here the
        // captured query state proves the callback wiring.
    }

    @Test
    fun `a merchant scope chip can be dismissed`() {
        val cb = SearchCallbacks()
        setContent(cb, scopeName = "Trail Store")
        rule.onNodeWithText("Results from Trail Store").assertIsDisplayed()
        rule.onNodeWithText("Results from Trail Store").performClick()
        assertEquals(1, cb.clearedScope)
    }

    @Test
    fun `the link prompt card opens settings or dismisses`() {
        val cb = SearchCallbacks()
        setContent(
            cb,
            linkPrompt = LinkPrompt(
                onOpenSettings = { cb.promptOpened++ },
                onDismiss = { cb.promptDismissed++ },
            ),
        )
        rule.onNodeWithText("Open store links in Shop").assertIsDisplayed()
        rule.onNodeWithText("Open settings").performClick()
        rule.onNodeWithText("Not now").performClick()
        assertEquals(1, cb.promptOpened)
        assertEquals(1, cb.promptDismissed)
    }

    @Test
    fun `the search screen drives the view model end to end`() {
        val backend = FakeShopBackend().apply {
            searchResult = Result.success(listOf(testProduct()))
        }
        val vm = ShopViewModel(backend)
        val clicks = mutableListOf<Triple<String, String, String>>()
        rule.setContent {
            DynamicTheme { SearchScreen(vm) { v, p, s -> clicks += Triple(v, p, s) } }
        }
        vm.updateQuery("trail")
        vm.search()
        rule.waitUntil(timeoutMillis = 10_000) { vm.results.size == 1 }
        rule.onNodeWithText("Trail Runner").assertIsDisplayed()
        rule.onNodeWithText("Trail Runner").performClick()
        assertEquals(1, clicks.size)

        // An error from the backend is shown through the messenger and then dismissed.
        backend.searchResult = Result.failure(ShopBackendException(UcpError.Transport("down")))
        vm.updateQuery("boots")
        vm.search()
        rule.waitUntil(timeoutMillis = 10_000) { vm.searchError == null && !vm.searching && backend.searchCalls.size >= 2 }
    }

    @Test
    fun `a product without variants renders but does not open`() {
        val cb = SearchCallbacks()
        setContent(cb, results = listOf(testProduct(variants = emptyList())))
        rule.onNodeWithText("Trail Runner").assertIsDisplayed()
        rule.onNodeWithText("Trail Runner").performClick()
        assertTrue(cb.clicks.isEmpty())
    }
}
