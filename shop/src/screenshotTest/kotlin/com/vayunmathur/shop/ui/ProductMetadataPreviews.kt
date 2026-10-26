package com.vayunmathur.shop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.ProductVariant
import com.vayunmathur.shop.data.UcpProduct

/** Immutable metadata fixture: no ViewModel, remote images, initialization or network. */
class ProductMetadataPreviews {
    private val product = UcpProduct(
        id = "fixture-product",
        title = "Trail Runner",
        description = "Lightweight shoes for everyday trails.",
        descriptionHtml = "<p><b>Ready for the trail.</b> Lightweight shoes with a grippy sole.</p>" +
            "<p><i>Recycled mesh</i> keeps every mile comfortable. " +
            "<a href=\"https://trail.example/care\">Care guide</a></p>",
        priceRange = Money(12999, "USD"),
        platformId = "shopify",
        shopDomain = "catalog.example",
        storeName = "Trail Collective",
        checkoutEligible = true,
        variants = listOf(
            ProductVariant(
                id = "fixture-blue",
                title = "9 / Blue",
                price = Money(12999, "USD"),
                available = true,
                storeName = "Trail Outfitters",
                shopDomain = "trail.example",
            ),
            ProductVariant(
                id = "fixture-green",
                title = "10 / Green",
                price = Money(12999, "USD"),
                available = true,
                storeName = "Trail Outfitters",
                shopDomain = "trail.example",
            ),
        ),
    )

    @PreviewTest
    @Preview(name = "5-product", group = "phone", widthDp = 360, heightDp = 780)
    @Composable
    fun Preview5Product() {
        DynamicTheme(darkTheme = false) {
            AppScaffold(title = product.title, scrollBehavior = appBarScrollBehavior()) { padding ->
                ProductDetailContent(
                    product = product,
                    selectedVariantId = "fixture-blue",
                    quantity = 2,
                    loading = false,
                    verified = true,
                    failure = null,
                    onSelectVariant = {},
                    onQuantityChange = {},
                    onRetry = {},
                    onAddToCart = {},
                    onBuyNow = {},
                    onOpenUrl = {},
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
        }
    }
}
