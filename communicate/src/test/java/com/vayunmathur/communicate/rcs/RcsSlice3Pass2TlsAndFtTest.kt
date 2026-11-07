package com.vayunmathur.communicate.rcs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsFileTransferHttp
import com.vayunmathur.communicate.data.rcs.RcsGbaAuth
import com.vayunmathur.communicate.data.rcs.RcsMsrpTls
import java.io.File
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice-3 pass 2: MSRP TLS identity lifecycle (real EC key generation and
 * self-signed DER certificates on the JVM) and FT-over-HTTP helpers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RcsSlice3Pass2ConnectivityShadow::class])
class RcsSlice3Pass2TlsAndFtTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var testDb: RcsDatabase

    @BeforeTest
    fun setUp() {
        testDb = androidx.room3.Room.inMemoryDatabaseBuilder(context, RcsDatabase::class.java)
            .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        RcsDatabase.overrideForTest = testDb
    }

    @AfterTest
    fun tearDown() {
        RcsDatabase.overrideForTest = null
        testDb.close()
    }

    @Test
    fun tlsIdentityGeneratesCachesAndPersists() = runTest {
        val identity = RcsMsrpTls.ensureIdentity(context)
        assertNotNull(identity)
        // Fingerprint format: colon-separated uppercase SHA-256 hex.
        assertEquals(32, identity.fingerprint.split(":").size)
        assertTrue(identity.fingerprint.split(":").all { it.length == 2 })
        assertEquals("a=fingerprint:SHA-256 ${identity.fingerprint}", identity.sdpLine())
        // The DER parses as a real X.509 certificate.
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(identity.certDer.inputStream())
        assertNotNull(cert)
        assertEquals(identity.fingerprint, RcsMsrpTls.fingerprintOf(identity.certDer))
        // Second call returns the cached instance.
        val again = RcsMsrpTls.ensureIdentity(context)
        assertEquals(identity, again)
        assertEquals(identity.hashCode(), again?.hashCode())
        // Equality distinguishes certificates.
        val kpg = KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }
        val otherKey = kpg.generateKeyPair()
        val otherDer = RcsMsrpTls.selfSignedCert(
            otherKey.public.encoded, otherKey.private,
            System.currentTimeMillis(), System.currentTimeMillis() + 86_400_000, "other",
        )
        assertFalse(identity == RcsMsrpTls.Identity(otherDer, RcsMsrpTls.fingerprintOf(otherDer), otherKey.private))
        assertTrue(identity != RcsMsrpTls.Identity(otherDer, identity.fingerprint, otherKey.private))

        // The identity files were persisted under filesDir.
        val dir = File(context.filesDir, "rcs-msrp-tls")
        assertTrue(File(dir, "identity.der").exists())
        assertTrue(File(dir, "identity.pk8").exists())

        // A server SSLContext builds from the identity.
        assertNotNull(RcsMsrpTls.serverContext(context))
    }

    @Test
    fun tlsClientSocketRequiresFingerprint() = runTest {
        assertNull(RcsMsrpTls.clientSocket(context, "127.0.0.1", 9, null))
        assertNull(RcsMsrpTls.clientSocket(context, "127.0.0.1", 9, ""))
        assertNull(RcsMsrpTls.clientSocket(context, "127.0.0.1", 9, "  "))
        // With a fingerprint but no listener, the plain socket leg fails too.
        assertNull(RcsMsrpTls.clientSocket(context, "127.0.0.1", 1, "AA:BB"))
    }

    @Test
    fun tlsLoopbackHandshakeAndFingerprintPinning() = runTest {
        val identity = RcsMsrpTls.ensureIdentity(context)
        assertNotNull(identity)
        val serverContext = RcsMsrpTls.serverContext(context)
        assertNotNull(serverContext)
        val serverSocket = java.net.ServerSocket(0)
        val port = serverSocket.localPort
        val serverError = java.util.concurrent.atomic.AtomicReference<String>()
        val serverSide = java.util.concurrent.atomic.AtomicReference<javax.net.ssl.SSLSocket>()
        val accepted = java.util.concurrent.CountDownLatch(1)
        Thread {
            try {
                val raw = serverSocket.accept()
                val ssl = serverContext.socketFactory.createSocket(
                    raw, raw.inetAddress.hostAddress, raw.port, true,
                ) as javax.net.ssl.SSLSocket
                ssl.useClientMode = false
                ssl.wantClientAuth = true
                ssl.startHandshake()
                serverSide.set(ssl)
            } catch (e: Throwable) {
                serverError.set(e.toString())
            }
            accepted.countDown()
        }.apply { isDaemon = true; start() }

        // Client pins the same self-signed identity the server presents.
        val client = RcsMsrpTls.clientSocket(context, "127.0.0.1", port, identity.fingerprint)
        assertNotNull(client, "client socket failed; server=${serverError.get()}")
        assertTrue(accepted.await(15, java.util.concurrent.TimeUnit.SECONDS))
        assertNull(serverError.get())
        val server = serverSide.get()
        assertNotNull(server)
        // Server-side verification against the client's (identical) cert.
        assertTrue(RcsMsrpTls.verifyServerSide(server, identity.fingerprint))
        assertTrue(RcsMsrpTls.verifyServerSide(server, identity.fingerprint.lowercase()))
        assertFalse(RcsMsrpTls.verifyServerSide(server, "00:11:22"))
        // No expected fingerprint: accepted unverified by design.
        assertTrue(RcsMsrpTls.verifyServerSide(server, null))
        assertTrue(RcsMsrpTls.verifyServerSide(server, ""))
        client.close()
        server.close()
        serverSocket.close()
    }

    @Test
    fun selfSignedCertParsesWithOurIdentity() = runTest {
        val identity = RcsMsrpTls.ensureIdentity(context)
        assertNotNull(identity)
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(identity.certDer.inputStream()) as java.security.cert.X509Certificate
        cert.checkValidity()
        assertTrue(cert.subjectX500Principal.name.contains("rcs-msrp-tls"), cert.subjectX500Principal.name)
        // Our own signature verifies against the cert's public key.
        cert.verify(cert.publicKey)
    }

    // --- FT-over-HTTP helpers ---

    @Test
    fun buildFileCpimShapes() {
        val withCaption = RcsFileTransferHttp.buildFileCpim("a.png", "image/png", 1234, "look")
        assertTrue(withCaption.contains("File-Name: a.png\r\n"))
        assertTrue(withCaption.contains("File-MIME: image/png\r\n"))
        assertTrue(withCaption.contains("File-Size: 1234\r\n"))
        assertTrue(withCaption.contains("Content-Type: application/octet-stream\r\n"))
        assertTrue(withCaption.endsWith("\r\nlook"))
        val bare = RcsFileTransferHttp.buildFileCpim("b.bin", "application/pdf", 0, "")
        assertFalse(bare.contains("\r\n\r\n"))
    }

    @Test
    fun parseContentServerVariants() {
        assertEquals(
            "https://ft.example.com/cs",
            RcsFileTransferHttp.parseContentServer(
                "<rcs><ftHTTPCSURI> https://ft.example.com/cs </ftHTTPCSURI></rcs>".toByteArray(),
            ),
        )
        assertEquals(
            "https://ft.example.com/upper",
            RcsFileTransferHttp.parseContentServer(
                "<FTHTTPCSURI>https://ft.example.com/upper</FTHTTPCSURI>".toByteArray(),
            ),
        )
        assertNull(RcsFileTransferHttp.parseContentServer("<rcs/>".toByteArray()))
        assertNull(RcsFileTransferHttp.parseContentServer("<ftHTTPCSURI>  </ftHTTPCSURI>".toByteArray()))
        assertNull(RcsFileTransferHttp.parseContentServer(ByteArray(0)))
    }

    @Test
    fun parseFtBodyExtraShapes() {
        val info = RcsFileTransferHttp.parseFtBody(
            "file-url: https://files.example/x.jpg\r\nfile-mime: image/jpeg\r\n" +
                "File-Name:   my photo.jpg  \r\nFile-Size: 98765\r\n",
        )
        assertNotNull(info)
        assertEquals("https://files.example/x.jpg", info.url)
        assertEquals("image/jpeg", info.mime)
        assertEquals("my photo.jpg", info.fileName)
        assertEquals(98765L, info.fileSize)
        // Equality of the data class.
        assertEquals(info, info.copy())
        assertFalse(info == info.copy(fileSize = 1L))
    }

    @Test
    fun thumbnailOfRealPngAndGarbage() {
        // 1x1 transparent PNG.
        val png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        val thumb = RcsFileTransferHttp.makeThumbnail(png)
        assertNotNull(thumb)
        assertTrue(thumb.isNotEmpty())
        // JPEG magic.
        assertEquals(0xFF.toByte(), thumb[0])
        assertEquals(0xD8.toByte(), thumb[1])
    }


    @Test
    fun downloadRejectsBlankClosedPortAndOversize() = runTest {
        assertNull(RcsFileTransferHttp.download(context, ""))
        assertNull(RcsFileTransferHttp.download(context, "http://127.0.0.1:1/ft/file"))
    }

    @Test
    fun sendFileFailsClosedWithoutTransport() = runTest {
        val att = CommunicateAttachment("content://a/2", "image/png", "a.png")
        assertFalse(RcsFileTransferHttp.sendFile(context, CommunicateRepository, "+1555", att))
        assertFalse(RcsFileTransferHttp.sendFile(context, CommunicateRepository, "+1555", att, "cap"))
    }

    @Test
    fun gbaCredentialsDataClassBehaviour() {
        RcsGbaAuth.injected = null
        assertNull(RcsGbaAuth.injected)
        val creds = RcsGbaAuth.GbaCredentials("bt-123", byteArrayOf(1, 2, 3))
        assertEquals("bt-123", creds.btId)
        assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), creds.key.toList())
        RcsGbaAuth.injected = creds
        assertEquals(creds, RcsGbaAuth.injected)
        RcsGbaAuth.injected = null
        assertNull(RcsGbaAuth.injected)
    }
}
