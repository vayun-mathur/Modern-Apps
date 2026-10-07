plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:speech:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "record_voice_over"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.speech"
    }

    androidResources {
        // Load-bearing, not an optimisation. `AssetManager.openFd` throws for a deflated entry,
        // and the fd is how Supertonic's ~105 MB and whisper-base's 70.6 MiB of weights reach the
        // GPU without being copied through the Java heap three times - see
        // SupertonicSynthesizer.inAssets and WhisperHandle.inAssets. It costs nothing on download
        // size either: int8 and fp16 weights are already incompressible.
        noCompress += "maml"
    }
}
dependencies {
    // Both directions run on :library:ml, our own Vulkan compute runtime, and this app links no
    // third-party inference runtime at all.
    //
    // Text-to-speech is Supertonic 3: four networks for all 31 languages, every one of them on the
    // GPU - the duration predictor, the text encoder, a flow-matching sampler and a ConvNeXt
    // vocoder. Speech-to-text is whisper-base, one 70.6 MiB `.maml` holding a 6-layer audio encoder
    // and a 6-layer KV-cached decoder, with the decode loop in `post::whisper`.
    //
    // Both bundles ship in `assets/` and are streamed from a file descriptor, which is what
    // `noCompress += "maml"` above is for.
    implementation(project(":library:ml"))
    // No `:library:downloadservice` and no DataStore: both models ship in the APK, so this app
    // downloads nothing and stores no preferences. They went when Piper's 1,834 MB of voices did.
}
