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
// This module has no rustNativeLib of its own: the native load arrives
// transitively from :library:ml (libmodelrunner via MlNative), which is
// what puts this module's tests on the forkEvery = 1 path at all. Only
// the classes below actually load it: WhisperModelAndEngineTest and
// SupertonicEngineAndBundleTest drive the real engines' ensure() paths
// (WhisperHandle.inAssets / SupertonicSynthesizer.inAssets, both gated
// on MlNative.isAvailable), and the two service tests build the real
// services, whose onCreate threads call engine.preload() on the real
// engine (the fake-engine tests in the same files never reach ensure,
// but a class cannot be split below class granularity). Quarantine
// those loaders into testDevUnitTestNative (forkEvery = 1); the
// remaining classes batch 20-per-JVM in testDevUnitTest, which depends
// on the quarantine task so the usual invocation still runs everything.
// No isolatedClasses: this app has no DataStore and no ViewModel (see
// the dependencies note below), the WhisperRecognitionSessionTest fake
// engine overrides every native entry point, and SupertonicBundle's
// process-wide isPresent cache returns the same asset-derived answer
// for every caller — its own test already tolerates a warm cache.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.speech.platform.SupertonicEngineAndBundleTest",
            "com.vayunmathur.speech.platform.WhisperModelAndEngineTest",
            "com.vayunmathur.speech.service.SupertonicTtsServiceTest",
            "com.vayunmathur.speech.service.WhisperRecognitionServiceTest",
        )
    )
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
