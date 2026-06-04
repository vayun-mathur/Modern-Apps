plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260604
        versionName = "v2.5.2"
        applicationId = "com.vayunmathur.weather"
    }
}

dependencies {
    implementation(project(":library:network"))
    implementation(project(":library:widgets"))
    implementation(libs.androidx.datastore.preferences)
    implementRoom(libs)
}
