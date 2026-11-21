plugins {
    id("common-conventions-library")
}

// This module has no rustNativeLib of its own: the native load arrives
// transitively from :library:ml (libmodelrunner), which is what puts this
// module's tests on the forkEvery = 1 path at all. Only OcrEngineTest
// actually loads it: OcrEngine.isAvailable/recognize construct a
// TextRecognizer (in :library:ml), whose init reads MlNative.isAvailable —
// the System.loadLibrary("modelrunner") attempt. OcrMappingTest only
// exercises the pure OcrEngine.linesToResult mapping and never constructs
// an engine or a recogniser. Quarantine the loader into
// testDebugUnitTestNative (forkEvery = 1); OcrMappingTest batches
// 20-per-JVM in testDebugUnitTest, which depends on the quarantine task
// so the usual invocation still runs everything. No isolatedClasses: no
// DataStore/ViewModel or process-wide singleton here.
rustNativeTests {
    nativeClasses.add("com.vayunmathur.library.ocr.OcrEngineTest")
}

dependencies {
    // On-device OCR via PP-OCRv5 on the Vulkan compute runtime in `:library:ml`. Both
    // models ship as `.maml` in this module's assets and the whole detect-crop-recognise
    // pipeline is Rust; consumers only see the OcrEngine API.
    //
    // `implementation` is enough even though `:library:ml` supplies `libmodelrunner.so`:
    // an Android library's jniLibs are packaged into the consuming APK transitively, and
    // OcrEngine maps `RecognizedLine` into its own TextBox rather than re-exporting it.
    implementation(project(":library:ml"))
    implementation(libs.kotlinx.coroutines.android)
}
