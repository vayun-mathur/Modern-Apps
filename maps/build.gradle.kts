plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260707
        versionName = "v2.5.7b"
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