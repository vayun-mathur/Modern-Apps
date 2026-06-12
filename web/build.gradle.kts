plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260612
        versionName = "v2.5.3"
        applicationId = "com.vayunmathur.web"
    }
}

dependencies {
    implementation(libs.geckoview)
    implementation(libs.jsoup)
    implementRoom(libs)
}
