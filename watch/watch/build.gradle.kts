plugins {
    id("common-conventions-wear")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.watch.watch"
    }
    buildTypes {
        // The dev build inherits the release arm64-v8a abiFilter, but this app has
        // no native libraries and the Pixel Watch 2 is 32-bit (armeabi-v7a). Clear
        // the filter so the dev APK is universal and installable on the watch.
        getByName("dev") {
            ndk {
                abiFilters.clear()
            }
        }
    }
}

dependencies {
    implementation(project(":watch:shared"))
    implementation(libs.androidx.health.services.client)
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.material)
    implementation(libs.androidx.wear.protolayout.expression)
    implementation(libs.androidx.concurrent.futures)
    implementRoom(libs)
}
