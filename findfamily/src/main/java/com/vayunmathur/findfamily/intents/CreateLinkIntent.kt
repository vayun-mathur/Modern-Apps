package com.vayunmathur.findfamily.intents

import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.ui.dialogs.encodeBase26
import com.vayunmathur.findfamily.util.Networking
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/** Wire shape of the `DATA` extra; field names must match the caller. */
@Serializable
data class CreateLinkData(val name: String, val expiryMillis: Long)

/**
 * Lets another app (e.g. the messages app) mint a FindFamily location-sharing
 * link. Reuses FindFamily's temporary-link mechanism: generate an ML-KEM link key,
 * persist a [TemporaryLink] (the background tracking service then publishes
 * encrypted location for it until it expires), and return the same recipient URL
 * the in-app copy button produces.
 */
@OptIn(InternalSerializationApi::class)
class CreateLinkIntent : AssistantIntent<CreateLinkData, String>(
    serializer<CreateLinkData>(),
    serializer<String>(),
) {
    override suspend fun performCalculation(input: CreateLinkData): String {
        // Links are post-quantum only — with no classic fallback, a PQC keygen failure has to
        // fail the whole call rather than hand back a link that can never publish.
        val linkKey = Networking.generatePqcLinkKey()
        val link = TemporaryLink(
            name = input.name,
            deleteAt = Clock.System.now() + input.expiryMillis.milliseconds,
            pqcPublicKey = linkKey.publicBundleB64,
            pqcSeed = linkKey.seedB64Url,
        )
        FindFamilyRepository.get(this).temporaryLinkStore.upsert(link)
        // The id is generated with the link (newTemporaryLinkId), not handed back by the
        // insert, so read it off the entity — @Upsert returns -1 on the update path.
        // Must match the URL format produced by TemporaryLinkCard in MainPage.
        return "${Networking.SHARE_BASE}/${link.id.encodeBase26()}#s=${linkKey.seedB64Url}"
    }
}
