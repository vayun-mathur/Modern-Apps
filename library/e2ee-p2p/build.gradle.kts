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

// Only the classes below actually load a native library: PqcTest drives
// Pqc.generateKem/generateDsa/encryptTo/signWith directly (libe2ee_pqc via
// PqcNative), and E2eeHkdfIdentityTest's identity tests mint and reload a
// PqcIdentity through the same bridge (its HKDF/AES/security-code tests
// are pure, but the class cannot be split below class granularity).
// InviteFingerprintTest is pure hashing over a fixed bundle and never
// touches Pqc. Quarantine the loaders into testDebugUnitTestNative
// (forkEvery = 1); InviteFingerprintTest batches 20-per-JVM in
// testDebugUnitTest, which depends on the quarantine task so the usual
// invocation still runs everything. No isolatedClasses: no
// DataStore/ViewModel or process-wide singleton here.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.e2ee.E2eeHkdfIdentityTest",
            "com.vayunmathur.e2ee.PqcTest",
        )
    )
}
