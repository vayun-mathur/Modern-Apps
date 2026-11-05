import Foundation

// ML-DSA-65 (FIPS 204) key generation, matching the `fips204` Rust crate used by
// Android's `libe2ee_pqc.so`.
//
// Only KeyGen is implemented: a device's public identity bundle is
// [kemPub ‖ dsaPub], so the ML-DSA half has to be a real, correctly-encoded key
// even though FindFamily itself never signs or verifies with it (the signature
// half exists for Office's roster authentication and is carried here for format
// compatibility). If signing is ever needed on iOS, Sign/Verify go here.
//
// Parameter set ML-DSA-65: q=8380417, n=256, k=6, l=5, eta=4, d=13.

enum MLDSAError: Error {
    case badLength(String)
}

enum MLDSA65 {
    // MARK: - Parameters

    static let n = 256
    static let q: Int32 = 8_380_417
    static let k = 6
    static let l = 5
    static let eta: Int32 = 4
    static let d = 13

    /// 32 + 32*k*(bitlen(q-1) - d) = 32 + 32*6*10.
    static let publicKeyLength = 1952
    /// 128 + 32*(l*4 + k*4 + k*13).
    static let privateKeyLength = 4032
    static let seedLength = 32

    // MARK: - Modular arithmetic

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

    /// Reverses 8 bits.
    private static func bitRev8(_ x: Int) -> Int {
        var r = 0
        for i in 0..<8 where (x >> i) & 1 == 1 { r |= 1 << (7 - i) }
        return r
    }

    /// zetas[i] = 1753^BitRev8(i) mod q.
    private static let zetas: [Int32] = (0..<256).map { powMod(1753, bitRev8($0)) }

    // MARK: - NTT (full 256-point, so multiplication is plain pointwise)

    /// FIPS 204 Algorithm 41.
    static func ntt(_ w: inout [Int32]) {
        var i = 0
        var len = 128
        while len >= 1 {
            var start = 0
            while start < n {
                i += 1
                let z = zetas[i]
                for j in start..<(start + len) {
                    let t = mulMod(z, w[j + len])
                    w[j + len] = subMod(w[j], t)
                    w[j] = addMod(w[j], t)
                }
                start += 2 * len
            }
            len /= 2
        }
    }

    /// FIPS 204 Algorithm 42.
    static func inverseNTT(_ w: inout [Int32]) {
        var i = 256
        var len = 1
        while len < n {
            var start = 0
            while start < n {
                i -= 1
                let z = q - zetas[i]      // -zeta^BitRev8(i)
                for j in start..<(start + len) {
                    let t = w[j]
                    w[j] = addMod(t, w[j + len])
                    w[j + len] = mulMod(z, subMod(t, w[j + len]))
                }
                start += 2 * len
            }
            len *= 2
        }
        // 256^-1 mod q
        for j in 0..<n { w[j] = mulMod(w[j], 8_347_681) }
    }

    // MARK: - Sampling

    /// FIPS 204 Algorithm 30: rejection sampling of a uniform polynomial in NTT form.
    private static func rejNTTPoly(_ seed: [UInt8]) -> [Int32] {
        var xof = Keccak.shake128Reader(seed)
        var a = [Int32](repeating: 0, count: n)
        var j = 0
        while j < n {
            let b = xof.squeeze(3)
            let z = Int32(b[0]) | (Int32(b[1]) << 8) | (Int32(b[2] & 0x7F) << 16)
            if z < q {
                a[j] = z
                j += 1
            }
        }
        return a
    }

    /// FIPS 204 Algorithm 31 with eta = 4: coefficients in [-4, 4].
    private static func rejBoundedPoly(_ seed: [UInt8]) -> [Int32] {
        var xof = Keccak.shake256Reader(seed)
        var a = [Int32](repeating: 0, count: n)
        var j = 0
        // CoeffFromHalfByte for eta=4: b < 9 -> 4 - b, else reject.
        while j < n {
            let z = xof.squeeze(1)[0]
            let lo = Int32(z & 0x0F)
            let hi = Int32(z >> 4)
            if lo < 9 {
                a[j] = 4 - lo
                j += 1
            }
            if hi < 9 && j < n {
                a[j] = 4 - hi
                j += 1
            }
        }
        return a
    }

    /// FIPS 204 Algorithm 32. Note the index order: Â[r,s] from ρ‖s‖r.
    private static func expandA(_ rho: [UInt8]) -> [[[Int32]]] {
        var a = [[[Int32]]]()
        a.reserveCapacity(k)
        for r in 0..<k {
            var row = [[Int32]]()
            row.reserveCapacity(l)
            for s in 0..<l {
                row.append(rejNTTPoly(rho + [UInt8(s), UInt8(r)]))
            }
            a.append(row)
        }
        return a
    }

    /// FIPS 204 Algorithm 33.
    private static func expandS(_ rhoPrime: [UInt8]) -> (s1: [[Int32]], s2: [[Int32]]) {
        var s1 = [[Int32]]()
        for r in 0..<l {
            s1.append(rejBoundedPoly(rhoPrime + [UInt8(r & 0xFF), UInt8((r >> 8) & 0xFF)]))
        }
        var s2 = [[Int32]]()
        for r in 0..<k {
            let idx = r + l
            s2.append(rejBoundedPoly(rhoPrime + [UInt8(idx & 0xFF), UInt8((idx >> 8) & 0xFF)]))
        }
        return (s1, s2)
    }

