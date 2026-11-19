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
