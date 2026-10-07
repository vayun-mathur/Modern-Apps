plugins {
    id("common-conventions-app")
    // No metadata convention. The game screen is a Vulkan SurfaceView driven by the Rust
    // engine below, with Compose only as a HUD on top, so Layoutlib can render the joystick
    // and hotbar but never the world behind them. The listing images are hand-captured and
    // committed under metadata_data/photos/, which release.sh picks up directly.
}

launcherIcon {
    symbol = "view_in_ar"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.games.voxels"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Full Rust + Vulkan engine (ash) with Matcha texture atlas (16 textures → 64x64)
rustNativeLib("voxels_engine", "voxels")

dependencies {
    implementation(project(":sdk:games"))
    implementation(project(":library:network"))
    implementation(project(":library:e2ee-p2p"))
}
