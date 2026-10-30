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
