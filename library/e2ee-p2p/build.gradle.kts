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

    testImplementation("junit:junit:4.13.2")
}
