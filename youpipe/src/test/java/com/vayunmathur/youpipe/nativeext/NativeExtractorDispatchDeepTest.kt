package com.vayunmathur.youpipe.nativeext

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.vayunmathur.youpipe.network.NativeExtractor
import com.vayunmathur.youpipe.network.NativeExtractorException

/** Native API dispatch: without the Rust .so every call must fail, not hang or succeed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeExtractorDispatchDeepTest {
    private fun fails(block: suspend () -> Any?) {
        val e = assertFailsWith<Throwable> { runBlocking { block() } }
        assertTrue(e is UnsatisfiedLinkError || e is NativeExtractorException || e is ExceptionInInitializerError, e.toString())
    }
    @Test fun search_failsWithoutNativeLib() = fails { NativeExtractor.search("cats", "videos") }
    @Test fun searchPage_failsWithoutNativeLib() = fails { NativeExtractor.searchPage("tok") }
    @Test fun suggestions_failsWithoutNativeLib() = fails { NativeExtractor.suggestions("cat") }
    @Test fun streamInfo_failsWithoutNativeLib() = fails { NativeExtractor.streamInfo("abc") }
    @Test fun channelInfo_failsWithoutNativeLib() = fails { NativeExtractor.channelInfo("UCx") }
    @Test fun playlistInfo_failsWithoutNativeLib() = fails { NativeExtractor.playlistInfo("PLx") }
    @Test fun trending_failsWithoutNativeLib() = fails { NativeExtractor.trending() }
    @Test fun browseContinuation_failsWithoutNativeLib() = fails { NativeExtractor.browseContinuation("tok") }
    @Test fun comments_failsWithoutNativeLib() = fails { NativeExtractor.comments("abc") }
    @Test fun commentsPage_failsWithoutNativeLib() = fails { NativeExtractor.commentsPage("tok") }
}
