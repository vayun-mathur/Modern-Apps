plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260329
        versionName = "v2.2.3"
        applicationId = "com.vayunmathur.contacts"
    }
}

dependencies {
    // External Libraries
    implementation(libs.libphonenumber)
}