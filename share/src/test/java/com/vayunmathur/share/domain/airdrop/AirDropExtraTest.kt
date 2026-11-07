package com.vayunmathur.share.domain.airdrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirDropExtraTest {

    @Test
    fun `safeRelativePath strips wrappers and rejects traversal`() {
        assertEquals("a.txt", AirDropMessages.safeRelativePath("./a.txt"))
        assertEquals("sub/b.txt", AirDropMessages.safeRelativePath("./sub/b.txt"))
        assertEquals("c.txt", AirDropMessages.safeRelativePath("./NSIRD_AirDrop_42/c.txt"))
        assertEquals("win.txt", AirDropMessages.safeRelativePath(".\\win.txt"))
        assertNull(AirDropMessages.safeRelativePath("../evil.txt"))
        assertNull(AirDropMessages.safeRelativePath("./../evil.txt"))
        assertNull(AirDropMessages.safeRelativePath("./"))
        assertNull(AirDropMessages.safeRelativePath(""))
    }

    @Test
    fun `randomShortId is 12 lowercase hex chars`() {
        val id = AirDropMessages.randomShortId()
        assertEquals(12, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `discover response plist round trip and blank rejection`() {
        val r = AirDropMessages.DiscoverResponse("Mac", "Apple", byteArrayOf(1, 2))
        val back = AirDropMessages.DiscoverResponse.fromPlist(r.toPlist())!!
        assertEquals("Mac", back.receiverComputerName)
        assertEquals("Apple", back.manufacturer)
        assertNull(AirDropMessages.DiscoverResponse.fromPlist(mapOf("ReceiverComputerName" to "  ")))
        assertNull(AirDropMessages.DiscoverResponse.fromPlist(emptyMap()))
    }

    @Test
    fun `ask file plist defaults and forFile typing`() {
        val f = AirDropMessages.AskFile.forFile("photo.JPG", 42L)
        assertEquals("public.jpeg", f.fileType)
        assertEquals("./photo.JPG", f.fileBomPath)
        val back = AirDropMessages.AskFile.fromPlist(f.toPlist().mapKeys { it.key })!!
        assertEquals("photo.JPG", back.fileName)
        assertEquals(42L, back.fileSize)
        assertNull(AirDropMessages.AskFile.fromPlist(mapOf("FileName" to "")))
        val minimal = AirDropMessages.AskFile.fromPlist(mapOf("FileName" to "x.bin"))!!
        assertEquals("public.data", minimal.fileType)
        assertEquals(-1L, minimal.fileSize)
    }

    @Test
    fun `ask request plist handles string and map transfer ids and links`() {
        val req = AirDropMessages.AskRequest(
            senderComputerName = "Phone", senderModelName = "Pixel", senderId = "abcd",
            files = listOf(AirDropMessages.AskFile.forFile("a.txt", 3L)), transferId = "t1",
        )
        val back = AirDropMessages.AskRequest.fromPlist(req.toPlist())!!
        assertEquals("Phone", back.senderComputerName)
        assertEquals("t1", back.transferId)
        assertEquals(3L, back.totalBytes)
        // string-form TransferID (Apple style)
        val apple = AirDropMessages.AskRequest.fromPlist(mapOf(
            "Files" to listOf(mapOf("FileName" to "b.txt")),
            "TransferID" to "plain-id",
        ))!!
        assertEquals("plain-id", apple.transferId)
        assertEquals("AirDrop", apple.senderComputerName)
        // links-only
        val links = AirDropMessages.AskRequest.fromPlist(mapOf("Items" to listOf("https://example.com")))!!
        assertEquals(1, links.items.size)
        assertNull(AirDropMessages.AskRequest.fromPlist(emptyMap()))
    }

    @Test
    fun `uti lookups`() {
        assertEquals("public.png", Uti.forFileName("a.PNG").uti)
        assertEquals("public.data", Uti.forFileName("a.unknownext").uti)
        assertEquals("public.data", Uti.forFileName("noext").uti)
        assertEquals("image/jpeg", Uti.mimeTypeOf("public.jpeg"))
        assertEquals("text/plain", Uti.mimeTypeOf(Uti.PLAIN_TEXT))
        assertNull(Uti.mimeTypeOf("public.data"))
        assertNull(Uti.mimeTypeOf(null))
        assertEquals("jpg", Uti.extensionForSubType("public.jpeg"))
        assertEquals("heic", Uti.extensionForSubType("public.heic"))
        assertNull(Uti.extensionForSubType("unknown"))
        assertNull(Uti.extensionForSubType(null))
    }
}
