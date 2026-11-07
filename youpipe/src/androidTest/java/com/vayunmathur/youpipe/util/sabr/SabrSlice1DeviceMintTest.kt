package com.vayunmathur.youpipe.util.sabr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException

/**
 * Slice-1 (sabr/) device pass: a complete PO-token mint on a real WebView.
 * The BotGuard "VM" is a small script we supply as the challenge's
 * interpreter: it registers the async snapshot function the asset polls
 * for, pushes a getMinter into the WebPO signal output, and that minter
 * returns fixed token bytes — so create, mint, provider and session-token
 * paths all run end to end without YouTube.
 */
@RunWith(AndroidJUnit4::class)
class SabrSlice1DeviceMintTest {

    companion object {
        val FAKE_VM_JS = """
            window.MyVM = {
              a: function(program, vmFunctionsCallback, arg2, el, noop, arr, undef, bool, loggers) {
                var asyncSnapshot = function(onSuccess, args) {
                  args[2].push(function(integrityToken) {
                    return function(identifier) { return new Uint8Array([1, 2, 3, 4]); };
                  });
                  onSuccess("BOTGUARD_RESPONSE");
                };
                vmFunctionsCallback(asyncSnapshot, function(){}, function(){}, function(){});
                return [function(){}];
              }
            };
        """.trimIndent()

        private fun bootstrap(binding: YoutubePoTokenBinding = YoutubePoTokenBinding.CONTENT): YoutubePageAttestationBootstrap =
            YoutubePageAttestationBootstrap(
                visitorData = "visitor-data",
                clientName = "WEB",
                clientVersion = "2.5.1",
                binding = binding,
                eventId = "evt-1",
                challenge = SabrAttChallengeData("PROG", "MyVM", FAKE_VM_JS, null),
            )

        fun homePage(): String {
            val challengeJson = kotlinx.serialization.json.buildJsonObject {
                put("bgChallenge", kotlinx.serialization.json.buildJsonObject {
                    put("program", "PROG"); put("globalName", "MyVM")
                    put("interpreterJavascript", kotlinx.serialization.json.buildJsonObject {
                        put("privateDoNotAccessOrElseSafeScriptWrappedValue", FAKE_VM_JS.replace("\n", " "))
                    })
                })
            }.toString()
            return "<html><script>" +
                "ytcfg.set({\"EVENT_ID\":\"evt-1\",\"EOM_VISITOR_DATA\":\"visitor-data\",\"INNERTUBE_CONTEXT\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"2.5.1\"}}});" +
                "window.ytAtN({'R': '" + challengeJson.replace("\\", "\\\\").replace("'", "\\'") + "'});" +
                "</script></html>"
        }

        /** Routes by URL: GenerateIT -> integrity token, everything else -> UMP or home by caller. */
        class RoutingDownloader(private val fallback: (Request) -> Response) : Downloader() {
            override fun execute(request: Request): Response {
                if (request.url.contains("jnn-pa.googleapis.com")) {
                    return Response(200, null, null, "[\"aGk=\", \"3600\"]", request.url)
                }
                return fallback(request)
            }
        }
    }

    @Test fun generatorMintsWithFakeBotGuardVm() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NewPipe.init(RoutingDownloader { Response(200, null, null, "", it.url) })
        val generator = LocalDomPoTokenGenerator.create(context, bootstrap())
        assertTrue(!generator.isExpired())
        assertTrue(generator.mint("vid-1").contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertTrue(generator.mint("vid-2").contentEquals(byteArrayOf(1, 2, 3, 4)))
        generator.close(); generator.close()
        try {
            generator.mint("vid-3"); fail("closed generator must refuse")
        } catch (expected: SabrProtocolException) {
            assertTrue(expected.message!!.contains("closed"))
        }
    }

    @Test fun providerMintsContentAndSessionTokens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NewPipe.init(RoutingDownloader { request ->
            Response(200, null, null, homePage(), request.url)
        })
        val provider = LocalDomPoTokenProvider(context)
        val bytes = provider.getPoTokenBytes("vid-9")
        assertTrue(bytes!!.contentEquals(byteArrayOf(1, 2, 3, 4)))
        val sessionToken = provider.getSessionPoToken("WEB", Localization("en", "US"), ContentCountry("US"), false)
        assertEquals("visitor-data", sessionToken!!.visitorData)
        assertTrue(sessionToken.getPoToken().isNotBlank())
        provider.prewarmSessionPoToken()
        provider.invalidate() // completed state: tears down and re-warms; must not throw
        val again = provider.getPoTokenBytes("vid-9")
        assertTrue(again!!.contentEquals(byteArrayOf(1, 2, 3, 4)))
    }
}
