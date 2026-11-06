package com.vayunmathur.photos.domain

import kotlinx.coroutines.runBlocking
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic identities: unit centres in 512-d, faces at a controlled cosine to their centre. */
private class Faces(seed: Long) {
    private val rng = Random(seed)
    private var nextId = 1L

    fun centre(): FloatArray = unit(FloatArray(DIM) { rng.nextGaussian().toFloat() })

    /** A face whose expected cosine to another face of the same centre is [sameSim]. */
    fun face(centre: FloatArray, sameSim: Float = 0.55f, clusterable: Boolean = true, label: String? = null): ClusterInput {
        val noise = unit(FloatArray(DIM) { rng.nextGaussian().toFloat() })
        val a = sqrt(sameSim)
        val b = sqrt(1f - sameSim)
        return ClusterInput(nextId++, unit(FloatArray(DIM) { a * centre[it] + b * noise[it] }), clusterable, label)
    }

    fun many(centre: FloatArray, count: Int, label: String? = null) = List(count) { face(centre, label = label) }

    fun shuffled(list: List<ClusterInput>): List<ClusterInput> = list.shuffled(rng)

    companion object {
        const val DIM = 512
        fun unit(v: FloatArray): FloatArray {
            val n = sqrt(v.fold(0f) { s, x -> s + x * x })
            return FloatArray(v.size) { v[it] / n }
        }
        fun mid(a: FloatArray, b: FloatArray) = unit(FloatArray(DIM) { a[it] + b[it] })
    }
}

private fun partition(result: ClusteringResult): Set<Set<Long>> =
    result.clusters.map { it.faceIds.toSet() }.toSet()

class FaceClusteringTest {

    @Test
    fun eachIdentityBecomesExactlyOneCluster() = runBlocking {
        val gen = Faces(1)
        val people = List(6) { gen.many(gen.centre(), 15) }
        val result = clusterFaces(gen.shuffled(people.flatten()))
        assertEquals(people.map { p -> p.map { it.faceId }.toSet() }.toSet(), partition(result))
        assertEquals(0, result.unassigned.size)
    }

    @Test
    fun bridgeFacesBetweenTwoPeopleDoNotMergeThem() = runBlocking {
        val gen = Faces(2)
        val a = gen.centre()
        val b = gen.centre()
        val aList = gen.many(a, 20)
        val bList = gen.many(b, 20)
        val bridges = List(6) { gen.face(Faces.mid(a, b)) }
        val aFaces = aList.map { it.faceId }.toSet()
        val bFaces = bList.map { it.faceId }.toSet()
        val result = clusterFaces(aList + bList + bridges)
        for (cluster in result.clusters) {
            val ids = cluster.faceIds.toSet()
            assertFalse(ids.any { it in aFaces } && ids.any { it in bFaces }, "a cluster mixes A and B")
        }
        assertTrue(result.clusters.any { it.faceIds.toSet().containsAll(aFaces) }, "A was split")
        assertTrue(result.clusters.any { it.faceIds.toSet().containsAll(bFaces) }, "B was split")
    }

    @Test
    fun inputOrderDoesNotChangeTheResult() = runBlocking {
        val gen = Faces(3)
        val faces = List(5) { gen.many(gen.centre(), 10) }.flatten() + List(4) { gen.face(gen.centre()) }
        val first = partition(clusterFaces(faces))
        repeat(3) { assertEquals(first, partition(clusterFaces(gen.shuffled(faces)))) }
    }

    @Test
    fun clustersBelowTheMinimumAreDissolved() = runBlocking {
        val gen = Faces(4)
        val big = gen.many(gen.centre(), 10)
        val pair = gen.many(gen.centre(), 2)
        val result = clusterFaces(big + pair)
        assertEquals(1, result.clusters.size)
        assertEquals(pair.map { it.faceId }.toSet(), result.unassigned.toSet())
    }

