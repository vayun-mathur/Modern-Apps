plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260513
        versionName = "v2.4.5"
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

    // ktor
    implementation(project(":library:network"))
}