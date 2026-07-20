plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "sync"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.everysync"

        // OAuth2 configuration. These are ALL public values (client IDs are
        // identifiers, not secrets) so they can be committed and the build stays
        // reproducible. Override per-build with a -PEVERYSYNC_* gradle property.
        // No client secret is ever shipped: confidential secrets (Withings,
        // optionally Samsung) live behind a *_TOKEN_PROXY_URL backend relay.
        val googleClientId = (project.findProperty("EVERYSYNC_GOOGLE_CLIENT_ID")
            ?: "827025129169-tvph1v4c7b4n36s0prbe3u8dh3kd12t7.apps.googleusercontent.com").toString()

        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$googleClientId\"")
        // The OAuth redirect is the app-id custom scheme (com.vayunmathur.everysync:/oauth),
        // caught by OAuthCallbackActivity — see OAuthConfig.REDIRECT_URI. It is NOT
        // derived from the client ID.
    }
    buildFeatures {
        buildConfig = true
    }
}

metadataScreenshots {
    permissions.addAll(
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
        "android.permission.GET_ACCOUNTS",
    )
}

dependencies {
    // Custom Tabs for OAuth PKCE flow
    implementation(libs.androidx.browser)

    // Health Connect (Withings + Samsung/Google Health bridge)
    implementation(libs.androidx.connect.client)

    // Background sync
    implementation(libs.androidx.work.runtime.ktx)

    // ktor client (custom WebDAV methods + JSON)
    implementation(project(":library:network"))

    // Material icons (Default.* + AutoMirrored)
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation(libs.junit)
}
