plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260603
        versionName = "v2.5.1"
        applicationId = "com.vayunmathur.photos"
    }
    packaging {
        jniLibs {
            pickFirsts.add("**/libc++_shared.so")
            pickFirsts.add("**/libtensorflowlite_jni.so")
            pickFirsts.add("**/libtensorflowlite_gpu_jni.so")
        }
    }
}

dependencies {
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.maplibre.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.androidx.exifinterface)

    implementRoom(libs)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose.material3)


    implementation(project(":library:downloadservice"))
    implementation(project(":library:widgets"))
    implementation(project(":library:biometric"))

    implementation(libs.litert)
}