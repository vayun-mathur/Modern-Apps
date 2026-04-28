plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260427
        versionName = "v2.4.2"
        applicationId = "com.vayunmathur.notes"
    }
}

dependencies {
    implementRoom(libs)
}