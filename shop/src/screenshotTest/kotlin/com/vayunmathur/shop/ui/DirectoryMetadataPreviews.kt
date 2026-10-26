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
import com.vayunmathur.shop.data.MerchantListing

class DirectoryMetadataPreviews {
    @PreviewTest
    @Preview(name = "3-merchants", group = "phone", widthDp = 360, heightDp = 780)
    @Composable
    fun Preview3Merchants() {
        DynamicTheme(darkTheme = false) {
            AppScaffold(title = "Merchants", scrollBehavior = appBarScrollBehavior()) { padding ->
                MerchantDirectoryContent(
                    rows = listOf(
                        MerchantListing("trail", "Trail Supply", "trail.example", "https://trail.example", directoryStatus = "live"),
                        MerchantListing("home", "Home Goods", "home.example", "https://home.example", directoryStatus = "pending"),
                    ),
                    query = "",
                    onQueryChange = {},
                    loaded = true,
                    loading = false,
                    checking = null,
                    error = null,
                    onLoad = {},
                    onUse = {},
                    onOpenSite = {},
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
        }
    }
}
