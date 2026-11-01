package com.vayunmathur.globalesim.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.globalesim.data.api.NetworkDto
import com.vayunmathur.globalesim.data.api.ProfileDto
import com.vayunmathur.globalesim.data.api.RateDto
import com.vayunmathur.globalesim.platform.EsimUiState
import com.vayunmathur.library.ui.DynamicTheme

private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:globalesim`, rendered from Compose previews.
 * `./gradlew :globalesim:metadata` renders these into
 * `metadata_data/photos/globalesim/`. Literal state only — no ViewModel,
 * no network. Every preview needs both @PreviewTest and @Preview, must be
 * a class member, and is named Preview<N>… for listing order.
 */
class MetadataPreviews {
    private val demoProfile = ProfileDto(
        iccid = "8999201200000000001",
        status = "active",
        activationCode = "LPA:1\$demo.example\$CODE",
        smdpAddress = "demo.example",
        walletUsdCents = 1250,
    )
    private val demoRates = listOf(
        RateDto(
            "US", "United States", 87,
            networks = listOf(
                NetworkDto("T-Mobile USA", 87),
                NetworkDto("AT&T", 128),
                NetworkDto("Verizon Wireless", 165),
            ),
        ),
        RateDto(
            "JP", "Japan", 138,
            networks = listOf(
                NetworkDto("KDDI Corporation", 138),
                NetworkDto("Rakuten", 170),
            ),
        ),
        RateDto("FR", "France", 52),
        RateDto("GB", "United Kingdom", 95),
    )

    @PreviewTest
    @Preview(name = "1-purchase", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Purchase() {
        DynamicTheme(darkTheme = true) {
            HomeScreen(
                state = EsimUiState(statusLoaded = true, simId = "demo", profile = demoProfile),
                onStartCardPurchase = { _, _, _ -> },
                onStartCryptoPurchase = {},
                onClearCryptoInvoice = {},
                onClaim = {},
                onRefresh = {},
                // No Stripe launcher in Layoutlib (it needs a SavedStateRegistryOwner).
                onPresentPaymentSheet = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-pricing", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Pricing() {
        DynamicTheme(darkTheme = true) {
            PricingScreen(
                state = EsimUiState(rates = demoRates),
                onRefresh = {},
            )
        }
    }
}