    @Test
    fun aNamedClusterIsKeptAtAnySize() = runBlocking {
        val gen = Faces(5)
        val named = gen.many(gen.centre(), 2, label = "Ann")
        val result = clusterFaces(gen.many(gen.centre(), 10) + named)
        assertTrue(result.clusters.any { it.label == "Ann" && it.faceIds.toSet() == named.map { f -> f.faceId }.toSet() })
    }

    @Test
    fun differentlyNamedClustersNeverMerge() = runBlocking {
        val gen = Faces(6)
        val c = gen.centre()
        val ann = gen.many(c, 8, label = "Ann")
        val bea = gen.many(c, 8, label = "Bea")
        val result = clusterFaces(ann + bea)
        assertEquals(setOf("Ann", "Bea"), result.clusters.mapNotNull { it.label }.toSet())
        for (cluster in result.clusters) {
            val ids = cluster.faceIds.toSet()
            assertFalse(ids.any { id -> ann.any { it.faceId == id } } && ids.any { id -> bea.any { it.faceId == id } })
        }
    }

    @Test
    fun lowQualityFacesAttachOnlyWhenClearlyClosest() = runBlocking {
        val gen = Faces(7)
        val a = gen.centre()
        val b = gen.centre()
        val faces = gen.many(a, 15) + gen.many(b, 15)
        val blurryA = gen.face(a, clusterable = false)
        val between = gen.face(Faces.mid(a, b), clusterable = false)
        val result = clusterFaces(faces + blurryA + between)
        val aCluster = result.clusters.single { blurryA.faceId in it.faceIds }
        assertTrue(faces.take(15).all { it.faceId in aCluster.faceIds })
        assertTrue(between.faceId in result.unassigned)
    }

    @Test
    fun lowQualityFacesNeverFormClustersOnTheirOwn() = runBlocking {
        val gen = Faces(8)
        val c = gen.centre()
        val result = clusterFaces(List(6) { gen.face(c, clusterable = false) })
        assertTrue(result.clusters.isEmpty())
        assertEquals(6, result.unassigned.size)
    }

    @Test
    fun manyIdentitiesStayPure() = runBlocking {
        val gen = Faces(9)
        val people = List(100) { gen.many(gen.centre(), 30) }
        val owner = HashMap<Long, Int>()
        people.forEachIndexed { p, faces -> faces.forEach { owner[it.faceId] = p } }
        val started = System.nanoTime()
        val result = clusterFaces(gen.shuffled(people.flatten()))
        println("clustered ${owner.size} faces in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertEquals(100, result.clusters.size)
        for (cluster in result.clusters) assertEquals(1, cluster.faceIds.map { owner.getValue(it) }.toSet().size)
    }

    @Test
    fun emptyInputGivesNothing() = runBlocking {
        val result = clusterFaces(emptyList())
        assertTrue(result.clusters.isEmpty())
        assertEquals(0, result.unassigned.size)
    }
}

class FaceClusterIdentityTest {

    private fun cluster(vararg ids: Long) = FaceCluster(ids, FloatArray(1), null)

    @Test
    fun clustersKeepThePersonHoldingMostOfTheirFaces() {
        val resolution = resolveIdentities(
            listOf(cluster(1, 2, 3), cluster(4, 5)),
            mapOf(1L to 10L, 2L to 10L, 3L to 11L, 4L to 11L, 5L to 11L),
            listOf(10L, 11L, 12L),
        )
        assertEquals(listOf(10L, 11L), resolution.persons.map { it.personId })
        assertEquals(listOf(12L), resolution.deletedPersonIds)
    }

    @Test
    fun aSplitPersonKeepsItsIdOnTheLargerPart() {
        val resolution = resolveIdentities(
            listOf(cluster(1, 2), cluster(3, 4, 5)),
            mapOf(1L to 7L, 2L to 7L, 3L to 7L, 4L to 7L, 5L to 7L),
            listOf(7L),
        )
        assertNull(resolution.persons[0].personId)
        assertEquals(7L, resolution.persons[1].personId)
    }

