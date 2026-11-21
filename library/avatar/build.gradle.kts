plugins {
    id("common-conventions-library")
}

android {
    // Detached-fd asset handoff (YuuAvatarAssets): the .glb must stay stored
    // uncompressed so openFd() maps it. Same arrangement as :library:ml.
    aaptOptions {
        noCompress += ".glb"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

dependencies {
    implementation(project(":library:ui"))
    implementation(libs.androidx.lifecycle.runtime.compose)
}

// Yuu 3D avatar engine: `library/avatar/src/main/rust`, glTF upper-body base
// mesh, skeletal skinning, preset poses, Vulkan-first + GLES fallback.
//
// Same arrangement as :library:map: the crate builds into rustJniLibs and
// YuuAvatarNative.isAvailable gates every call, so devices without the .so
// keep the 2D YuuFace preview. arm64 only (map precedent).
rustNativeLib(
    "yuu_avatar",
    "avatar",
    // yuu_avatar_gles.* are GLSL ES, compiled at run time by the GLES path.
    shaders = listOf("yuu_avatar.vert", "yuu_avatar.frag"),
)

// Only YuuPoseTest never loads a native library (the pure pose gallery).
// Every other class reaches YuuAvatarNative: YuuAvatarNativeTest drives
// the JNI bridge directly against the host libyuu_avatar, YuuAvatarAssetsTest
// goes through adoptBundledMesh/handOff (both gated on
// YuuAvatarNative.isAvailable, and handOff forwards the fd natively), and
// the components tests compose YuuAvatar3D / drive YuuAvatarSurfaceView,
// whose first check is YuuAvatarNative.isAvailable. Quarantine those
// loaders into testDebugUnitTestNative (forkEvery = 1); YuuPoseTest
// batches 20-per-JVM in testDebugUnitTest, which depends on the
// quarantine task so the usual invocation still runs everything. No
// isolatedClasses: no DataStore/ViewModel or process-wide singleton here.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.library.avatar.YuuAvatarAssetsTest",
            "com.vayunmathur.library.avatar.YuuAvatarNativeTest",
            "com.vayunmathur.library.avatar.components.YuuAvatar3DLifecycleTest",
            "com.vayunmathur.library.avatar.components.YuuAvatar3DTest",
            "com.vayunmathur.library.avatar.components.YuuAvatarSurfaceViewTest",
        )
    )
}
