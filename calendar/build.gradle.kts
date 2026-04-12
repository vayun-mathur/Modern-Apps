plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260411
        versionName = "v2.3.1"
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    // Glance app widget
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
}