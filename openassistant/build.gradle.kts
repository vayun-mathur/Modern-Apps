plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260430
        versionName = "v2.4.3"
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

    // markdown
    implementation(libs.compose.markdown)
}