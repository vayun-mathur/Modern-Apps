plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260321
        versionName = "v2.2.1"
        applicationId = "com.vayunmathur.contacts"
    }
}

dependencies {
    // External Libraries
    implementation(libs.libphonenumber)
}