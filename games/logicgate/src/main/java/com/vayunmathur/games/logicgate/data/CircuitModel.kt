package com.vayunmathur.games.logicgate.data

/**
 * Bus-aware circuit: Wire.busWidth 1=thin bit, 4=orange nibble bus, 8=blue byte bus.
 * JOIN_8 = Expander bits->BUS8 (0 cost, less tedious), SPLIT_8 = Contractor BUS8->bits.
 * Level I/O terminals also have widths: ADDER_8B uses A[8], B[8] not 16 thin wires.
 */

data class PlacedChip(val instanceId: String, val chipId: String, val x: Float = 0f, val y: Float = 0f)
data class WireEnd(val instanceId: String, val pinIndex: Int)
data class Wire(val id: String, val from: WireEnd, val to: WireEnd, val busWidth: Int = 1)
data class OutputMapping(val outputIndex: Int, val from: WireEnd)
data class IoPos(val x: Float, val y: Float)

data class Circuit(
    val gates: List<PlacedChip> = emptyList(),
    val wires: List<Wire> = emptyList(),
    val outputMappings: List<OutputMapping> = emptyList(),
    val inputPositions: Map<Int, IoPos> = emptyMap(),
    val outputPositions: Map<Int, IoPos> = emptyMap()
) { fun totalNandCost(): Int = gates.sumOf { ChipLibrary.get(it.chipId).nandCost } }

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

    fun evaluate(level: LevelDef, circuit: Circuit): EvalResult {
        if (isMemoryLevel(level) || containsSequentialGate(circuit)) {
            return evaluateIterativeFull(level, circuit)
        }
        val bits = level.totalInputBits
        val quick = if (bits == 0) emptyList() else List(bits) { false }
        val qc = evalCombo(level, circuit, quick)
        if (qc is ComboEval.Err) return EvalResult.Error(qc.msg)
        if (qc is ComboEval.Cycle) {
            return evaluateIterativeFull(level, circuit)
        }
        val combos = if (bits <= 10) (0 until (1 shl bits)).toList() else {
            val rnd = java.util.Random(1234)
            val set = LinkedHashSet<Int>(); set.add(0); set.add((1 shl minOf(bits, 20)) - 1)
            while (set.size < 256) { var v = 0; for (b in 0 until minOf(bits, 20)) if (rnd.nextBoolean()) v = v or (1 shl b); set.add(v) }
            set.toList()
        }
        val res = mutableListOf<List<Boolean>>()
        for (c in combos) {
            val flat = if (bits == 0) emptyList() else List(bits) { i ->
                if (i < 20) ((c shr i) and 1) == 1 else java.util.Random(c.toLong() + i).nextBoolean()
            }
            when (val r = evalCombo(level, circuit, flat)) {
                is ComboEval.Ok -> res.add(r.outputs)
                is ComboEval.Err -> return EvalResult.Error("Row ${formatCombo(flat)}: ${r.msg}")
                is ComboEval.Cycle -> return evaluateIterativeFull(level, circuit)
            }
        }
        return EvalResult.Success(res)
    }

    private fun evaluateIterativeFull(level: LevelDef, circuit: Circuit): EvalResult {
        val bits = level.totalInputBits
        val combos = if (bits <= 10) (0 until (1 shl bits)).toList() else {
            val rnd = java.util.Random(1234)
            val set = LinkedHashSet<Int>(); set.add(0); set.add((1 shl minOf(bits, 20)) - 1)
            while (set.size < 256) { var v = 0; for (b in 0 until minOf(bits, 20)) if (rnd.nextBoolean()) v = v or (1 shl b); set.add(v) }
            set.toList()
        }
        val res = mutableListOf<List<Boolean>>()
        for (c in combos) {
            val flat = if (bits == 0) emptyList() else List(bits) { i ->
                if (i < 20) ((c shr i) and 1) == 1 else java.util.Random(c.toLong() + i).nextBoolean()
            }
            when (val r = evalComboIterative(level, circuit, flat)) {
                is ComboEval.Ok -> res.add(r.outputs)
                is ComboEval.Err -> return EvalResult.Error("Row ${formatCombo(flat)}: ${r.msg}")
                is ComboEval.Cycle -> return EvalResult.Cycle(r.ids)
            }
        }
        return EvalResult.Success(res)
    }

    fun evaluateFullCorrectness(level: LevelDef, circuit: Circuit): Pair<Boolean, List<Int>> {
        // SR latch structural pass – 2 NOR cross-coupled = first memory, feedback cycle required
        if (level.id == "SR_LATCH" && isSrLatchStructureValid(circuit)) return true to emptyList()
        val target = try { ChipLibrary.get(level.targetChipId) } catch (_: Exception) { null } ?: return false to emptyList()
        val inBits = level.totalInputBits
        val outBits = level.totalOutputBits
        val vectors: List<List<Boolean>> = if (inBits <= 10) (0 until (1 shl inBits)).map { cc -> List(inBits) { i -> ((cc shr i) and 1) == 1 } }
        else {
            val rnd = java.util.Random(1234)
            val vs = mutableListOf<List<Boolean>>()
            vs.add(List(inBits) { false }); vs.add(List(inBits) { true }); vs.add(List(inBits) { it % 2 == 0 })
            while (vs.size < 256) vs.add(List(inBits) { rnd.nextBoolean() })
            vs
        }
        val expected = vectors.map { it -> target.eval(it).take(outBits) }
        val actual = mutableListOf<List<Boolean>>()
        for (vec in vectors) {
            when (val r = evalComboWithMemoryFallback(level, circuit, vec)) {
                is ComboEval.Ok -> actual.add(r.outputs)
                else -> return false to listOf(0)
            }
        }
        val failing = mutableListOf<Int>()
        for (i in expected.indices) {
            if (i >= actual.size) { failing.add(i); continue }
            if (level.id == "SR_LATCH") {
                val inp = vectors[i]
                val s = inp.getOrElse(0) { false }
                val r = inp.getOrElse(1) { false }
                if (!s && !r) continue
            }
            if (expected[i] != actual[i]) failing.add(i)
        }
        return (failing.isEmpty()) to failing
    }

    fun isCorrect(level: LevelDef, circuit: Circuit): Pair<Boolean, List<Int>> = evaluateFullCorrectness(level, circuit)

    private fun isMemoryLevel(level: LevelDef): Boolean = level.chapter == ChapterId.MEMORY

    private fun containsSequentialGate(circuit: Circuit): Boolean {
        return circuit.gates.any {
            val def = try { ChipLibrary.get(it.chipId) } catch (_: Exception) { null }
            def?.isSequential == true
        }
    }

    private fun isSrLatchStructureValid(circuit: Circuit): Boolean {
        val norGates = circuit.gates.filter { it.chipId == "NOR" }
        if (norGates.size < 2) return false
        val ids = norGates.map { it.instanceId }.toSet()
        val crossWires = circuit.wires.filter { it.from.instanceId in ids && it.to.instanceId in ids && it.from.instanceId != it.to.instanceId }
        if (crossWires.size < 2) return false
        val fromTo = crossWires.map { it.from.instanceId to it.to.instanceId }.toSet()
        val hasBothDirections = norGates.any { a -> norGates.any { b -> a.instanceId != b.instanceId && (a.instanceId to b.instanceId) in fromTo && (b.instanceId to a.instanceId) in fromTo } }
        if (!hasBothDirections) return false
        val inputWires = circuit.wires.filter { it.from.instanceId.startsWith("__IN_") && it.to.instanceId in ids }
        if (inputWires.size < 2) return false
        if (circuit.outputMappings.size < 2) return false
        return true
    }

    private fun evalComboWithMemoryFallback(level: LevelDef, circuit: Circuit, flatInputs: List<Boolean>): ComboEval {
        val r = evalCombo(level, circuit, flatInputs)
        return if (r is ComboEval.Cycle && (isMemoryLevel(level) || containsSequentialGate(circuit))) {
            evalComboIterative(level, circuit, flatInputs)
        } else r
    }

    private fun evalComboIterative(level: LevelDef, circuit: Circuit, flatInputs: List<Boolean>): ComboEval {
        val computed = mutableMapOf<String, List<Boolean>>()
        for (idx in level.inputs.indices) {
            val off = level.inputBitOffset(idx); val w = level.inputWidth(idx)
            computed["__IN_$idx"] = if (off + w <= flatInputs.size) flatInputs.slice(off until off + w) else List(w) { false }
        }
        for (g in circuit.gates) {
            val def = try { ChipLibrary.get(g.chipId) } catch (_: Exception) { continue }
            computed[g.instanceId] = List(def.totalOutputBits) { false }
        }
        val incoming = mutableMapOf<String, MutableMap<Int, Wire>>()
        circuit.gates.forEach { g -> incoming[g.instanceId] = mutableMapOf() }
        for (w in circuit.wires) {
            val dst = w.to.instanceId
            if (dst.startsWith("__OUT_")) continue
            incoming[dst]?.put(w.to.pinIndex, w)
        }
        var changed: Boolean
        var iters = 0
        do {
            changed = false
            iters++
            for (placed in circuit.gates) {
                val def = try { ChipLibrary.get(placed.chipId) } catch (_: Exception) { continue }
                val flatIn = MutableList(def.totalInputBits) { false }
                var hasAll = true
                for (pin in 0 until def.inputCount) {
                    val wire = incoming[placed.instanceId]?.get(pin)
                    if (wire == null) { hasAll = false; break }
                    val srcVals = computed[wire.from.instanceId] ?: run { hasAll = false; break }
                    val srcDef = if (wire.from.instanceId.startsWith("__IN_")) null else circuit.gates.find { it.instanceId == wire.from.instanceId }?.let { try { ChipLibrary.get(it.chipId) } catch (_: Exception) { null } }
                    val srcOff = srcDef?.outputPinBitOffset(wire.from.pinIndex) ?: 0
                    val slice = if (srcOff + wire.busWidth <= srcVals.size) srcVals.slice(srcOff until srcOff + wire.busWidth) else List(wire.busWidth) { k -> srcVals.getOrElse(srcOff + k) { false } }
                    val dstOff = def.inputPinBitOffset(pin)
                    for (k in slice.indices) if (dstOff + k < flatIn.size) flatIn[dstOff + k] = slice[k]
                }
                if (!hasAll) continue
                val outVals = try { def.eval(flatIn) } catch (_: Exception) { continue }
                val padded = if (outVals.size < def.totalOutputBits) outVals + List(def.totalOutputBits - outVals.size) { false } else outVals.take(def.totalOutputBits)
                if (computed[placed.instanceId] != padded) {
                    computed[placed.instanceId] = padded
                    changed = true
                }
            }
        } while (changed && iters < 30)

        val outMap = circuit.outputMappings.associateBy { it.outputIndex }
        val flatOut = mutableListOf<Boolean>()
        for (oi in 0 until level.outputs.size) {
            val map = outMap[oi] ?: return ComboEval.Err("Output ${level.outputs[oi]} not connected – drag from gate output dot to output terminal pill")
            val srcVals = computed[map.from.instanceId] ?: return ComboEval.Err("Output ${level.outputs[oi]} source not computed")
            val expectedW = level.outputWidth(oi)
            val srcDef = if (map.from.instanceId.startsWith("__IN_")) null else circuit.gates.find { it.instanceId == map.from.instanceId }?.let { try { ChipLibrary.get(it.chipId) } catch (_: Exception) { null } }
            val srcOff = srcDef?.outputPinBitOffset(map.from.pinIndex) ?: 0
            val slice = if (srcOff + expectedW <= srcVals.size) srcVals.slice(srcOff until srcOff + expectedW) else List(expectedW) { k -> srcVals.getOrElse(srcOff + k) { false } }
            flatOut.addAll(slice)
        }
        return ComboEval.Ok(flatOut)
    }

    private fun evalCombo(level: LevelDef, circuit: Circuit, flatInputs: List<Boolean>): ComboEval {
        val computed = mutableMapOf<String, List<Boolean>>()
        for (idx in level.inputs.indices) {
            val off = level.inputBitOffset(idx); val w = level.inputWidth(idx)
            computed["__IN_$idx"] = if (off + w <= flatInputs.size) flatInputs.slice(off until off + w) else List(w) { false }
        }
        val deps = mutableMapOf<String, MutableSet<String>>()
        val incoming = mutableMapOf<String, MutableMap<Int, Wire>>()
        circuit.gates.forEach { g -> deps[g.instanceId] = mutableSetOf(); incoming[g.instanceId] = mutableMapOf() }
        for (w in circuit.wires) {
            val dst = w.to.instanceId
            if (dst.startsWith("__OUT_")) continue
            if (incoming.containsKey(dst)) {
                incoming[dst]!![w.to.pinIndex] = w
                if (!w.from.instanceId.startsWith("__IN_")) deps[dst]?.add(w.from.instanceId)
            }
        }
        val order = topoSort(circuit.gates.map { it.instanceId }, deps) ?: return ComboEval.Cycle(circuit.gates.map { it.instanceId })
        for (gid in order) {
            val placed = circuit.gates.find { it.instanceId == gid } ?: continue
            val def = ChipLibrary.get(placed.chipId)
            val flatIn = MutableList(def.totalInputBits) { false }
            for (pin in 0 until def.inputCount) {
                val wire = incoming[gid]?.get(pin) ?: return ComboEval.Err("Gate ${def.displayName}[${def.inputs.getOrElse(pin) { "in$pin" }}] unconnected – ${def.inputs.size} pins need wires")
                val need = def.inputPinWidth(pin)
                if (wire.busWidth != need) {
                    val hint = when {
                        need == 8 && wire.busWidth == 1 -> "JOIN_8 expander (8 bits → BUS8[8] thick blue)"
                        need == 1 && wire.busWidth == 8 -> "SPLIT_8 contractor (BUS8 → bits)"
                        need == 4 && wire.busWidth == 1 -> "JOIN_4 (4 bits → BUS4[4] orange)"
                        need == 1 && wire.busWidth == 4 -> "SPLIT_4 (BUS4 → bits)"
                        else -> "JOIN/SPLIT expander/contractor to convert"
                    }
                    return ComboEval.Err("Width mismatch ${def.displayName}.${def.inputs[pin]}: need ${need}b got ${wire.busWidth}b. Use $hint.")
                }
                val srcVals = computed[wire.from.instanceId] ?: return ComboEval.Err("Source ${wire.from.instanceId} not computed (cycle?)")
                val srcDef = if (wire.from.instanceId.startsWith("__IN_")) null else circuit.gates.find { it.instanceId == wire.from.instanceId }?.let { ChipLibrary.get(it.chipId) }
                val srcOff = srcDef?.outputPinBitOffset(wire.from.pinIndex) ?: 0
                val srcW = srcDef?.outputPinWidth(wire.from.pinIndex) ?: run {
                    val ti = wire.from.instanceId.removePrefix("__IN_").toIntOrNull() ?: -1
                    if (ti >= 0) level.inputWidth(ti) else 1
                }
                if (srcW != wire.busWidth) return ComboEval.Err("Src width mismatch ${wire.from.instanceId}[${wire.from.pinIndex}] is ${srcW}b vs wire ${wire.busWidth}b")
                val slice = if (srcOff + wire.busWidth <= srcVals.size) srcVals.slice(srcOff until srcOff + wire.busWidth)
                else List(wire.busWidth) { k -> srcVals.getOrElse(srcOff + k) { false } }
                val dstOff = def.inputPinBitOffset(pin)
                for (k in slice.indices) if (dstOff + k < flatIn.size) flatIn[dstOff + k] = slice[k]
            }
            val outVals = try { def.eval(flatIn) } catch (e: Exception) { return ComboEval.Err("Eval ${def.id}: ${e.message}") }
            val padded = if (outVals.size < def.totalOutputBits) outVals + List(def.totalOutputBits - outVals.size) { false } else outVals.take(def.totalOutputBits)
            computed[gid] = padded
        }
        val outMap = circuit.outputMappings.associateBy { it.outputIndex }
        val flatOut = mutableListOf<Boolean>()
        for (oi in 0 until level.outputs.size) {
            val map = outMap[oi] ?: return ComboEval.Err("Output ${level.outputs[oi]} not connected – drag from gate output dot to output terminal pill")
            val srcVals = computed[map.from.instanceId] ?: return ComboEval.Err("Output ${level.outputs[oi]} source not computed")
            val expectedW = level.outputWidth(oi)
            val srcDef = if (map.from.instanceId.startsWith("__IN_")) null else circuit.gates.find { it.instanceId == map.from.instanceId }?.let { ChipLibrary.get(it.chipId) }
            val srcOff = srcDef?.outputPinBitOffset(map.from.pinIndex) ?: 0
            val srcW = srcDef?.outputPinWidth(map.from.pinIndex) ?: run {
                val ti = map.from.instanceId.removePrefix("__IN_").toIntOrNull() ?: -1
                if (ti >= 0) level.inputWidth(ti) else expectedW
            }
            val slice = if (expectedW <= srcW) {
                if (srcOff + expectedW <= srcVals.size) srcVals.slice(srcOff until srcOff + expectedW)
                else List(expectedW) { k -> srcVals.getOrElse(srcOff + k) { false } }
            } else {
                if (srcOff + srcW <= srcVals.size) srcVals.slice(srcOff until srcOff + srcW) + List(expectedW - srcW) { false }
                else List(expectedW) { k -> srcVals.getOrElse(srcOff + k) { false } }
            }
            flatOut.addAll(slice)
        }
        return ComboEval.Ok(flatOut)
    }

    private fun topoSort(nodes: List<String>, deps: Map<String, Set<String>>): List<String>? {
        val inDegree = mutableMapOf<String, Int>(); nodes.forEach { inDegree[it] = deps[it]?.size ?: 0 }
        val outEdges = mutableMapOf<String, MutableList<String>>(); nodes.forEach { outEdges[it] = mutableListOf() }
        deps.forEach { (node, depSet) -> depSet.forEach { dep -> outEdges.getOrPut(dep) { mutableListOf() }.add(node) } }
        val q = ArrayDeque<String>(); inDegree.filter { it.value == 0 }.keys.forEach { q.add(it) }
        val order = mutableListOf<String>()
        while (q.isNotEmpty()) {
            val n = q.removeFirst(); order.add(n)
            outEdges[n]?.forEach { m -> inDegree[m] = (inDegree[m] ?: 1) - 1; if (inDegree[m] == 0) q.add(m) }
        }
        return if (order.size == nodes.size) order else null
    }

    fun generateTruthTable(target: ChipDef, inputBits: Int, outputBits: Int, limit: Int = 256): List<List<Boolean>> {
        val total = if (inputBits <= 10) (1 shl inputBits) else minOf(limit, 1 shl minOf(inputBits, 20))
        return if (inputBits <= 10) {
            (0 until total).map { c -> val inp = List(inputBits) { i -> ((c shr i) and 1) == 1 }; target.eval(inp).take(outputBits) }
        } else {
            val rnd = java.util.Random(42)
            val vecs = mutableListOf<List<Boolean>>()
            vecs.add(List(inputBits) { false }); vecs.add(List(inputBits) { true }); vecs.add(List(inputBits) { it % 2 == 0 })
            while (vecs.size < minOf(limit, total)) vecs.add(List(inputBits) { rnd.nextBoolean() })
            vecs.map { inp -> target.eval(inp).take(outputBits) }
        }
    }

    fun formatCombo(bits: List<Boolean>): String = bits.joinToString("") { if (it) "1" else "0" }
}
