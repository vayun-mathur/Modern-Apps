plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:backup:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "backup"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.backup"
    }
}

// This module registers no Rust library of its own; it only inherits
// forkEvery = 1 because :library:e2ee-p2p (implementation dep below)
// registers libe2ee_pqc. No backup test actually loads that library:
// backup's crypto path is E2ee's pure-JCE AES-GCM plus the pure-JCE
// Hkdf, and nothing here references Pqc/PqcNative. So there are no
// nativeClasses — the quarantine below exists purely for the shared-JVM
// polluters. Quarantine them into testDevUnitTestNative
// (forkEvery = 1); the remaining classes batch 20-per-JVM in
// testDevUnitTest, which depends on the quarantine task so the usual
// invocation still runs everything.
rustNativeTests {
    // Shared-JVM polluters keep forkEvery = 1 in the companion task:
    // BackupConfig is the module's only DataStore owner, and
    // BackupViewModel collects its settings flow in viewModelScope
    // forever (BackupViewModelTest itself documents that a leaked VM
    // deadlocks a later runBlocking DataStore edit on the paused main
    // looper); persisted DataStore state also leaks into classes
    // expecting defaults. Audit rule: any test instantiating
    // BackupViewModel or using BackupConfig directly belongs here.
    // All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.backup.NavigationAndRouteTest",
            "com.vayunmathur.backup.data.BackupConfigTest",
            "com.vayunmathur.backup.platform.BackupViewModelTest",
            "com.vayunmathur.backup.platform.KeyManagerAndWorkerTest",
            "com.vayunmathur.backup.service.ConfigurableBackupTransportTest",
            "com.vayunmathur.backup.service.TransportWithRepoTest",
        )
    )
}

dependencies {
    // Compile-only stubs for the framework's @SystemApi app-data backup transport
    // classes (android.app.backup.BackupTransport + RestoreDescription/RestoreSet).
    // Provided by the framework at runtime on a system image; must NOT be packaged.
    compileOnly(project(":library:aosp-stubs"))
    // WebDAV/Nextcloud remote reaches the server over the platform HTTP stack.
    implementation(project(":library:network"))
    // Scheduled file/media backups.
    implementation(project(":library:work"))
    // Optional biometric gate for the recovery code / master key.
    implementation(project(":library:biometric"))
    // AES-256-GCM segment crypto (aesEncrypt/aesDecrypt, newContentKey).
    implementation(project(":library:e2ee-p2p"))
    // Local config (backend uri/creds handle, enabled sets, last-run).
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.androidx.work.testing)
    testImplementation(project(":library:aosp-stubs"))
}
