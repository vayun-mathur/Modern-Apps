package com.vayunmathur.photos.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlin.math.min
import kotlin.math.sqrt

/** Untuned defaults for ArcFace-style unit embeddings (MobileFaceNet w600k). */
object FaceClusterConfig {
    /** Neighbours per face considered for stage-A links. */
    const val NEIGHBORS = 5

    /** Cosine a mutual-neighbour pair needs to join a micro-cluster. Strict on purpose. */
    const val MICRO_LINK = 0.65f

    /** Average pairwise cosine two clusters need to merge. */
    const val AVERAGE_LINK = 0.35f

    /** Faces a cluster needs to become a person, unless it carries a name. */
    const val MIN_CLUSTER_FACES = 3

    /** Cosine to a person's mean an unclustered face needs to join it. */
    const val ATTACH_SIMILARITY = 0.60f

    /** How far the best person must beat the runner-up for that attach. */
    const val ATTACH_MARGIN = 0.10f
}

/**
 * One stored face. [embedding] must be L2-normalised. [label] is the name of the person
 * it belonged to in the previous run, if that person was named.
 */
class ClusterInput(
    val faceId: Long,
    val embedding: FloatArray,
    val clusterable: Boolean,
    val label: String?,
)

/** One person: its faces, the normalised mean of its clusterable faces, and its name label. */
class FaceCluster(val faceIds: LongArray, val centroid: FloatArray, val label: String?)

/** People found, plus faces that belong to none of them. */
class ClusteringResult(val clusters: List<FaceCluster>, val unassigned: LongArray)

/**
 * Group faces into people. Deterministic for a given set of faces, whatever their order.
 *
 * 1. Clusterable faces that are mutual top-[FaceClusterConfig.NEIGHBORS] neighbours with
 *    cosine ≥ [FaceClusterConfig.MICRO_LINK] form micro-clusters (union-find, best links
 *    first).
 * 2. Micro-clusters merge by exact average linkage (nearest-neighbour chain) while the
 *    average pairwise cosine is ≥ [FaceClusterConfig.AVERAGE_LINK]. For unit vectors that
 *    average is `ΣA·ΣB / (|A|·|B|)`, so only per-cluster sums are kept. Unlike a running
 *    centroid with a per-face threshold, one look-alike pair cannot pull two people
 *    together: every pair across the clusters counts. Faces or clusters with different
 *    labels never join, in either stage.
 * 3. Clusters under [FaceClusterConfig.MIN_CLUSTER_FACES] without a label are dissolved.
 * 4. Remaining faces (low quality or dissolved) attach to a person only if clearly
 *    closest to its mean; they never move a mean, so they cannot bridge two people.
 */
suspend fun clusterFaces(faces: List<ClusterInput>): ClusteringResult {
    val sorted = faces.sortedBy { it.faceId }
    val core = sorted.filter { it.clusterable }
    val merged = averageLinkage(microClusters(core), FaceClusterConfig.AVERAGE_LINK)
        .sortedBy { it.members.min() }
    val (kept, dissolved) = merged.partition {
        it.members.size >= FaceClusterConfig.MIN_CLUSTER_FACES || it.label != null
    }
    val pool = sorted.filter { !it.clusterable } + dissolved.flatMap { g -> g.members.map { core[it] } }
    return attach(kept.map { g -> g to g.members.map { core[it].faceId } }, pool.sortedBy { it.faceId })
}

private class Group(val sum: FloatArray, val members: MutableList<Int>, var label: String?)

