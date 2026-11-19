package com.vayunmathur.library.log

/**
 * The one guarded way to load a native library.
 *
 * Every `<Feature>Native` wrapper in the repo used to hand-roll this:
 * twenty-plus copies in four variants (a `runCatching` one-liner, a
 * try/catch that logs, a silent try/catch, and — worst — an unguarded
 * `init { System.loadLibrary(...) }` in photos, euicc, passwords,
 * the e2ee libraries and youpipe, where a missing `.so` poisons the
 * class with `ExceptionInInitializerError` on every later touch).
 * Two of the hand-rolled variants also caught only `Exception`, which
 * lets `UnsatisfiedLinkError` — an `Error` — escape the guard entirely.
 *
 * [load] never throws: it catches `Throwable`, treats an
 * already-loaded report as success (the same library can be reached
 * through more than one class loader path), records the outcome
 * through [Log], and returns whether the library is usable, so a
 * wrapper becomes `val available = NativeLoader.load("pdf_render",
 * "PdfNative")` and its callers can fall back instead of crashing.
 * The logging itself is guarded too: on a plain host JVM (unit tests
 * without Robolectric) `android.util.Log` is an unmocked stub that
 * throws, and a logger failure must never escape [load] — otherwise
 * the very call meant to stop `ExceptionInInitializerError` in a
 * wrapper's class init becomes its cause.
 *
 * Lives in `:library:log` because that is the one module every app
 * and library module already depends on; JNI-frozen wrapper classes
 * stay in their apps (their names are symbol-mangling contracts) —
 * only this guard is shared.
 */
object NativeLoader {

    /**
     * Load the library named [name] (without the `lib` prefix or
     * `.so` suffix), logging under [tag]. Returns true when the
     * library is loaded and usable, false when it is absent or was
     * refused; never throws.
     */
    fun load(name: String, tag: String): Boolean = try {
        System.loadLibrary(name)
        logStatus(tag, "lib$name loaded")
        true
    } catch (failure: Throwable) {
        if (failure.message?.contains("already loaded", ignoreCase = true) == true) {
            logStatus(tag, "lib$name already loaded")
            true
        } else {
            logError(tag, "System.loadLibrary($name) failed", failure)
            false
        }
    }

    // Logging must never throw out of [load]: `Log` delegates to
    // `android.util.Log`, which throws on a host JVM without Robolectric
    // (and could throw for other stub/shadow reasons). Swallowing the
    // logger's failure keeps the load outcome — the return value — as
    // the only signal a wrapper's class init depends on.
    private fun logStatus(tag: String, content: String) {
        runCatching { Log.status(tag, content) }
    }

    private fun logError(tag: String, content: String, failure: Throwable) {
        runCatching { Log.error(tag, content, failure) }
    }
}
