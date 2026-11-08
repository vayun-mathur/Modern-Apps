package com.vayunmathur.updater.platform

import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SystemBuildCheckerTest {

    private lateinit var sysProps: Map<String, String>

    @BeforeTest
    fun saveProps() {
        sysProps = TestSysProps.snapshot()
    }

    @AfterTest
    fun tearDown() {
        UpdateChecker.connectionOverride = null
        TestSysProps.restore(sysProps)
    }

    @Test
    fun `ota server defaults and trims a trailing slash from the property`() {
        TestSysProps.set("ro.maos.ota.server", "")
        assertEquals("https://ota.maproj.org", SystemBuild.otaServer())
        TestSysProps.set("ro.maos.ota.server", "https://updates.example.test/")
        assertEquals("https://updates.example.test", SystemBuild.otaServer())
    }

    @Test
    fun `device and fingerprint come from system properties`() {
        TestSysProps.setRunningBuild(device = "husky")
        assertEquals("husky", SystemBuild.device())
        assertEquals("Modern/husky/husky:16/2026090500/user/release-keys", SystemBuild.fingerprint())
    }

    @Test
    fun `current build needs both an incremental and a positive date`() {
        TestSysProps.setRunningBuild(build = "2026090500", dateUtcSeconds = 4242L)
        assertEquals(
            com.vayunmathur.updater.domain.UpdateComparison.CurrentBuild("2026090500", 4242L),
            SystemBuild.current(),
        )
        TestSysProps.set("ro.build.date.utc", "0")
        assertNull(SystemBuild.current())
        TestSysProps.set("ro.build.date.utc", "4242")
        TestSysProps.set("ro.build.version.incremental", "")
        assertNull(SystemBuild.current())
    }

    private fun fakeCheck(body: String, code: Int = 200, capture: MutableList<String>? = null) {
        UpdateChecker.connectionOverride = { url ->
            capture?.add(url)
            FakeHttpsConnection(URL(url), code, body.toByteArray())
        }
    }

    @Test
    fun `check reports a newer build as available`() {
        TestSysProps.setRunningBuild(dateUtcSeconds = 1000L)
        val urls = mutableListOf<String>()
        fakeCheck("2026090700 2000 shiba stable\n", capture = urls)
        val result = UpdateChecker.check()
        val available = assertIs<UpdateChecker.Result.Available>(result)
        assertEquals("2026090700", available.metadata.build)
        assertEquals(2000L, available.metadata.buildDateUtcSeconds)
        assertTrue(urls.single().endsWith("/shiba-stable"), urls.toString())
    }

    @Test
    fun `check reports an equal or older build as up to date`() {
        TestSysProps.setRunningBuild(dateUtcSeconds = 2000L)
        fakeCheck("2026090700 2000 shiba stable")
        assertEquals(UpdateChecker.Result.UpToDate, UpdateChecker.check())
        fakeCheck("2026090600 1999 shiba stable")
        assertEquals(UpdateChecker.Result.UpToDate, UpdateChecker.check())
    }

    @Test
    fun `check fails when the running build cannot be read`() {
        TestSysProps.set("ro.build.version.incremental", "")
        fakeCheck("2026090700 2000 shiba stable")
        val failed = assertIs<UpdateChecker.Result.Failed>(UpdateChecker.check())
        assertTrue(failed.reason.contains("running build"), failed.reason)
    }

    @Test
    fun `check fails when the device cannot be read`() {
        TestSysProps.setRunningBuild()
        TestSysProps.set("ro.product.device", "")
        val failed = assertIs<UpdateChecker.Result.Failed>(UpdateChecker.check())
        assertTrue(failed.reason.contains("device"), failed.reason)
    }

    @Test
    fun `check fails on a 404 and on other HTTP errors`() {
        TestSysProps.setRunningBuild()
        fakeCheck("", code = 404)
        var failed = assertIs<UpdateChecker.Result.Failed>(UpdateChecker.check())
        assertTrue(failed.reason.contains("could not reach"), failed.reason)
        fakeCheck("", code = 503)
        failed = assertIs<UpdateChecker.Result.Failed>(UpdateChecker.check())
        assertTrue(failed.reason.contains("could not reach"), failed.reason)
    }

    @Test
    fun `check fails when the body is not a metadata line`() {
        TestSysProps.setRunningBuild()
        fakeCheck("<html>captive portal</html>")
        val failed = assertIs<UpdateChecker.Result.Failed>(UpdateChecker.check())
        assertTrue(failed.reason.contains("metadata line"), failed.reason)
    }

    @Test
    fun `check refuses a non-HTTPS metadata URL without connecting`() {
        TestSysProps.setRunningBuild()
        TestSysProps.set("ro.maos.ota.server", "http://127.0.0.1:9")
        UpdateChecker.connectionOverride = null
        val failed = assertIs<UpdateChecker.Result.Failed>(UpdateChecker.check())
        assertTrue(failed.reason.contains("could not reach"), failed.reason)
    }

    @Test
    fun `check fails when the connection blows up`() {
        TestSysProps.setRunningBuild()
        UpdateChecker.connectionOverride = { url ->
            FakeHttpsConnection(URL(url), 200, failOnInput = true)
        }
        val failed = assertIs<UpdateChecker.Result.Failed>(UpdateChecker.check())
        assertTrue(failed.reason.contains("could not reach"), failed.reason)
    }
}
