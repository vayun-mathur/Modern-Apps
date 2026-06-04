plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260604
        versionName = "v2.5.2"
        minSdk = 35
        applicationId = "com.vayunmathur.passwords"
    }
}

dependencies {
    implementation(project(":library:biometric"))
    implementRoom(libs)
    implementation(libs.androidx.fragment.ktx)
}