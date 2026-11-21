plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:code:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "code"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.code"
    }
}

// Native tree-sitter highlighter (Rust → libcode_ts.so, arm64-v8a). Built on every `code` build;
// TreeSitterNative loads it at runtime and highlights the 10 supported languages, with the regex
// tokenizer still covering the remaining languages. Requires the Android NDK +
// `rustup target add aarch64-linux-android`; the pinned grammar set is locked in
// code/src/main/rust/Cargo.lock.
androidComponents {
    onVariants { variant ->
        val rustDir = layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        variant.sources.jniLibs?.addStaticSourceDirectory(rustDir)
    }
}
rustNativeLib("code_ts", "code")

// Only the classes below actually load a native library: they execute a
// path that touches TreeSitterNative (libcode_ts via NativeLoader) —
// directly, through treeSitterColorSpans, or through CodeEditor /
// CodeEditorView's rememberTreeSitterSpans when a tabbed EditorScreen /
// EditorBody / SplitEditors composition runs — or they drive GitRepo
// (JGit) against real on-disk repositories. TreeSitterNativeTest and
// EditorChromeLogicTest are plain JVM tests, so mixing them in one JVM
// with the Robolectric sandbox loaders binds libcode_ts to two
// classloaders. Quarantine the loaders and the shared-JVM polluters
// below into testDevUnitTestNative (forkEvery = 1); the remaining
// classes batch 20-per-JVM in testDevUnitTest, which depends on the
// quarantine task so the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.code.ui.CodeEditorUiTest",
            "com.vayunmathur.code.ui.EditorChromeLogicTest",
            "com.vayunmathur.code.ui.EditorScreenUiTest",
            "com.vayunmathur.code.ui.Wave2TreeScreenUiTest",
            "com.vayunmathur.code.util.GitLocalRemoteTest",
            "com.vayunmathur.code.util.GitPushTest",
            "com.vayunmathur.code.util.GitRepoTest",
            "com.vayunmathur.code.util.TreeSitterNativeTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // EditorPrefs is the module's only DataStore owner (the top-level
    // preferencesDataStore delegate for "code_editor"), and
    // EditorViewModel init launches ~20 viewModelScope collectors that
    // read its flows (several setters then persist through it), never
    // cleared when the test never calls onCleared; EditorPrefs also
    // holds the process-wide sealerForTest static that CodeTokenSealing /
    // EditorPrefs tests swap. Persisted DataStore state (folder, session
    // tabs, snippets, git credentials — SessionRestoreTest even cleans
    // up explicitly "so later VMs in this process start empty") leaks
    // into classes expecting defaults. Audit rule: any test
    // instantiating EditorViewModel (or launching a screen that builds
    // one), or using EditorPrefs directly, belongs here. Several of
    // these UI classes also render the editor and would load
    // libcode_ts anyway. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.code.data.CodeTokenSealingTest",
            "com.vayunmathur.code.ui.EditorPagesUiTest",
            "com.vayunmathur.code.ui.EditorScreenDialogsUiTest",
            "com.vayunmathur.code.ui.ExperimentalAndPagesUiTest",
            "com.vayunmathur.code.ui.GitPageUiTest",
            "com.vayunmathur.code.ui.MainAndExpandedUiTest",
            "com.vayunmathur.code.ui.MoreInteractionsUiTest",
            "com.vayunmathur.code.ui.PreviewAndMinimapBehaviourTest",
            "com.vayunmathur.code.ui.RepoAndBodyUiTest",
            "com.vayunmathur.code.ui.SearchPreviewUiTest",
            "com.vayunmathur.code.ui.Wave2EditorFindUiTest",
            "com.vayunmathur.code.ui.Wave2GitToolbarUiTest",
            "com.vayunmathur.code.ui.Wave2MoreUiTest",
            "com.vayunmathur.code.ui.Wave3FinalPushTest",
            "com.vayunmathur.code.util.EditorActionsTest",
            "com.vayunmathur.code.util.EditorFileTreeOpsBehaviourTest",
            "com.vayunmathur.code.util.EditorFileTreeOpsTest",
            "com.vayunmathur.code.util.EditorPrefsTest",
            "com.vayunmathur.code.util.EditorSearchOpsTest",
            "com.vayunmathur.code.util.EditorViewModelTest",
            "com.vayunmathur.code.util.LoadFailureTest",
            "com.vayunmathur.code.util.SessionRestoreTest",
            "com.vayunmathur.code.util.VmOpsTest",
            "com.vayunmathur.code.util.Wave2VmActionsTest",
        )
    )
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.jgit)
    implementation(libs.androidx.webkit)
}
