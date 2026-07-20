plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "mail"
}

android {
    namespace = "com.vayunmathur.email"
    defaultConfig {
        versionCode = 20260718
        versionName = "v2.6.0"
        applicationId = "com.vayunmathur.email"

        // Outlook / Microsoft 365 OAuth. Public values (client ID is an
        // identifier, not a secret) so they're committed and the build stays
        // reproducible. Override with -PEVERYSYNC_* style properties if needed.
        val outlookClientId = (project.findProperty("EMAIL_OUTLOOK_CLIENT_ID")
            ?: "4ee55fe9-12c1-4392-82e6-6c7a2a7954c8").toString()
        buildConfigField("String", "OUTLOOK_OAUTH_CLIENT_ID", "\"$outlookClientId\"")
        buildConfigField("String", "OUTLOOK_REDIRECT_URI", "\"com.vayunmathur.email://oauth\"")
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
            // Ensure JavaMail provider descriptors are kept
            pickFirsts += "META-INF/javamail.providers"
            pickFirsts += "META-INF/javamail.default.providers"
            pickFirsts += "META-INF/javamail.default.address.map"
            pickFirsts += "META-INF/mailcap"
            pickFirsts += "META-INF/mailcap.default"
        }
    }
}

dependencies {
    implementation(libs.jakarta.mail)
    implementation(libs.jakarta.activation)
    implementation(libs.androidx.browser)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(project(":library:room"))
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(project(":library:widgets"))
}
