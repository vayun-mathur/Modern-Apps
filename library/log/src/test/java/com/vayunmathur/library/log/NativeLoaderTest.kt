package com.vayunmathur.library.log

import kotlin.test.Test
import kotlin.test.assertFalse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The shared native-load guard: a library that does not exist (every
 * library, on a host JVM) must answer false and log the failure rather
 * than throwing — that is the whole point of routing the twenty-plus
 * hand-rolled `<Feature>Native` load guards through here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeLoaderTest {

    @Test
    fun missingLibraryReturnsFalseAndLogsInsteadOfThrowing() {
        assertFalse(NativeLoader.load("definitely_not_a_real_library_zzz", "NativeLoaderTest"))
        val logs = ShadowLog.getLogsForTag("NativeLoaderTest")
        assert(logs.any { it.msg.contains("definitely_not_a_real_library_zzz") }) {
            "expected the failed load to be logged, got $logs"
        }
    }
}

/**
 * The regression this guards: consumer modules run plain host-JVM unit
 * tests WITHOUT Robolectric, where `android.util.Log` is an unmocked
 * stub that throws on every call. Routing the wrappers through
 * [NativeLoader] in 086ec10fa put `Log.error` on that path, so a failed
 * load threw out of `load()` and poisoned each wrapper's class init
 * (`ExceptionInInitializerError`, then `NoClassDefFoundError`).
 * Without the Robolectric runner this class runs against the stub
 * android.jar, reproducing that environment: logging does fail here,
 * and `load()` must still return false without throwing.
 */
class NativeLoaderHostJvmTest {

    @Test
    fun missingLibraryReturnsFalseEvenWhenLoggingThrows() {
        assertFalse(NativeLoader.load("definitely_not_a_real_library_zzz", "NativeLoaderHostJvmTest"))
    }
}
