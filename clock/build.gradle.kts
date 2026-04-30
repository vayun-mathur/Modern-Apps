plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260430
        versionName = "v2.4.3"
        applicationId = "com.vayunmathur.clock"
    }
}
dependencies {
    implementRoom(libs)
}
