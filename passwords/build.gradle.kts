plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260513
        versionName = "v2.4.5"
        minSdk = 35
        applicationId = "com.vayunmathur.passwords"
    }
}

dependencies {
    implementation(project(":library:biometric"))
    implementRoom(libs)
    implementation(libs.androidx.fragment.ktx)
}