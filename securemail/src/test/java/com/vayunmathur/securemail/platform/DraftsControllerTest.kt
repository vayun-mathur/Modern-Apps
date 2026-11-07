package com.vayunmathur.securemail.platform

import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.data.StoredMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DraftsControllerTest {
    @Test fun `draft decode round trips and empty draft detection`() {
        val row = StoredMessage(
            id = "d1", from = "me@maemail.org",
            toJson = """{"to":["a@x.org"],"cc":[],"bcc":["b@x.org"],"inReplyTo":"m1"}""",
            dateMillis = 1, messageId = "d1", blobBase64 = "",
            decryptedBody = "hello", decryptedSubject = "hi", folder = MailFolder.DRAFTS,
        )
        val draft = DraftsController.decode(row)
        assertEquals(listOf("a@x.org"), draft.to)
        assertEquals(listOf("b@x.org"), draft.bcc)
        assertEquals("hi", draft.subject); assertEquals("hello", draft.body)
        assertEquals("m1", draft.inReplyTo)
        assertFalse(draft.isEmpty)
        assertTrue(Draft().isEmpty)
        assertTrue(Draft(subject = "x").isEmpty.not())
        val bad = DraftsController.decode(row.copy(toJson = "not json", from = ""))
        assertTrue(bad.to.isEmpty())
        assertNull(bad.from)
    }
}