private suspend fun microClusters(core: List<ClusterInput>): MutableList<Group> {
    val n = core.size
    if (n == 0) return mutableListOf()
    val dim = core[0].embedding.size
    val flat = FloatArray(n * dim)
    core.forEachIndexed { i, f -> f.embedding.copyInto(flat, i * dim) }
    val k = min(FaceClusterConfig.NEIGHBORS, n - 1)
    val (neighbors, sims) = topNeighbors(flat, n, dim, k)
    val (root, label) = unionWithLabels(mutualEdges(neighbors, sims, n, k), Array(n) { core[it].label })

    val byRoot = LinkedHashMap<Int, MutableList<Int>>()
    for (i in 0 until n) byRoot.getOrPut(root[i]) { mutableListOf() } += i
    return byRoot.mapTo(mutableListOf()) { (r, members) ->
        val sum = FloatArray(dim)
        for (i in members) for (d in 0 until dim) sum[d] += flat[i * dim + d]
        Group(sum, members, label[r])
    }
}

private class Edge(val a: Int, val b: Int, val sim: Float)

/** Pairs that are in each other's top-k with cosine ≥ [FaceClusterConfig.MICRO_LINK], best first. */
private fun mutualEdges(neighbors: IntArray, sims: FloatArray, n: Int, k: Int): List<Edge> {
    val edges = ArrayList<Edge>()
    for (i in 0 until n) {
        for (slot in 0 until k) {
            val j = neighbors[i * k + slot]
            val s = sims[i * k + slot]
            if (j > i && s >= FaceClusterConfig.MICRO_LINK && (0 until k).any { neighbors[j * k + it] == i }) {
                edges += Edge(i, j, s)
            }
        }
    }
    // Best links first, because the label constraint makes union order matter.
    return edges.sortedWith(compareByDescending<Edge> { it.sim }.thenBy { it.a }.thenBy { it.b })
}

/** Union-find over [edges] that never joins differently labelled sets; returns roots and root labels. */
private fun unionWithLabels(edges: List<Edge>, label: Array<String?>): Pair<IntArray, Array<String?>> {
    val parent = IntArray(label.size) { it }
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
        return r
    }
    for (edge in edges) {
        val ri = find(edge.a)
        val rj = find(edge.b)
        if (ri == rj || !compatible(label[ri], label[rj])) continue
        parent[rj] = ri
        label[ri] = label[ri] ?: label[rj]
    }
    return IntArray(label.size) { find(it) } to label
}

private fun compatible(a: String?, b: String?) = a == null || b == null || a == b

/** Each row's [k] most similar other rows, best first; ties keep the lower index. */
private suspend fun topNeighbors(flat: FloatArray, n: Int, dim: Int, k: Int): Pair<IntArray, FloatArray> {
    val neighbors = IntArray(n * k) { -1 }
    val sims = FloatArray(n * k) { Float.NEGATIVE_INFINITY }
    if (k <= 0) return neighbors to sims
    val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, n)
    val chunk = (n + workers - 1) / workers
    coroutineScope {
        (0 until workers).map { w ->
            async(Dispatchers.Default) {
                for (i in w * chunk until min(n, (w + 1) * chunk)) {
                    ensureActive()
                    val base = i * k
                    for (j in 0 until n) {
                        if (j == i) continue
                        val s = dot(flat, i * dim, flat, j * dim, dim)
                        if (s <= sims[base + k - 1]) continue
                        var at = k - 1
                        while (at > 0 && s > sims[base + at - 1]) {
                            sims[base + at] = sims[base + at - 1]
                            neighbors[base + at] = neighbors[base + at - 1]
                            at--
                        }
                        sims[base + at] = s
                        neighbors[base + at] = j
                    }
                }
            }
        }.awaitAll()
    }
    return neighbors to sims
}

/**
 * Nearest-neighbour-chain average linkage, cut at [threshold].
 *
 * A group whose best link is under [threshold] can never merge above it (average linkage
 * is reducible, and the label constraint keeps it so), so it is finished immediately
 * instead of building the whole dendrogram.
 */
