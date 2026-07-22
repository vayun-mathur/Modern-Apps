package com.vayunmathur.games.logicgate.data

/**
 * Circuit representation:
 * - Inputs: "__IN_<idx>"
 * - Gates: placed chips with generated unique ids
 * - Wires: connections from (sourceId, outputIdx) to (destId, inputIdx)
 * - OutputMappings: final outputs resolved
 */

data class PlacedChip(
    val instanceId: String,
    val chipId: String,
    val x: Float = 0f,
    val y: Float = 0f
)

data class WireEnd(val instanceId: String, val pinIndex: Int)

data class Wire(
    val id: String,
    val from: WireEnd,
    val to: WireEnd
)

data class OutputMapping(
    val outputIndex: Int,
    val from: WireEnd
)

data class Circuit(
    val gates: List<PlacedChip> = emptyList(),
    val wires: List<Wire> = emptyList(),
    val outputMappings: List<OutputMapping> = emptyList()
) {
    fun totalNandCost(): Int = gates.sumOf { ChipLibrary.get(it.chipId).nandCost }
}

sealed class EvalResult {
    data class Success(val rows: List<List<Boolean>>) : EvalResult()
    data class Error(val message: String) : EvalResult()
    data class Cycle(val ids: List<String>) : EvalResult()
}

object CircuitEvaluator {

    private sealed class ComboEval {
        data class Ok(val outputs: List<Boolean>) : ComboEval()
        data class Err(val msg: String) : ComboEval()
        data class Cycle(val ids: List<String>) : ComboEval()
    }

    /** Evaluate circuit for all input combos, but sample if inputCount > 10 to avoid explosion. */
    fun evaluate(level: LevelDef, circuit: Circuit): EvalResult {
        val inputCount = level.inputs.size
        // If circuit has structural issues we still try one combo first to surface errors fast
        val quickCheck = evalCombo(level, circuit, List(inputCount) { false })
        if (quickCheck is ComboEval.Err) return EvalResult.Error(quickCheck.msg)
        if (quickCheck is ComboEval.Cycle) return EvalResult.Cycle(quickCheck.ids)

        val combos = if (inputCount <= 10) {
            (0 until (1 shl inputCount)).toList()
        } else {
            // Sample: exhaustive for low bits + random
            val sampleSize = 256
            val rnd = java.util.Random(1234)
            val set = LinkedHashSet<Int>()
            // include edges
            set.add(0); set.add((1 shl minOf(inputCount, 20)) - 1)
            while (set.size < sampleSize) {
                var v = 0
                for (b in 0 until inputCount) if (rnd.nextBoolean()) v = v or (1 shl b)
                // clamp if inputCount > 30 we limited loops but still sample via bits
                if (inputCount > 30) v = rnd.nextInt(1 shl 18)
                set.add(v)
            }
            set.toList()
        }

        val results = mutableListOf<List<Boolean>>()
        for (combo in combos) {
            val inputVals = List(inputCount) { i ->
                if (i < 30) ((combo shr i) and 1) == 1 else false
            }
            when (val r = evalCombo(level, circuit, inputVals)) {
                is ComboEval.Ok -> results.add(r.outputs)
                is ComboEval.Err -> return EvalResult.Error("Combo ${formatCombo(inputVals)}: ${r.msg}")
                is ComboEval.Cycle -> return EvalResult.Cycle(r.ids)
            }
        }
        return EvalResult.Success(results)
    }

    /** For full truth table comparison, generate expected rows matching the same sampled combos */
    fun evaluateFullCorrectness(level: LevelDef, circuit: Circuit): Pair<Boolean, List<Int>> {
        val targetDef = ChipLibrary.get(level.targetChipId)
        val inputCount = level.inputs.size
        val outputCount = level.outputs.size

        val combos: List<Int> = if (inputCount <= 10) {
            (0 until (1 shl inputCount)).toList()
        } else {
            val sampleSize = 256
            val rnd = java.util.Random(1234)
            val set = LinkedHashSet<Int>()
            set.add(0)
            while (set.size < sampleSize) {
                var v = 0
                for (b in 0 until minOf(inputCount, 18)) if (rnd.nextBoolean()) v = v or (1 shl b)
                if (inputCount > 18) {
                    for (b in 18 until inputCount) if (rnd.nextBoolean()) v = v or (1 shl (b % 18)) // overflow guard
                    // Actually re-generate full bits sampled
                }
                set.add(v)
            }
            set.toList()
        }

        // For large input counts we can't use combo as int for >30 bits; generate bit lists directly in sampled mode
        val testVectors: List<List<Boolean>> = if (inputCount <= 10) {
            combos.map { c -> List(inputCount) { i -> ((c shr i) and 1) == 1 } }
        } else {
            val rnd = java.util.Random(1234)
            val vs = mutableListOf<List<Boolean>>()
            vs.add(List(inputCount) { false })
            vs.add(List(inputCount) { true })
            vs.add(List(inputCount) { it % 2 == 0 })
            while (vs.size < 256) {
                vs.add(List(inputCount) { rnd.nextBoolean() })
            }
            vs
        }

        val expectedRows = testVectors.map { vec -> targetDef.eval(vec).take(outputCount) }
        val actualRows = mutableListOf<List<Boolean>>()

        for ((idx, vec) in testVectors.withIndex()) {
            when (val r = evalCombo(level, circuit, vec)) {
                is ComboEval.Ok -> actualRows.add(r.outputs)
                else -> return false to listOf(idx) // structure error = incorrect
            }
        }

        val failing = mutableListOf<Int>()
        for (i in expectedRows.indices) {
            if (i >= actualRows.size || expectedRows[i] != actualRows[i]) failing.add(i)
        }
        return (failing.isEmpty()) to failing
    }

