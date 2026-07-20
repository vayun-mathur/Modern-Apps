plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "ecg_heart"
}

android {
    defaultConfig {
        versionCode = 20260718
        versionName = "v2.6.0"
        applicationId = "com.vayunmathur.health"
    }
}

dependencies {
    // healthconnect
    implementation(libs.androidx.connect.client)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.fhir.model)

    // room
    implementRoom(libs)
    implementation(project(":library:room"))

    implementation(project(":library:network"))
}