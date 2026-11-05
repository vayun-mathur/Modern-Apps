import Foundation

// Keccak-f[1600] and the SHA-3 / SHAKE functions that FIPS 203 (ML-KEM) and
// FIPS 204 (ML-DSA) are built on.
//
// CryptoKit exposes neither SHA-3 nor SHAKE below iOS 26, and the deployment
// target here is iOS 18, so the post-quantum code brings its own. This is a
// straight transcription of FIPS 202 — no shortcuts, no platform assumptions.

enum Keccak {
    // MARK: - Keccak-f[1600] permutation

    private static let roundConstants: [UInt64] = [
        0x0000000000000001, 0x0000000000008082, 0x800000000000808a, 0x8000000080008000,
        0x000000000000808b, 0x0000000080000001, 0x8000000080008081, 0x8000000000008009,
        0x000000000000008a, 0x0000000000000088, 0x0000000080008009, 0x000000008000000a,
        0x000000008000808b, 0x800000000000008b, 0x8000000000008089, 0x8000000000008003,
        0x8000000000008002, 0x8000000000000080, 0x000000000000800a, 0x800000008000000a,
        0x8000000080008081, 0x8000000000008080, 0x0000000080000001, 0x8000000080008008,
    ]

    /// Rotation offsets ρ, indexed as `x + 5*y`.
    private static let rotationOffsets: [UInt64] = [
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14,
    ]

    @inline(__always)
    private static func rotl(_ x: UInt64, _ n: UInt64) -> UInt64 {
        n == 0 ? x : (x << n) | (x >> (64 - n))
    }

    /// In-place Keccak-f[1600] on a 25-lane state.
    static func permute(_ a: inout [UInt64]) {
        var b = [UInt64](repeating: 0, count: 25)
        var c = [UInt64](repeating: 0, count: 5)
        var d = [UInt64](repeating: 0, count: 5)

        for round in 0..<24 {
            // θ
            for x in 0..<5 {
                c[x] = a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20]
            }
            for x in 0..<5 {
                d[x] = c[(x + 4) % 5] ^ rotl(c[(x + 1) % 5], 1)
            }
            for y in 0..<5 {
                for x in 0..<5 { a[x + 5 * y] ^= d[x] }
            }
            // ρ and π: B[y, 2x+3y] = rotl(A[x, y], r[x, y])
            for y in 0..<5 {
                for x in 0..<5 {
                    b[y + 5 * ((2 * x + 3 * y) % 5)] = rotl(a[x + 5 * y], rotationOffsets[x + 5 * y])
                }
            }
            // χ
            for y in 0..<5 {
                for x in 0..<5 {
                    a[x + 5 * y] = b[x + 5 * y] ^ (~b[(x + 1) % 5 + 5 * y] & b[(x + 2) % 5 + 5 * y])
                }
            }
            // ι
            a[0] ^= roundConstants[round]
        }
    }

    // MARK: - Sponge

    /// Absorbs a complete message, then squeezes on demand. Every use here knows its
    /// input up front, so there's no incremental-absorb path to get wrong.
    struct Sponge {
        private var state = [UInt64](repeating: 0, count: 25)
        private let rate: Int          // bytes
        private var block: [UInt8]     // current squeeze block
        private var offset: Int        // read position within `block`

        init(rate: Int, padByte: UInt8, input: [UInt8]) {
            self.rate = rate
            self.block = []
            self.offset = 0

            // Absorb full blocks, then the padded remainder.
            var padded = input
            let remainder = padded.count % rate
            var tail = [UInt8](repeating: 0, count: rate - remainder)
            tail[0] = padByte
            tail[tail.count - 1] |= 0x80
            padded.append(contentsOf: tail)

            var i = 0
            while i < padded.count {
                absorbBlock(Array(padded[i..<(i + rate)]))
                i += rate
            }
            squeezeBlock()
        }

        private mutating func absorbBlock(_ bytes: [UInt8]) {
            for j in 0..<(rate / 8) {
                var lane: UInt64 = 0
                for b in 0..<8 {
                    lane |= UInt64(bytes[j * 8 + b]) << UInt64(8 * b)
                }
                state[j] ^= lane
            }
            Keccak.permute(&state)
        }

        private mutating func squeezeBlock() {
            var out = [UInt8](repeating: 0, count: rate)
            for j in 0..<(rate / 8) {
                let lane = state[j]
                for b in 0..<8 {
                    out[j * 8 + b] = UInt8(truncatingIfNeeded: lane >> UInt64(8 * b))
                }
            }
            block = out
            offset = 0
        }

        /// Squeezes exactly `count` bytes, permuting as needed.
        mutating func squeeze(_ count: Int) -> [UInt8] {
            var out = [UInt8]()
            out.reserveCapacity(count)
            var remaining = count
            while remaining > 0 {
                if offset == rate {
                    Keccak.permute(&state)
                    squeezeBlock()
                }
                let take = min(remaining, rate - offset)
                out.append(contentsOf: block[offset..<(offset + take)])
                offset += take
                remaining -= take
            }
            return out
        }
    }

    // MARK: - Named functions

    static func shake128(_ input: [UInt8], outputCount: Int) -> [UInt8] {
        var s = Sponge(rate: 168, padByte: 0x1F, input: input)
        return s.squeeze(outputCount)
    }

    static func shake256(_ input: [UInt8], outputCount: Int) -> [UInt8] {
        var s = Sponge(rate: 136, padByte: 0x1F, input: input)
        return s.squeeze(outputCount)
    }

    /// Incremental SHAKE128 reader — ML-KEM's matrix sampling rejects candidates,
    /// so it can't know its output length in advance.
    static func shake128Reader(_ input: [UInt8]) -> Sponge {
        Sponge(rate: 168, padByte: 0x1F, input: input)
    }

    /// Incremental SHAKE256 reader — used by ML-DSA rejection sampling.
    static func shake256Reader(_ input: [UInt8]) -> Sponge {
        Sponge(rate: 136, padByte: 0x1F, input: input)
    }

    static func sha3_256(_ input: [UInt8]) -> [UInt8] {
        var s = Sponge(rate: 136, padByte: 0x06, input: input)
        return s.squeeze(32)
    }

    static func sha3_512(_ input: [UInt8]) -> [UInt8] {
        var s = Sponge(rate: 72, padByte: 0x06, input: input)
        return s.squeeze(64)
    }
}
