package com.vayunmathur.appstore.ui
import com.vayunmathur.appstore.platform.AppDetailActions
import com.vayunmathur.appstore.platform.AppDetailUiState
import com.vayunmathur.appstore.platform.AppSection
import com.vayunmathur.appstore.platform.HomeActions
import com.vayunmathur.appstore.platform.HomeUiState
import com.vayunmathur.appstore.platform.LibraryActions
import com.vayunmathur.appstore.platform.LibraryUiState
import com.vayunmathur.appstore.platform.SearchActions
import com.vayunmathur.appstore.platform.SearchUiState
import com.vayunmathur.appstore.platform.SectionLayout
import com.vayunmathur.appstore.platform.UpdatesActions
import com.vayunmathur.appstore.platform.UpdatesUiState

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconProvider
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Stand-in launcher icon.
 *
 * Real icons come either from PackageManager or over the network from F-Droid/Play, and a
 * preview has neither, so the sample rows carry a generated tile rather than a blank
 * square. It goes in through `installedIcons`, which is the one icon path that does not
 * touch the network.
 */
private fun tile(top: Long, bottom: Long): Drawable =
    GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(top.toInt(), bottom.toInt()),
    )

/**
 * Store listing images for `:appstore`, rendered from Compose previews instead of from
 * an instrumented test on a device.
 *
 * `./gradlew :appstore:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/appstore/`, where `release.sh` picks them up.
 *
 * Two things to keep in mind when editing:
 *
 *  - Order matters, and it comes from the function names. The generated PNG filenames
 *    embed the function name, so `Preview1Home`/`Preview2Detail`/... sort into listing
 *    order no matter how the plugin formats the rest of the filename. Renumber the
 *    functions if you reorder the listing.
 *  - Everything must be a literal. These render with no ViewModel, no database and no
 *    network, so the state below is the whole input — which is also what makes the output
 *    reproducible from a clean checkout. In particular no preview can show a screenshot
 *    or a remote icon, since both would need a fetch.
 *  - Each preview needs @PreviewTest as well as @Preview. @Preview alone renders in
 *    Studio but is not collected as a screenshot test, and the build fails with the
 *    unhelpful "did not discover any tests".
 *  - The previews must be members of a class, not top-level functions. Android Studio
 *    renders top-level previews happily, but the screenshot engine discovers previews as
 *    JUnit tests and needs a real class to attach them to — top-level functions land in a
 *    synthetic `…Kt` facade and are silently skipped.
 *
 * The last slot goes to the "how apps are checked" page, because comparing what each
 * source actually guarantees is the thing that distinguishes this store from any other
 * F-Droid or Play client.
 *
 * Home, Search, Updates and Library render inside [ChromePreview] — the real
 * [TabbedPagerScaffold] with the app's real tabs — so the bottom bar users see is
 * in the shot. Detail and Trust are pushed routes with no tab bar on device, so
 * they render bare, exactly as shipped. Only the selected tab is literal.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-home", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Home() {
        ChromePreview(selectedTab = 0)
    }

    @PreviewTest
    @Preview(name = "2-detail", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Detail() {
        DynamicTheme(darkTheme = true) {
            AppDetailScreen(
                state = AppDetailUiState(
                    app = AEGIS.copy(
                        versionName = "3.4.1",
                        versionCode = 341,
                        sizeBytes = 9_871_360,
                        license = "GPL-3.0-only",
                        categories = listOf("Security", "Internet"),
                        sourceCode = "https://github.com/beemdevelopment/Aegis",
                        website = "https://getaegis.app",
                        contentRating = "Everyone",
                        antiFeatures = listOf("NonFreeNet"),
                        whatsNew = "Faster vault unlock and a fix for importing " +
                            "Authenticator Plus backups.",
                        updatedOn = "28 July 2026",
                        description = "Aegis is a free, secure and open source app for " +
                            "Android to manage your 2-step verification tokens.\n\n" +
                            "Vaults are encrypted with AES-256 and can be unlocked with " +
                            "a password or your fingerprint. Backups are written to a " +
                            "location you choose, and nothing ever leaves the device.",
                    ),
                    installedInfo = InstalledInfo(
                        packageName = "com.beemdevelopment.aegis",
                        name = "Aegis Authenticator",
                        versionName = "3.4.0",
                        versionCode = 340,
                    ),
                    // What the last install actually proved, in the verifier's own words.
                    verification = VerificationResult.Verified(
                        "hash matches for 1 of 1 file(s); " +
                            "signed by the key F-Droid's signed app list expects"
                    ),
                    installedIcon = tile(0xFF3A6EA5, 0xFF17324D),
                ),
                actions = AppDetailActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-search", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Search() {
        ChromePreview(selectedTab = 1)
    }

    @PreviewTest
    @Preview(name = "4-trust", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4Trust() {
        DynamicTheme(darkTheme = true) {
            TrustPage(
                ownSigningCertificates = setOf(SAMPLE_FINGERPRINT),
                onBack = {},
                // Items 0 and 1 are the intro and the shared-guarantees card; 2 is the
                // first per-source card, which is what the listing should show.
                initialFirstVisibleItem = 2,
            )
        }
    }

    @PreviewTest
    @Preview(name = "5-updates", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview5Updates() {
        ChromePreview(selectedTab = 2)
    }

    @PreviewTest
    @Preview(name = "6-library", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview6Library() {
        ChromePreview(selectedTab = 3)
    }
}

@Composable
private fun HomeContent() {
    HomeScreen(
        state = HomeUiState(
            sections = listOf(
                AppSection(
                    id = "modern-recent",
                    title = "New from Modern Apps",
                    subtitle = "The newest builds from this repo",
                    apps = MODERN,
                ),
                AppSection(
                    id = "proprietary-recent",
                    title = "New in Proprietary",
                    subtitle = "The newest builds from the Play mirror",
                    apps = PROPRIETARY,
                ),
                AppSection(
                    id = "fdroid-recent",
                    title = "New on F-Droid",
                    subtitle = "The newest builds on F-Droid",
                    apps = FDROID,
                    layout = SectionLayout.LIST,
                ),
            ),
            categories = listOf("Security", "Internet", "Multimedia", "Writing"),
            updateCount = 3,
            installedPackages = setOf(
                "com.vayunmathur.calculator",
                "com.beemdevelopment.aegis",
            ),
            installedIcons = ICONS,
        ),
        actions = HomeActions.Noop,
    )
}

@Composable
private fun SearchContent() {
    SearchScreen(
        state = SearchUiState(
            query = "password",
            results = SEARCH_HITS,
            hasSearched = true,
            installedPackages = setOf("com.beemdevelopment.aegis"),
            installedIcons = ICONS,
        ),
        actions = SearchActions.Noop,
    )
}

@Composable
private fun UpdatesContent() {
    UpdatesScreen(
        state = UpdatesUiState(
            updates = listOf(
                AEGIS.copy(versionName = "3.4.1", versionCode = 341),
            ),
            installedIcons = ICONS,
            installedInfos = mapOf(
                "com.beemdevelopment.aegis" to InstalledInfo(
                    packageName = "com.beemdevelopment.aegis",
                    name = "Aegis Authenticator",
                    versionName = "3.4.0",
                    versionCode = 340,
                ),
            ),
            lastCheckedAt = 1_785_566_534_600,
        ),
        actions = UpdatesActions.Noop,
    )
}

@Composable
private fun LibraryContent() {
    LibraryScreen(
        state = LibraryUiState(
            apps = MODERN + FDROID,
            installedIcons = ICONS,
        ),
        actions = LibraryActions.Noop,
    )
}

/**
 * The app's real tab chrome around preview screen content: the same
 * [TabbedPagerScaffold] in [TabStyle.BottomNav] with the same five tabs that
 * `AppTabs` declares — the pager starts on [selectedTab], so the bar highlights
 * the shown screen. The Sources tab needs the ViewModel, so it shows an empty
 * page (never visible — only the selected tab renders); everything else is the
 * real screen with literal state.
 */
