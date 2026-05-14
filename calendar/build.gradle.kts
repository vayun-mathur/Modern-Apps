plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260513
        versionName = "v2.4.5"
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    implementation(project(":library:widgets"))
}