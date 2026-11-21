plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:openassistant:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "robot_2"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.openassistant"
    }

    packaging {
        jniLibs {
            pickFirsts.add("**/libc++_shared.so")
        }
    }
}

// This module has no Rust of its own, but :library:ml registers
// libmodelrunner (rustNativeLib), so every unit test here has kept
// forkEvery = 1. Only the classes below actually load that library:
// they execute a path that touches MlNative.isAvailable
// (System.loadLibrary("modelrunner") via NativeLoader), through
// Gemma4Handle.inDirectory (MamlChatEngine's real model opener),
// Gemma4VisionHandle / Gemma4AudioHandle.inDirectory (the multimodal
// towers MamlChatEngine opens for image/audio turns, even when its
// text model is a fake), or InferenceService's onCreate pre-warm with
// the default (real) engine factory. Tests that merely reference
// Gemma4Handle.Turn / ToolDeclaration, call the pure
// declareGemma4Tools / parseGemma4ToolCall helpers, or inject a fake
// text model / fake engine never load the library and stay in the
// main task. Quarantine the loaders and the shared-JVM polluters
// below into testDevUnitTestNative (forkEvery = 1); the remaining
// classes batch 20-per-JVM in testDevUnitTest, which depends on the
// quarantine task so the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.openassistant.platform.MamlAndAssistTest",
            "com.vayunmathur.openassistant.platform.MamlChatEngineFakeModelTest",
            "com.vayunmathur.openassistant.util.InferenceServiceTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // DataStoreUtils is a process-wide singleton whose init eagerly
    // collects the preferences DataStore, and AssistantViewModel turns
    // repository flows into viewModelScope stateIn collectors
    // (SharingStarted.WhileSubscribed) that are never cleared when the
    // test never calls onCleared; its init also launches
    // viewModelScope IO coroutines that call
    // DataStoreUtils.getInstance, start InferenceService, and delete
    // files under the external files dir. Same hazard for tests that
    // launch MainActivity (which builds the ViewModel), use the
    // OpenAssistantRepository.get process-wide singleton (and its
    // cached Room database) directly, or build the real
    // InferenceService: its serviceScope job loop, onCreate engine
    // pre-warm, DataStore-backed system-prompt read, and the
    // process-wide InferenceService.halt / newTitle statics outlive
    // the test, and InferenceServiceModelLoadTest deletes and
    // rewrites the shared model directory. Audit rule: any test
    // instantiating AssistantViewModel (or launching MainActivity,
    // which builds one), using OpenAssistantRepository.get directly,
    // or building InferenceService belongs here. All other classes
    // batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.openassistant.MainActivityAndDeviceToolsTest",
            "com.vayunmathur.openassistant.data.RepositoryAndSettingsBindingTest",
            "com.vayunmathur.openassistant.ui.BubbleInputSurfaceExtraTest",
            "com.vayunmathur.openassistant.ui.LiteRTChatUiTest",
            "com.vayunmathur.openassistant.util.AssistantViewModelFakeDataTest",
            "com.vayunmathur.openassistant.util.AssistantViewModelTest",
            "com.vayunmathur.openassistant.util.InferenceServiceFakeEngineTest",
            "com.vayunmathur.openassistant.util.InferenceServiceHostMarkerTest",
            "com.vayunmathur.openassistant.util.InferenceServiceModelLoadTest",
        )
    )
}

dependencies {

    // room
    implementRoom(libs)
    implementation(project(":library:room"))

    // adaptive navigation
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)

    // display images
    implementation(project(":library:image"))

    // ai
    // Gemma 4 chat runs on `:library:ml`'s Vulkan runtime (Gemma4Handle).
    implementation(project(":library:ml"))
    implementation(libs.kotlinx.coroutines.android)

    implementation(project(":library:downloadservice"))

    // web search/reading via api.maproj.org (Phase W)
    implementation(project(":library:network"))

    // generative UI: the model emits A2UI components, rendered natively in chat
    implementation(libs.androidx.a2ui.model)
    implementation(libs.androidx.a2ui.engine)
    implementation(libs.kotlinx.serialization.json)

    // Mock Context/PackageManager for cross-app intent tests.
    testImplementation(libs.mockito.core)
}
