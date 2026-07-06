package com.vayunmathur.office.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A hierarchical (tree) CRDT over a flat-ODF XML document. Unlike the flat sequence CRDT, every
 * character/element is a **node with a parent**, so deleting an element deletes its whole subtree —
 * including characters a peer inserted into it concurrently. This eliminates the "stranded text"
 * edge of char-merging serialized markup while still merging text within a node at character level.
 *
 * Model: each node has an id `lamport:device`, a `parent` id, a `left` sibling id (RGA order), a
 * [kind] ("e" element, "s" self-closing element, "c" character, "r" raw/opaque run) and a [payload]
 * (the open tag, the char, or the raw text). Rendering is a DFS that skips deleted nodes and never
 * descends into a deleted element, so a deletion cascades automatically.
 */
class DocumentTreeCrdt(private val device: String) {

    @Serializable
    data class Node(
        val id: String,
        val parent: String,
        val left: String,
        val kind: String,        // "e" | "s" | "c" | "r"
        val payload: String,
        var deleted: Boolean = false,
        val lamport: Long,
        val dev: String,
    )

    @Serializable
    data class State(val device: String, val clock: Long, val nodes: List<Node>)

    private val nodes = LinkedHashMap<String, Node>()
    private var clock = 0L
    private val json = Json { ignoreUnknownKeys = true }

    fun toState(): State = State(device, clock, nodes.values.map { it.copy() })

    fun loadState(json: String) {
        val s = runCatching { this.json.decodeFromString<State>(json) }.getOrNull() ?: return
        nodes.clear(); s.nodes.forEach { nodes[it.id] = it.copy() }; clock = s.clock
    }

    fun serialize(): String = json.encodeToString(toState())

    /** Merges a batch of remote node ops. Commutative + idempotent; deletion is monotonic. */
    fun apply(ops: List<Node>) {
        for (op in ops) {
            clock = maxOf(clock, op.lamport)
            val cur = nodes[op.id]
            if (cur == null) nodes[op.id] = op.copy()
            else if (op.deleted) cur.deleted = true
        }
    }

    /** Renders the current merged document back to flat XML. */
    fun render(): String {
        val sb = StringBuilder()
        renderChildren("", sb)
        return sb.toString()
    }

    private fun renderChildren(parent: String, sb: StringBuilder) {
        for (node in orderedChildren(parent)) {
            if (node.deleted) continue // skip deleted node + its subtree (never descend)
            when (node.kind) {
                "e" -> { sb.append(node.payload); renderChildren(node.id, sb); sb.append(closeTagOf(node.payload)) }
                else -> sb.append(node.payload) // "s", "c", "r"
            }
        }
    }

    /** RGA order of a parent's children, INCLUDING tombstones (needed to chain `left` links). */
    private fun orderedChildren(parent: String): List<Node> {
        val byLeft = HashMap<String, MutableList<Node>>()
        for (n in nodes.values) if (n.parent == parent) byLeft.getOrPut(n.left) { mutableListOf() }.add(n)
        if (byLeft.isEmpty()) return emptyList()
        val cmp = compareByDescending<Node> { it.lamport }.thenByDescending { it.dev }
        for (l in byLeft.values) l.sortWith(cmp)
        val result = ArrayList<Node>()
        val stack = ArrayDeque<Node>()
        byLeft[""]?.asReversed()?.forEach { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val e = stack.removeLast()
            result.add(e)
            byLeft[e.id]?.asReversed()?.forEach { stack.addLast(it) }
        }
        return result
    }

    // --- Reconcile the current tree toward a new flat-XML state, producing ops ---

    /** Diffs [xml] against the current tree, mutating this replica and returning ops to broadcast. */
    fun update(xml: String): List<Node> {
        val ops = ArrayList<Node>()
        diffChildren("", parse(xml).children, ops)
        return ops
    }

    private fun newId(): String { clock += 1; return "$clock:$device" }

    private fun keyOfDesired(d: Desired): String = when (d) {
        is Desired.El -> (if (d.selfClosing) "s:" else "e:") + d.tag
        is Desired.Text -> (if (d.raw) "r:" else "c:") + d.text
    }

    private fun keyOfNode(n: Node): String = n.kind + ":" + n.payload

