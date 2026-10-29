plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:shop:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "shopping_bag"
}

android {
    defaultConfig {
        versionCode = 20261028
        versionName = "v2.6.11b"
        applicationId = "com.vayunmathur.shop"
    }
}

dependencies {
    // Shopify UCP endpoints (myshopify.com, catalog.shopify.com, cdn.shopify.com)
    // serve public-CA chains: STANDARD, not FIRST_PARTY.
    implementation(project(":library:network"))
    // Product images over the platform HTTP stack.
    implementation(project(":library:image"))
    // Hosted-checkout fallback (continue_url) in a Custom Tab.
    implementation(libs.androidx.browser)
}
