package com.vayunmathur.auto.protocol

import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves a credential rotation is a PEM swap with zero code change.
 *
 * The `gal-rotated/` fixtures stand in for the next gearhead leaf: a fresh CA,
 * leaf and PKCS#8 key minted the same way (see `auto/docs/HANDOFF.md` §7).
 * Everything runs through [GalCredential.create] with fixture PEM strings —
 * exactly what a real rotation feeds it — and completes the same mutual-auth
 * handshake [GalCredentialTest] proves for the shipped credential.
 */
class GalCredentialRotationTest {

    private fun fixture(name: String): String {
        val file = File("src/test/assets/gal-rotated", name)
        assertTrue(file.exists(), "missing rotation fixture ${file.absolutePath}")
        return file.readText()
    }

    @Test
    fun `rotated fixtures chain to their own root, not the shipped GAL root`() {
        val shippedRoot = CertificateFactory.getInstance("X.509").generateCertificate(
            File("src/main/assets/gal", "root.pem").readBytes().inputStream(),
        ) as X509Certificate
        val rotatedLeaf = CertificateFactory.getInstance("X.509").generateCertificate(
            fixture("client-cert.pem").byteInputStream(),
        ) as X509Certificate
        assertTrue(
            rotatedLeaf.issuerX500Principal != shippedRoot.subjectX500Principal,
            "rotated leaf must chain to its own CA, saw ${rotatedLeaf.issuerX500Principal}",
        )
    }

    @Test
    fun `rotated leaf expires roughly a year out, far past the 2026-12-09 shipped expiry`() {
        // Measure from the leaf's own notBefore, not the wall clock: the
        // fixture's validity window is fixed (minted 2026-09-11 for ~1 year),
        // so a now-relative bound decays by a day every day and fails a year
        // after minting even though nothing about the rotation changed.
        val rotatedPem = fixture("client-cert.pem")
        val rotatedLeaf = CertificateFactory.getInstance("X.509").generateCertificate(
            rotatedPem.byteInputStream(),
        ) as X509Certificate
        val issuedAt = rotatedLeaf.notBefore.time
        val days = GalCredential.daysRemaining(rotatedPem, issuedAt)
        assertTrue(
            days in 300L..400L,
            "rotated leaf should be good for ~1 year, saw $days days",
        )
        val shippedPem = File("src/main/assets/gal", "client-cert.pem").readText()
        assertTrue(
            GalCredential.notAfterMillis(rotatedPem) > GalCredential.notAfterMillis(shippedPem),
            "rotated leaf must expire after the shipped 2026-12-09 leaf",
        )
        val shippedDays = GalCredential.daysRemaining(shippedPem, issuedAt)
        assertTrue(
            shippedDays < days,
            "shipped leaf ($shippedDays days) must be nearer expiry than the rotation",
        )
    }

    @Test
    fun `rotated creds complete the mutual-auth handshake with zero code change`() {
        val context = GalCredential.create(
            certPem = fixture("client-cert.pem"),
            keyPem = fixture("client-key.pem"),
            rootPem = fixture("root.pem"),
        )
        val server = GalCredential.serverEngine(context)
        val car = TestTls.carEngine(context)

        TestTls.handshake(server, car)

        assertEquals("TLSv1.2", server.session.protocol)
        val peer = server.session.peerCertificates.first() as X509Certificate
        assertTrue(
            peer.subjectX500Principal.name.contains("rotated-leaf"),
            "server should have authenticated the rotated client, saw ${peer.subjectX500Principal}",
        )
    }

    @Test
    fun `expiry math truncates to whole days and goes negative past expiry`() {
        val notAfter = GalCredential.notAfterMillis(fixture("client-cert.pem"))
        val dayMs = TimeUnit.DAYS.toMillis(1)
        // 23 hours before expiry reads 0, not 1.
        assertEquals(0L, GalCredential.daysRemaining(fixture("client-cert.pem"), notAfter - 23 * 3_600_000))
        // One day plus a minute before expiry reads 1.
        assertEquals(1L, GalCredential.daysRemaining(fixture("client-cert.pem"), notAfter - dayMs - 60_000))
        // A day after expiry reads negative.
        assertTrue(GalCredential.daysRemaining(fixture("client-cert.pem"), notAfter + dayMs) < 0)
    }
}
