plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260706
        versionName = "v2.5.7"
        applicationId = "com.vayunmathur.web"
    }
}

dependencies {
    implementation(libs.geckoview)
    implementation(libs.jsoup)
    implementRoom(libs)
}
