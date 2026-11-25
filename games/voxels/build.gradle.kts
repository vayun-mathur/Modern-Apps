plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
    // Preview-metadata for Layoutlib-renderable Compose ONLY (menus/inventory/
    // settings overlays in src/screenshotTest). The game screen is a Vulkan
    // SurfaceView driven by the Rust engine below: Layoutlib can render the
    // Compose chrome but never the world behind it, so no game render is
    // previewed or faked — see MetadataPreviews.kt for the documented exception.
}

launcherIcon {
    symbol = "view_in_ar"
}

android {
    defaultConfig {
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
rustNativeLib("voxels_engine", "voxels", shaders = listOf("*.vert", "*.frag"))

dependencies {
    implementation(project(":sdk:games"))
    implementation(project(":library:network"))
    implementation(project(":library:e2ee-p2p"))
}
