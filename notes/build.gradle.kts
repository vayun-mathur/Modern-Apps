plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260416
        versionName = "v2.3.3"
        applicationId = "com.vayunmathur.notes"
    }
}

dependencies {
    implementRoom(libs)
}