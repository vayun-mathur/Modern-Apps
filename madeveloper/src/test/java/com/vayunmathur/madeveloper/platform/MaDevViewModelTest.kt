package com.vayunmathur.madeveloper.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.madeveloper.data.DevSocketTransport
import com.vayunmathur.madeveloper.data.GitHostRepository
import com.vayunmathur.madeveloper.domain.DevMessage
import com.vayunmathur.madeveloper.domain.DevProtocol
import com.vayunmathur.madeveloper.domain.InstallPhase
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Canned git-host replies keyed by URL fragment; records every requested URL. */
class FakeGitHost {
    val requested = mutableListOf<String>()
    var failWith: IOException? = null

    private fun commitsJson(start: Int, count: Int): String =
        (start until start + count).joinToString(",", "[", "]") { i ->
            """{"sha":"${"sha%04d".format(i)}${"x".repeat(30)}","author":"vayun","time":"2026-11-16T0${i % 10}:00:00Z","subject":"commit $i","body":"body $i"}"""
        }

    val fetcher: suspend (String) -> String = { url ->
        synchronized(requested) { requested += url }
        failWith?.let { throw it }
        when {
            url.endsWith("/api/repos") ->
                """[{"name":"Modern-Apps","defaultBranch":"main","headSha":"aabbccdd11223344","headSubject":"top subject","headTime":"2026-11-16T10:00:00Z"},{"name":"MAOS","defaultBranch":"main","headSha":"1111222233334444","headSubject":"maos subject","headTime":"2026-11-15T10:00:00Z"}]"""
            url.contains("/api/repos/Modern-Apps/commits") ->
                if (url.contains("after=")) commitsJson(30, 2) else commitsJson(0, 30)
            url.contains("/api/repos/MAOS/commits") -> commitsJson(100, 3)
            url.contains("/api/repos/Modern-Apps/tree") || url.contains("/api/repos/MAOS/tree") ->
                if (url.contains("path=src")) {
                    """[{"name":"Main.kt","type":"blob","size":120},{"name":"logo.png","type":"blob","size":99}]"""
                } else {
                    """[{"name":"README.md","type":"blob","size":42},{"name":"src","type":"tree"}]"""
                }
            url.contains("/file") && url.contains("logo.png") ->
                """{"path":"src/logo.png","size":99,"isBinary":true,"truncated":false,"sha":"def4567890abcdef"}"""
            url.contains("/file") ->
                """{"path":"src/Main.kt","size":20,"isBinary":false,"truncated":false,"sha":"abc1234567","content":"fun main() {\n    println(1)\n}\n"}"""
            url.endsWith("/api/repos/Modern-Apps") || url.endsWith("/api/repos/MAOS") -> {
                val name = url.substringAfterLast("/")
                """{"name":"$name","defaultBranch":"main","branches":[{"name":"main","sha":"11111111aaaa","time":"2026-11-15T00:00:00Z"},{"name":"dev","sha":"22222222bbbb","time":"2026-11-14T00:00:00Z"}],"recentCommits":[{"sha":"recent0001aaaa","author":"vayun","time":"2026-11-15T01:00:00Z","subject":"recent work","body":"notes"}]}"""
            }
            url.contains("/tests.json") ->
                """{"latest":{"id":"a","sha":"abc123","subject":"fix thing","status":"passed","failed":2,"tests":10,"coverage":{"covered":50,"total":100}},"running":{"id":"r","sha":"fff000","subject":"running now","status":"running","failed":0,"tests":0},"history":[{"id":"b","sha":"def456","subject":"old run","status":"failed","failed":1,"tests":5}]}"""
            url.contains("/issues.json") ->
                """{"repos":[{"github":"vayun-mathur/Modern-Apps","issues":[{"number":7,"title":"Bug one","author":"vayun","comments":3,"labels":[{"name":"bug"}],"updated_at":"2026-01-01"}]}]}"""
            url.contains("/prs.json") ->
                """{"repos":[{"repo":"Modern-Apps","prs":[{"number":12,"title":"Add thing","author":"vayun"}]}]}"""
            url.contains("index-v2.json") ->
                """{"packages":{"com.example.app":{"metadata":{"name":{"en-US":"Example"},"summary":{"en-US":"Demo app"}},"versions":{"h1":{"file":{"name":"/a_1.apk","size":100},"manifest":{"versionCode":1,"versionName":"1.0"}}}}}}"""
            else -> throw IOException("unexpected url: $url")
        }
    }
}

