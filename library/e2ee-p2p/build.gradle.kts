plugins {
    id("common-conventions-library")
}

android {
    // The convention plugin derives the namespace from the module name, which contains a hyphen
    // ("e2ee-p2p") and is not a valid package segment — so set it explicitly.
    namespace = "com.vayunmathur.e2ee"
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native post-quantum crypto (Rust: fips203 ML-KEM-768 + fips204 ML-DSA-65).
// See library/e2ee-p2p/src/main/rust/. Replaces Bouncy Castle; keys cross as DER,
// byte-compatible with the previously-deployed BC encoding.
// OpenPGP (rPGP) used to live in this crate too; it moved to :library:e2ee-openpgp's
// `e2ee_pgp` crate so non-mail apps don't link the PGP cipher suite (#873).
// armeabi-v7a as well as arm64: :cast:tv runs on 32-bit-userspace Google TV hardware
// and links this .so. Phone apps filter it back out via their arm64-only abiFilters.
rustNativeLib("e2ee_pqc", "e2ee-pqc", extraAbis = listOf(ABI_ARMV7))
