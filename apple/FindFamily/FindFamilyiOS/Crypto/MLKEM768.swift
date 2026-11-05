import Foundation
import Security

// ML-KEM-768 (FIPS 203), matching the `fips203` Rust crate that Android's
// `libe2ee_pqc.so` uses, so ciphertexts and keys interoperate byte-for-byte.
//
// Written out here because CryptoKit's MLKEM768 needs iOS 26 and this app
// targets iOS 18. Parameter set: n=256, q=3329, k=3, eta1=eta2=2, du=10, dv=4.
//
// Naming follows FIPS 203 so the code can be read against the spec:
//   K-PKE.KeyGen / K-PKE.Encrypt / K-PKE.Decrypt are the underlying PKE,
//   ML-KEM.KeyGen / Encaps / Decaps add the Fujisaki-Okamoto transform.

enum MLKEMError: Error {
    case badLength(String)
}

enum MLKEM768 {
    // MARK: - Parameters

    static let n = 256
    static let q: Int32 = 3329
    static let k = 3
    static let eta1 = 2
    static let eta2 = 2
    static let du = 10
    static let dv = 4

    /// Encapsulation (public) key: 384*k + 32.
    static let ekLength = 1184
    /// Decapsulation (private) key: 768*k + 96.
    static let dkLength = 2400
    /// Ciphertext: 32*(du*k + dv).
    static let ctLength = 1088
    /// Key-generation seed d‖z.
    static let seedLength = 64

    // MARK: - Modular arithmetic (values kept in [0, q))

    @inline(__always) private static func addMod(_ a: Int32, _ b: Int32) -> Int32 {
        let s = a + b
        return s >= q ? s - q : s
    }

    @inline(__always) private static func subMod(_ a: Int32, _ b: Int32) -> Int32 {
        let s = a - b
        return s < 0 ? s + q : s
    }

    @inline(__always) private static func mulMod(_ a: Int32, _ b: Int32) -> Int32 {
        Int32((Int(a) * Int(b)) % Int(q))
    }

    private static func powMod(_ base: Int32, _ exp: Int) -> Int32 {
        var result: Int32 = 1
        var b = base
        var e = exp
        while e > 0 {
            if e & 1 == 1 { result = mulMod(result, b) }
            b = mulMod(b, b)
            e >>= 1
        }
        return result
    }

    /// Reverses the low 7 bits — the NTT's zeta ordering.
    private static func bitRev7(_ x: Int) -> Int {
        var r = 0
        for i in 0..<7 where (x >> i) & 1 == 1 { r |= 1 << (6 - i) }
        return r
    }

    /// zetas[i] = 17^BitRev7(i) mod q.
    private static let zetas: [Int32] = (0..<128).map { powMod(17, bitRev7($0)) }

    // MARK: - Number-theoretic transform

    /// FIPS 203 Algorithm 9.
    static func ntt(_ f: inout [Int32]) {
        var i = 1
        var len = 128
        while len >= 2 {
            var start = 0
            while start < n {
                let zeta = zetas[i]
                i += 1
                for j in start..<(start + len) {
                    let t = mulMod(zeta, f[j + len])
                    f[j + len] = subMod(f[j], t)
                    f[j] = addMod(f[j], t)
                }
                start += 2 * len
            }
            len /= 2
        }
    }

    /// FIPS 203 Algorithm 10.
    static func inverseNTT(_ f: inout [Int32]) {
        var i = 127
        var len = 2
        while len <= 128 {
            var start = 0
            while start < n {
                let zeta = zetas[i]
                i -= 1
                for j in start..<(start + len) {
                    let t = f[j]
                    f[j] = addMod(t, f[j + len])
                    f[j + len] = mulMod(zeta, subMod(f[j + len], t))
                }
                start += 2 * len
            }
            len *= 2
        }
        // 128^-1 mod q
        for j in 0..<n { f[j] = mulMod(f[j], 3303) }
    }

    /// FIPS 203 Algorithms 11 + 12: pointwise product in the NTT domain.
    static func multiplyNTTs(_ a: [Int32], _ b: [Int32]) -> [Int32] {
        var c = [Int32](repeating: 0, count: n)
        for i in 0..<128 {
            // gamma = zeta^(2*BitRev7(i)+1)
            let gamma = mulMod(mulMod(zetas[i], zetas[i]), 17)
            let a0 = a[2 * i], a1 = a[2 * i + 1]
            let b0 = b[2 * i], b1 = b[2 * i + 1]
            c[2 * i] = addMod(mulMod(a0, b0), mulMod(mulMod(a1, b1), gamma))
            c[2 * i + 1] = addMod(mulMod(a0, b1), mulMod(a1, b0))
        }
        return c
    }

