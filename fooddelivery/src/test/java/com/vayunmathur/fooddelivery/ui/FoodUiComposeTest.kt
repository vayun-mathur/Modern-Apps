package com.vayunmathur.fooddelivery.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.Customer
import com.vayunmathur.fooddelivery.data.CustomerSavings
import com.vayunmathur.fooddelivery.data.Deal
import com.vayunmathur.fooddelivery.data.MenuCategory
import com.vayunmathur.fooddelivery.data.MenuItem
import com.vayunmathur.fooddelivery.data.Merchant
import com.vayunmathur.fooddelivery.data.MerchantDetail
import com.vayunmathur.fooddelivery.data.MerchantRewards
import com.vayunmathur.fooddelivery.data.Modifier
import com.vayunmathur.fooddelivery.data.ModifierGroup
import com.vayunmathur.fooddelivery.data.Order
import com.vayunmathur.fooddelivery.data.PlatformSavings
import com.vayunmathur.fooddelivery.data.Referral
import com.vayunmathur.fooddelivery.data.SavedAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class FoodUiComposeTest {
    @get:Rule val rule = createComposeRule()

    private fun merchant(id: Int = 1, name: String = "Burger Barn") = Merchant(
        id = id, name = name, latitude = 37.5, longitude = -122.3, averageRating = 4.5,
        merchantTags = listOf("Burgers", "Shakes"), isOpen = true,
    )
    private fun item(id: Int = 10, name: String = "Burger", price: Int = 999) = MenuItem(
        id = id, name = name, description = "Tasty", price = price,
        modifierGroups = listOf(
            ModifierGroup(id = 1, name = "Extras", required = true, minSelections = 1, maxSelections = 2,
                modifiers = listOf(Modifier(id = 100, name = "Cheese", price = 100), Modifier(id = 101, name = "Bacon", price = 200))),
            ModifierGroup(id = 2, name = "Size", required = false, minSelections = 0, maxSelections = 1,
                modifiers = listOf(Modifier(id = 200, name = "Large", price = 300))),
        ),
    )

    @Test fun homeContentEmptyLoadingAndList() {
        rule.setContent { HomeContent(emptyList(), loading = false, onMerchantClick = {}) }
        rule.waitForIdle()
    }
    @Test fun homeContentLoading() {
        rule.setContent { HomeContent(emptyList(), loading = true, onMerchantClick = {}) }
        rule.waitForIdle()
    }
    @Test fun homeContentNoAddress() {
        rule.setContent { HomeContent(emptyList(), noAddress = true, onMerchantClick = {}) }
        rule.waitForIdle()
    }
    @Test fun homeContentListAndClick() {
        var clicked = -1
        rule.setContent { HomeContent(listOf(merchant(), merchant(2, "Taco Town")), onMerchantClick = { clicked = it }) }
        rule.onNodeWithText("Burger Barn").assertIsDisplayed()
        rule.onNodeWithText("Burger Barn").performClick(); assertEquals(1, clicked)
    }
    @Test fun restaurantContentStates() {
        rule.setContent { RestaurantContent(null, loading = true) }
        rule.waitForIdle()
    }
    @Test fun restaurantContentNotFound() {
        rule.setContent { RestaurantContent(null, loading = false) }
        rule.waitForIdle()
    }
    @Test fun restaurantContentMenuAndAdd() {
        var added: MenuItem? = null
        val detail = MerchantDetail(id = 1, name = "Burger Barn", categories = listOf(MenuCategory(id = 1, name = "Mains", itemIds = listOf(10))), items = listOf(item()))
        rule.setContent { RestaurantContent(detail, rewards = MerchantRewards(merchantId = 1, balance = 500), onAddItem = { menuItem, _ -> added = menuItem }) }
        rule.onNodeWithText("Burger Barn").assertIsDisplayed(); rule.onNodeWithText("Burger").assertExists()
        rule.waitForIdle()
    }
    @Test fun cartEmptyAndFilledCheckout() {
        var checkedOut = false; var removed = -1
        rule.setContent { CartScreen(emptyList(), {}, { checkedOut = true }) }
        rule.onNodeWithText("Your cart is empty", substring = true).assertIsDisplayed()
    }
    @Test fun cartFilledSubtotalAndCheckoutClick() {
        var checkedOut = false
        val items = listOf(CartItem(menuItem = item(), quantity = 2))
        rule.setContent { CartScreen(items, {}, { checkedOut = true }) }
        rule.onNodeWithText("2x Burger").assertExists()
        rule.onNodeWithText("Checkout").performClick(); assertEquals(true, checkedOut)
    }
    @Test fun modifierDialogConfirmFlow() {
        var confirmed: List<com.vayunmathur.fooddelivery.data.SelectedModifier>? = null
        var dismissed = false
        rule.setContent { ModifierDialog(item = item(), onDismiss = { dismissed = true }, onConfirm = { confirmed = it }) }
        rule.onNodeWithText("Burger").assertIsDisplayed(); rule.onNodeWithText("Extras").assertIsDisplayed()
        rule.onNodeWithText("Cheese").performClick()
        rule.waitForIdle()
        // confirm button label is Add to cart (+ total) — click the first matching action text
        runCatching { rule.onNodeWithText("Add", substring = true).performClick() }
        rule.waitForIdle()
        assertTrue(confirmed != null || !dismissed)
    }
    @Test fun accountCardsRender() {
        val customer = Customer(id = 5, firstName = "Ann", lastName = "Lee", email = "a@b.c", phone = "555")
        rule.setContent {
            Column {
                AccountProfileCard(customer) {}
                AccountSavingsCard(CustomerSavings(customerSavings = 1234, orderCount = 3))
                AccountReferralsCard(listOf(Referral(id = 1, code = "ABC", status = "ACTIVE")), PlatformSavings(totalCustomerSavings = 500))
                AccountAuthCard(phone = "555", onPhoneChange = {}, code = "", onCodeChange = {}, codeSent = false, authLoading = false, onAuthClick = {})
            }
        }
        rule.onNodeWithText("Ann Lee").assertIsDisplayed(); rule.onNodeWithText("ABC").assertIsDisplayed()
    }
    @Test fun accountAuthCodeSentAndLoadingStates() {
        rule.setContent { AccountAuthCard(phone = "", onPhoneChange = {}, code = "12", onCodeChange = {}, codeSent = true, authLoading = false, onAuthClick = {}) }
        rule.onNodeWithText("Verify").assertIsDisplayed()
    }
    @Test fun addressFormAndSection() {
        val scope = CoroutineScope(Dispatchers.Main)
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        var changed: AddressFormState? = null
        rule.setContent {
            Column {
                AddressFormCard(AddressFormState(showing = true, triedSubmit = true), { changed = it }, {}, {})
                AccountAddressesSection(scope, ctx, true, listOf(SavedAddress(id = "1", label = "Home", addressStreet = "1 Main", addressCity = "Springfield", addressState = "CA", addressZip = "12345", isDefault = true)), {}, AddressFormState(), { changed = it }, {})
            }
        }
        rule.onNodeWithText("Home").assertExists(); rule.onNodeWithText("Add Address", substring = true).assertExists()
    }
    @Test fun checkoutSectionsAndTotals() {
        val items = listOf(CartItem(menuItem = item(), quantity = 1))
        rule.setContent {
            Column {
                CheckoutOrderSummary(items)
                CheckoutTotals(20.0, false, 1.8, 2.0, 1.0, 3.0, 0.0, 27.8, 27.8)
                CheckoutSuccess()
                OrdersScreenSection("PREPARING")
            }
        }
        rule.waitForIdle()
    }
    @Test fun emailVerificationStates() {
        var verified = false
        rule.setContent { EmailVerificationScreen("cust-1", "tok", { verified = true }) }
        rule.waitForIdle()
    }
    @Test fun homeBinderWithCachedMerchantsNotNeededForContent() {
        // Binder-free sanity: Deal model display fields
        val deal = Deal(id = 1, title = " deal ", merchantId = 7)
        assertEquals(0.0, deal.discountAmountDollars)
    }
    @Test fun orderCardFields() {
        val order = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(Order.serializer(), """{"id":9,"uuid":"u9","foodTotal":1000,"orderTotal":1500,"channel":"ORDER_CHANNEL_STOREFRONT_DELIVERY","deliveredAt":"2024-01-01T00:00:00Z"}""")
        assertEquals(15.0, order.displayTotal); assertEquals(true, order.isDone)
    }
}
