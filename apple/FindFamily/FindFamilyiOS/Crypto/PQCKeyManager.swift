import Foundation

// MARK: - PQC Key Manager (iOS mirror of Android PqcIdentity.loadOrCreate)
//
// Reuses the *exact same* cryptographic core as Office's Android e2ee-p2p module:
//   - ML-KEM-768 for encryption (key encapsulation + AES-256-GCM)
//   - ML-DSA-65 for signatures (reserved for future roster changes, but part of bundle)
//   - Keys as DER, byte-compatible with Bouncy Castle encoding (fixed SPKI/PKCS#8 prefixes)
//   - Bundle format [4B kemLen BE][kemPubDer][dsaPubDer] as in `Pqc.kt:bundle`
//   - KDF SHA256(BE32(1) || rawSS) as in lib.rs `concat_kdf_sha256`
//
// Storage: 4 separate Keychain entries (to stay under 4KB generic-password limit per item):
//   ff_pqcKemPub, ff_pqcKemPriv, ff_pqcDsaPub, ff_pqcDsaPriv
// This mirrors `PqcIdentity.kt` which stores `${prefix}KemPub/Priv`, `DsaPub/Priv` via E2eeKeyStore.
//
// This class is deliberately independent of Office — prefix `ff_pqc` ensures FindFamily PQC keys
// don't collide with any future Office iOS implementation that might use `office` prefix.

final class PQCKeyManager {
    static let shared = PQCKeyManager()

    // Keychain account names — use ff_pqc prefix matching Android's "ff_pqc" prefix.
    private enum KC {
        static let kemPub = "ff_pqcKemPub"
        static let kemPriv = "ff_pqcKemPriv"
        static let dsaPub = "ff_pqcDsaPub"
        static let dsaPriv = "ff_pqcDsaPriv"
        static let publicBundle = "ff_pqcPublicBundle" // cached bundle for fast register
    }

    private var _kemPub: Data?
    private var _kemPriv: Data?
    private var _dsaPub: Data?
    private var _dsaPriv: Data?
    private var _publicBundle: Data?
    private var _privateBundle: Data?

    private var isLoaded = false
    private let loadQueue = DispatchQueue(label: "cc.findfamily.pqc.keymanager")

    private init() {}

    // MARK: - Load / Create (mirrors PqcIdentity.loadOrCreate)

