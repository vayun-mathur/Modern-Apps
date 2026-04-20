plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260419
        versionName = "v2.4.0"
        applicationId = "com.vayunmathur.notes"
    }
}

dependencies {
    implementRoom(libs)
}