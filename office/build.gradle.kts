plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:<module>:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "description"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.office"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native ODF formula engine + document CRDT (Rust). See office/src/main/rust/.
rustNativeLib("office_engine", "office")

// Only the classes below actually load a native library: they touch
// OfficeNative (liboffice_engine: workbook/CRDT handles, or the XLSX
// importer's native number formatter via OoxmlXlsx.formatDisplayValue),
// DocumentTreeCrdt over it, or the PQC stack in :library:e2ee-p2p
// (libe2ee_pqc via Pqc / PqcIdentity, reached through OfficeSync's
// seal/sign helpers). Quarantine them into testDevUnitTestNative
// (forkEvery = 1); the remaining classes batch 20-per-JVM in
// testDevUnitTest, which depends on the quarantine task so the usual
// invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.office.data.odf.OoxmlDirectSliceTest",
            "com.vayunmathur.office.data.odf.OoxmlImporterTest",
            "com.vayunmathur.office.data.odf.OoxmlRoundTripTest",
            "com.vayunmathur.office.data.odf.OoxmlXlsxSliceTest",
            "com.vayunmathur.office.data.odf.W4FinalParserTest",
            "com.vayunmathur.office.data.odf.W4OdfPackageFormatsTest",
            "com.vayunmathur.office.data.odf.W5XlsxKotlinFormatTest",
            "com.vayunmathur.office.platform.OfficeDocTombstoneTest",
            "com.vayunmathur.office.platform.OfficeSharingE2ETest",
            "com.vayunmathur.office.platform.OfficeViewModelLiveSlice3Test",
            "com.vayunmathur.office.platform.OfficeViewModelSyncSlice3Test",
            "com.vayunmathur.office.platform.Slice4SyncLogicTest",
            "com.vayunmathur.office.platform.W4ShareOpenOnlineTest",
            "com.vayunmathur.office.platform.W4SyncDocMergeTest",
            "com.vayunmathur.office.platform.W4SyncRelayTest",
            "com.vayunmathur.office.ui.Slice4SheetsTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // every OfficeViewModel start touches the process-wide DataStoreUtils
    // singleton in init (hasOnlineIdentity) and starts Eagerly viewModelScope
    // collectors that are never cleared when the test never calls
    // onCleared; loadSettings/saveSettings with the default-on auto-save
    // additionally start an infinite delay loop on viewModelScope (the
    // known hang shape when batched). Audit rule: any test instantiating
    // OfficeViewModel (harness newVm/idleVm/loadedVm or a real
    // ApplicationProvider construction), launching MainActivity (which
    // builds one), or mocking/replacing the DataStoreUtils singleton
    // belongs here. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.office.Slice4BottomBarPaneTest",
            "com.vayunmathur.office.Slice4DocOverlaysTest",
            "com.vayunmathur.office.Slice4FindBarsTest",
            "com.vayunmathur.office.Slice4MenuBarOnlineTest",
            "com.vayunmathur.office.Slice4MenusOverlaysTest",
            "com.vayunmathur.office.Slice4OverlaysTest",
            "com.vayunmathur.office.Slice4Pass2CropOverlaysTest",
            "com.vayunmathur.office.Slice4Pass2MainActivityTest",
            "com.vayunmathur.office.Slice4Pass2MenusOverlaysTest",
            "com.vayunmathur.office.Slice4Pass2OnlineHomeTest",
            "com.vayunmathur.office.Slice4Pass2PaneExportTest",
            "com.vayunmathur.office.Slice4ScreenTest",
            "com.vayunmathur.office.Slice4ShellTest",
            "com.vayunmathur.office.W4DocumentScreenRichTest",
            "com.vayunmathur.office.W4FindBarsInteractTest",
            "com.vayunmathur.office.W4MainActivityTest",
            "com.vayunmathur.office.W5DocumentScreenMenusTest",
            "com.vayunmathur.office.platform.OfficeViewModelBranchesSlice3Test",
            "com.vayunmathur.office.platform.OfficeViewModelCoreSlice3Test",
            "com.vayunmathur.office.platform.OfficeViewModelDocsSlice3Test",
            "com.vayunmathur.office.platform.OfficeViewModelExportBranchesSlice3Test",
            "com.vayunmathur.office.platform.OfficeViewModelSaveSlice3Test",
            "com.vayunmathur.office.platform.OfficeViewModelSheetSlice3Test",
            "com.vayunmathur.office.platform.OfficeViewModelSlidesSlice3Test",
            "com.vayunmathur.office.platform.OfficeViewModelTextSlice3Test",
            "com.vayunmathur.office.platform.OfficeViewModelTracked2Slice3Test",
            "com.vayunmathur.office.platform.Slice4TrackedChangesTest",
            "com.vayunmathur.office.platform.W4SaveTest",
            "com.vayunmathur.office.platform.W4SlidesElementTextTest",
            "com.vayunmathur.office.ui.Slice4FormatControlsTest",
            "com.vayunmathur.office.ui.Slice4FormatterWiringTest",
            "com.vayunmathur.office.ui.Slice4FormattersTest",
            "com.vayunmathur.office.ui.Slice4Pass2CellElementFormatTest",
            "com.vayunmathur.office.ui.Slice4Pass2FloatingDocTest",
            "com.vayunmathur.office.ui.Slice4Pass2FormatMenusTest",
            "com.vayunmathur.office.ui.Slice4PresentationTest",
            "com.vayunmathur.office.ui.W4BottomBarTest",
        )
    )
}

dependencies {
    implementation(project(":library:network"))
    implementation(project(":library:e2ee-p2p"))

    // Provides a real XmlPullParser implementation for JVM unit tests (Android's is a stub).
    testImplementation(libs.kxml2)
    // Android framework statics (Uri.parse/fromFile) for slice 3/4 ViewModel tests.
    testImplementation(libs.robolectric)
    // Plain-JVM mocks for Android framework classes (slice 3/4 ViewModel tests).
    testImplementation(libs.mockito.core)
}
