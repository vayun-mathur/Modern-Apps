package com.vayunmathur.fooddelivery.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.fooddelivery.network.BitesAuth
import com.vayunmathur.fooddelivery.network.BitesCore
import com.vayunmathur.fooddelivery.data.AuthToken
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.Deal
import com.vayunmathur.fooddelivery.data.MenuCategory
import com.vayunmathur.fooddelivery.data.MenuItem
import com.vayunmathur.fooddelivery.data.Merchant
import com.vayunmathur.fooddelivery.data.MerchantDetail
import com.vayunmathur.fooddelivery.data.MerchantRewards
import com.vayunmathur.fooddelivery.data.Modifier
import com.vayunmathur.fooddelivery.data.ModifierGroup
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.fooddelivery.data.SelectedModifier
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.network.SimpleResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

/** Wave-3: restaurant/home/deals/checkout section branches not hit by earlier flows. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FoodWave3ScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val realTransport = BitesCore.transport

    @After fun restore() {
        BitesCore.transport = realTransport
        BitesAuth.clearToken()
    }

    private fun awaitNode(text: String, substring: Boolean = true) {
        val deadline = System.currentTimeMillis() + 15_000
        var found = false
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            rule.waitForIdle()
            found = try {
                rule.onNodeWithText(text, substring = substring).fetchSemanticsNode() != null
            } catch (_: AssertionError) { false }
            if (found) break
            Thread.sleep(50)
        }
        assertTrue(found, "node '$text' never appeared")
    }

    private fun idle(seconds: Long = 1) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(seconds, java.util.concurrent.TimeUnit.SECONDS)
        rule.waitForIdle()
    }

    @Test fun restaurantRichContentFiltersAndSearches() {
        val items = listOf(
            MenuItem(id = 10, name = "Burger", description = "Beef patty", price = 999),
            MenuItem(id = 11, name = "Shake", description = "Vanilla shake", price = 499),
            MenuItem(id = 12, name = "Ghost", description = "Unavailable", price = 100, isAvailable = false),
            MenuItem(id = 13, name = "SoldOut", description = "Gone", price = 100, isInStock = false),
        )
        val detail = MerchantDetail(
            id = 1, name = "Burger Barn", imageUrl = "",
            merchantTags = listOf("Burgers", "Shakes"), averageRating = 4.5, totalRatings = 42,
            nextOpenWindow = "Opens at 9 AM", rewardsPercentage = 5.0,
            categories = listOf(
                MenuCategory(id = 2, name = "Drinks", sortOrder = 2, itemIds = listOf(11)),
                MenuCategory(id = 1, name = "Mains", sortOrder = 1, itemIds = listOf(10, 12, 13)),
                MenuCategory(id = 3, name = "Hidden", sortOrder = 0, isActive = false, itemIds = listOf(10)),
            ),
            items = items,
        )
        rule.setContent {
            RestaurantContent(detail, rewards = MerchantRewards(merchantId = 1, balance = 0), onAddItem = { _, _ -> })
        }
        rule.onNodeWithText("Burger Barn").assertIsDisplayed()
        rule.onNodeWithText("4.5 (42)").assertExists()
        rule.onNodeWithText("Opens at 9 AM").assertExists()
        // Unavailable / out-of-stock / inactive-category rows never render.
        rule.onNodeWithText("Ghost").assertDoesNotExist()
        rule.onNodeWithText("SoldOut").assertDoesNotExist()
        // Search settles through the debounce, then only the matching row remains.
        rule.onNodeWithText("Search menu").performTextInput("shake")
        // The menu re-filter is debounced on the compose clock, not the wall clock.
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(2_000L)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        awaitNode("Shake")
        rule.onNodeWithText("Burger", substring = false).assertDoesNotExist()
    }

    @Test fun restaurantEmptyMenuShowsNoMatchMessage() {
        val detail = MerchantDetail(
            id = 1, name = "Empty Place",
            categories = listOf(MenuCategory(id = 1, name = "Mains", itemIds = listOf(99))),
            items = emptyList(),
        )
        rule.setContent { RestaurantContent(detail) }
        rule.onNodeWithText("No menu items", substring = true).assertExists()
    }

    @Test fun menuItemRowPlainVariantAndAddClick() {
        var adds = 0
        val plain = MenuItem(id = 1, name = "Fries", description = "", price = 399, doordashPrice = 299)
        rule.setContent { MenuItemRow(plain) { adds++ } }
        rule.onNodeWithText("Fries").assertIsDisplayed()
        rule.onNodeWithText("$3.99").assertExists()
        // DoorDash price below ours is not shown as a comparison.
        rule.onNodeWithText("on DD", substring = true).assertDoesNotExist()
        rule.onNode(hasClickAction()).performClick()
        assertEquals(1, adds)
    }

    @Test fun menuItemRowFancyVariant() {
        val fancy = MenuItem(
            id = 2, name = "Burger", description = "Beef", price = 999, doordashPrice = 1299,
            imageUrl = "https://example.com/b.jpg",
            modifierGroups = listOf(
                ModifierGroup(id = 1, name = "Extras", modifiers = listOf(Modifier(id = 1, name = "Cheese", price = 100))),
            ),
        )
        rule.setContent { MenuItemRow(fancy) {} }
        rule.onNodeWithText("$12.99 on DD").assertExists()
        rule.onNodeWithText("Customizable").assertExists()
    }

    @Test fun merchantRewardsJoinTrimsAndClears() {
        var joined: String? = null
        var left = false
        rule.setContent {
            MerchantRewardsCard(rewards = null, onJoin = { joined = it }, onLeave = { left = true })
        }
        rule.onNodeWithText("No rewards", substring = true).assertExists()
        rule.onNodeWithText("Invite code").performTextInput("  CODE9  ")
        rule.onNodeWithText("Join").performClick()
        assertEquals("CODE9", joined)
        assertTrue(!left)
    }

    @Test fun merchantRewardsLeaveFires() {
        var left = false
        rule.setContent {
            MerchantRewardsCard(
                rewards = MerchantRewards(merchantId = 1, balance = 250),
                onJoin = {}, onLeave = { left = true },
            )
        }
        rule.onNodeWithText("$2.50", substring = true).assertExists()
        rule.onNodeWithText("Leave", substring = true).performClick()
        assertTrue(left)
    }

    @Test fun homeMerchantCardBranchVariants() {
        val closed = Merchant(
            id = 1, name = "Closed Cafe", isOpen = false, closingTime = "",
            addressCity = "Springfield", addressStreet = "1 Main", addressState = "IL",
            doordashMarkup = 500, doordashMarkupComparison = 300,
        )
        val open = Merchant(
            id = 2, name = "Open Diner", isOpen = true, nextOpenWindow = "Open until 10 PM",
            imageUrl = "https://example.com/o.jpg", logoUrl = "https://example.com/l.jpg",
            averageRating = 3.5, distance = 1.25, rewardsPercentage = 10.0,
            doordashMarkup = 200, doordashMarkupComparison = 700, merchantTags = listOf("Diner"),
        )
        rule.setContent { HomeContent(listOf(closed, open), onMerchantClick = {}) }
        rule.onNodeWithText("Closed Cafe").assertExists()
        rule.onNodeWithText("Closed").assertExists()
        rule.onNodeWithText("Open Diner").assertExists()
        rule.onNodeWithText("Open until 10 PM").assertExists()
        rule.onNodeWithText("1.3 mi").assertExists()
        rule.onNodeWithText("Save $5 vs DoorDash").assertExists()
        rule.onNodeWithText("3.5").assertExists()
    }

    @Test fun dealsScreenRendersProgressAndClickRules() {
        BitesCore.transport = { url, _, _, _ ->
            when {
                url.contains("/deals/1/progress") ->
                    SimpleResponse(200, "", """{"data":{"ordersCount":2,"isComplete":true}}""", emptyMap(), url)
                url.contains("/deals/2/progress") ->
                    SimpleResponse(200, "", """{"data":{"ordersCount":1,"isComplete":false}}""", emptyMap(), url)
                url.contains("/deals") -> SimpleResponse(
                    200, "",
                    """{"data":[
                      {"id":1,"title":"Unlocked Deal","merchantId":7,"merchantName":"Greens","description":"Done","image":"https://example.com/d.jpg","discountAmount":500,"minOrderCount":2,"isActive":true},
                      {"id":2,"title":"Percent Deal","merchantId":0,"merchantName":"","discountPercent":25.0,"minOrderCount":4,"isActive":true},
                      {"id":3,"title":"Inactive Deal","merchantId":9,"isActive":false}
                    ]}""",
                    emptyMap(), url,
                )
                else -> SimpleResponse(404, "", "{}", emptyMap(), url)
            }
        }
        BitesAuth.setToken(AuthToken("acc", "ref", "Bearer", 3600))
        AppInit.start(ctx)
        var clicked = -1
        rule.setContent { DealsScreen { clicked = it } }
        awaitNode("Unlocked Deal")
        rule.onNodeWithText("Inactive Deal").assertDoesNotExist()
        rule.onNodeWithText("$5.00 off").assertExists()
        rule.onNodeWithText("25% off").assertExists()
        rule.onNodeWithText("Unlocked Deal").performClick()
        assertEquals(7, clicked)
        // A deal without a merchant opens nothing.
        rule.onNodeWithText("Percent Deal").performClick()
        assertEquals(7, clicked)
    }

    @Test fun checkoutTotalsFetchingBranch() {
        rule.setContent {
            CheckoutTotals(20.0, true, null, null, null, null, 0.0, null, null)
        }
        rule.onNodeWithText("$20.00").assertExists()
        rule.onNodeWithText("Tax").assertDoesNotExist()
    }

    @Test fun checkoutTotalsNoTaxQuoteBranch() {
        rule.setContent {
            CheckoutTotals(20.0, false, null, null, null, null, 0.0, null, null)
        }
        rule.onNodeWithText("$20.00").assertExists()
        rule.onNodeWithText("Total", substring = true).assertDoesNotExist()
    }

    @Test fun checkoutTotalsRewardsAndDisplayFallback() {
        // Zero fees are hidden, rewards show as a negative row, and the total
        // falls back to displayTotal when payTotal is absent.
        rule.setContent {
            CheckoutTotals(
                subtotal = 20.0, fetchingPrices = false, taxesDollars = 2.0,
                deliveryFeeDollars = 0.0, feesDollars = 0.0, tipsDollars = 0.0,
                rewardsApplied = 1.5, payTotal = null, displayTotal = 20.5,
            )
        }
        rule.onNodeWithText("Rewards").assertExists()
        rule.onNodeWithText("$20.50").assertExists()
        rule.onNodeWithText("Delivery fee").assertDoesNotExist()
    }

    @Test fun checkoutTotalsSubtotalFallback() {
        // Both totals absent: the subtotal itself is the displayed total.
        rule.setContent {
            CheckoutTotals(33.0, false, 3.0, 2.0, 1.0, 4.0, 0.0, null, null)
        }
        // Subtotal and total rows both read $33.00 in this fallback.
        rule.onAllNodesWithText("$33.00").assertCountEquals(2)
        rule.onNodeWithText("Delivery fee").assertExists()
        rule.onNodeWithText("Service fees").assertExists()
    }

    @Test fun checkoutSectionInteractionsWave3() {
        var tip = -1
        var promo = ""
        var dealSel: Int? = 99
        var selected: SavedAddress? = null
        var instructions = ""
        val addr = SavedAddress(
            id = "a1", label = "", addressStreet = "1 Main St",
            aptUnit = "4B", gateCode = "1234",
        )
        val items = listOf(
            CartItem(
                menuItem = MenuItem(id = 1, name = "Burger", price = 1000), quantity = 2,
                selectedModifiers = listOf(SelectedModifier(name = "Cheese", price = 100)),
            ),
        )
        rule.setContent {
            Column {
                CheckoutOrderSummary(items)
                CheckoutTipPicker(300) { tip = it }
                CheckoutDealsPicker(
                    deals = listOf(Deal(id = 5, title = "Deal Five", description = "")),
                    selectedDealId = 5,
                    onSelect = { dealSel = it },
                    promoCode = promo,
                    onPromoChange = { promo = it },
                )
                CheckoutAddressPicker(
                    addresses = listOf(addr), addressesLoaded = true, selectedAddress = addr,
                    onSelect = { selected = it }, deliveryInstructions = instructions,
                    onInstructionsChange = { instructions = it },
                )
            }
        }
        rule.onNodeWithText("2x Burger").assertExists()
        rule.onNodeWithText("Cheese").assertExists()
        rule.onNodeWithText("$5.00").performClick()
        assertEquals(500, tip)
        // Tapping the already-chosen deal clears the selection.
        rule.onNodeWithText("Deal Five").performClick()
        assertEquals(null, dealSel)
        rule.onNodeWithText("Address").assertExists()
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText("1 Main St")), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals("a1", selected?.id)
    }
}
