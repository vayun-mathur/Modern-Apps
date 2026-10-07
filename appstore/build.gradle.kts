plugins {
    id("common-conventions-app")
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

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
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
// See appstore/src/main/rust/.
rustNativeLib("appstore_signify", "appstore-signify")

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(project(":library:network"))
    implementation(project(":library:work"))
    implementation(libs.androidx.datastore.preferences)
    implementation(project(":library:image"))
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
