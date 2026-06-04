plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260604
        versionName = "v2.5.2"
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    implementation(project(":library:widgets"))
}