    fun isCorrect(level: LevelDef, circuit: Circuit): Pair<Boolean, List<Int>> = evaluateFullCorrectness(level, circuit)

    private fun evalCombo(level: LevelDef, circuit: Circuit, inputVals: List<Boolean>): ComboEval {
        val computed = mutableMapOf<String, List<Boolean>>()
        inputVals.forEachIndexed { idx, v -> computed["__IN_$idx"] = listOf(v) }

        val deps = mutableMapOf<String, MutableSet<String>>()
        val incoming = mutableMapOf<String, MutableMap<Int, WireEnd>>()
        circuit.gates.forEach { g ->
            deps[g.instanceId] = mutableSetOf()
            incoming[g.instanceId] = mutableMapOf()
        }
        for (w in circuit.wires) {
            val srcId = w.from.instanceId
            val dstId = w.to.instanceId
            if (dstId.startsWith("__OUT_")) continue
            if (incoming.containsKey(dstId)) {
                incoming[dstId]!![w.to.pinIndex] = w.from
                if (!srcId.startsWith("__IN_")) deps[dstId]?.add(srcId)
            }
        }

        val order = topoSort(circuit.gates.map { it.instanceId }, deps)
            ?: return ComboEval.Cycle(circuit.gates.map { it.instanceId })

        for (gid in order) {
            val placed = circuit.gates.find { it.instanceId == gid } ?: continue
            val chipDef = ChipLibrary.get(placed.chipId)
            val inputs = mutableListOf<Boolean>()
            for (pinIdx in 0 until chipDef.inputCount) {
                val srcEnd = incoming[gid]?.get(pinIdx)
                if (srcEnd == null) {
                    return ComboEval.Err("Gate ${chipDef.displayName}[${chipDef.inputs.getOrElse(pinIdx) { "in$pinIdx" }}] unconnected")
                }
                val srcVals = computed[srcEnd.instanceId]
                if (srcVals == null) return ComboEval.Err("Source ${srcEnd.instanceId} not yet computed (cycle?)")
                inputs.add(if (srcEnd.pinIndex < srcVals.size) srcVals[srcEnd.pinIndex] else false)
            }
            val outVals = try {
                chipDef.eval(inputs)
            } catch (e: Exception) {
                return ComboEval.Err("Eval ${chipDef.id}: ${e.message}")
            }
            computed[gid] = outVals
        }

        val outMap = circuit.outputMappings.associateBy { it.outputIndex }
        val outputs = mutableListOf<Boolean>()
        for (oi in 0 until level.outputs.size) {
            val mapping = outMap[oi] ?: return ComboEval.Err("Output ${level.outputs[oi]} not connected")
            val srcVals = computed[mapping.from.instanceId]
                ?: return ComboEval.Err("Output ${level.outputs[oi]} source not computed")
            outputs.add(if (mapping.from.pinIndex < srcVals.size) srcVals[mapping.from.pinIndex] else false)
        }
        return ComboEval.Ok(outputs)
    }

    private fun topoSort(nodes: List<String>, deps: Map<String, Set<String>>): List<String>? {
        val inDegree = mutableMapOf<String, Int>()
        nodes.forEach { inDegree[it] = deps[it]?.size ?: 0 }
        val outEdges = mutableMapOf<String, MutableList<String>>()
        nodes.forEach { outEdges[it] = mutableListOf() }
        deps.forEach { (node, depSet) ->
            depSet.forEach { dep -> outEdges.getOrPut(dep) { mutableListOf() }.add(node) }
        }
        val q = ArrayDeque<String>()
        inDegree.filter { it.value == 0 }.keys.forEach { q.add(it) }
        val order = mutableListOf<String>()
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            order.add(n)
            outEdges[n]?.forEach { m ->
                inDegree[m] = (inDegree[m] ?: 1) - 1
                if (inDegree[m] == 0) q.add(m)
            }
        }
        return if (order.size == nodes.size) order else null
    }

    fun generateTruthTable(target: ChipDef, inputCount: Int, outputCount: Int, limit: Int = 256): List<List<Boolean>> {
        val total = if (inputCount <= 10) (1 shl inputCount) else minOf(limit, 1 shl minOf(inputCount, 20))
        if (inputCount <= 10) {
            return (0 until total).map { c ->
                val inp = List(inputCount) { i -> ((c shr i) and 1) == 1 }
                target.eval(inp).take(outputCount)
            }
        } else {
            // Sampled truth table for large gates
            val rnd = java.util.Random(42)
            val vectors = mutableListOf<List<Boolean>>()
            vectors.add(List(inputCount) { false })
            vectors.add(List(inputCount) { true })
            vectors.add(List(inputCount) { it % 2 == 0 })
            while (vectors.size < minOf(limit, total)) {
                vectors.add(List(inputCount) { rnd.nextBoolean() })
            }
            return vectors.map { inp -> target.eval(inp).take(outputCount) }
        }
    }

    fun formatCombo(bits: List<Boolean>): String = bits.joinToString("") { if (it) "1" else "0" }
}
