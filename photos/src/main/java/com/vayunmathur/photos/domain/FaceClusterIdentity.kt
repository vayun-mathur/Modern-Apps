package com.vayunmathur.photos.domain

/** A cluster bound to a person row: [personId] is null when it needs a new row. */
class ResolvedPerson(val personId: Long?, val cluster: FaceCluster)

/** Persons to keep or insert, in cluster order, and person ids to delete. */
class IdentityResolution(val persons: List<ResolvedPerson>, val deletedPersonIds: List<Long>)

/**
 * Carry person ids (and so their names) across a re-cluster.
 *
 * Each new cluster takes the previous person that holds most of its faces, greedily by
 * overlap and one-to-one, so a person who splits keeps its id on the larger part and two
 * persons that merge keep the larger one's. Previous persons left unmatched are deleted.
 *
 * @param previousPersonOfFace face id → the person id it was assigned to last run.
 */
fun resolveIdentities(
    clusters: List<FaceCluster>,
    previousPersonOfFace: Map<Long, Long>,
    previousPersonIds: Collection<Long>,
): IdentityResolution {
    class Candidate(val overlap: Int, val cluster: Int, val personId: Long)

    val candidates = ArrayList<Candidate>()
    clusters.forEachIndexed { c, cluster ->
        cluster.faceIds.asList().mapNotNull { previousPersonOfFace[it] }
            .groupingBy { it }.eachCount()
            .forEach { (personId, overlap) -> candidates += Candidate(overlap, c, personId) }
    }
    candidates.sortWith(
        compareByDescending<Candidate> { it.overlap }.thenBy { it.cluster }.thenBy { it.personId },
    )

    val assigned = arrayOfNulls<Long>(clusters.size)
    val usedPersons = HashSet<Long>()
    for (candidate in candidates) {
        if (assigned[candidate.cluster] != null || candidate.personId in usedPersons) continue
        assigned[candidate.cluster] = candidate.personId
        usedPersons += candidate.personId
    }
    return IdentityResolution(
        persons = clusters.mapIndexed { c, cluster -> ResolvedPerson(assigned[c], cluster) },
        deletedPersonIds = previousPersonIds.filter { it !in usedPersons }.sorted(),
    )
}