/** In-memory push socket: the test emits server messages and reads replies. */
class FakeTransport : DevSocketTransport {
    val incoming = MutableSharedFlow<DevMessage>(extraBufferCapacity = 16)
    override val messages: Flow<DevMessage> = incoming
    val sent = mutableListOf<String>()
    var closed = false
    override suspend fun send(text: String) {
        synchronized(sent) { sent += text }
    }
    override suspend fun close() {
        closed = true
    }
}

@RunWith(RobolectricTestRunner::class)
class MaDevViewModelTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val host = FakeGitHost()

    @BeforeTest fun setUp() {
        PairingStore(app).clear()
    }

    private fun vm(): MaDevViewModel {
        val viewModel = MaDevViewModel(app)
        viewModel.repo = GitHostRepository(host.fetcher)
        return viewModel
    }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    @Test fun refreshLoadsHomeStateAndSkipsWhenLoaded() {
        val viewModel = vm()
        viewModel.refresh()
        await("home loaded") { viewModel.state.repos.size == 2 && viewModel.state.tests != null }
        assertEquals(false, viewModel.state.loading)
        assertNull(viewModel.state.error)
        assertEquals("Modern-Apps", viewModel.state.repos[0].name)
        assertEquals("passed", viewModel.state.tests?.latest?.status)
        assertEquals(50.0, viewModel.state.tests?.coveragePct ?: 0.0, 0.01)
        assertEquals("Bug one", viewModel.state.issues.single().title)
        assertEquals("Add thing", viewModel.state.prs.single().title)
        val requestCount = synchronized(host.requested) { host.requested.size }
        viewModel.loadHome()
        // loadHome is a no-op once repos are present (and starts no reload).
        assertEquals(requestCount, synchronized(host.requested) { host.requested.size })
        assertEquals("Modern-Apps", viewModel.repoByName("Modern-Apps")?.name)
        assertNull(viewModel.repoByName("nope"))
    }

    @Test fun refreshSurfacesHostFailureAsError() {
        host.failWith = IOException("HTTP 404 for https://git.maproj.org/api/repos")
        val viewModel = vm()
        viewModel.refresh()
        await("error shown") { viewModel.state.error != null }
        assertTrue(viewModel.state.error!!.contains("404"), viewModel.state.error!!)
        assertFalse(viewModel.state.loading)
    }

    @Test fun catalogLoadsFromFdroidIndex() {
        val viewModel = vm()
        viewModel.loadCatalog()
        await("catalog loaded") { viewModel.state.catalog.isNotEmpty() }
        val entry = viewModel.state.catalog.single()
        assertEquals("Example", entry.name)
        assertEquals("com.example.app", entry.packageName)
        assertEquals(1L, entry.versionCode)
    }

    @Test fun repoBrowsingBranchSwitchAndCommitPaging() {
        val viewModel = vm()
        viewModel.loadRepo("Modern-Apps")
        await("browser open") { viewModel.browser("Modern-Apps") != null }
        val browser = viewModel.browser("Modern-Apps")!!
        assertEquals("main", browser.branch)
        assertEquals(listOf("main", "dev"), browser.branches.map { it.name })
        assertEquals("recent work", browser.commits.single().subject)

        // A fresh first page replaces the recent-commits preview.
        viewModel.loadCommits("Modern-Apps", loadMore = false)
        await("first page") { viewModel.browser("Modern-Apps")!!.commits.size == 30 }
        assertFalse(viewModel.browser("Modern-Apps")!!.commitsExhausted)

        viewModel.loadCommits("Modern-Apps", loadMore = true)
        await("second page appended") { viewModel.browser("Modern-Apps")!!.commits.size == 32 }
        assertTrue(viewModel.browser("Modern-Apps")!!.commitsExhausted)
        // No further page is fetched once exhausted.
        val commitsRequests = synchronized(host.requested) { host.requested.count { it.contains("/commits") } }
        viewModel.loadCommits("Modern-Apps", loadMore = true)
        Thread.sleep(300)
        assertEquals(commitsRequests, synchronized(host.requested) { host.requested.count { it.contains("/commits") } })

        // Branch switch resets path/commits and reloads on the new branch.
        viewModel.selectBranch("Modern-Apps", "dev")
        assertEquals("dev", viewModel.browser("Modern-Apps")!!.branch)
        assertEquals("", viewModel.browser("Modern-Apps")!!.path)

        // Tree navigation is pure state.
        viewModel.navigateTree("Modern-Apps", "src")
        assertEquals("src", viewModel.browser("Modern-Apps")!!.path)
        assertEquals(listOf("src" to "src"), viewModel.browser("Modern-Apps")!!.breadcrumbs)
        viewModel.navigateTree("Modern-Apps", "")
        assertEquals("", viewModel.browser("Modern-Apps")!!.path)

        // Reopening an already-open repo does not refetch its detail.
        val detailRequests = synchronized(host.requested) {
            host.requested.count { it.endsWith("/api/repos/Modern-Apps") }
        }
        viewModel.loadRepo("Modern-Apps")
        assertEquals(detailRequests, synchronized(host.requested) {
            host.requested.count { it.endsWith("/api/repos/Modern-Apps") }
        })

        // Unknown repos have no browser and ignore navigation calls.
        assertNull(viewModel.browser("nope"))
        viewModel.selectBranch("nope", "dev")
        viewModel.navigateTree("nope", "src")
        viewModel.loadCommits("nope", loadMore = false)
    }

    @Test fun repoLoadFailureSetsRepoError() {
        host.failWith = IOException("HTTP 404 for repo")
        val viewModel = vm()
        viewModel.loadRepo("Modern-Apps")
        await("repo error") { viewModel.state.repoError != null }
        assertTrue(viewModel.state.repoError!!.contains("404"))
        assertNull(viewModel.browser("Modern-Apps"))
    }

    @Test fun treeAndFileReadsGoThroughRepositoryCaches() = kotlinx.coroutines.test.runTest {
        val viewModel = vm()
        val entries = viewModel.tree("Modern-Apps", "main", "")
        assertEquals(listOf("src", "README.md"), entries.map { it.name })
        val requestsAfterTree = synchronized(host.requested) { host.requested.size }
        viewModel.tree("Modern-Apps", "main", "")
        assertEquals(requestsAfterTree, synchronized(host.requested) { host.requested.size }, "tree reads are cached")
        val file = viewModel.file("Modern-Apps", "main", "src/Main.kt")
        assertTrue(file.content!!.contains("fun main()"))
        viewModel.file("Modern-Apps", "main", "src/Main.kt")
        assertEquals(requestsAfterTree + 1, synchronized(host.requested) { host.requested.size }, "file reads are cached")
    }

    @Test fun shaHelperMatchesMessageDigest() {
        val viewModel = vm()
        val bytes = "hello world".toByteArray()
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, viewModel.shaOf(bytes))
        assertFalse(viewModel.canInstall(), "host JVM never holds the install permission")
    }

    @Test fun pairingStoreHoldsOnlyServerIssuedCredentials() {
        val store = PairingStore(app)
        assertNull(store.pairingCode(), "no code exists before server registration")
        assertNull(store.token())
        assertNull(store.deviceId())
        // Holding credentials alone does not mark the device paired; only a
        // live socket does.
        assertFalse(MaDevViewModel(app).state.paired)
        store.saveRegistration("device-9", "token-1", "123456")
        assertEquals("123456", store.pairingCode())
        assertEquals("token-1", store.token())
        assertEquals("device-9", store.deviceId())
        assertEquals("123456", MaDevViewModel(app).state.pairingCode)
        assertFalse(MaDevViewModel(app).state.paired)
        store.clear()
        assertNull(store.token())
        assertNull(store.pairingCode())
    }
}

