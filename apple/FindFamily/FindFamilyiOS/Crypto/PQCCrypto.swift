import Foundation
import CryptoKit
import Security

// MARK: - PQC Bundle framing (identical to Kotlin Pqc.kt)
//
// Public bundle:  [4B kemPubLen BE][kemPub SPKI DER][dsaPub SPKI DER]
// Private bundle: [4B kemPrivLen BE][kemPriv PKCS#8 DER][dsaPriv PKCS#8 DER]
// Sealed blob:     [4B encapLen BE][encap(1088)][aes] where aes = [12B iv][ciphertext||tag]
// Shared secret: SHA256(BE32(1) || rawSS) — same KDF as Office / Bouncy Castle's default.

enum PQCBundle {
    // MARK: - Length-prefix framing

    static func lenPrefix(_ a: Data, _ b: Data) -> Data {
        var out = Data()
        out.reserveCapacity(4 + a.count + b.count)
        let alen = UInt32(a.count).bigEndian
        withUnsafeBytes(of: alen) { out.append(contentsOf: $0) }
        out.append(a)
        out.append(b)
        return out
    }

    static func unLenPrefix(_ x: Data) -> (Data, Data)? {
        guard x.count >= 4 else { return nil }
        let base = x.startIndex
        let len = (Int(x[base]) << 24) | (Int(x[base + 1]) << 16) | (Int(x[base + 2]) << 8) | Int(x[base + 3])
        guard len >= 0, 4 + len <= x.count else { return nil }
        let first = x.subdata(in: (base + 4)..<(base + 4 + len))
        let second = x.subdata(in: (base + 4 + len)..<x.endIndex)
        return (first, second)
    }

    // MARK: - Public/Private bundle builders

    static func buildPublic(kemPub: Data, dsaPub: Data) -> Data {
        lenPrefix(kemPub, dsaPub)
    }

    static func buildPrivate(kemPriv: Data, dsaPriv: Data) -> Data {
        lenPrefix(kemPriv, dsaPriv)
    }

    static func splitPublic(_ bundle: Data) -> (kemPub: Data, dsaPub: Data)? {
        guard let (k, d) = unLenPrefix(bundle) else { return nil }
        return (k, d)
    }

    static func splitPrivate(_ bundle: Data) -> (kemPriv: Data, dsaPriv: Data)? {
        guard let (k, d) = unLenPrefix(bundle) else { return nil }
        return (k, d)
    }

    // MARK: - Sealed blob helpers [4B encapLen][encap][aes] where aes = [12B iv][ct||tag]

    static func buildSealed(encap: Data, aes: Data) -> Data {
        lenPrefix(encap, aes)
    }

    static func splitSealed(_ blob: Data) -> (encap: Data, aes: Data)? {
        guard let (e, a) = unLenPrefix(blob) else { return nil }
        return (e, a)
    }
}

// MARK: - AES-256-GCM helpers (mirrors E2ee.aesEncrypt / aesDecrypt in Kotlin)
//
// Layout: E2ee.aesEncrypt produces iv(12) + ct||tag (16) with random IV prepended.
// We reproduce that via CryptoKit AES.GCM.

enum PQCAES {
    static let ivLen = 12

    /// Encrypts with `key` (32 bytes), returns `iv(12) + ciphertext||tag`.
    static func seal(key: Data, plaintext: Data) throws -> Data {
        guard key.count == 32 else { throw NSError(domain: "pqc_aes", code: -1, userInfo: [NSLocalizedDescriptionKey: "key must be 32 bytes"]) }
        let symKey = SymmetricKey(data: key)
        let nonce = AES.GCM.Nonce() // random 12 bytes
        let sealed = try AES.GCM.seal(plaintext, using: symKey, nonce: nonce)
        var out = Data()
        out.reserveCapacity(12 + sealed.ciphertext.count + sealed.tag.count)
        out.append(Data(nonce))
        out.append(sealed.ciphertext)
        out.append(sealed.tag)
        return out
    }

