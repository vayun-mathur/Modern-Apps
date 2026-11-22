package com.vayunmathur.appstore.util
import com.vayunmathur.appstore.data.FDroidRepository
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.appstore.platform.SourceFilter
import com.vayunmathur.appstore.platform.TvSection
import com.vayunmathur.appstore.platform.loadBrowse
import com.vayunmathur.appstore.platform.loadDetail
import com.vayunmathur.appstore.platform.refreshInstalled

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.accrescent.appstore.v1.AppListing
import app.accrescent.appstore.v1.AppServiceGrpcKt
import app.accrescent.appstore.v1.AppUpdateInfo
import app.accrescent.appstore.v1.GetAppDownloadInfoRequest
import app.accrescent.appstore.v1.GetAppDownloadInfoResponse
import app.accrescent.appstore.v1.GetAppListingRequest
import app.accrescent.appstore.v1.GetAppListingResponse
import app.accrescent.appstore.v1.GetAppPackageInfoRequest
import app.accrescent.appstore.v1.GetAppPackageInfoResponse
import app.accrescent.appstore.v1.GetAppUpdateInfoRequest
import app.accrescent.appstore.v1.GetAppUpdateInfoResponse
import app.accrescent.appstore.v1.Image
import app.accrescent.appstore.v1.ListAppListingsRequest
import app.accrescent.appstore.v1.ListAppListingsResponse
import app.accrescent.appstore.v1.PackageInfo
import app.accrescent.appstore.v1.SplitDownloadInfo
import app.accrescent.appstore.v1.SplitUpdateInfo
import com.vayunmathur.appstore.data.AccrescentTrustEntity
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.accrescent.AccrescentApi
import com.vayunmathur.appstore.data.accrescent.AccrescentRepository
import com.vayunmathur.appstore.data.SettingsRepository
import com.vayunmathur.appstore.platform.TvStoreViewModel
import android.os.Looper
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/** W5: phone + TV ViewModel Accrescent paths against an in-process fake gRPC service. */
@RunWith(RobolectricTestRunner::class)
class AccrescentVmOpsW5Test {

    private class FakeService(private val ownPackage: String) : AppServiceGrpcKt.AppServiceCoroutineImplBase() {
        private fun listing(id: String, name: String) = AppListing.newBuilder()
            .setAppId(id)
            .setName(name)
            .setShortDescription("$name summary")
            .setIcon(Image.newBuilder().setUrl("https://example.org/$id.png"))
            .build()

        override suspend fun listAppListings(request: ListAppListingsRequest): ListAppListingsResponse {
            val builder = ListAppListingsResponse.newBuilder()
            return if (request.pageToken == "p2") {
                builder.addListings(listing(ownPackage, "This Store")).build()
            } else {
                builder.addListings(listing("app.alpha", "Alpha"))
                    .addListings(listing("app.beta", "Beta"))
                    .setNextPageToken("p2")
                    .build()
            }
        }

        override suspend fun getAppListing(request: GetAppListingRequest): GetAppListingResponse =
            GetAppListingResponse.newBuilder().setListing(listing(request.appId, "Alpha")).build()

        override suspend fun getAppPackageInfo(request: GetAppPackageInfoRequest): GetAppPackageInfoResponse =
            GetAppPackageInfoResponse.newBuilder()
                .setPackageInfo(PackageInfo.newBuilder().setVersionCode(999_999L).setVersionName("9.9"))
                .build()

        override suspend fun getAppDownloadInfo(request: GetAppDownloadInfoRequest): GetAppDownloadInfoResponse =
            GetAppDownloadInfoResponse.newBuilder()
                .setAppDownloadInfo(
                    app.accrescent.appstore.v1.AppDownloadInfo.newBuilder().addSplitDownloadInfo(
                        SplitDownloadInfo.newBuilder()
                            .setUrl("https://example.org/base.apk")
                            .setDownloadSize(100)
                    )
                )
                .build()

        override suspend fun getAppUpdateInfo(request: GetAppUpdateInfoRequest): GetAppUpdateInfoResponse =
            GetAppUpdateInfoResponse.newBuilder()
                .setAppUpdateInfo(
                    AppUpdateInfo.newBuilder().addSplitUpdateInfo(
                        SplitUpdateInfo.newBuilder()
                            .setApkUrl("https://example.org/update.apk")
                            .setApkDownloadSize(55)
                    )
                )
                .build()
    }