    private fun diffChildren(parent: String, desired: List<Desired>, ops: MutableList<Node>) {
        val current = orderedChildren(parent).filter { !it.deleted }
        val keyC = current.map { keyOfNode(it) }
        val keyD = desired.map { keyOfDesired(it) }

        // Common prefix / suffix are matched directly (keeps typing O(changed), not O(n^2)).
        var pre = 0
        while (pre < current.size && pre < desired.size && keyC[pre] == keyD[pre]) pre++
        var suf = 0
        while (suf < current.size - pre && suf < desired.size - pre &&
            keyC[current.size - 1 - suf] == keyD[desired.size - 1 - suf]) suf++

        var prevId = ""
        fun reuse(node: Node, d: Desired) {
            if (d is Desired.El && node.kind == "e") diffChildren(node.id, d.children, ops)
            prevId = node.id
        }
        fun insert(d: Desired) {
            val id = newId()
            val kind = when (d) { is Desired.El -> if (d.selfClosing) "s" else "e"; is Desired.Text -> if (d.raw) "r" else "c" }
            val payload = when (d) { is Desired.El -> d.tag; is Desired.Text -> d.text }
            val node = Node(id, parent, prevId, kind, payload, false, clock, device)
            nodes[id] = node; ops.add(node.copy())
            if (d is Desired.El && !d.selfClosing) diffChildren(id, d.children, ops)
            prevId = id
        }

        // prefix
        for (i in 0 until pre) reuse(current[i], desired[i])
        // middle via LCS
        val midC = current.subList(pre, current.size - suf)
        val midD = desired.subList(pre, desired.size - suf)
        val pairs = lcs(midD.map { keyOfDesired(it) }, midC.map { keyOfNode(it) })
        val matchedC = HashSet<Int>()
        var pp = 0
        for (di in midD.indices) {
            if (pp < pairs.size && pairs[pp].first == di) {
                val ci = pairs[pp].second; pp++
                matchedC.add(ci)
                reuse(midC[ci], midD[di])
            } else insert(midD[di])
        }
        for (ci in midC.indices) if (ci !in matchedC) tombstone(midC[ci], ops)
        // suffix
        for (i in 0 until suf) reuse(current[current.size - suf + i], desired[desired.size - suf + i])
    }

    private fun tombstone(node: Node, ops: MutableList<Node>) {
        if (node.deleted) return
        node.deleted = true
        ops.add(node.copy(deleted = true))
        // Children cascade at render time; no need to tombstone them individually.
    }

    /** Longest common subsequence of two key lists → matched (indexInA, indexInB) pairs, in order. */
    private fun lcs(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val n = a.size; val m = b.size
        if (n == 0 || m == 0) return emptyList()
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out.add(i to j); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }

    // --- Flat XML → desired tree ---

    private sealed class Desired {
        class El(val tag: String, val selfClosing: Boolean, val children: MutableList<Desired> = ArrayList()) : Desired()
        class Text(val text: String, val raw: Boolean) : Desired()
    }

    private fun parse(xml: String): Desired.El {
        val root = Desired.El("", false)
        val stack = ArrayDeque<Desired.El>(); stack.addLast(root)
        val n = xml.length
        var i = 0
        var inBinary = false
        while (i < n) {
            if (xml[i] == '<') {
                var j = i + 1; var quote = '\u0000'
                while (j < n) {
                    val c = xml[j]
                    when {
                        quote != '\u0000' -> if (c == quote) quote = '\u0000'
                        c == '"' || c == '\'' -> quote = c
                        c == '>' -> break
                    }
                    j++
                }
                val tag = xml.substring(i, minOf(j + 1, n))
                when {
                    tag.startsWith("</") -> { if (stack.size > 1) stack.removeLast(); if (tag.startsWith("</office:binary-data")) inBinary = false }
                    // XML declaration, processing instructions, comments, doctype: opaque leaves (no close tag).
                    tag.startsWith("<?") || tag.startsWith("<!") -> stack.last().children.add(Desired.El(tag, true))
                    tag.endsWith("/>") -> stack.last().children.add(Desired.El(tag, true))
                    else -> {
                        val el = Desired.El(tag, false); stack.last().children.add(el); stack.addLast(el)
                        if (tag.startsWith("<office:binary-data")) inBinary = true
                    }
                }
                i = j + 1
            } else {
                val end = xml.indexOf('<', i).let { if (it < 0) n else it }
                val text = xml.substring(i, end)
                if (inBinary) stack.last().children.add(Desired.Text(text, raw = true))
                else for (c in text) stack.last().children.add(Desired.Text(c.toString(), raw = false))
                i = end
            }
        }
        return root
    }

    private fun closeTagOf(openTag: String): String {
        // "<name att...>" -> "</name>"
        var k = 1
        while (k < openTag.length && openTag[k] != ' ' && openTag[k] != '>' && openTag[k] != '\t' && openTag[k] != '\n') k++
        return "</" + openTag.substring(1, k) + ">"
    }
}
