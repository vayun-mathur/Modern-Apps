plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260620
        versionName = "v2.5.5"
        applicationId = "com.vayunmathur.web"
    }
}

dependencies {
    implementation(project(":library:ui"))
    implementation(libs.geckoview)
    implementation(libs.jsoup)
    implementRoom(libs)
}
