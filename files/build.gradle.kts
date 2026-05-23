plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260523
        versionName = "v2.4.6"
        applicationId = "com.vayunmathur.files"
    }
}

dependencies {
    implementation(project(":library:downloadservice"))
}