    /// Reverses `seal`: input `iv(12) + ct||tag`, key 32 bytes.
    static func open(key: Data, sealed: Data) throws -> Data {
        guard key.count == 32 else { throw NSError(domain: "pqc_aes", code: -1, userInfo: [NSLocalizedDescriptionKey: "key must be 32 bytes"]) }
        guard sealed.count >= ivLen + 16 else { throw NSError(domain: "pqc_aes", code: -2, userInfo: [NSLocalizedDescriptionKey: "sealed too short"]) }
        let iv = sealed.subdata(in: sealed.startIndex..<(sealed.startIndex + ivLen))
        let ctAndTag = sealed.subdata(in: (sealed.startIndex + ivLen)..<sealed.endIndex)
        guard ctAndTag.count >= 16 else { throw NSError(domain: "pqc_aes", code: -3) }
        let tag = ctAndTag.subdata(in: (ctAndTag.endIndex - 16)..<ctAndTag.endIndex)
        let ct = ctAndTag.subdata(in: ctAndTag.startIndex..<(ctAndTag.endIndex - 16))
        let symKey = SymmetricKey(data: key)
        let nonce = try AES.GCM.Nonce(data: iv)
        let sealedBox = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ct, tag: tag)
        return try AES.GCM.open(sealedBox, using: symKey)
    }
}

// MARK: - Security code (same as Kotlin SecurityCode.compute)

enum PQCSecurityCode {
    /// Computes a 6-group 5-digit safety number from two public bundles, identical both sides.
    static func compute(myBundle: Data, theirBundle: Data) -> String {
        SecurityCodeFormat.compute(myBundle, theirBundle)
    }
}

// MARK: - DER encoding (Bouncy Castle 1.85 layouts, mirrored from the Kotlin/Rust side)
//
// The prefixes are fixed because ML-KEM/ML-DSA keys are fixed-size, so the whole
// DER is prefix ‖ raw. These byte arrays are copied verbatim from
// `library/e2ee-p2p/src/main/rust/src/lib.rs` so the two sides agree exactly.

enum PQCDer {
    /// SPKI prefix = SEQUENCE, AlgorithmIdentifier(OID), BIT STRING header (+ unused = 00).
    static let kemPubPrefix: [UInt8] = [
        0x30, 0x82, 0x04, 0xb2, 0x30, 0x0b, 0x06, 0x09, 0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x04,
        0x02, 0x03, 0x82, 0x04, 0xa1, 0x00,
    ]
    static let dsaPubPrefix: [UInt8] = [
        0x30, 0x82, 0x07, 0xb2, 0x30, 0x0b, 0x06, 0x09, 0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x03,
        0x12, 0x03, 0x82, 0x07, 0xa1, 0x00,
    ]
    /// PKCS#8 prefix up to and including the seed OCTET STRING header.
    static let kemPrivPrefix: [UInt8] = [
        0x30, 0x82, 0x09, 0xbe, 0x02, 0x01, 0x00, 0x30, 0x0b, 0x06, 0x09, 0x60, 0x86, 0x48, 0x01, 0x65,
        0x03, 0x04, 0x04, 0x02, 0x04, 0x82, 0x09, 0xaa, 0x30, 0x82, 0x09, 0xa6, 0x04, 0x40,
    ]
    /// expandedKey OCTET STRING header.
    static let kemPrivMid: [UInt8] = [0x04, 0x82, 0x09, 0x60]
    static let dsaPrivPrefix: [UInt8] = [
        0x30, 0x82, 0x0f, 0xfe, 0x02, 0x01, 0x00, 0x30, 0x0b, 0x06, 0x09, 0x60, 0x86, 0x48, 0x01, 0x65,
        0x03, 0x04, 0x03, 0x12, 0x04, 0x82, 0x0f, 0xea, 0x30, 0x82, 0x0f, 0xe6, 0x04, 0x20,
    ]
    static let dsaPrivMid: [UInt8] = [0x04, 0x82, 0x0f, 0xc0]

    static func wrapKemPublic(_ ek: [UInt8]) -> Data { Data(kemPubPrefix + ek) }
    static func wrapDsaPublic(_ pk: [UInt8]) -> Data { Data(dsaPubPrefix + pk) }

