plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:openassistant:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "robot_2"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.openassistant"
    }

    packaging {
        jniLibs {
            pickFirsts.add("**/libc++_shared.so")
        }
    }
}

dependencies {

    // room
    implementRoom(libs)
    implementation(project(":library:room"))

    // adaptive navigation
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)

    // display images
    implementation(project(":library:image"))

    // ai
    // Gemma 4 chat runs on `:library:ml`'s Vulkan runtime (Gemma4Handle).
    implementation(project(":library:ml"))
    implementation(libs.kotlinx.coroutines.android)

    implementation(project(":library:downloadservice"))
}
