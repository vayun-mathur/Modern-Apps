plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260513
        versionName = "v2.4.5"
        applicationId = "com.vayunmathur.files"
    }
}

dependencies {
    implementation(project(":library:downloadservice"))
}
