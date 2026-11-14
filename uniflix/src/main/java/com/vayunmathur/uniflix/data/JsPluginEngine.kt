package com.vayunmathur.uniflix.data

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.function
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import com.vayunmathur.uniflix.domain.JsPluginManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wall-clock bound for one plugin call. QuickJS's [QuickJs.evaluationTimeoutMillis]
 * hard-bounds time spent executing JavaScript; the [withTimeout] below is a
 * backstop for it, and socket timeouts in [NetworkClientBridge] bound network.
 */
private const val ENGINE_TIMEOUT_MS = 15_000L
private const val MAX_RANDOM_BYTES = 1024
private const val BRIDGE_CONNECT_TIMEOUT_MS = 10_000L
private const val BRIDGE_READ_TIMEOUT_MS = 15_000L
private const val GCM_TAG_BITS = 128

/** One bridged HTTP response, mirroring NetworkClient's RawResponse. */
data class FetchResult(
    val status: Int,
    val bytes: ByteArray,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * Host HTTP for JS plugins. Production delegates to [NetworkClient] so the
 * STANDARD trust bundle and pinning apply; tests inject a fake (no Robolectric).
 */
interface NetworkBridge {
    suspend fun fetch(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): FetchResult
}

/** Production bridge: all plugin traffic flows through NetworkClient. */
object NetworkClientBridge : NetworkBridge {
    override suspend fun fetch(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): FetchResult {
        val resp = com.vayunmathur.library.network.NetworkClient.execute(
            url,
            method = method,
            headers = headers,
            body = body,
            connectTimeoutMs = BRIDGE_CONNECT_TIMEOUT_MS,
            readTimeoutMs = BRIDGE_READ_TIMEOUT_MS,
        )
        return FetchResult(status = resp.status, bytes = resp.bytes, headers = resp.headers)
    }
}

private val bridgeJson = Json { ignoreUnknownKeys = true }

/** Live WASM instances for one engine invocation, keyed by small integer handles. */
private class WasmTable {
    private var nextId = 1
    private val instances = mutableMapOf<Int, Instance>()

    fun add(bytes: ByteArray): Int {
        // Zero-import modules take the empty-imports path; the default memory
        // factory is ByteBufferMemory, the Android-preferred implementation.
        // Never select ByteArrayMemory (VarHandle/MethodHandles).
        val instance = Instance.builder(Parser.parse(bytes)).build()
        val id = nextId++
        instances[id] = instance
        return id
    }

    fun get(id: Int): Instance = instances[id] ?: error("__uwasm: unknown handle $id")
}

/**
 * Runs one installable JS plugin inside QuickJS with generic host capabilities.
 *
 * The contract is fully synchronous by design: plugin functions
 * (`resolve(args)`, `servers()`, `alternates(args)`) are plain sync functions
 * returning JSON strings, and every host binding is a sync `function`. In
 * particular `__ufetch` blocks the JS thread (via `runBlocking` over
 * [NetworkBridge]) instead of returning a promise — quickjs-kt only unwraps a
 * promise result on paths no plugin should depend on, so promises are kept
 * out of the contract entirely.
 *
 * Exposed globals (capabilities only — no provider logic, origins, or keys):
 * - `__ufetch(url, method, headersJson, body?)` -> `{status, ok, text, bytes, headers}` (blocking)
 * - `__uwasm_embedded()` -> handle for the plugin's embedded WASM section (0 when absent)
 * - `__uwasm_new(bytes)` / `__uwasm_alloc(handle, size)` / `__uwasm_write(handle, ptr, data)` /
 *   `__uwasm_read(handle, ptr, len)` / `__uwasm_call(handle, name, argsJson)` / `__uwasm_free(handle, ptr, size)`
 * - `__uaesgcm_open(key, iv, aad, ctAndTag)` -> plaintext bytes (throws on auth failure)
 * - `__urand(n)` -> n random bytes
 *
 * Byte arrays cross the boundary as `Uint8Array` (Kotlin `UByteArray`); inputs
 * also accept `Int8Array` (Kotlin `ByteArray`).
 *
 * A fresh runtime is created per call and closed in `finally`. There is no
 * DOM, no fetch, and no system trust — all network flows through
 * [NetworkBridge].
 */
@OptIn(ExperimentalUnsignedTypes::class)
class JsPluginEngine(
    private val manifest: JsPluginManifest,
    private val network: NetworkBridge,
    private val timeoutMs: Long = ENGINE_TIMEOUT_MS,
    private val bridgeTimeoutMs: Long = timeoutMs,
) {
    /** Calls `resolve(args)`; returns the plugin's JSON string. */
    suspend fun resolve(argsJson: String): String = call("resolve", argsJson)

    /** Calls `servers()`; returns the plugin's JSON string array. */
    suspend fun servers(): String = call("servers", null)

    /** Calls `alternates(args)`; `[]` when the plugin does not define it. */
    suspend fun alternates(argsJson: String): String =
        runCatching { call("alternates", argsJson) }.getOrDefault("[]")

    private suspend fun call(function: String, argsJson: String?): String {
        val qjs = runCatching { QuickJs.create(Dispatchers.Default) }.getOrElse {
            error("'${manifest.name}' could not start JS runtime: ${it.message}")
        }
        try {
            qjs.evaluationTimeoutMillis = timeoutMs
            val wasm = WasmTable()
            defineFetch(qjs)
            defineWasm(qjs, wasm)
            defineCrypto(qjs)
            return withTimeout(timeoutMs) {
                runCatching {
                    qjs.evaluate<Unit>(manifest.jsSource, "${manifest.id}.js")
                }.getOrElse {
                    throw mapError("failed to load: ${it.message}")
                }
                // Args travel as a JSON string literal parsed inside JS, so
                // object literals never hit expression-position parsing and
                // quoting is handled by the JSON encoding, not by hand.
                //
                // One sync IIFE per call: the async-constructor check rejects
                // promise-returning plugin fns up front (quickjs-kt does not
                // unwrap promise results on the JNI path), and the typeof
                // check keeps non-string returns as JS errors with the fn
                // name instead of converter failures.
                val args = if (argsJson == null) {
                    "undefined"
                } else {
                    "JSON.parse(${bridgeJson.encodeToString(argsJson)})"
                }
                val expression = "(() => { if ($function.constructor.name === 'AsyncFunction') " +
                    "throw new Error('$function() must be sync and return a JSON string (no promises)'); " +
                    "var r = $function($args); " +
                    "if (typeof r !== 'string') throw new Error('$function() must return a JSON string'); " +
                    "return r; })()"
                val raw = runCatching {
                    qjs.evaluate<String>(expression, "${manifest.id}.js")
                }.getOrElse {
                    failCall(mapError(it))
                }
                if (raw.startsWith("Promise {")) {
                    failCall(mapError("$function() must be sync and return a JSON string (no promises)"))
                }
                raw
            }
        } catch (e: TimeoutCancellationException) {
            qjs.interruptEvaluation()
            throw IllegalStateException("'${manifest.name}' timed out after ${timeoutMs}ms", e)
        } finally {
            runCatching { qjs.close() }
        }
    }

    /** Throws [t]; keeps throw sites out of the JS call path. */
    private fun failCall(t: Throwable): Nothing = throw t

    private fun mapError(cause: Throwable): Throwable {
        // Structured cancellation (outer withTimeout) must propagate as-is so
        // the caller's catch maps it to the timeout message; wrapping it here
        // would defeat the deadline.
        if (cause is TimeoutCancellationException) throw cause
        if (cause is QuickJsInterruptedException) {
            return IllegalStateException("'${manifest.name}' timed out after ${timeoutMs}ms")
        }
        if (cause is QuickJsException) {
            val location = listOfNotNull(cause.fileName, cause.lineNumber?.let { ":$it" })
                .joinToString("").ifBlank { null }
            val detail = listOfNotNull(cause.message, location?.let { "at $it" }).joinToString(" ")
            return IllegalStateException("'${manifest.name}' error: $detail")
        }
        return IllegalStateException("'${manifest.name}' error: ${cause.message}")
    }

    private fun mapError(message: String): Throwable =
        IllegalStateException("'${manifest.name}' $message")

    private fun defineFetch(qjs: QuickJs) {
        // Sync binding over a suspend bridge: blocks the evaluating worker
        // while the fetch runs on IO. Safe because the bridge never re-enters
        // this QuickJs instance (it only touches NetworkClient), and the
        // registry resolves addons sequentially so at most one worker blocks.
        qjs.function("__ufetch") { args ->
            val url = args.getOrNull(0) as? String ?: error("__ufetch: url must be a string")
            val method = args.getOrNull(1) as? String ?: "GET"
            val headersJson = args.getOrNull(2) as? String ?: "{}"
            val body = args.bytesOrNull(3)
            val headers = runCatching {
                bridgeJson.decodeFromString<Map<String, String>>(headersJson)
            }.getOrDefault(emptyMap())
            val res = runBlocking(Dispatchers.IO) {
                // Bounded here, not just by the outer withTimeout: the worker
                // thread blocks inside this binding, so an unbounded fetch
                // would wedge it past every outer deadline.
                withTimeout(bridgeTimeoutMs) { network.fetch(url, method, headers, body) }
            }
            bridgeJson.encodeToString(
                FetchResponse.serializer(),
                FetchResponse(
                    status = res.status,
                    ok = res.isSuccess,
                    text = res.bytes.toString(Charsets.UTF_8),
                    bytes = java.util.Base64.getEncoder().encodeToString(res.bytes),
                ),
            )
        }
    }

    private fun defineWasm(qjs: QuickJs, table: WasmTable) {
        qjs.function("__uwasm_embedded") {
            val bytes = manifest.wasmBytes ?: return@function 0
            table.add(bytes)
        }
        qjs.function("__uwasm_new") { args ->
            table.add(args.bytes(0, "__uwasm_new"))
        }
        qjs.function("__uwasm_alloc") { args ->
            val handle = args.int(0, "__uwasm_alloc")
            val size = args.int(1, "__uwasm_alloc")
            table.get(handle).export("alloc").apply(size.toLong())[0].toInt()
        }
        qjs.function("__uwasm_write") { args ->
            val handle = args.int(0, "__uwasm_write")
            val ptr = args.int(1, "__uwasm_write")
            val data = args.bytes(2, "__uwasm_write")
            table.get(handle).exports().memory("memory").write(ptr, data)
            true
        }
        qjs.function("__uwasm_read") { args ->
            val handle = args.int(0, "__uwasm_read")
            val ptr = args.int(1, "__uwasm_read")
            val len = args.int(2, "__uwasm_read")
            table.get(handle).exports().memory("memory").readBytes(ptr, len).asUByteArray()
        }
        qjs.function("__uwasm_call") { args ->
            val handle = args.int(0, "__uwasm_call")
            val name = args.getOrNull(1) as? String ?: error("__uwasm_call: name required")
            val paramsJson = args.getOrNull(2) as? String ?: "[]"
            val params = runCatching {
                bridgeJson.decodeFromString(ListSerializer(Long.serializer()), paramsJson)
            }.getOrElse { error("__uwasm_call: args must be a JSON array of numbers") }
            table.get(handle).export(name).apply(*params.toLongArray())[0]
        }
        qjs.function("__uwasm_free") { args ->
            val handle = args.int(0, "__uwasm_free")
            val ptr = args.int(1, "__uwasm_free")
            val size = args.int(2, "__uwasm_free")
            table.get(handle).export("dealloc").apply(ptr.toLong(), size.toLong())
            true
        }
    }

    private fun defineCrypto(qjs: QuickJs) {
        qjs.function("__uaesgcm_open") { args ->
            val key = args.bytes(0, "__uaesgcm_open")
            val iv = args.bytes(1, "__uaesgcm_open")
            val aad = args.bytes(2, "__uaesgcm_open")
            val ct = args.bytes(3, "__uaesgcm_open")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(ct).asUByteArray()
        }
        qjs.function("__urand") { args ->
            val n = args.int(0, "__urand")
            if (n < 0 || n > MAX_RANDOM_BYTES) error("__urand: byte count out of range")
            ByteArray(n).also { SecureRandom().nextBytes(it) }.asUByteArray()
        }
    }

    private fun Array<Any?>.int(index: Int, what: String): Int =
        (getOrNull(index) as? Number)?.toInt() ?: error("$what: numeric argument required")

    /** Accepts `Uint8Array` (UByteArray) or `Int8Array` (ByteArray) from JS. */
    private fun Array<Any?>.bytes(index: Int, what: String): ByteArray =
        bytesOrNull(index) ?: error("$what: byte array required")

    private fun Array<Any?>.bytesOrNull(index: Int): ByteArray? =
        when (val value = getOrNull(index)) {
            null -> null
            is ByteArray -> value
            is UByteArray -> value.asByteArray()
            else -> null
        }
}

/**
 * `__ufetch` result as a JSON string (not a JS object): string returns are
 * the proven marshalling shape, so structured host results cross as JSON the
 * plugin parses. `bytes` is base64; decode with a tiny `b64ToBytes` helper.
 */
@kotlinx.serialization.Serializable
private data class FetchResponse(
    val status: Int,
    val ok: Boolean,
    val text: String,
    val bytes: String,
)