    /// BC's seed+expanded "both" form: prefix ‖ seed ‖ mid ‖ expandedKey.
    static func wrapKemPrivate(seed: [UInt8], expanded: [UInt8]) -> Data {
        Data(kemPrivPrefix + seed + kemPrivMid + expanded)
    }

    static func wrapDsaPrivate(seed: [UInt8], expanded: [UInt8]) -> Data {
        Data(dsaPrivPrefix + seed + dsaPrivMid + expanded)
    }

    /// A raw public key is the trailing `rawLength` bytes of the SPKI DER.
    static func rawPublic(_ der: Data, rawLength: Int) -> [UInt8]? {
        guard der.count >= rawLength else { return nil }
        return Array(der.suffix(rawLength))
    }

    /// The expanded private key is the trailing `expandedLength` bytes of the PKCS#8 DER.
    static func expandedPrivate(_ der: Data, expandedLength: Int) -> [UInt8]? {
        guard der.count >= expandedLength else { return nil }
        return Array(der.suffix(expandedLength))
    }
}

// MARK: - High-level PQC ops that mirror Kotlin Pqc.encryptTo / decrypt

enum PQCCrypto {
    /// SP 800-56A single-step KDF with SHA-256 and empty OtherInfo — what Bouncy Castle's
    /// `KEMGenerateSpec(pub, "AES")` applies by default, so derived AES keys match the
    /// already-deployed Android ciphertexts. key = SHA-256(BE32(1) ‖ Z).
    static func concatKDF(_ z: [UInt8]) -> Data {
        var hasher = SHA256()
        withUnsafeBytes(of: UInt32(1).bigEndian) { hasher.update(bufferPointer: $0) }
        hasher.update(data: Data(z))
        return Data(hasher.finalize())
    }

    /// Encrypts `plaintext` to a recipient's public PQC bundle (from PQCBundle.buildPublic).
    /// Layout sealed = [4B encapLen BE][encap(1088)][aes] where aes = [12B iv][ct||tag],
    /// identical to Kotlin's Pqc.encryptTo (which does encaps + E2ee.aesEncrypt).
    static func encryptTo(bundle: Data, plaintext: Data) throws -> Data {
        guard let (kemPubDer, _) = PQCBundle.splitPublic(bundle),
              let ek = PQCDer.rawPublic(kemPubDer, rawLength: MLKEM768.ekLength) else {
            throw NSError(domain: "pqc", code: -1, userInfo: [NSLocalizedDescriptionKey: "invalid PQC bundle"])
        }
        let result = try MLKEM768.encapsulate(ek: ek)
        let aes = try PQCAES.seal(key: concatKDF(result.sharedSecret), plaintext: plaintext)
        return PQCBundle.buildSealed(encap: Data(result.ciphertext), aes: aes)
    }

    /// Decrypts a sealed blob produced by `encryptTo` using our KEM private DER.
    static func decrypt(kemPrivateDer: Data, sealed: Data) throws -> Data {
        guard let (encap, aes) = PQCBundle.splitSealed(sealed) else {
            throw NSError(domain: "pqc", code: -2, userInfo: [NSLocalizedDescriptionKey: "invalid sealed blob"])
        }
        guard let dk = PQCDer.expandedPrivate(kemPrivateDer, expandedLength: MLKEM768.dkLength) else {
            throw NSError(domain: "pqc", code: -3, userInfo: [NSLocalizedDescriptionKey: "invalid ML-KEM private key"])
        }
        let sharedSecret = try MLKEM768.decapsulate(dk: dk, ciphertext: Array(encap))
        return try PQCAES.open(key: concatKDF(sharedSecret), sealed: aes)
    }

    /// Decrypt using a private bundle [4B kemPrivLen][kemPriv][dsaPriv]
    /// (how ephemeral share links store their private half).
    static func decryptWithPrivateBundle(privateBundle: Data, sealed: Data) throws -> Data {
        guard let (kemPriv, _) = PQCBundle.splitPrivate(privateBundle) else {
            throw NSError(domain: "pqc", code: -4, userInfo: [NSLocalizedDescriptionKey: "invalid private bundle"])
        }
        return try decrypt(kemPrivateDer: kemPriv, sealed: sealed)
    }

