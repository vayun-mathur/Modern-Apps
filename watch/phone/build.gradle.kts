plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260706
        versionName = "v2.5.7"
        applicationId = "com.vayunmathur.watch.phone"
        minSdk = 34
    }
}

dependencies {
    implementation(project(":watch:shared"))
    implementation(libs.androidx.connect.client)
    implementation(libs.androidx.work.runtime.ktx)
    implementRoom(libs)
    testImplementation("junit:junit:4.13.2")
}