@Composable
private fun ChromePreview(selectedTab: Int) {
    DynamicTheme(darkTheme = true) {
        val tabs = listOf(
            PagerTab("Home", { IconHome() }) { HomeContent() },
            PagerTab("Search", { IconSearch() }) { SearchContent() },
            PagerTab("Updates", { IconDownload() }) { UpdatesContent() },
            PagerTab("Library", { IconPackage() }) { LibraryContent() },
            PagerTab("Sources", { IconProvider() }) { },
        )
        TabbedPagerScaffold(
            tabs = tabs,
            pagerState = rememberPagerState(initialPage = selectedTab, pageCount = { tabs.size }),
            tabStyle = TabStyle.BottomNav,
        )
    }
}

/**
 * Only ever passed to [com.vayunmathur.appstore.data.security.ApkCertificates.abbreviate],
 * which shows the first eight bytes, so this is a plausible-looking constant rather than
 * anyone's real signing certificate.
 */
private const val SAMPLE_FINGERPRINT =
    "7a3f19c4d8025be6104fbb27a9c31d5e08f7462a1c9d3b80e5fa62471d0c98b3"

private val AEGIS = UnifiedApp(
    packageName = "com.beemdevelopment.aegis",
    source = AppSource.FDROID,
    name = "Aegis Authenticator",
    summary = "Two-factor authentication with an encrypted, exportable vault",
    author = "Beem Development",
)

