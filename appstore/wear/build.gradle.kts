plugins {
    id("common-conventions-wear")
}

launcherIcon {
    symbol = "store"
}

android {
    // The Wearable Data Layer only connects apps that share a package name and
    // signing certificate, so this module MUST ship as the phone app's package:
    // same applicationId (below) and the same signing config the convention
    // plugins already apply to both (debug key for dev, release key for
    // release). The convention plugin derives this module's namespace from its
    // Gradle path (:appstore:wear -> com.vayunmathur.appstore.wear).
    defaultConfig {
        applicationId = "com.vayunmathur.appstore"
        // The wear convention plugin pins 30, but :library:log (which it also
        // wires in) requires 31. Wear OS devices on API 30 lose out; the
        // alternative is dropping the repo logging facade, which is worse.
        minSdk = 31
    }
}

dependencies {
    // Watch side of the Wear OS sync (see domain/WearSyncProtocol.kt, the
    // byte-identical copy of the phone protocol it must stay in step with).
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