/** The back stack is saved through the Route serializer; every destination must round-trip. */
class MaDevRouteTest {
    @Test fun everyRouteRoundTrips() {
        val json = kotlinx.serialization.json.Json
        val routes: List<com.vayunmathur.madeveloper.Route> = listOf(
            com.vayunmathur.madeveloper.Route.Home,
            com.vayunmathur.madeveloper.Route.Repo("Modern-Apps"),
            com.vayunmathur.madeveloper.Route.Commits("Modern-Apps", "main"),
            com.vayunmathur.madeveloper.Route.Commit("Modern-Apps", "main", "abc123"),
            com.vayunmathur.madeveloper.Route.File("Modern-Apps", "src/Main.kt", "dev"),
            com.vayunmathur.madeveloper.Route.Issues,
            com.vayunmathur.madeveloper.Route.Prs,
            com.vayunmathur.madeveloper.Route.Tests,
            com.vayunmathur.madeveloper.Route.Catalog,
            com.vayunmathur.madeveloper.Route.Push,
        )
        for (route in routes) {
            val encoded = json.encodeToString(com.vayunmathur.madeveloper.Route.serializer(), route)
            assertEquals(route, json.decodeFromString(com.vayunmathur.madeveloper.Route.serializer(), encoded), encoded)
        }
    }
}