    /// Generates a full identity: ML-KEM-768 + ML-DSA-65, DER-wrapped and bundled.
    ///
    /// The private DER carries BC's seed+expanded form, so the 2400-byte decapsulation
    /// key sits at the tail exactly where Android and the web share page look for it.
    static func generateIdentity() throws -> (publicBundle: Data, privateBundle: Data) {
        let kemSeed = try MLKEM768.randomSeed()
        let kem = try MLKEM768.keyGen(seed: kemSeed)

        var dsaSeed = [UInt8](repeating: 0, count: MLDSA65.seedLength)
        let status = SecRandomCopyBytes(kSecRandomDefault, MLDSA65.seedLength, &dsaSeed)
        guard status == errSecSuccess else {
            throw NSError(domain: "pqc", code: -5, userInfo: [NSLocalizedDescriptionKey: "SecRandomCopyBytes failed (\(status))"])
        }
        let dsa = try MLDSA65.keyGen(seed: dsaSeed)

        let publicBundle = PQCBundle.buildPublic(
            kemPub: PQCDer.wrapKemPublic(kem.ek),
            dsaPub: PQCDer.wrapDsaPublic(dsa.publicKey)
        )
        let privateBundle = PQCBundle.buildPrivate(
            kemPriv: PQCDer.wrapKemPrivate(seed: kemSeed, expanded: kem.dk),
            dsaPriv: PQCDer.wrapDsaPrivate(seed: dsaSeed, expanded: dsa.privateKey)
        )
        return (publicBundle, privateBundle)
    }

    /// Length of a share-link seed. A link carries only this in its URL fragment (`#s=`),
    /// so it is the link's whole secret and both platforms must expand it identically.
    static let linkSeedLength = 32

    /// Generates a share-link key: a 32-byte ML-KEM seed plus the ML-KEM-only public bundle
    /// it derives. The seed is the link's whole secret — it is what goes in the URL fragment,
    /// and the recipient's browser expands it back into the keypair.
    static func generateLinkKey() throws -> (seed: Data, publicBundle: Data) {
        var seed = [UInt8](repeating: 0, count: linkSeedLength)
        let status = SecRandomCopyBytes(kSecRandomDefault, linkSeedLength, &seed)
        guard status == errSecSuccess else {
            throw NSError(domain: "pqc", code: -7, userInfo: [NSLocalizedDescriptionKey: "SecRandomCopyBytes failed (\(status))"])
        }
        return (Data(seed), try linkPublicBundle(fromSeed: seed))
    }

    /// Re-derives a share-link public bundle from a stored 32-byte seed.
    static func linkPublicFromSeed(_ seed: Data) throws -> Data {
        try linkPublicBundle(fromSeed: Array(seed))
    }

    /// A link bundle keeps the [4B kemLen][kemPub][dsaPub] framing with an empty ML-DSA half,
    /// matching `pqc_link_keygen`'s `len_prefix(&kem_pub, &[])`, so `splitPublic` and
    /// `encryptTo` need no special casing for links.
    private static func linkPublicBundle(fromSeed seed: [UInt8]) throws -> Data {
        guard seed.count == linkSeedLength else {
            throw NSError(domain: "pqc", code: -8, userInfo: [NSLocalizedDescriptionKey: "link seed must be \(linkSeedLength) bytes, got \(seed.count)"])
        }
        let kem = try MLKEM768.keyGen(seed: try expandLinkSeed(seed))
        return PQCBundle.buildPublic(kemPub: PQCDer.wrapKemPublic(kem.ek), dsaPub: Data())
    }

    /// Expands a 32-byte link seed into the 64-byte `d‖z` that FIPS 203 KeyGen_internal takes.
    ///
    /// FIPS 203 defines no 32-byte seed form, so this must reproduce whatever
    /// `mlkem_link_keygen_from_seed` in the Rust crate does, byte for byte — the web share
    /// page expands the same seed and must land on the same public key.
    private static func expandLinkSeed(_ seed: [UInt8]) throws -> [UInt8] {
        throw NSError(domain: "pqc", code: -9, userInfo: [
            NSLocalizedDescriptionKey: "link seed expansion not ported from mlkem_link_keygen_from_seed",
        ])
    }
}
