plugins {
    id("common-conventions-library")
}

android {
    // The convention plugin derives the namespace from the module name, which contains a hyphen
    // ("e2ee-openpgp") and is not a valid package segment — so set it explicitly.
    // Same package as :library:e2ee-p2p's PQC surface on purpose: the JNI entry
    // points below (`Java_com_vayunmathur_e2ee_PgpNative_*`) are bound to this
    // package, and consumers keep importing `com.vayunmathur.e2ee.Pgp`.
    namespace = "com.vayunmathur.e2ee"
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native OpenPGP (Rust: rPGP, RFC 9580 Ed25519 + X25519). Split out of
// :library:e2ee-p2p's `e2ee_pqc` crate (#873): only :securemail uses OpenPGP,
// and rPGP's cipher suite materially grows the .so for every other e2ee-p2p
// consumer (office, cast, findfamily, everysync, backup, voxels, ...).
// Phone-app only, so arm64-v8a alone (no :cast:tv 32-bit requirement here).
rustNativeLib("e2ee_pgp", "e2ee-pgp")
