package com.vayunmathur.findfamily.intents

import com.vayunmathur.findfamily.data.FFDatabase
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.util.Networking
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.library.room.buildDatabase
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/** Wire shape of the `DATA` extra; field names must match the caller. */
@Serializable
data class CreateLinkData(val name: String, val expiryMillis: Long)

/**
 * Lets another app (e.g. the messages app) mint a FindFamily location-sharing
 * link. Reuses FindFamily's temporary-link mechanism: generate an RSA keypair,
 * persist a [TemporaryLink] (the background tracking service then publishes
 * encrypted location for it until it expires), and return the same recipient
 * URL the in-app copy button produces.
 */
@OptIn(InternalSerializationApi::class)
class CreateLinkIntent : AssistantIntent<CreateLinkData, String>(
    serializer<CreateLinkData>(),
    serializer<String>(),
) {
    override suspend fun performCalculation(input: CreateLinkData): String {
        val keypair = Networking.generateKeyPair()
        val link = TemporaryLink(
            input.name,
            Base64.encode(keypair.privateKeyPem),
            Base64.encode(keypair.publicKeyPem),
            Clock.System.now() + input.expiryMillis.milliseconds,
        )
        val id = buildDatabase<FFDatabase>().temporaryLinkDao().upsert(link)
        // Must match the URL format produced by TemporaryLinkCard in MainPage.
        return "https://findfamily.cc/view/$id#key=${link.key}"
    }
}
