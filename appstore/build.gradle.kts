plugins {
    id("common-conventions-app")
    // androidx.tv, so the TV entry is built on a design system that knows what a D-pad is
    // (the cast:tv precedent: name it alongside, not instead — the launcherIcon/nativeAbis
    // accessors below are generated from directly-applied plugins).
    id("common-conventions-tv")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:appstore:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
    // gRPC/protobuf codegen for the Accrescent app source (data/accrescent). The .proto
    // schemas are vendored under src/main/proto (see the note there); protoc + the grpc
    // plugins generate the message + coroutine-stub classes at build time.
    alias(libs.plugins.protobuf)
}

launcherIcon {
    symbol = "store"
}

// Google TV hardware runs a 32-bit userspace: an arm64-only APK is rejected at install.
nativeAbis {
    armv7 = true
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.appstore"
    }
    packaging {
        resources {
            // grpc-okhttp (first okhttp in the repo) and grpc pull in several duplicated
            // license / service-loader descriptors that collide during Java-resource merge.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module",
            )
            // grpc discovers transports via META-INF/services; keep those descriptors.
            merges += setOf("META-INF/services/io.grpc.**")
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// ed25519 verification of Accrescent's signify-signed repodata (Rust).
// See appstore/src/main/rust/. extraAbis: the .so must also load on 32-bit Google TV.
rustNativeLib("appstore_signify", "appstore-signify", extraAbis = listOf("armeabi-v7a"))

// Only FingerprintNormalizeTest actually loads libappstore_signify: it calls
// Signify.verify, which initialises SignifyNative. Every other Accrescent
// test stubs AccrescentRepoDataFetcher.fetch(), so Signify never executes.
// Quarantine it and the shared-JVM polluters below into
// testDevUnitTestNative (forkEvery = 1); the remaining classes batch
// 20-per-JVM in testDevUnitTest, which depends on the quarantine task so
// the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.add("com.vayunmathur.appstore.data.security.FingerprintNormalizeTest")
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // batching them at forkEvery = 20 hangs deterministically (a leaked
    // ViewModel's viewModelScope keeps collecting the process-wide
    // appstore-settings DataStore on the paused Robolectric main looper, so
    // a later class's main-thread runBlocking DataStore edit never resumes;
    // persisted DataStore state also leaks into classes expecting defaults).
    // Audit rule: any test instantiating AppStoreViewModel/TvStoreViewModel
    // or a DataStore-backed repository (SettingsRepository, PlayRepository,
    // AccrescentRepository) belongs here. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.appstore.data.CacheAndSettingsW5Test",
            "com.vayunmathur.appstore.data.SettingsRepositoryTest",
            "com.vayunmathur.appstore.data.UpdateCheckWorkerDoWorkTest",
            "com.vayunmathur.appstore.data.accrescent.AccrescentFakeServerTest",
            "com.vayunmathur.appstore.data.accrescent.AccrescentRepositoryOfflineTest",
            "com.vayunmathur.appstore.data.accrescent.AccrescentRepositoryTest",
            "com.vayunmathur.appstore.data.installer.InstallCoordinatorAccrescentTest",
            "com.vayunmathur.appstore.data.installer.InstallCoordinatorDownloadTest",
            "com.vayunmathur.appstore.data.installer.InstallCoordinatorHappyPathTest",
            "com.vayunmathur.appstore.data.installer.InstallCoordinatorPlayFakeTest",
            "com.vayunmathur.appstore.data.installer.InstallCoordinatorPlayTest",
            "com.vayunmathur.appstore.data.installer.InstallCoordinatorSplitInstallTest",
            "com.vayunmathur.appstore.data.installer.InstallCoordinatorTest",
            "com.vayunmathur.appstore.data.play.PlayRepositoryAuthFailureTest",
            "com.vayunmathur.appstore.data.play.PlayRepositoryRestoreTest",
            "com.vayunmathur.appstore.data.play.PlayRestorePersistedTest",
            "com.vayunmathur.appstore.data.play.PlayRestoreValidJsonTest",
            "com.vayunmathur.appstore.data.play.PlayWithApiTest",
            "com.vayunmathur.appstore.tv.TvActionIntentsTest",
            "com.vayunmathur.appstore.tv.TvCheckBranchesTest",
            "com.vayunmathur.appstore.tv.TvDetailWrapperAndActionsTest",
            "com.vayunmathur.appstore.tv.TvHomeBranchesTest",
            "com.vayunmathur.appstore.tv.TvLibraryOpsPlayTest",
            "com.vayunmathur.appstore.tv.TvLibraryTest",
            "com.vayunmathur.appstore.tv.TvLoadHomePlayAccrescentTest",
            "com.vayunmathur.appstore.tv.TvSearchLiveSourcesTest",
            "com.vayunmathur.appstore.tv.TvSearchOpsTest",
            "com.vayunmathur.appstore.tv.TvStoreLoadHomeTest",
            "com.vayunmathur.appstore.tv.TvStoreSourcesScreenTest",
            "com.vayunmathur.appstore.tv.TvStoreViewModelTest",
            "com.vayunmathur.appstore.tv.TvUpdateAllEligibleTest",
            "com.vayunmathur.appstore.tv.TvUpdateCheckTest",
            "com.vayunmathur.appstore.tv.TvWrapperScreensTest",
            "com.vayunmathur.appstore.ui.PagesWrapperTest",
            "com.vayunmathur.appstore.ui.SourcesPageAllDisabledTest",
            "com.vayunmathur.appstore.ui.SourcesPageTest",
            "com.vayunmathur.appstore.util.AccrescentLoadTest",
            "com.vayunmathur.appstore.util.AccrescentVmOpsW5Test",
            "com.vayunmathur.appstore.util.ActionOpsTest",
            "com.vayunmathur.appstore.util.AppStoreViewModelTest",
            "com.vayunmathur.appstore.util.DetailAndBrowseOpsTest",
            "com.vayunmathur.appstore.util.HomeOpsTest",
            "com.vayunmathur.appstore.util.LoadHomeTest",
            "com.vayunmathur.appstore.util.OnClearedTest",
            "com.vayunmathur.appstore.util.OpenAppActionTest",
            "com.vayunmathur.appstore.util.PhoneCheckAccrescentTest",
            "com.vayunmathur.appstore.util.PhoneInstallTest",
            "com.vayunmathur.appstore.util.PhoneLoadHomePlayTest",
            "com.vayunmathur.appstore.util.PhoneSearchLiveTest",
            "com.vayunmathur.appstore.util.PhoneSourceToggleTest",
            "com.vayunmathur.appstore.util.PhoneSyncSourcesTest",
            "com.vayunmathur.appstore.util.PhoneUpdateAllEligibleTest",
            "com.vayunmathur.appstore.util.UpdateAllTest",
            "com.vayunmathur.appstore.util.UpdateCheckPlayBranchTest",
            "com.vayunmathur.appstore.util.UpdateCheckTest",
        )
    )
}

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(project(":library:network"))
    implementation(project(":library:work"))
    implementation(libs.androidx.datastore.preferences)
    implementation(project(":library:image"))
    implementation(project(":library:tv"))
    // APK source-stamp verification. The stamp is a second signing identity that
    // survives Play App Signing re-signing, so it can be pinned per package where the
    // APK signing key (held by Google) cannot be.
    implementation(libs.apksig)
    // HttpURLConnection-based PlayHttpClient/AnonymousAuthRepository/PlayDownloader – no okhttp
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.auroraoss.gplayapi)

    // --- Accrescent app source -------------------------------------------------------
    // gRPC (appstore-api.accrescent.app) over grpc-okhttp, with protobuf-lite messages and
    // grpc-kotlin coroutine stubs. This is the first okhttp/grpc/protobuf-full surface in
    // the store, which otherwise uses HttpURLConnection only — accepted deliberately, see
    // the plan. grpc-okhttp transitively pulls okhttp/okio.
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.androidx.work.testing)
    implementation(libs.protobuf.javalite)
    // grpc-java generated stubs reference @javax.annotation.Generated, absent on Android.
    compileOnly(libs.javax.annotation.api)
    // ed25519 verification of Accrescent's signify-signed repodata allowlist runs in
    // libappstore_signify.so (see rustNativeLib above), not Bouncy Castle.
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    plugins {
        create("grpc") {
            artifact = libs.grpc.protoc.gen.java.get().toString()
        }
        create("grpckt") {
            artifact = "${libs.grpc.protoc.gen.kotlin.get()}:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                // `create`, not `named` as in :youpipe:extractor: on the Android variant path the
                // plugin does not pre-register the java builtin, so it has to be added here.
                create("java") { option("lite") }
            }
            task.plugins {
                create("grpc") { option("lite") }
                create("grpckt")
            }
        }
    }
}
