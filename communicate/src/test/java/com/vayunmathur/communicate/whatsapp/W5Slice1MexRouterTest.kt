package com.vayunmathur.communicate.whatsapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.mex.MexPersistedQueryProvider
import com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMex
import com.vayunmathur.communicate.data.whatsapp.registration.WhatsAppPqPreKey
import com.vayunmathur.communicate.data.whatsapp.registration.WhatsAppRegistrationConstants
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): MEX offline router + PQ signing-input framing + persist-id asset.
 * Robolectric (needs Context/assets + Android Log).
 *
 * - WhatsAppMex.call with the client disconnected and an uncaptured op returns
 *   typed `no_persisted_id:<op>` without touching the network.
 * - WhatsAppPqPreKey.signingInput is pure framing (0x08 || pub).
 * - The bundled mex_persist_ids.json parses and carries the three seeded ops.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice1MexRouterTest {

    @Test
    fun mexCall_uncapturedOp_returnsNoPersistedId() = runTest {
        val r = WhatsAppMex.call(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            operationName = "DefinitelyNotACapturedOp",
            variablesJson = "{}",
        )
        assertFalse(r.isSuccess)
        assertEquals("no_persisted_id:DefinitelyNotACapturedOp", r.transportError)
    }

    @Test
    fun signingInput_prefixesKyberTypeByte() {
        val pub = ByteArray(1568) { it.toByte() }
        val input = WhatsAppPqPreKey.signingInput(pub)
        assertEquals(1569, input.size)
        assertEquals(WhatsAppRegistrationConstants.KEY_TYPE_KYBER, input[0])
        assertTrue(input.copyOfRange(1, input.size).contentEquals(pub))
    }

    @Test
    fun persistIdsAsset_hasSeededOps() {
        val ctx: android.content.Context =
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        MexPersistedQueryProvider.invalidate()
        try {
            assertEquals("27462649126753603", MexPersistedQueryProvider.docIdFor(ctx, "QueryGroupInfo"))
            assertEquals("7225825540870559", MexPersistedQueryProvider.docIdFor(ctx, "UsernameSetMutation"))
            assertEquals(null, MexPersistedQueryProvider.docIdFor(ctx, "NoSuchOp"))
        } finally {
            MexPersistedQueryProvider.invalidate()
        }
    }
}
