plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
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
            ?: "827025129169-1nm22b5uec77b3b7e0qjl0lah29g82h7.apps.googleusercontent.com").toString()

        // Google installed-app PKCE uses the reverse-DNS custom scheme derived from
        // the client ID (e.g. com.googleusercontent.apps.<id>:/oauth2redirect).
        val googleReverse = if (googleClientId.endsWith(".apps.googleusercontent.com"))
            "com.googleusercontent.apps." + googleClientId.removeSuffix(".apps.googleusercontent.com")
        else ""
        val googleRedirectUri = if (googleReverse.isNotBlank()) "$googleReverse:/oauth2redirect"
        else "com.vayunmathur.everysync:/oauth"

        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$googleClientId\"")
        buildConfigField("String", "GOOGLE_REDIRECT_URI", "\"$googleRedirectUri\"")

        // Register the Google reverse-DNS redirect scheme for OAuthCallbackActivity.
        manifestPlaceholders["googleRedirectScheme"] = googleReverse.ifBlank { "com.vayunmathur.everysync.oauthunused" }
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
