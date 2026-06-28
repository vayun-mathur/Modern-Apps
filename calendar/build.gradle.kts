plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260625
        versionName = "v2.5.6"
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    implementation(project(":library:widgets"))
}