    // MARK: - Rounding

    /// FIPS 204 Algorithm 35: r -> (r1, r0) with r = r1*2^d + r0, r0 centered.
    private static func power2Round(_ r: Int32) -> (r1: Int32, r0: Int32) {
        let rPlus = r % q
        var r0 = rPlus & ((1 << Int32(d)) - 1)
        if r0 > (1 << Int32(d - 1)) { r0 -= (1 << Int32(d)) }
        let r1 = (rPlus - r0) >> Int32(d)
        return (r1, r0)
    }

    // MARK: - Bit packing

    /// FIPS 204 Algorithm 16: pack coefficients directly in `bits` bits each.
    private static func simpleBitPack(_ w: [Int32], bits: Int) -> [UInt8] {
        packLSBFirst(w.map { UInt32(bitPattern: $0) }, bits: bits)
    }

    /// FIPS 204 Algorithm 17: pack (b - w[i]) in `bits` bits each.
    private static func bitPack(_ w: [Int32], b: Int32, bits: Int) -> [UInt8] {
        packLSBFirst(w.map { UInt32(bitPattern: b - $0) }, bits: bits)
    }

    private static func packLSBFirst(_ values: [UInt32], bits: Int) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: (values.count * bits + 7) / 8)
        var acc: UInt64 = 0
        var accBits = 0
        var pos = 0
        for v in values {
            acc |= UInt64(v & ((1 << UInt32(bits)) - 1)) << UInt64(accBits)
            accBits += bits
            while accBits >= 8 {
                out[pos] = UInt8(acc & 0xFF)
                pos += 1
                acc >>= 8
                accBits -= 8
            }
        }
        if accBits > 0 { out[pos] = UInt8(acc & 0xFF) }
        return out
    }

    // MARK: - KeyGen

    /// FIPS 204 Algorithm 6 (KeyGen_internal). Returns the raw FIPS-encoded key pair.
    static func keyGen(seed xi: [UInt8]) throws -> (publicKey: [UInt8], privateKey: [UInt8]) {
        guard xi.count == seedLength else {
            throw MLDSAError.badLength("ML-DSA seed must be \(seedLength) bytes, got \(xi.count)")
        }
        // (rho, rho', K) <- H(xi ‖ k ‖ l, 128)
        let h = Keccak.shake256(xi + [UInt8(k), UInt8(l)], outputCount: 128)
        let rho = Array(h[0..<32])
        let rhoPrime = Array(h[32..<96])
        let bigK = Array(h[96..<128])

        let aHat = expandA(rho)
        let (s1, s2) = expandS(rhoPrime)

        // t = NTT^-1(Â ∘ NTT(s1)) + s2
        var s1Hat = s1.map { poly -> [Int32] in
            var p = poly.map { $0 < 0 ? $0 + q : $0 }
            ntt(&p)
            return p
        }
        var t = [[Int32]]()
        for r in 0..<k {
            var acc = [Int32](repeating: 0, count: n)
            for s in 0..<l {
                for i in 0..<n {
                    acc[i] = addMod(acc[i], mulMod(aHat[r][s][i], s1Hat[s][i]))
                }
            }
            inverseNTT(&acc)
            for i in 0..<n {
                let s2c = s2[r][i] < 0 ? s2[r][i] + q : s2[r][i]
                acc[i] = addMod(acc[i], s2c)
            }
            t.append(acc)
        }
        s1Hat.removeAll()

        // (t1, t0) = Power2Round(t)
        var t1 = [[Int32]]()
        var t0 = [[Int32]]()
        for r in 0..<k {
            var hi = [Int32](repeating: 0, count: n)
            var lo = [Int32](repeating: 0, count: n)
            for i in 0..<n {
                let (a, b) = power2Round(t[r][i])
                hi[i] = a
                lo[i] = b
            }
            t1.append(hi)
            t0.append(lo)
        }

        // pkEncode: rho ‖ SimpleBitPack(t1[i], 10 bits)
        var pk = rho
        for r in 0..<k { pk += simpleBitPack(t1[r], bits: 10) }

        // skEncode: rho ‖ K ‖ tr ‖ BitPack(s1, eta) ‖ BitPack(s2, eta) ‖ BitPack(t0, 2^(d-1))
        let tr = Keccak.shake256(pk, outputCount: 64)
        var sk = rho + bigK + tr
        for r in 0..<l { sk += bitPack(s1[r], b: eta, bits: 4) }
        for r in 0..<k { sk += bitPack(s2[r], b: eta, bits: 4) }
        for r in 0..<k { sk += bitPack(t0[r], b: 1 << Int32(d - 1), bits: d) }

        guard pk.count == publicKeyLength, sk.count == privateKeyLength else {
            throw MLDSAError.badLength("ML-DSA encode produced pk=\(pk.count) sk=\(sk.count)")
        }
        return (pk, sk)
    }
}
