plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260411
        versionName = "v2.3.1"
        applicationId = "com.vayunmathur.clock"
    }
}
dependencies {
    implementRoom(libs)
}