private val MODERN = listOf(
    UnifiedApp(
        packageName = "com.vayunmathur.calculator",
        source = AppSource.MODERN_APPS,
        name = "Calculator",
        summary = "Scientific calculator with graphing, history and memory",
        author = "Vayun Mathur",
        versionName = "1.8.0",
        versionCode = 180,
    ),
    UnifiedApp(
        packageName = "com.vayunmathur.email",
        source = AppSource.MODERN_APPS,
        name = "Email",
        summary = "IMAP mail with threads, drafts, snooze and an offline outbox",
        author = "Vayun Mathur",
        versionName = "1.8.0",
        versionCode = 180,
    ),
    UnifiedApp(
        packageName = "com.vayunmathur.maps",
        source = AppSource.MODERN_APPS,
        name = "Maps",
        summary = "Offline vector maps with search and turn-by-turn navigation",
        author = "Vayun Mathur",
        versionName = "1.8.0",
        versionCode = 180,
    ),
    UnifiedApp(
        packageName = "com.vayunmathur.photos",
        source = AppSource.MODERN_APPS,
        name = "Photos",
        summary = "Gallery with on-device search and a non-destructive editor",
        author = "Vayun Mathur",
        versionName = "1.8.0",
        versionCode = 180,
    ),
)

private val FDROID = listOf(
    AEGIS,
    UnifiedApp(
        packageName = "com.kunzisoft.keepass.libre",
        source = AppSource.FDROID,
        name = "KeePassDX",
        summary = "Password manager for KeePass databases, fully offline",
        author = "Kunzisoft",
    ),
    UnifiedApp(
        packageName = "com.nutomic.syncthingandroid",
        source = AppSource.FDROID,
        name = "Syncthing",
        summary = "Continuous file synchronisation between your own devices",
        author = "Syncthing Community",
    ),
)

private val PROPRIETARY = listOf(
    UnifiedApp(
        packageName = "com.whatsapp",
        source = AppSource.PROPRIETARY,
        name = "WhatsApp",
        summary = "Messaging and calling, privately mirrored from Play",
        author = "WhatsApp LLC",
    ),
)

/** A search that hit all three sources, which is the point of the screen. */
private val SEARCH_HITS = listOf(
    UnifiedApp(
        packageName = "com.kunzisoft.keepass.libre",
        source = AppSource.FDROID,
        name = "KeePassDX",
        summary = "Password manager for KeePass databases, fully offline",
        author = "Kunzisoft",
    ),
    AEGIS,
    UnifiedApp(
        packageName = "com.vayunmathur.passwords",
        source = AppSource.MODERN_APPS,
        name = "Passwords",
        summary = "Password manager with autofill and an encrypted local vault",
        author = "Vayun Mathur",
    ),
    UnifiedApp(
        packageName = "com.agilebits.onepassword",
        source = AppSource.PLAYSTORE,
        name = "1Password",
        summary = "Password manager and secure wallet",
        author = "AgileBits",
        rating = 4.4f,
        ratingCount = 512_000,
        installs = 10_000_000,
    ),
    UnifiedApp(
        packageName = "com.bitwarden.authenticator",
        source = AppSource.PLAYSTORE,
        name = "Bitwarden Authenticator",
        summary = "Two-factor codes, from the Bitwarden team",
        author = "Bitwarden Inc.",
        rating = 4.6f,
        ratingCount = 21_000,
        installs = 1_000_000,
    ),
)

private val ICONS: Map<String, Drawable> = mapOf(
    "com.vayunmathur.calculator" to tile(0xFF6D5BD0, 0xFF2E2467),
    "com.vayunmathur.email" to tile(0xFF1E88A8, 0xFF0B3C4C),
    "com.vayunmathur.maps" to tile(0xFF2E9E63, 0xFF0F4630),
    "com.vayunmathur.photos" to tile(0xFFC2456A, 0xFF541C2E),
    "com.vayunmathur.passwords" to tile(0xFF8A6D3B, 0xFF3B2E19),
    "com.beemdevelopment.aegis" to tile(0xFF3A6EA5, 0xFF17324D),
    "com.kunzisoft.keepass.libre" to tile(0xFFB0562A, 0xFF4C2312),
    "com.nutomic.syncthingandroid" to tile(0xFF3E6FB5, 0xFF17304F),
    "com.agilebits.onepassword" to tile(0xFF1A73E8, 0xFF0B2E5C),
    "com.bitwarden.authenticator" to tile(0xFF175DDC, 0xFF0A2456),
)
