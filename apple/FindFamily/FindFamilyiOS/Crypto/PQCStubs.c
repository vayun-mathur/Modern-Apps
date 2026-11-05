// PQCStubs.c — weak weak fallback stubs for Rust PQC native lib.
//
// Purpose: allows the iOS app to build and link even when libe2ee_pqc.a XCFramework
// is not present. Each function returns failure (0) / no-op, so PQC is reported as
// unavailable — no crash, no link error. There is no classic fallback: sharing simply
// does not work until the real library is linked.
//
// When the real Rust lib (src/main/rust) is built for iOS and added as
// e2ee_pqc.xcframework (static lib), its strong symbols override these weak ones
// at link time. The bundle format [4B kemLen][kemPub][dsaPub], sealed layout
// [4B encapLen][encap][aes] where aes = [12B iv][ct||tag], and KDF
// SHA256(BE32(1)||rawSS) stay identical to Office Android's e2ee-p2p implementation.
//
// Build XCFramework (from /Users/vayun/Documents/Modern-Apps/library/e2ee-p2p/src/main/rust):
//   rustup target add aarch64-apple-ios x86_64-apple-ios aarch64-apple-ios-sim
//   cargo build --release --target aarch64-apple-ios
//   cargo build --release --target x86_64-apple-ios
//   cargo build --release --target aarch64-apple-ios-sim
//   # lipo or xcodebuild -create-xcframework...
//   # Place result at FindFamilyiOS/Frameworks/e2ee_pqc.xcframework and link it.
//
// No extra dependencies (dashmap not needed); the server relay stays opaque.

#include <stddef.h>
#include <stdint.h>

__attribute__((weak)) void pqc_free(uint8_t *ptr, size_t len) {
    (void)ptr; (void)len;
}

__attribute__((weak)) void pqc_secure_zero(uint8_t *ptr, size_t len) {
    if (!ptr) return;
    for (size_t i = 0; i < len; i++) ptr[i] = 0;
}

__attribute__((weak)) int32_t pqc_kem_keygen(
    uint8_t **pub_out, size_t *pub_len_out,
    uint8_t **priv_out, size_t *priv_len_out) {
    (void)pub_out; (void)pub_len_out; (void)priv_out; (void)priv_len_out;
    return 0;
}

__attribute__((weak)) int32_t pqc_dsa_keygen(
    uint8_t **pub_out, size_t *pub_len_out,
    uint8_t **priv_out, size_t *priv_len_out) {
    (void)pub_out; (void)pub_len_out; (void)priv_out; (void)priv_len_out;
    return 0;
}

__attribute__((weak)) int32_t pqc_identity_keygen(
    uint8_t **pub_bundle_out, size_t *pub_bundle_len_out,
    uint8_t **priv_bundle_out, size_t *priv_bundle_len_out) {
    (void)pub_bundle_out; (void)pub_bundle_len_out;
    (void)priv_bundle_out; (void)priv_bundle_len_out;
    return 0;
}

__attribute__((weak)) int32_t pqc_link_keygen(
    uint8_t **seed_out, size_t *seed_len_out,
    uint8_t **pub_bundle_out, size_t *pub_bundle_len_out) {
    (void)seed_out; (void)seed_len_out;
    (void)pub_bundle_out; (void)pub_bundle_len_out;
    return 0;
}

__attribute__((weak)) int32_t pqc_link_pub_from_seed(
    const uint8_t *seed, size_t seed_len,
    uint8_t **pub_bundle_out, size_t *pub_bundle_len_out) {
    (void)seed; (void)seed_len;
    (void)pub_bundle_out; (void)pub_bundle_len_out;
    return 0;
}

__attribute__((weak)) int32_t pqc_kem_encaps(
    const uint8_t *kem_pub, size_t kem_pub_len,
    uint8_t **ct_out, size_t *ct_len_out,
    uint8_t **ss_out, size_t *ss_len_out) {
    (void)kem_pub; (void)kem_pub_len;
    (void)ct_out; (void)ct_len_out;
    (void)ss_out; (void)ss_len_out;
    return 0;
}

__attribute__((weak)) int32_t pqc_kem_decaps(
    const uint8_t *kem_priv, size_t kem_priv_len,
    const uint8_t *ct, size_t ct_len,
    uint8_t **ss_out, size_t *ss_len_out) {
    (void)kem_priv; (void)kem_priv_len;
    (void)ct; (void)ct_len;
    (void)ss_out; (void)ss_len_out;
    return 0;
}

__attribute__((weak)) int32_t pqc_dsa_sign(
    const uint8_t *dsa_priv, size_t dsa_priv_len,
    const uint8_t *msg, size_t msg_len,
    uint8_t **sig_out, size_t *sig_len_out) {
    (void)dsa_priv; (void)dsa_priv_len;
    (void)msg; (void)msg_len;
    (void)sig_out; (void)sig_len_out;
    return 0;
}

__attribute__((weak)) int32_t pqc_dsa_verify(
    const uint8_t *dsa_pub, size_t dsa_pub_len,
    const uint8_t *msg, size_t msg_len,
    const uint8_t *sig, size_t sig_len) {
    (void)dsa_pub; (void)dsa_pub_len;
    (void)msg; (void)msg_len;
    (void)sig; (void)sig_len;
    return -1;
}

__attribute__((weak)) int32_t pqc_security_code(
    const uint8_t *my_bundle, size_t my_bundle_len,
    const uint8_t *their_bundle, size_t their_bundle_len,
    uint8_t *out, size_t out_len) {
    (void)my_bundle; (void)my_bundle_len;
    (void)their_bundle; (void)their_bundle_len;
    (void)out; (void)out_len;
    return 0;
}

__attribute__((weak)) int32_t pqc_bundle_is_valid(const uint8_t *bundle, size_t bundle_len) {
    (void)bundle; (void)bundle_len;
    return 0;
}

__attribute__((weak)) int32_t pqc_bundle_inspect(
    const uint8_t *bundle, size_t bundle_len,
    size_t *kem_pub_len_out, size_t *dsa_pub_len_out) {
    (void)bundle; (void)bundle_len;
    (void)kem_pub_len_out; (void)dsa_pub_len_out;
    return 0;
}

__attribute__((weak)) int32_t pqc_bundle_build(
    const uint8_t *kem_pub, size_t kem_pub_len,
    const uint8_t *dsa_pub, size_t dsa_pub_len,
    uint8_t **out, size_t *out_len) {
    (void)kem_pub; (void)kem_pub_len;
    (void)dsa_pub; (void)dsa_pub_len;
    (void)out; (void)out_len;
    return 0;
}

__attribute__((weak)) int32_t pqc_bundle_split(
    const uint8_t *bundle, size_t bundle_len,
    uint8_t **kem_pub_out, size_t *kem_pub_len_out,
    uint8_t **dsa_pub_out, size_t *dsa_pub_len_out) {
    (void)bundle; (void)bundle_len;
    (void)kem_pub_out; (void)kem_pub_len_out;
    (void)dsa_pub_out; (void)dsa_pub_len_out;
    return 0;
}