    @Test
    fun mergedPersonsKeepTheLargerIdAndDeleteTheOther() {
        val resolution = resolveIdentities(
            listOf(cluster(1, 2, 3, 4, 5, 6, 7)),
            mapOf(1L to 1L, 2L to 1L, 3L to 1L, 4L to 1L, 5L to 2L, 6L to 2L, 7L to 2L),
            listOf(1L, 2L),
        )
        assertEquals(1L, resolution.persons.single().personId)
        assertEquals(listOf(2L), resolution.deletedPersonIds)
    }

    @Test
    fun brandNewFacesGetANewPerson() {
        val resolution = resolveIdentities(listOf(cluster(1, 2, 3)), emptyMap(), emptyList())
        assertNull(resolution.persons.single().personId)
        assertTrue(resolution.deletedPersonIds.isEmpty())
    }
}

class FaceAlignmentTest {

    private fun apply(t: FloatArray, pts: FloatArray) = FloatArray(pts.size) { i ->
        val x = pts[i - i % 2]
        val y = pts[i - i % 2 + 1]
        if (i % 2 == 0) t[0] * x + t[1] * y + t[2] else t[3] * x + t[4] * y + t[5]
    }

    @Test
    fun recoversAKnownSimilarityTransform() {
        val angle = 0.5
        val s = 2.3f
        val forward = floatArrayOf(
            (s * cos(angle)).toFloat(), (-s * sin(angle)).toFloat(), 40f,
            (s * sin(angle)).toFloat(), (s * cos(angle)).toFloat(), -15f,
        )
        val src = apply(forward, ARCFACE_TEMPLATE)
        val back = assertNotNull(similarityTransform(src, ARCFACE_TEMPLATE))
        val mapped = apply(back, src)
        for (i in mapped.indices) assertEquals(ARCFACE_TEMPLATE[i], mapped[i], 1e-2f)
    }

    @Test
    fun theTemplateMapsToItselfAsTheIdentity() {
        val t = assertNotNull(similarityTransform(ARCFACE_TEMPLATE, ARCFACE_TEMPLATE))
        val expected = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
        for (i in expected.indices) assertEquals(expected[i], t[i], 1e-4f)
    }

    @Test
    fun coincidentPointsHaveNoTransform() {
        assertNull(similarityTransform(FloatArray(10) { 5f }, ARCFACE_TEMPLATE))
    }
}

class FaceQualityTest {

    private val frontal = ARCFACE_TEMPLATE.copyOf()

    @Test
    fun aLargeSharpConfidentFrontalFaceIsClusterable() {
        assertTrue(FaceQuality.isClusterable(120f, 140f, 0.9f, frontal, 200f))
    }

    @Test
    fun eachGateRejectsOnItsOwn() {
        assertFalse(FaceQuality.isClusterable(30f, 140f, 0.9f, frontal, 200f))
        assertFalse(FaceQuality.isClusterable(120f, 140f, 0.6f, frontal, 200f))
        assertFalse(FaceQuality.isClusterable(120f, 140f, 0.9f, frontal, 5f))
        val profile = frontal.copyOf().also { it[4] = it[2] }
        assertFalse(FaceQuality.isClusterable(120f, 140f, 0.9f, profile, 200f))
    }

    @Test
    fun yawIsRotationInvariant() {
        val rotated = FloatArray(10) { i -> if (i % 2 == 0) -frontal[i + 1] else frontal[i - 1] }
        assertEquals(FaceQuality.yawRatio(frontal), FaceQuality.yawRatio(rotated), 1e-4f)
        assertTrue(FaceQuality.yawRatio(frontal) < 0.05f)
    }

    @Test
    fun laplacianVarianceSeparatesFlatFromDetailed() {
        val size = 16
        val flat = IntArray(size * size) { 0xff808080.toInt() }
        val checker = IntArray(size * size) { i -> if ((i % size + i / size) % 2 == 0) -1 else 0xff000000.toInt() }
        assertEquals(0f, FaceQuality.laplacianVariance(flat, size, size), 1e-3f)
        assertTrue(FaceQuality.laplacianVariance(checker, size, size) > 1000f)
    }
}
