plugins {
    id("common-conventions-app")
    id("com.google.devtools.ksp")
}

android {
    defaultConfig {
        versionCode = 20260620
        versionName = "v2.5.5"
        applicationId = "com.vayunmathur.contacts"
    }
}

dependencies {
    implementation(project(":library:ui"))

    // External Libraries
    implementation(libs.libphonenumber)
    implementation(libs.androidx.work.runtime.ktx)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
