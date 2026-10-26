package com.vayunmathur.shop.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.ProductVariant
import com.vayunmathur.shop.data.UcpProduct

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:shop`. See `common-conventions-preview-metadata`.
 *
 * Search results and cart lines are fixture data with empty image URLs: `AsyncImage`
 * needs a real `Context` and a network round-trip that a static preview never waits
 * for, so a URL here would buy an empty box at best and a Layoutlib failure at worst —
 * the card layouts already degrade to text-only when there is no image.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in
 * Studio but is not collected as a screenshot test. Previews must also be class
 * members, not top-level functions. Order comes from the function names
 * (Preview1…, Preview2…).
 */
class MetadataPreviews {

    private val variants = listOf(
        ProductVariant(
            id = "gid://shopify/ProductVariant/11",
            title = "9 / Blue",
            price = Money(12999, "USD"),
            optionValues = listOf("9", "Blue"),
            sku = "TR-9-BLUE",
        ),
        ProductVariant(
            id = "gid://shopify/ProductVariant/12",
            title = "10 / Blue",
            price = Money(12999, "USD"),
            optionValues = listOf("10", "Blue"),
        ),
    )

    private val products = listOf(
        UcpProduct(
            id = "gid://shopify/Product/1",
            title = "Trail Runner",
            description = "Grippy shoes for muddy miles",
            priceRange = Money(12999, "USD"),
            platformId = "shopify",
            shopDomain = "example.myshopify.com",
            variants = variants,
            checkoutEligible = true,
        ),
        UcpProduct(
            id = "gid://shopify/Product/2",
            title = "Wool Overshirt",
            description = "Heavyweight merino flannel",
            priceRange = Money(18900, "USD"),
            platformId = "shopify",
            shopDomain = "example.myshopify.com",
            variants = listOf(
                ProductVariant(
                    id = "gid://shopify/ProductVariant/21",
                    title = "M / Charcoal",
                    price = Money(18900, "USD"),
                ),
            ),
            checkoutEligible = false,
        ),
    )

    private val cart = listOf(
        CartLine(
            variantId = "gid://shopify/ProductVariant/11",
            productTitle = "Trail Runner",
            variantTitle = "9 / Blue",
            unitPrice = Money(12999, "USD"),
            platformId = "shopify",
            shopDomain = "example.myshopify.com",
            quantity = 1,
        ),
        CartLine(
            variantId = "gid://shopify/ProductVariant/21",
            productTitle = "Wool Overshirt",
            variantTitle = "M / Charcoal",
            unitPrice = Money(18900, "USD"),
            platformId = "shopify",
            shopDomain = "example.myshopify.com",
            quantity = 2,
        ),
    )

    @PreviewTest
    @Preview(name = "1-search", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Search() {
        DynamicTheme(darkTheme = false) {
            AppScaffold(title = "Shop", scrollBehavior = appBarScrollBehavior()) { padding ->
                SearchContent(
                    query = "trail shoes",
                    onQueryChange = {},
                    searching = false,
                    results = products,
                    onSearch = {},
                    onProductClick = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
        }
    }

    @PreviewTest
    @Preview(name = "2-cart", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Cart() {
        DynamicTheme(darkTheme = false) {
            AppScaffold(title = "Cart", scrollBehavior = appBarScrollBehavior()) { padding ->
                CartContent(cart = cart, modifier = Modifier.fillMaxSize().padding(padding))
            }
        }
    }
}
