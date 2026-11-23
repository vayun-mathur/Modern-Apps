plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "newspaper"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.news"
    }
}

dependencies {
    implementation(project(":library:network"))
    implementation(project(":library:image"))
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.androidx.sqlite.bundled.jvm)
}
