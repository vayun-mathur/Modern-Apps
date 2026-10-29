plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:kart:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "directions_car"
}

android {
    defaultConfig {
        versionCode = 20261028
        versionName = "v2.6.11b"
        applicationId = "com.vayunmathur.games.kart"
    }
}

// ONN 4K box (32-bit armeabi-v7a userspace, Mali-G31, no Vulkan): the GLES
// fallback in src/main/rust/gles renders 3D there. Same arrangement as
// :cast:tv + :library:e2ee-p2p (the only armv7 precedent in the tree).
nativeAbis {
    armv7 = true
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native 3D racer engine (Sunset Oval track, kart physics, chase cam, items +
// AI). Same arrangement as games/voxels: the crate builds into rustJniLibs and
// KartNative.isAvailable gates every call, so devices without the .so run the
// placeholder race. Listing images are hand-captured (Layoutlib can't render
// the Vulkan surface); see the plan's verification notes.
// armeabi-v7a as well as arm64: the ONN box runs this app directly on 32-bit
// Google TV hardware. Only kart_engine opts in (:library:e2ee-p2p precedent) —
// no other native module changes.
rustNativeLib("kart_engine", "kart", extraAbis = listOf(ABI_ARMV7))

dependencies {
    implementation(project(":sdk:controller"))
    implementation(project(":library:e2ee-p2p"))
    implementation(libs.androidx.datastore.preferences)
}
