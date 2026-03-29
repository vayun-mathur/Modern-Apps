plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260329
        versionName = "v2.2.3"
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    // Glance app widget
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
}