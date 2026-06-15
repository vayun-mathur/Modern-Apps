plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260615
        versionName = "v2.5.4"
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    implementation(project(":library:widgets"))
}