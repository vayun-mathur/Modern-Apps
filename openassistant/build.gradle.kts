plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260625
        versionName = "v2.5.6"
        applicationId = "com.vayunmathur.openassistant"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    packaging {
        jniLibs {
            pickFirsts.add("**/libLiteRtTopKOpenClSampler.so")
            pickFirsts.add("**/libc++_shared.so")
        }
    }
}

dependencies {

    // room
    implementRoom(libs)

    // adaptive navigation
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)

    // display images
    implementation(libs.coil.compose)

    // ai
    implementation(libs.litertlm.android)

    implementation(project(":library:downloadservice"))
}