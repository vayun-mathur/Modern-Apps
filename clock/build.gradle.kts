android {
    defaultConfig {
        versionCode = 20260317
        versionName = "v2.2.0"
        applicationId = "com.vayunmathur.clock"
    }
}
dependencies {
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.kotlin.csv.jvm)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.room.compiler)
}
