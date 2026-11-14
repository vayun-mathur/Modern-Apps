package com.vayunmathur.library.ml

import kotlin.math.exp
import kotlin.random.Random

/**
 * How [Gemma4Handle.generate] picks the next token.
 *
 * Split out of [Gemma4Handle]'s companion so the handle file stays under the FileLength limit,
 * following the `Gemma4Prompt.kt` precedent. The sampler itself is pure - logits in, token out -
 * so it is tested on the JVM without a model, exactly like `fitAudio`.
 *
 * # Greedy is the default, sampling is the caller's
 *
 * [Gemma4Handle.generate] steps greedily unless a config is passed. Chat turns pass [Chat] -
 * the top_k 64 / top_p 0.95 behaviour the previous runtime had; structured extraction passes
 * nothing and stays deterministic.
 *
 * @param topK only the this-many highest logits participate; 1 is greedy. Must be >= 1.
 * @param topP the smallest set of highest-probability tokens whose mass exceeds this
 * participates; 1.0 disables the filter. Must be in (0, 1].
 * @param temperature 0 selects the argmax deterministically; otherwise logits are divided by
 * this before the softmax. Must be >= 0.
 * @param seed derivation salt for the per-step draw. The draw for step n is seeded with
 * `seed + n`, so a fixed seed reproduces a reply exactly and callers that want variety pass
 * something that moves (a timestamp, a counter).
 */
data class Sampling(
    val topK: Int = 64,
    val topP: Float = 0.95f,
    val temperature: Float = 1.0f,
    val seed: Long = 0L,
) {
    /** Preset sampling configurations: chat defaults and deterministic greedy decoding. */
    companion object {
        /**
         * The previous runtime's behaviour: top_k 64, top_p 0.95, temperature 1.
         *
         * Seed 0 is deliberate rather than a missing argument: sampling must be reproducible
         * unless a caller asks for variety by passing a seed that moves.
         */
        val Chat = Sampling()

        /** Deterministic argmax, spelling what `temperature = 0` means at the call site. */
        val Greedy = Sampling(topK = 1, topP = 1.0f, temperature = 0f)
    }
}

/**
 * One token from [logits] under [config], drawing step [step].
 *
 * Pure: the same inputs give the same token, which is what makes reply reproduction a matter of
 * keeping the seed. The per-step seed is `config.seed + step`, so two steps never share a draw
 * and two replies with the same seed agree everywhere.
 *
 * Returns -1 when [logits] is empty rather than throwing: the generate loop treats it exactly
 * like a failed native step and stops.
 */
fun sampleGemma4Logits(logits: FloatArray, config: Sampling, step: Int): Int {
    if (logits.isEmpty()) return EMPTY_LOGITS_TOKEN
    if (config.temperature <= 0f || (config.topK <= 1 && config.topP >= 1f)) {
        return argmaxGemma4(logits)
    }
    // Descending logit order, truncated to top_k first: top_p then runs over at most k entries
    // rather than all 262,144 on every step.
    val order = logits.indices.sortedByDescending { logits[it] }
    val pool = if (config.topK in 1 until order.size) order.subList(0, config.topK) else order
    val weights = softmaxWeights(logits, pool, config.temperature)
    val kept = topPKept(weights, config.topP)
    return drawFromPool(pool, weights, kept, config.seed + step)
}

/** Softmax weights over [pool] in the temperature-scaled space. */
private fun softmaxWeights(
    logits: FloatArray,
    pool: List<Int>,
    temperature: Float
): DoubleArray = softmaxOver(pool.map { logits[it] }, temperature)

/** Candidates [MlNative.topKGemma4] returns per step; covers [Sampling.Chat]'s top_k. */
const val GEMMA4_TOP_K_CANDIDATES = 64

/**
 * [sampleGemma4Logits] over [MlNative.topKGemma4]'s `[logit, id, ...]` candidates, highest
 * first. Draws exactly the token [sampleGemma4Logits] would from the full logits whenever
 * `config.topK` is at most the candidate count: the pool, its weights and the draw are the same.
 */
fun sampleGemma4Candidates(candidates: FloatArray, config: Sampling, step: Int): Int {
    if (candidates.size < 2) return EMPTY_LOGITS_TOKEN
    val count = candidates.size / 2
    val ids = List(count) { candidates[it * 2 + 1].toInt() }
    if (config.temperature <= 0f || (config.topK <= 1 && config.topP >= 1f)) return ids[0]
    val take = if (config.topK in 1 until count) config.topK else count
    val pool = ids.subList(0, take)
    val weights = softmaxOver(List(take) { candidates[it * 2] }, config.temperature)
    val kept = topPKept(weights, config.topP)
    return drawFromPool(pool, weights, kept, config.seed + step)
}

private fun softmaxOver(values: List<Float>, temperature: Float): DoubleArray {
    val invTemp = 1f / temperature
    var peak = Float.NEGATIVE_INFINITY
    for (value in values) {
        val scaled = value * invTemp
        if (scaled > peak) peak = scaled
    }
    // `exp(top - peak)`: shifting by the peak keeps the largest term at 1 rather than
    // overflowing, and monotonicity means the ordering is unchanged.
    val mass = DoubleArray(values.size)
    var total = 0.0
    for (at in values.indices) {
        val weight = exp((values[at] * invTemp - peak).toDouble())
        mass[at] = weight
        total += weight
    }
    for (at in mass.indices) mass[at] /= total
    return mass
}

/**
 * The smallest head of the pool whose mass exceeds [topP]; always keeps the first entry, so a
 * vanishing top_p degrades to greedy rather than to an empty draw.
 */
private fun topPKept(weights: DoubleArray, topP: Float): Int {
    var kept = 1
    var accumulated = 0.0
    while (kept < weights.size) {
        accumulated += weights[kept - 1]
        if (accumulated >= topP) break
        kept++
    }
    return kept
}

/** Random draw over the kept head of [pool], weighted by [weights]. */
private fun drawFromPool(
    pool: List<Int>,
    weights: DoubleArray,
    kept: Int,
    seed: Long
): Int {
    var drawTotal = 0.0
    for (at in 0 until kept) drawTotal += weights[at]
    var draw = Random(seed).nextDouble() * drawTotal
    for (at in 0 until kept) {
        draw -= weights[at]
        if (draw <= 0.0) return pool[at]
    }
    return pool[kept - 1]
}

private const val EMPTY_LOGITS_TOKEN = -1

/** The most likely token, and nothing else. Shared with the temperature-0 path above. */
private fun argmaxGemma4(logits: FloatArray): Int {
    var best = 0
    for (index in 1 until logits.size) {
        if (logits[index] > logits[best]) best = index
    }
    return best
}
