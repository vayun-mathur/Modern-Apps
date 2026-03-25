plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260325
        versionName = "v2.2.2"
        applicationId = "com.vayunmathur.contacts"
    }
}

dependencies {
    // External Libraries
    implementation(libs.libphonenumber)
}