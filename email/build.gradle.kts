plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "mail"
}

android {
    namespace = "com.vayunmathur.email"
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.email"

        // Outlook OAuth — own Azure public client (PKCE, no secret)
        val outlookClientId = (project.findProperty("EMAIL_OUTLOOK_CLIENT_ID")
            ?: "4ee55fe9-12c1-4392-82e6-6c7a2a7954c8").toString()

        buildConfigField("String", "OUTLOOK_OAUTH_CLIENT_ID", "\"$outlookClientId\"")
        buildConfigField("String", "OAUTH_REDIRECT_URI", "\"com.vayunmathur.email://oauth\"")
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
        }
    }
}

dependencies {
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.serialization.json)
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(project(":library:widgets"))
    implementation(project(":library:network"))
}