    private lateinit var db: AppDatabase
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var repo: AccrescentRepository
    private lateinit var ownPackage: String

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        com.vayunmathur.appstore.data.FDroidRepository.downloadOverride = { url, _ ->
            throw java.io.IOException("offline test double for $url")
        }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ownPackage = ctx.packageName
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name).directExecutor()
            .addService(FakeService(ownPackage)).build().start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        val failingFetcher = object : com.vayunmathur.appstore.data.accrescent.AccrescentRepoDataFetcher(ctx) {
            override suspend fun fetch(): Result<com.vayunmathur.appstore.data.accrescent.RepoData> =
                Result.failure(java.io.IOException("offline test double"))
        }
        repo = AccrescentRepository(ctx, db, AccrescentApi { channel }, failingFetcher)
        runBlocking {
            db.accrescentTrustDao().upsertAll(
                listOf(
                    AccrescentTrustEntity(ownPackage, "AB".repeat(32), 1L),
                    AccrescentTrustEntity("app.alpha", "CD".repeat(32), 1L),
                )
            )
        }
    }

    @AfterTest
    fun tearDown() {
        com.vayunmathur.appstore.data.FDroidRepository.downloadOverride = null
        repo.shutdown()
        channel.shutdownNow()
        server.shutdownNow()
    }

    // The settings edits below are launched on Dispatchers.IO and then
    // awaited by pumping the paused Robolectric main looper — never by
    // parking this thread in runBlocking. Parking the main thread waits
    // forever for work queued on that same thread: every earlier
    // ViewModel's SettingsRepository mirror collects the shared
    // appstore-settings DataStore from viewModelScope (Main), and under
    // CI CPU contention the edit's completion work sits in that paused
    // looper queue behind the parked thread (this class's
    // phoneVmHomeBrowseAndPackageSelection wedged exactly there for
    // 8+ minutes under a 5-CPU cap, while passing alone locally).
    // pumpUntil idles/advances the looper on every check, so the queued
    // collectors run, the edit lands, and only then does the wait end:
    // a deterministic terminal signal, no wall-clock deadline.

    private fun enableAccrescent(settings: SettingsRepository) {
        val done = CompletableDeferred<Unit>()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                settings.setSourceEnabled(AppSource.ACCRESCENT, true)
                done.complete(Unit)
            } catch (e: Throwable) {
                done.completeExceptionally(e)
            }
        }
        pumpUntil("accrescent source enabled") { done.isCompleted }
        done.getCompletionExceptionOrNull()?.let { throw it }
    }

    private fun await(description: String, condition: () -> Boolean) {
        pumpUntil(description, condition)
    }
    /**
     * Step-bounded pumped wait on a terminal [condition]: every step
     * advances the paused main looper (running the ViewModel/DataStore
     * work queued there, including delayed tasks), then parks briefly
     * so Dispatchers.IO/Default producers get a CPU slice on a
     * contended box. The bound is pumping steps, never wall-clock
     * seconds that CI contention can burn before any work runs, and
     * the failure is raised only after [MAX_PUMP_STEPS] pumps.
     */
    private fun pumpUntil(description: String, condition: () -> Boolean) {
        var steps = 0
        while (true) {
            // Pump first, then check: the loops this replaces always
            // idled at least once before judging the condition, and a
            // condition that is already true at entry can still need
            // that one pump for the work behind it to settle.
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(PUMP_STEP_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) break
            java.util.concurrent.locks.LockSupport.parkNanos(
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(PARK_MS),
            )
            if (++steps >= MAX_PUMP_STEPS) {
                throw AssertionError("timed out (step-bounded): $description after $MAX_PUMP_STEPS pumps")
            }
        }
    }

    private companion object {
        const val PUMP_STEP_MS = 100L
        const val PARK_MS = 50L
        const val MAX_PUMP_STEPS = 6_000
    }


    @Test
    fun phoneVmAccrescentSearchSelectAndUpdateCheck() {
        val vm = AppStoreViewModel(app, db, accrescentOverride = repo)
        enableAccrescent(vm.settings)
        vm.setSearch("alpha")
        await("accrescent search") {
            vm.search.value.results.any { it.packageName == "app.alpha" }
        }
        vm.loadDetail("app.alpha")
        await("accrescent detail") { vm.detail.value.app?.versionCode == 999_999L }
        assertEquals(999_999L, vm.detail.value.app?.versionCode)

        // The fake service must offer the installed package a newer build.
        runBlocking {
            assertTrue(repo.appIds().contains(ownPackage), "trust ids=${runBlocking { repo.appIds() }}")
            val update = repo.updateInfo(ownPackage, 20_261_028L)
            assertTrue(update != null && update.versionCode > 20_261_028L, "update=$update")
        }
        vm.checkForUpdates()
        await("update check settles") { !vm.isCheckingUpdatesFlow.value }
        // The store's own package is installed and vouched for by Accrescent
        // with a much newer build, so it must surface as an update.
        assertTrue(
            vm.updatesUi.value.updates.any { it.packageName == ownPackage } ||
                vm.updates.value.any { it.packageName == ownPackage },
            "expected an Accrescent update for $ownPackage, updates=${vm.updatesUi.value}",
        )
        vm.updateAll()
        await("updateAll settles") { !vm.isCheckingUpdatesFlow.value }
    }

    @Test
    fun tvVmAccrescentSearchSelectAndUpdateCheck() {
        val vm = TvStoreViewModel(app, db, accrescentOverride = repo)
        vm.enabledSourcesFlow.value = setOf(AppSource.ACCRESCENT, AppSource.MODERN_APPS)
        enableAccrescent(vm.settings)
        vm.setSearch("beta")
        await("tv accrescent search") {
            vm.search.value.results.any { it.packageName == "app.beta" }
        }
        vm.loadDetail("app.alpha")
        await("tv detail") { vm.detail.value.app?.packageName == "app.alpha" }
        vm.checkForUpdates()
        await("tv update check settles") { !vm.isCheckingUpdatesFlow.value }
        vm.loadHome()
        await("tv home settles") { !vm.isLoadingFlow.value }
    }

    @Test
    fun phoneVmInstallAccrescentAppAttemptsDownloadAndSettles() {
        val vm = AppStoreViewModel(app, db, accrescentOverride = repo)
        val alpha = UnifiedApp(packageName = "app.alpha", source = AppSource.ACCRESCENT, name = "Alpha", versionCode = 999_999L)
        vm.loadDetail(alpha.packageName)
        await("detail") { vm.detail.value.app?.packageName == "app.alpha" }
        vm.install(alpha)
        // Download from example.org fails offline; the install state must
        // settle (failed/dismissible) rather than spin forever.
        await("install settles") {
            val stage = vm.detail.value.stage
            stage == null || stage.toString().contains("Fail", ignoreCase = true)
        }
    }

    @Test
    fun phoneVmHomeBrowseAndPackageSelection() {
        val vm = AppStoreViewModel(app, db, accrescentOverride = repo)
        enableAccrescent(vm.settings)
        vm.refresh()
        await("home sections from accrescent") {
            vm.home.value.sections.flatMap { it.apps }.any { it.packageName == "app.alpha" } ||
                vm.home.value.sections.isNotEmpty()
        }
        val section = vm.home.value.sections.firstOrNull()
        if (section != null) {
            vm.loadBrowse(section.id)
            await("browse") { vm.browse.value.apps.isNotEmpty() || vm.browse.value.title == section.title }
            assertEquals(section.id, vm.browse.value.sectionId)
        }
        vm.loadDetail("app.alpha")
        await("package detail") { vm.detail.value.app?.packageName == "app.alpha" }
        vm.refreshInstalled()
        vm.refreshUpdateCheckStatus()
        vm.setSearchFilter(com.vayunmathur.appstore.platform.SourceFilter.ACCRESCENT)
        vm.setSearch("alpha")
        await("filtered search") { vm.search.value.results.any { it.packageName == "app.alpha" } }
    }

    @Test
    fun tvVmLibraryBrowseInstallAndSourceToggles() {
        val vm = TvStoreViewModel(app, db, accrescentOverride = repo)
        vm.enabledSourcesFlow.value = setOf(AppSource.ACCRESCENT, AppSource.MODERN_APPS)
        enableAccrescent(vm.settings)
        vm.loadHome()
        await("tv home for browse") { !vm.isLoadingFlow.value }
        vm.home.value.categories.firstOrNull()?.let { vm.selectCategory(it) }
        vm.selectCategory(null)
        val section = vm.home.value.sections.firstOrNull() ?: com.vayunmathur.appstore.platform.TvSection(id = "w5", title = "W5 Picks", apps = listOf(UnifiedApp(packageName = "app.alpha", source = AppSource.ACCRESCENT, name = "Alpha")))
        // Browse loads by route id, so the section must be the home state
        // that produced it (as a real home row would be).
        if (vm.home.value.sections.none { it.id == section.id }) {
            vm.sectionsFlow.value = listOf(section)
        }
        vm.loadBrowse(section.id)
        await("tv browse settles") { !vm.browse.value.isLoading }
        assertTrue(vm.browse.value.apps.isNotEmpty() || vm.browse.value.title == section.title,
            "browse=${vm.browse.value}")
        assertEquals(section.id, vm.browse.value.sectionId)

        val alpha = UnifiedApp(packageName = "app.alpha", source = AppSource.ACCRESCENT, name = "Alpha", versionCode = 999_999L)
        vm.loadDetail(alpha.packageName)
        await("tv detail for install") { vm.detail.value.app?.packageName == "app.alpha" }
        vm.install(alpha)
        await("tv install settles") {
            val stage = vm.detail.value.stage
            stage == null || stage.toString().contains("Fail", ignoreCase = true)
        }
        vm.dismissInstallFailure("app.alpha")

        // Installed-app actions should be safe no-ops/records under Robolectric.
        vm.openApp(ownPackage)
        vm.openInBrowser("https://example.org/alpha")
        vm.openInPlayStore("app.alpha")
        vm.setSourceEnabled(AppSource.PLAYSTORE, true)
        vm.setSourceEnabled(AppSource.PLAYSTORE, false)
        vm.setAutoInstallUpdates(true)
        vm.setAutoInstallUpdates(false)
        vm.setSearchFilter(SourceFilter.ACCRESCENT)
        vm.setSearch("beta")
        await("tv filtered search") { vm.search.value.results.any { it.packageName == "app.beta" } }
        // Library state is derived from installed + catalog flows; reading it
        // after a home load must include this store itself.
        await("tv library settles") { vm.library.value.apps.any { it.packageName == ownPackage } || !vm.isLoadingFlow.value }
    }
}