private fun averageLinkage(groups: MutableList<Group>, threshold: Float): List<Group> {
    val active = BooleanArray(groups.size) { true }
    var remaining = groups.size
    var lowest = 0
    val chain = ArrayList<Int>()
    val done = ArrayList<Group>()
    while (remaining > 0) {
        if (chain.isEmpty()) {
            while (!active[lowest]) lowest++
            chain += lowest
        }
        val a = chain.last()
        val prev = if (chain.size >= 2) chain[chain.size - 2] else -1
        val (best, bestSim) = nearest(groups, active, a, prev)
        when {
            best < 0 || bestSim < threshold -> {
                done += groups[a]
                active[a] = false
                remaining--
                chain.removeAt(chain.size - 1)
            }
            best == prev -> {
                chain.removeAt(chain.size - 1)
                chain.removeAt(chain.size - 1)
                active[merge(groups, a, prev)] = false
                remaining--
            }
            else -> chain += best
        }
    }
    return done
}

/** [a]'s most-linked active group; ties go to [prev] so the chain cannot cycle. */
private fun nearest(groups: List<Group>, active: BooleanArray, a: Int, prev: Int): Pair<Int, Float> {
    var best = prev
    var bestSim = if (prev >= 0) linkage(groups[a], groups[prev]) else Float.NEGATIVE_INFINITY
    for (b in groups.indices) {
        if (!active[b] || b == a || b == prev) continue
        val s = linkage(groups[a], groups[b])
        if (s > bestSim) { bestSim = s; best = b }
    }
    return best to bestSim
}

/** Fold the higher-indexed of [a] and [b] into the other; returns the index folded away. */
private fun merge(groups: List<Group>, a: Int, b: Int): Int {
    val (keep, drop) = if (a < b) a to b else b to a
    val g = groups[keep]
    val other = groups[drop]
    for (d in g.sum.indices) g.sum[d] += other.sum[d]
    g.members += other.members
    g.label = g.label ?: other.label
    return drop
}

private fun linkage(a: Group, b: Group): Float {
    if (!compatible(a.label, b.label)) return Float.NEGATIVE_INFINITY
    return dot(a.sum, 0, b.sum, 0, a.sum.size) / (a.members.size.toFloat() * b.members.size)
}

private fun attach(kept: List<Pair<Group, List<Long>>>, pool: List<ClusterInput>): ClusteringResult {
    val centroids = kept.map { normalized(it.first.sum) }
    val attached = List(kept.size) { mutableListOf<Long>() }
    val unassigned = ArrayList<Long>()
    for (face in pool) {
        var best = -1
        var bestSim = -1f
        var second = -1f
        for ((c, centroid) in centroids.withIndex()) {
            val label = kept[c].first.label
            if (!compatible(face.label, label)) continue
            val s = dot(face.embedding, 0, centroid, 0, centroid.size)
            if (s > bestSim) { second = bestSim; bestSim = s; best = c } else if (s > second) second = s
        }
        if (best >= 0 && bestSim >= FaceClusterConfig.ATTACH_SIMILARITY &&
            bestSim - second >= FaceClusterConfig.ATTACH_MARGIN
        ) {
            attached[best] += face.faceId
        } else {
            unassigned += face.faceId
        }
    }
    val clusters = kept.mapIndexed { c, (group, ids) ->
        FaceCluster((ids + attached[c]).sorted().toLongArray(), centroids[c], group.label)
    }
    return ClusteringResult(clusters, unassigned.toLongArray())
}

private fun normalized(v: FloatArray): FloatArray {
    val norm = sqrt(dot(v, 0, v, 0, v.size))
    return if (norm == 0f) v.copyOf() else FloatArray(v.size) { v[it] / norm }
}

private fun dot(a: FloatArray, aOff: Int, b: FloatArray, bOff: Int, len: Int): Float {
    var s0 = 0f; var s1 = 0f; var s2 = 0f; var s3 = 0f
    var d = 0
    while (d + 3 < len) {
        s0 += a[aOff + d] * b[bOff + d]
        s1 += a[aOff + d + 1] * b[bOff + d + 1]
        s2 += a[aOff + d + 2] * b[bOff + d + 2]
        s3 += a[aOff + d + 3] * b[bOff + d + 3]
        d += 4
    }
    while (d < len) { s0 += a[aOff + d] * b[bOff + d]; d++ }
    return (s0 + s1) + (s2 + s3)
}
