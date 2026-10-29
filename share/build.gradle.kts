plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "share"
}

android {
    defaultConfig {
        versionCode = 20261028
        versionName = "v2.6.11b"
        applicationId = "com.vayunmathur.share"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Rust protocol crate (UKEY2, protobuf framing, secure messages, payload).
// See share/src/main/rust/ and share/PROTOCOL_CONTRACT.md.
rustNativeLib("share_nearby", "share")

dependencies {
    // ViewModel/Compose already via common-conventions-app; explicit lifecycle for clarity.
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // @SystemApi NetworkAgent/NetworkProvider for the Mosey `mosey0` AirDrop link; compile-only,
    // must NOT be packaged. See :library:aosp-stubs.
    compileOnly(project(":library:aosp-stubs"))
}
