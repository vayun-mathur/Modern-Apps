plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260615
        versionName = "v2.5.4"
        applicationId = "com.vayunmathur.clock"
    }
}
dependencies {
    implementRoom(libs)
}
