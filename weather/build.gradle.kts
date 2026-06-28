plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260625
        versionName = "v2.5.6"
        applicationId = "com.vayunmathur.weather"
    }
}

dependencies {
    implementation(project(":library:network"))
    implementation(project(":library:widgets"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.reorderable)
    implementRoom(libs)
}
