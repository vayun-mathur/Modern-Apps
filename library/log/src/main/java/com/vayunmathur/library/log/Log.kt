package com.vayunmathur.library.log

import android.util.Log as AndroidLog
import java.security.SecureRandom

/**
 * Repo-wide logging facade. Replaces direct `android.util.Log` use everywhere; a
 * fatal lint check (`DirectAndroidLog`) bans that import outside this file.
 *
 * Four levels, each an explicit `tag` plus `content` (existing call-site `TAG`
 * constants are kept as-is, so custom tags like `MaAuto.Server` survive):
 *  - [dev]    → DEBUG, only when [init] was called with `isDev = true`
 *               (the app's `debuggable` flag, wired by `MaInitProvider`)
 *  - [debug]  → DEBUG, always — shippable diagnostics only (see below)
 *  - [status] → INFO, always (absorbs old `Log.i` and `Log.w` — see below)
 *  - [error]  → ERROR, always
 *
 * The old `Log.w` maps to [status], not a warn level: nothing in the repo gates
 * behaviour on warn-vs-info, and keeping four functions keeps every call site a
 * mechanical rewrite. `Log.v` maps to [debug].
 *
 * The tag is an explicit parameter rather than a stack-derived caller class
 * name: R8 inlines and renames frames, so any `Thread.stackTrace` / reflection
 * scheme degrades in release builds, and an explicit tag costs nothing.
 *
 * ## [dev] vs [debug] — which one do I use?
 *
 * The split is about what is safe to ship, not about severity (both emit at
 * DEBUG priority):
 *  - [dev] is **dev-only**: silent in release builds. Use it for verbose or
 *    potentially sensitive detail — request/response payloads, decoded
 *    locations, protocol internals — anything you would not want in a shipped
 *    logcat (e.g. Uber GraphQL variables in taxi, PQC details in findfamily).
 *  - [debug] is **shippable diagnostics**: always emitted, so it must stay
 *    low-volume and payload-free — status codes, sizes, lifecycle/state
 *    transitions and ids, never bodies or secrets. Shipped code relies on it
 *    (e.g. every Lyft/Uber HTTP status line and ride-terminal transition in
 *    taxi, "no fix yet" heartbeat diagnostics in findfamily), which is why
 *    [debug] is deliberately *not* gated on [init]: gating it would silence
 *    those diagnostics in release.
 *
 * Each app gets the dev gate for free: `MaInitProvider` (merged from this
 * module's manifest) calls [init] from the manifest `debuggable` flag before
 * `Application.onCreate`. Until then [dev] is silent.
 *
 * ## Error correlation
 *
 * [newErrorId] mints a short per-error id (8 lowercase hex chars) and
 * [errorWithId] logs an error together with one, returning the id so the
 * caller can surface the same id in UI/error state and a user report can be
 * matched to the log line. Ids are correlation aids, not unique identifiers:
 * 32 bits collide occasionally, which is fine for matching one report.
 */
object Log {

    private val errorIdRandom = SecureRandom()

    @Volatile
    private var devEnabled: Boolean = false

    /**
     * Wire the dev gate. Call once from `Application.onCreate` with the app's
     * `BuildConfig.DEV_BUILD` (true on `dev` builds, false on `release`).
     * Idempotent — the last call wins.
     */
    fun init(isDev: Boolean) {
        devEnabled = isDev
    }

    /** True once [init] has enabled dev logging. Exposed for tests and diagnostics. */
    fun isDevEnabled(): Boolean = devEnabled

    /** DEBUG-level log that only emits when the dev gate is enabled. See [init]. */
    @JvmStatic
    fun dev(tag: String, content: String) {
        if (devEnabled) AndroidLog.d(tag, content)
    }

    /** DEBUG-level log with an attached throwable, gated like [dev]. */
    @JvmStatic
    fun dev(tag: String, content: String, tr: Throwable?) {
        if (devEnabled) AndroidLog.d(tag, content, tr)
    }

    /**
     * DEBUG-level log, always emitted — shippable diagnostics only: status
     * codes, sizes, lifecycle/state transitions and ids. No payloads, bodies
     * or secrets here; verbose or sensitive detail belongs in [dev].
     */
    @JvmStatic
    fun debug(tag: String, content: String) {
        AndroidLog.d(tag, content)
    }

    /**
     * DEBUG-level log with an attached throwable, always emitted. Same
     * payload-free contract as [debug] (tag, content) above.
     */
    @JvmStatic
    fun debug(tag: String, content: String, tr: Throwable?) {
        AndroidLog.d(tag, content, tr)
    }

    /** INFO-level log, always emitted. Absorbs old `Log.i` and `Log.w` call sites. */
    @JvmStatic
    fun status(tag: String, content: String) {
        AndroidLog.i(tag, content)
    }

    /** INFO-level log with an attached throwable, always emitted. */
    @JvmStatic
    fun status(tag: String, content: String, tr: Throwable?) {
        AndroidLog.i(tag, content, tr)
    }

    /** ERROR-level log, always emitted. */
    @JvmStatic
    fun error(tag: String, content: String) {
        AndroidLog.e(tag, content)
    }

    /** ERROR-level log with an attached throwable, always emitted. */
    @JvmStatic
    fun error(tag: String, content: String, tr: Throwable?) {
        AndroidLog.e(tag, content, tr)
    }

    /**
     * A fresh short error-correlation id: 8 lowercase hex chars (32 random
     * bits), e.g. `3f9a1c07`. Cheap and dependency-free; see the error
     * correlation note on [Log].
     */
    @JvmStatic
    fun newErrorId(): String {
        val bytes = ByteArray(ERROR_ID_BYTES)
        errorIdRandom.nextBytes(bytes)
        val sb = StringBuilder(ERROR_ID_HEX_LENGTH)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * ERROR-level log stamped with a fresh [newErrorId]; returns that id so
     * the caller can show the same id in error UI/state and a user report can
     * be grepped back to this line. The logged message is
     * `"errorId=<id> <content>"`.
     */
    @JvmStatic
    fun errorWithId(tag: String, content: String): String {
        val id = newErrorId()
        AndroidLog.e(tag, "errorId=$id $content")
        return id
    }

    /** [errorWithId] with an attached throwable. */
    @JvmStatic
    fun errorWithId(tag: String, content: String, tr: Throwable?): String {
        val id = newErrorId()
        AndroidLog.e(tag, "errorId=$id $content", tr)
        return id
    }

    private const val ERROR_ID_BYTES = 4
    private const val ERROR_ID_HEX_LENGTH = 8
    private const val HEX = "0123456789abcdef"
}