    /// Returns the device PQC identity, generating + persisting on first use.
    /// Throws if Rust native lib is not linked (native not available on this build) or generation fails.
    func identity() throws -> PQCIdentity {
        // Fast path in-memory.
        if isLoaded, let pubB = _publicBundle, let privB = _privateBundle,
           let kemPriv = _kemPriv, let kemPub = _kemPub {
            return PQCIdentity(publicBundle: pubB, privateBundle: privB,
                               kemPublic: kemPub, kemPrivate: kemPriv,
                               dsaPublic: _dsaPub, dsaPrivate: _dsaPriv)
        }
        return try loadQueue.sync {
            if isLoaded, let pubB = _publicBundle, let privB = _privateBundle,
               let kemPriv = _kemPriv, let kemPub = _kemPub {
                return PQCIdentity(publicBundle: pubB, privateBundle: privB,
                                   kemPublic: kemPub, kemPrivate: kemPriv,
                                   dsaPublic: _dsaPub, dsaPrivate: _dsaPriv)
            }

            // Try restore from Keychain.
            if let kemPub = Keychain.getData(key: KC.kemPub),
               let kemPriv = Keychain.getData(key: KC.kemPriv),
               let dsaPub = Keychain.getData(key: KC.dsaPub),
               let dsaPriv = Keychain.getData(key: KC.dsaPriv) {
                // Validate DER sizes roughly (to avoid corrupt entries).
                if kemPub.count > 1000 && kemPriv.count > 1000 {
                    let pubBundle = PQCBundle.buildPublic(kemPub: kemPub, dsaPub: dsaPub)
                    let privBundle = PQCBundle.buildPrivate(kemPriv: kemPriv, dsaPriv: dsaPriv)
                    _kemPub = kemPub
                    _kemPriv = kemPriv
                    _dsaPub = dsaPub
                    _dsaPriv = dsaPriv
                    _publicBundle = pubBundle
                    _privateBundle = privBundle
                    isLoaded = true
                    Keychain.setData(pubBundle, key: KC.publicBundle) // cache
                    print("PQCKeyManager: restored identity from Keychain pubBundle=\(pubBundle.count) privBundle=\(privBundle.count)")
                    return PQCIdentity(publicBundle: pubBundle, privateBundle: privBundle,
                                       kemPublic: kemPub, kemPrivate: kemPriv,
                                       dsaPublic: dsaPub, dsaPrivate: dsaPriv)
                }
            }

            // Generate fresh via Rust bridge. This requires libe2ee_pqc.a linked.
            // If not linked, PQCCrypto.generateIdentity throws "native not linked" → PQC unavailable.
            let (pubBundle, privBundle) = try PQCCrypto.generateIdentity()
            guard let (kemPriv, dsaPriv) = PQCBundle.splitPrivate(privBundle),
                  let (kemPub, dsaPub) = PQCBundle.splitPublic(pubBundle) else {
                throw NSError(domain: "pqc_keymanager", code: -1, userInfo: [NSLocalizedDescriptionKey: "generated bundles invalid"])
            }

            // Persist each DER separately (mirrors Android onlyIfAbsent race-safe, but iOS single process simpler).
            // Use onlyIfAbsent semantics: only write if not already present (best-effort race guard if two
            // processes — main app + background location — both start simultaneously on first launch).
            // We implement by checking again before write.
            if Keychain.getData(key: KC.kemPub) == nil { Keychain.setData(kemPub, key: KC.kemPub) }
            if Keychain.getData(key: KC.kemPriv) == nil { Keychain.setData(kemPriv, key: KC.kemPriv) }
            if Keychain.getData(key: KC.dsaPub) == nil { Keychain.setData(dsaPub, key: KC.dsaPub) }
            if Keychain.getData(key: KC.dsaPriv) == nil { Keychain.setData(dsaPriv, key: KC.dsaPriv) }

            // Re-read final persisted (winner of race) to avoid returning ephemeral identity that can't decrypt.
            let finalKemPub = Keychain.getData(key: KC.kemPub) ?? kemPub
            let finalKemPriv = Keychain.getData(key: KC.kemPriv) ?? kemPriv
            let finalDsaPub = Keychain.getData(key: KC.dsaPub) ?? dsaPub
            let finalDsaPriv = Keychain.getData(key: KC.dsaPriv) ?? dsaPriv

            let finalPubBundle = PQCBundle.buildPublic(kemPub: finalKemPub, dsaPub: finalDsaPub)
            let finalPrivBundle = PQCBundle.buildPrivate(kemPriv: finalKemPriv, dsaPriv: finalDsaPriv)

            _kemPub = finalKemPub
            _kemPriv = finalKemPriv
            _dsaPub = finalDsaPub
            _dsaPriv = finalDsaPriv
            _publicBundle = finalPubBundle
            _privateBundle = finalPrivBundle
            isLoaded = true
            Keychain.setData(finalPubBundle, key: KC.publicBundle)

            print("PQCKeyManager: generated fresh identity pubBundle=\(finalPubBundle.count) privBundle=\(finalPrivBundle.count) (final persisted)")

            return PQCIdentity(publicBundle: finalPubBundle, privateBundle: finalPrivBundle,
                               kemPublic: finalKemPub, kemPrivate: finalKemPriv,
                               dsaPublic: finalDsaPub, dsaPrivate: finalDsaPriv)
        }
    }

    /// Attempts to load identity without generating. Returns nil if not present or native unavailable.
    func existingIdentity() -> PQCIdentity? {
        try? identity()
    }

    /// Indicates whether PQC is available on this build (native lib linked and identity loadable).
    var isAvailable: Bool {
        (try? identity()) != nil
    }

    /// Returns the public bundle base64 for registration (like Android `Base64.encode(publicBundle)`).
    func publicBundleB64() throws -> String {
        try identity().publicBundle.base64EncodedString()
    }

    // MARK: - Share links (TemporaryLink)

    /// Generate a fresh share-link key. The link is ML-KEM only and derived from a 32-byte
    /// seed; the seed (base64url, unpadded) is what goes in the URL fragment, and the public
    /// bundle is what locations get encrypted to.
    func generateLinkKey() throws -> (seedB64Url: String, publicB64: String) {
        let (seed, pubBundle) = try PQCCrypto.generateLinkKey()
        let seedB64Url = seed.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return (seedB64Url, pubBundle.base64EncodedString())
    }

    // MARK: - Encrypt / Decrypt helpers (mirror Pqc.encryptTo / decrypt)

    func encryptTo(bundle: Data, plaintext: Data) throws -> Data {
        try PQCCrypto.encryptTo(bundle: bundle, plaintext: plaintext)
    }

    func decrypt(sealed: Data) throws -> Data {
        // Decrypt using our KEM private DER (from identity).
        let id = try identity()
        return try PQCCrypto.decrypt(kemPrivateDer: id.kemPrivate, sealed: sealed)
    }

    func decryptWithPrivateBundle(privateBundle: Data, sealed: Data) throws -> Data {
        try PQCCrypto.decryptWithPrivateBundle(privateBundle: privateBundle, sealed: sealed)
    }
}

// MARK: - In-memory identity value type

struct PQCIdentity {
    let publicBundle: Data
    let privateBundle: Data
    let kemPublic: Data
    let kemPrivate: Data
    let dsaPublic: Data?
    let dsaPrivate: Data?

    func securityCode(with peerBundle: Data) -> String {
        PQCSecurityCode.compute(myBundle: publicBundle, theirBundle: peerBundle)
    }
}