    private static func polyAdd(_ a: [Int32], _ b: [Int32]) -> [Int32] {
        var out = [Int32](repeating: 0, count: n)
        for i in 0..<n { out[i] = addMod(a[i], b[i]) }
        return out
    }

    private static func polySub(_ a: [Int32], _ b: [Int32]) -> [Int32] {
        var out = [Int32](repeating: 0, count: n)
        for i in 0..<n { out[i] = subMod(a[i], b[i]) }
        return out
    }

    // MARK: - Compression and serialization

    @inline(__always) static func compress(_ x: Int32, _ d: Int) -> Int32 {
        Int32(((Int(x) << d) + 1664) / Int(q)) & Int32((1 << d) - 1)
    }

    @inline(__always) static func decompress(_ y: Int32, _ d: Int) -> Int32 {
        Int32((Int(y) * Int(q) + (1 << (d - 1))) >> d)
    }

    /// FIPS 203 Algorithm 5: pack 256 d-bit coefficients little-endian.
    static func byteEncode(_ f: [Int32], _ d: Int) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: 32 * d)
        var acc: UInt32 = 0
        var accBits = 0
        var pos = 0
        for i in 0..<n {
            acc |= UInt32(f[i]) << UInt32(accBits)
            accBits += d
            while accBits >= 8 {
                out[pos] = UInt8(acc & 0xFF)
                pos += 1
                acc >>= 8
                accBits -= 8
            }
        }
        return out
    }

    /// FIPS 203 Algorithm 6. For d = 12 the values are reduced mod q.
    static func byteDecode(_ bytes: ArraySlice<UInt8>, _ d: Int) -> [Int32] {
        var f = [Int32](repeating: 0, count: n)
        let mask: UInt32 = (1 << UInt32(d)) - 1
        var acc: UInt32 = 0
        var accBits = 0
        var it = bytes.startIndex
        for i in 0..<n {
            while accBits < d {
                acc |= UInt32(bytes[it]) << UInt32(accBits)
                it += 1
                accBits += 8
            }
            var v = acc & mask
            acc >>= UInt32(d)
            accBits -= d
            if d == 12 { v %= UInt32(q) }
            f[i] = Int32(v)
        }
        return f
    }

    // MARK: - Sampling

    /// FIPS 203 Algorithm 7: rejection-sample a polynomial already in NTT form.
    static func sampleNTT(_ seed: [UInt8]) -> [Int32] {
        var xof = Keccak.shake128Reader(seed)
        var a = [Int32](repeating: 0, count: n)
        var j = 0
        while j < n {
            let c = xof.squeeze(3)
            let d1 = Int32(c[0]) | (Int32(c[1] & 0x0F) << 8)
            let d2 = Int32(c[1] >> 4) | (Int32(c[2]) << 4)
            if d1 < q {
                a[j] = d1
                j += 1
            }
            if d2 < q && j < n {
                a[j] = d2
                j += 1
            }
        }
        return a
    }

    /// FIPS 203 Algorithm 8: centered binomial distribution.
    static func samplePolyCBD(_ bytes: [UInt8], eta: Int) -> [Int32] {
        var f = [Int32](repeating: 0, count: n)
        @inline(__always) func bit(_ i: Int) -> Int32 {
            Int32((bytes[i >> 3] >> UInt8(i & 7)) & 1)
        }
        for i in 0..<n {
            var x: Int32 = 0
            var y: Int32 = 0
            for j in 0..<eta {
                x += bit(2 * i * eta + j)
                y += bit(2 * i * eta + eta + j)
            }
            f[i] = subMod(x, y)
        }
        return f
    }

    /// PRF_eta(s, b) = SHAKE256(s ‖ b, 64*eta).
    private static func prf(_ eta: Int, _ s: [UInt8], _ b: UInt8) -> [UInt8] {
        Keccak.shake256(s + [b], outputCount: 64 * eta)
    }

    /// Builds the matrix Â. FIPS 203 samples Â[i,j] from ρ‖j‖i (note the index order).
    private static func expandMatrix(_ rho: [UInt8]) -> [[[Int32]]] {
        var a = [[[Int32]]]()
        a.reserveCapacity(k)
        for i in 0..<k {
            var row = [[Int32]]()
            row.reserveCapacity(k)
            for j in 0..<k {
                row.append(sampleNTT(rho + [UInt8(j), UInt8(i)]))
            }
            a.append(row)
        }
        return a
    }

    // MARK: - K-PKE

    /// FIPS 203 Algorithm 13.
    static func kpkeKeyGen(d: [UInt8]) -> (ekPKE: [UInt8], dkPKE: [UInt8]) {
        let g = Keccak.sha3_512(d + [UInt8(k)])
        let rho = Array(g[0..<32])
        let sigma = Array(g[32..<64])

        let aHat = expandMatrix(rho)

        var counter: UInt8 = 0
        var s = [[Int32]]()
        for _ in 0..<k {
            s.append(samplePolyCBD(prf(eta1, sigma, counter), eta: eta1))
            counter += 1
        }
        var e = [[Int32]]()
        for _ in 0..<k {
            e.append(samplePolyCBD(prf(eta1, sigma, counter), eta: eta1))
            counter += 1
        }
        for i in 0..<k {
            ntt(&s[i])
            ntt(&e[i])
        }

        // t̂ = Â ∘ ŝ + ê
        var t = [[Int32]]()
        for i in 0..<k {
            var acc = [Int32](repeating: 0, count: n)
            for j in 0..<k {
                acc = polyAdd(acc, multiplyNTTs(aHat[i][j], s[j]))
            }
            t.append(polyAdd(acc, e[i]))
        }

        var ekPKE = [UInt8]()
        for i in 0..<k { ekPKE += byteEncode(t[i], 12) }
        ekPKE += rho

        var dkPKE = [UInt8]()
        for i in 0..<k { dkPKE += byteEncode(s[i], 12) }

        return (ekPKE, dkPKE)
    }

    /// FIPS 203 Algorithm 14.
    static func kpkeEncrypt(ekPKE: [UInt8], message: [UInt8], randomness r: [UInt8]) -> [UInt8] {
        var t = [[Int32]]()
        for i in 0..<k {
            t.append(byteDecode(ekPKE[(384 * i)..<(384 * (i + 1))], 12))
        }
        let rho = Array(ekPKE[(384 * k)..<(384 * k + 32)])
        let aHat = expandMatrix(rho)

        var counter: UInt8 = 0
        var y = [[Int32]]()
        for _ in 0..<k {
            y.append(samplePolyCBD(prf(eta1, r, counter), eta: eta1))
            counter += 1
        }
        var e1 = [[Int32]]()
        for _ in 0..<k {
            e1.append(samplePolyCBD(prf(eta2, r, counter), eta: eta2))
            counter += 1
        }
        let e2 = samplePolyCBD(prf(eta2, r, counter), eta: eta2)

        for i in 0..<k { ntt(&y[i]) }

        // u = NTT^-1(Â^T ∘ ŷ) + e1
        var u = [[Int32]]()
        for i in 0..<k {
            var acc = [Int32](repeating: 0, count: n)
            for j in 0..<k {
                acc = polyAdd(acc, multiplyNTTs(aHat[j][i], y[j]))
            }
            inverseNTT(&acc)
            u.append(polyAdd(acc, e1[i]))
        }

        // v = NTT^-1(t̂^T ∘ ŷ) + e2 + Decompress_1(m)
        var v = [Int32](repeating: 0, count: n)
        for i in 0..<k {
            v = polyAdd(v, multiplyNTTs(t[i], y[i]))
        }
        inverseNTT(&v)
        v = polyAdd(v, e2)
        let mu = byteDecode(message[message.startIndex...], 1).map { decompress($0, 1) }
        v = polyAdd(v, mu)

        var c = [UInt8]()
        for i in 0..<k {
            c += byteEncode(u[i].map { compress($0, du) }, du)
        }
        c += byteEncode(v.map { compress($0, dv) }, dv)
        return c
    }

    /// FIPS 203 Algorithm 15.
    static func kpkeDecrypt(dkPKE: [UInt8], ciphertext c: [UInt8]) -> [UInt8] {
        let c1Len = 32 * du * k
        var u = [[Int32]]()
        for i in 0..<k {
            let chunk = c[(32 * du * i)..<(32 * du * (i + 1))]
            u.append(byteDecode(chunk, du).map { decompress($0, du) })
        }
        let v = byteDecode(c[c1Len...], dv).map { decompress($0, dv) }

        var s = [[Int32]]()
        for i in 0..<k {
            s.append(byteDecode(dkPKE[(384 * i)..<(384 * (i + 1))], 12))
        }

        // w = v - NTT^-1(ŝ^T ∘ NTT(u))
        var acc = [Int32](repeating: 0, count: n)
        for i in 0..<k {
            var ui = u[i]
            ntt(&ui)
            acc = polyAdd(acc, multiplyNTTs(s[i], ui))
        }
        inverseNTT(&acc)
        let w = polySub(v, acc)

        return byteEncode(w.map { compress($0, 1) }, 1)
    }

    // MARK: - ML-KEM

    /// FIPS 203 Algorithm 16 (KeyGen_internal). `seed` is d‖z (64 bytes).
    static func keyGen(seed: [UInt8]) throws -> (ek: [UInt8], dk: [UInt8]) {
        guard seed.count == seedLength else {
            throw MLKEMError.badLength("ML-KEM seed must be \(seedLength) bytes, got \(seed.count)")
        }
        let d = Array(seed[0..<32])
        let z = Array(seed[32..<64])
        let (ekPKE, dkPKE) = kpkeKeyGen(d: d)
        // dk = dk_PKE ‖ ek ‖ H(ek) ‖ z
        let dk = dkPKE + ekPKE + Keccak.sha3_256(ekPKE) + z
        return (ekPKE, dk)
    }

    /// Generates a fresh random seed d‖z.
    static func randomSeed() throws -> [UInt8] {
        var seed = [UInt8](repeating: 0, count: seedLength)
        let status = SecRandomCopyBytes(kSecRandomDefault, seedLength, &seed)
        guard status == errSecSuccess else {
            throw MLKEMError.badLength("SecRandomCopyBytes failed (\(status))")
        }
        return seed
    }

    /// FIPS 203 Algorithm 17 (Encaps_internal) with caller-supplied message — deterministic, for tests.
    static func encapsulate(ek: [UInt8], message m: [UInt8]) throws -> (ciphertext: [UInt8], sharedSecret: [UInt8]) {
        guard ek.count == ekLength else {
            throw MLKEMError.badLength("ML-KEM public key must be \(ekLength) bytes, got \(ek.count)")
        }
        let g = Keccak.sha3_512(m + Keccak.sha3_256(ek))
        let sharedSecret = Array(g[0..<32])
        let r = Array(g[32..<64])
        let c = kpkeEncrypt(ekPKE: ek, message: m, randomness: r)
        return (c, sharedSecret)
    }

    /// FIPS 203 Algorithm 20 (Encaps): random message.
    static func encapsulate(ek: [UInt8]) throws -> (ciphertext: [UInt8], sharedSecret: [UInt8]) {
        var m = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, 32, &m)
        guard status == errSecSuccess else {
            throw MLKEMError.badLength("SecRandomCopyBytes failed (\(status))")
        }
        return try encapsulate(ek: ek, message: m)
    }

    /// FIPS 203 Algorithm 18 / 21 (Decaps), including implicit rejection.
    static func decapsulate(dk: [UInt8], ciphertext c: [UInt8]) throws -> [UInt8] {
        guard dk.count == dkLength else {
            throw MLKEMError.badLength("ML-KEM private key must be \(dkLength) bytes, got \(dk.count)")
        }
        guard c.count == ctLength else {
            throw MLKEMError.badLength("ML-KEM ciphertext must be \(ctLength) bytes, got \(c.count)")
        }
        let dkPKE = Array(dk[0..<(384 * k)])
        let ekPKE = Array(dk[(384 * k)..<(768 * k + 32)])
        let h = Array(dk[(768 * k + 32)..<(768 * k + 64)])
        let z = Array(dk[(768 * k + 64)..<(768 * k + 96)])

        let mPrime = kpkeDecrypt(dkPKE: dkPKE, ciphertext: c)
        let g = Keccak.sha3_512(mPrime + h)
        var kPrime = Array(g[0..<32])
        let rPrime = Array(g[32..<64])
        let kBar = Keccak.shake256(z + c, outputCount: 32)

        let cPrime = kpkeEncrypt(ekPKE: ekPKE, message: mPrime, randomness: rPrime)
        // Implicit rejection on re-encryption mismatch, in constant time.
        var diff: UInt8 = 0
        for i in 0..<ctLength { diff |= c[i] ^ cPrime[i] }
        let mismatch = Int32(bitPattern: (UInt32(diff) &+ 0xFF) >> 8) // 0 if equal, 1 if not
        let maskByte = UInt8(truncatingIfNeeded: 0 &- mismatch)
        for i in 0..<32 {
            kPrime[i] = (kPrime[i] & ~maskByte) | (kBar[i] & maskByte)
        }
        return kPrime
    }
}
