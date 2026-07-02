package com.vayunmathur.photos.data

import android.graphics.Bitmap

/**
 * Content-aware fill: remove the region marked by [holeMask] (normalized, at
 * [maskW] x [maskH]). For small/medium holes it uses exemplar-based synthesis
 * (onion-peel: fill boundary pixels by copying the best-matching known patch),
 * which reproduces texture far better than diffusion. Very large holes fall back
 * to a cheap Jacobi diffusion so the op stays bounded.
 */
fun inpaintBitmap(
    bitmap: Bitmap,
    holeMask: FloatArray,
    maskW: Int,
    maskH: Int,
    passes: Int = 60,
): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val px = IntArray(w * h)
    out.getPixels(px, 0, w, 0, 0, w, h)

    val hole = BooleanArray(w * h)
    var holeCount = 0
    var minX = w; var minY = h; var maxX = -1; var maxY = -1
    for (y in 0 until h) {
        val my = (y * maskH / h).coerceIn(0, maskH - 1)
        for (x in 0 until w) {
            val mx = (x * maskW / w).coerceIn(0, maskW - 1)
            if (holeMask[my * maskW + mx] >= 0.5f) {
                hole[y * w + x] = true
                holeCount++
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
    }
    if (maxX < minX) return out

    if (holeCount > 20000) {
        diffuseFill(px, w, h, hole, minX, minY, maxX, maxY, passes)
    } else {
        exemplarFill(px, w, h, hole, minX, minY, maxX, maxY)
    }
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

/** Onion-peel exemplar synthesis over the hole's bounding box. */
private fun exemplarFill(
    px: IntArray, w: Int, h: Int, hole: BooleanArray,
    minX: Int, minY: Int, maxX: Int, maxY: Int,
) {
    val patchR = 1
    val searchR = 12
    fun idx(x: Int, y: Int) = y * w + x
    fun diff(a: Int, b: Int): Int {
        val dr = ((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)
        val dg = ((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }

    var remaining = true
    var guard = (maxX - minX + 1) * (maxY - minY + 1) + 8
    while (remaining && guard-- > 0) {
        remaining = false
        // Collect current boundary hole pixels (adjacent to a known pixel).
        val boundary = ArrayList<Int>()
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                if (!hole[idx(x, y)]) continue
                val nb = (x > 0 && !hole[idx(x - 1, y)]) || (x < w - 1 && !hole[idx(x + 1, y)]) ||
                    (y > 0 && !hole[idx(x, y - 1)]) || (y < h - 1 && !hole[idx(x, y + 1)])
                if (nb) boundary.add(idx(x, y))
            }
        }
        if (boundary.isEmpty()) break
        remaining = true
        val newColors = IntArray(boundary.size)
        for (bi in boundary.indices) {
            val p = boundary[bi]
            val pxc = p % w; val pyc = p / w
            var bestQ = -1
            var bestScore = Int.MAX_VALUE
            val sx0 = (pxc - searchR).coerceAtLeast(patchR)
            val sx1 = (pxc + searchR).coerceAtMost(w - 1 - patchR)
            val sy0 = (pyc - searchR).coerceAtLeast(patchR)
            val sy1 = (pyc + searchR).coerceAtMost(h - 1 - patchR)
            var qy = sy0
            while (qy <= sy1) {
                var qx = sx0
                while (qx <= sx1) {
                    if (!hole[idx(qx, qy)]) {
                        var score = 0
                        var valid = true
                        var oy = -patchR
                        loop@ while (oy <= patchR) {
                            var ox = -patchR
                            while (ox <= patchR) {
                                val pnx = pxc + ox; val pny = pyc + oy
                                val qnx = qx + ox; val qny = qy + oy
                                if (pnx in 0 until w && pny in 0 until h && !hole[idx(pnx, pny)]) {
                                    if (hole[idx(qnx, qny)]) { valid = false; break@loop }
                                    score += diff(px[idx(pnx, pny)], px[idx(qnx, qny)])
                                }
                                ox++
                            }
                            oy++
                        }
                        if (valid && score < bestScore) { bestScore = score; bestQ = idx(qx, qy) }
                    }
                    qx++
                }
                qy++
            }
            newColors[bi] = if (bestQ >= 0) px[bestQ] else px[p]
        }
        // Commit this peel layer.
        for (bi in boundary.indices) {
            val p = boundary[bi]
            px[p] = newColors[bi]
            hole[p] = false
        }
    }
    // Safety: anything still marked (shouldn't happen) gets a neutral gray.
    for (y in minY..maxY) for (x in minX..maxX) if (hole[idx(x, y)]) px[idx(x, y)] = px[idx(x, y)] and -0x1000000 or 0x808080
}

/** Cheap Jacobi diffusion fallback for very large holes. */
private fun diffuseFill(
    px: IntArray, w: Int, h: Int, hole: BooleanArray,
    minX: Int, minY: Int, maxX: Int, maxY: Int, passes: Int,
) {
    val bw = maxX - minX + 1
    val bh = maxY - minY + 1
    val r = FloatArray(bw * bh); val g = FloatArray(bw * bh); val b = FloatArray(bw * bh)
    val holeBox = BooleanArray(bw * bh)
    for (y in 0 until bh) for (x in 0 until bw) {
        val gi = (minY + y) * w + (minX + x); val c = px[gi]; val bi = y * bw + x
        r[bi] = ((c ushr 16) and 0xFF).toFloat(); g[bi] = ((c ushr 8) and 0xFF).toFloat(); b[bi] = (c and 0xFF).toFloat()
        holeBox[bi] = hole[gi]
    }
    val nr = r.copyOf(); val ng = g.copyOf(); val nb = b.copyOf()
    fun s(a: FloatArray, x: Int, y: Int) = a[y.coerceIn(0, bh - 1) * bw + x.coerceIn(0, bw - 1)]
    repeat(passes) {
        for (y in 0 until bh) for (x in 0 until bw) {
            val bi = y * bw + x
            if (!holeBox[bi]) continue
            nr[bi] = (s(r, x - 1, y) + s(r, x + 1, y) + s(r, x, y - 1) + s(r, x, y + 1)) / 4f
            ng[bi] = (s(g, x - 1, y) + s(g, x + 1, y) + s(g, x, y - 1) + s(g, x, y + 1)) / 4f
            nb[bi] = (s(b, x - 1, y) + s(b, x + 1, y) + s(b, x, y - 1) + s(b, x, y + 1)) / 4f
        }
        System.arraycopy(nr, 0, r, 0, r.size); System.arraycopy(ng, 0, g, 0, g.size); System.arraycopy(nb, 0, b, 0, b.size)
    }
    for (y in 0 until bh) for (x in 0 until bw) {
        val bi = y * bw + x
        if (!holeBox[bi]) continue
        val gi = (minY + y) * w + (minX + x)
        px[gi] = (px[gi] and -0x1000000) or (r[bi].toInt().coerceIn(0, 255) shl 16) or
            (g[bi].toInt().coerceIn(0, 255) shl 8) or b[bi].toInt().coerceIn(0, 255)
    }
}
