plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260530
        versionName = "v2.5.0"
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    implementation(project(":library:widgets"))
}