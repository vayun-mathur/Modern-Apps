plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "hourglass"
}

android {
    defaultConfig {
        versionCode = 20261021
        versionName = "v2.6.10"
        applicationId = "com.vayunmathur.screentime"
    }
}

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(project(":library:widgets"))
}
