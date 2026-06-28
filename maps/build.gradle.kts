plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260625
        versionName = "v2.5.6"
        applicationId = "com.vayunmathur.maps"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

dependencies {
    implementation(libs.maplibre.compose)
    implementation(libs.coil.compose)

    implementation(project(":library:network"))

    implementation(libs.flatgeobuf)

    //reorderable
    implementation(libs.reorderable)

    // room
    implementRoom(libs)
    implementation(project(":library:downloadservice"))
}