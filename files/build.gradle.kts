plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260604
        versionName = "v2.5.2"
        applicationId = "com.vayunmathur.files"
    }
}

dependencies {
    implementation(project(":library:downloadservice"))
}
