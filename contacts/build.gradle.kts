plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.contacts"
    }
}

dependencies {
    // External Libraries
    implementation(libs.libphonenumber)
}