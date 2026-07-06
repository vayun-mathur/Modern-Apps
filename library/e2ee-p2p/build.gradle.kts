plugins {
    id("common-conventions-library")
}

android {
    // The convention plugin derives the namespace from the module name, which contains a hyphen
    // ("e2ee-p2p") and is not a valid package segment — so set it explicitly.
    namespace = "com.vayunmathur.e2ee"
}

dependencies {
    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider.jdk)
    // BouncyCastle for post-quantum crypto (ML-KEM / ML-DSA), used by the Office app only.
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
