package com.vayunmathur.youpipe.util.sabr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException

/**
 * Slice-1 (sabr/) device pass, mint variants: a minter whose obtain step
 * returns nothing (token-error path through the bridge), and a
 * SESSION-binding provider whose session token is minted at build time.
 */
@RunWith(AndroidJUnit4::class)
class SabrSlice1DeviceMintExtraTest {

    companion object {
        val ERROR_VM_JS = """
            window.MyVM = {
              a: function(program, vmFunctionsCallback) {
                var asyncSnapshot = function(onSuccess, args) {
                  args[2].push(function(integrityToken) {
                    return function(identifier) { return null; };
                  });
                  onSuccess("BOTGUARD_RESPONSE");
                };
                vmFunctionsCallback(asyncSnapshot, function(){}, function(){}, function(){});
                return [function(){}];
              }
            };
        """.trimIndent()
    }

    @Test fun mintFailsWhenObtainReturnsNothing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NewPipe.init(SabrSlice1DeviceMintTest.Companion.RoutingDownloader {
            Response(200, null, null, "", it.url)
        })
        val bootstrap = YoutubePageAttestationBootstrap(
            visitorData = "visitor-data", clientName = "WEB", clientVersion = "2.5.1",
            binding = YoutubePoTokenBinding.CONTENT, eventId = "evt-1",
            challenge = SabrAttChallengeData("PROG", "MyVM", ERROR_VM_JS, null),
        )
        val generator = LocalDomPoTokenGenerator.create(context, bootstrap)
        try {
            generator.mint("vid-err"); fail("a minter returning nothing must fail the mint")
        } catch (expected: SabrProtocolException) {
            assertTrue(expected.message!!.isNotBlank())
        }
        generator.close()
    }

    @Test fun sessionBindingProviderMintsSessionTokenAtBuild() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NewPipe.init(SabrSlice1DeviceMintTest.Companion.RoutingDownloader { request ->
            if (request.url.contains("youtube.com")) {
                Response(200, null, null, sessionHomePage(), request.url)
            } else {
                Response(200, null, null, "", request.url)
            }
        })
        val provider = LocalDomPoTokenProvider(context)
        // SESSION binding: the session token is minted from the visitor data at build time,
        // and getPoTokenBytes returns a clone of it rather than minting per video.
        val first = provider.getPoTokenBytes("vid-1")
        val second = provider.getPoTokenBytes("vid-2")
        assertTrue(first!!.contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertTrue(second!!.contentEquals(first))
        val sessionToken = provider.getSessionPoToken("WEB", Localization("en", "US"), ContentCountry("US"), true)
        assertTrue(sessionToken!!.visitorData == "visitor-data")
        assertTrue(sessionToken.getPoToken().isNotBlank())
    }

    private fun sessionHomePage(): String {
        val challengeJson = kotlinx.serialization.json.buildJsonObject {
            put("bgChallenge", kotlinx.serialization.json.buildJsonObject {
                put("program", "PROG"); put("globalName", "MyVM")
                put("interpreterJavascript", kotlinx.serialization.json.buildJsonObject {
                    put("privateDoNotAccessOrElseSafeScriptWrappedValue", SabrSlice1DeviceMintTest.Companion.FAKE_VM_JS.replace("\n", " "))
                })
            })
        }.toString()
        val escaped = challengeJson.replace("\\", "\\\\").replace("'", "\\'")
        return "<html><script>" +
            "ytcfg.set({\"EVENT_ID\":\"evt-1\",\"EOM_VISITOR_DATA\":\"visitor-data\",\"INNERTUBE_CONTEXT\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"2.5.1\"}},\"WEB_PLAYER_CONTEXT_CONFIGS\":{\"WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH\":{\"serializedExperimentFlags\":\"html5_generate_session_po_token=true\"}}});" +
            "window.ytAtN({'R': '$escaped'});" +
            "</script></html>"
    }
}
