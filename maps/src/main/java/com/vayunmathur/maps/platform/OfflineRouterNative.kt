package com.vayunmathur.maps.platform

import com.vayunmathur.library.log.NativeLoader

/**
 * The single guarded loader for `libofflinerouter.so` (docs/package-structure.md §4.4).
 *
 * [OfflineRouter][com.vayunmathur.maps.util.OfflineRouter] keeps the JNI-frozen
 * surface — native code resolves its members by class and name — but it must never
 * load the library itself: an unguarded `init { System.loadLibrary(...) }` poisons
 * the class with [ExceptionInInitializerError] on every later touch when the `.so`
 * is absent (wrong ABI, failed Rust build). Every caller instead checks [available]
 * here and degrades to "no routing" when it is false.
 */
object OfflineRouterNative {

    /** Whether `libofflinerouter.so` loaded and the JNI surface is usable. */
    val available: Boolean = NativeLoader.load("offlinerouter", "OfflineRouterNative")
}
