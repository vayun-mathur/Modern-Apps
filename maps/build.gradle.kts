plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
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

    implementKtor(libs)

    implementation("org.wololo:flatgeobuf:3.28.2")

    //reorderable
    implementation(libs.reorderable)

    // room
    implementRoom(libs)
}