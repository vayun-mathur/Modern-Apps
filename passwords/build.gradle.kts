plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:<module>:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "key_vertical"
}

android {
    defaultConfig {
        minSdk = 35
        applicationId = "com.vayunmathur.passwords"
    }
    packaging {
        resources.excludes += "META-INF/INDEX.LIST"
    }
    testOptions {
        // CtapProcessor logs through android.util.Log, which is a stub that throws in JVM unit
        // tests. Returning defaults lets the CTAP logic be tested without stripping the logging
        // that makes caBLE sessions diagnosable on a device.
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native KDBX (KeePass) read/write (Rust `keepass` crate). See passwords/src/main/rust/.
// Sole KDBX implementation (replaced keepassjava2 + Bouncy Castle, both now removed);
// existing .kdbx vaults stay interoperable.
rustNativeLib("passwords_kdbx", "passwords-kdbx")

// Only the classes below actually load a native library: they execute a
// path that touches KdbxNative (libpasswords_kdbx, via NativeKdbxCodec's
// read/write, KdbxBackupFormat's import/export over that codec, or
// runKdbxSync's engine wired to NativeKdbxCodec). Tests that merely use
// a FakeCodec, construct KdbxBackupFormat / a ViewModel without calling
// its export/import, or hit the fail-closed / NotConfigured branches
// before the codec never load the library and stay in the main task.
// Quarantine the loaders and the shared-JVM polluters below into
// testDevUnitTestNative (forkEvery = 1); the remaining classes batch
// 20-per-JVM in testDevUnitTest, which depends on the quarantine task so
// the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.passwords.platform.KdbxBackupFormatExtraTest",
            "com.vayunmathur.passwords.platform.KdbxBackupFormatVaultPathTest",
            "com.vayunmathur.passwords.sync.NativeKdbxCodecExtraTest",
            "com.vayunmathur.passwords.sync.NativeKdbxCodecTest",
            "com.vayunmathur.passwords.sync.RunKdbxSyncEndToEndTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // DataStoreUtils is a process-wide singleton whose init eagerly
    // collects the preferences DataStore, and PasswordsViewModel turns
    // the repository's flows into viewModelScope stateIn collectors
    // (SharingStarted.WhileSubscribed) that are never cleared when the
    // test never calls onCleared; persisted DataStore state also leaks
    // into classes expecting defaults. Same hazard for tests that use
    // KdbxSyncSettings / KdbxSyncSection (its DataStore-backed flows),
    // drive runKdbxSync's settings recording, initialise WorkManager /
    // KdbxSyncScheduler, launch MainActivity (which builds the
    // ViewModel), or drive the autofill / passkey credential services
    // and CableService, whose serviceScope (SupervisorJob + IO) launches
    // are never cancelled. Audit rule: any test instantiating
    // PasswordsViewModel (or launching MainActivity, which builds one),
    // using KdbxSyncSettings / DataStoreUtils-backed settings directly,
    // rendering KdbxSyncSection, or building PasswordAutofillService /
    // PasskeyCredentialService / CableService belongs here. All other
    // classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.passwords.platform.AutofillServiceDispatchTest",
            "com.vayunmathur.passwords.platform.ImportFailureTest",
            "com.vayunmathur.passwords.platform.MainActivityGateTest",
            "com.vayunmathur.passwords.platform.PasswordsViewModelCsvInternalsTest",
            "com.vayunmathur.passwords.platform.PasswordsViewModelExtraTest",
            "com.vayunmathur.passwords.platform.PasswordsViewModelImportTest",
            "com.vayunmathur.passwords.platform.PasswordsViewModelTest",
            "com.vayunmathur.passwords.platform.ServicesExtraTest",
            "com.vayunmathur.passwords.platform.ServicesRoboTest",
            "com.vayunmathur.passwords.sync.KdbxSettingsAndRunSyncTest",
            "com.vayunmathur.passwords.sync.KdbxSyncSchedulerTest",
            "com.vayunmathur.passwords.sync.RunKdbxSyncExtraTest",
            "com.vayunmathur.passwords.sync.RunKdbxSyncFullPathTest",
            "com.vayunmathur.passwords.ui.ComposeInteractionsExtraTest",
            "com.vayunmathur.passwords.ui.KdbxSyncSectionComposeTest",
            "com.vayunmathur.passwords.ui.KdbxSyncSectionExtraComposeTest",
            "com.vayunmathur.passwords.ui.PageWrappersComposeTest",
            "com.vayunmathur.passwords.ui.PagesComposeExtraTest",
            "com.vayunmathur.passwords.ui.PagesComposeTest",
        )
    )
}

dependencies {
    implementation(project(":library:biometric"))
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.credentials.lib)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.work.runtime.ktx)
    // Own WebSocketClient via :library:network – no Ktor
    implementation(project(":library:network"))
    testImplementation(libs.androidx.work.testing)
}
