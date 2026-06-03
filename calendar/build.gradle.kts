plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260603
        versionName = "v2.5.1"
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    implementation(project(":library:widgets"))
}