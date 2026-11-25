# AGENTS.md — youpipe/ (':youpipe')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `youpipe/` + allowed shared modules. Do not root-scan.

_Install: ./install youpipe (dev by default)._
- Gradle: :youpipe / dir: youpipe/
- Package roots present: ui, data, domain, platform, network, nativeext
- Entry files: MainActivity.kt, Route.kt, Navigation.kt, YouPipeApplication.kt
- Deps: :library:image, :youpipe:extractor, :sdk:cast, :library:room, :library:work, :library:network
- Metadata: metadata_data/youpipe.md (present)
- Rust: yes / screenshotTest: yes

## Overview
**Purpose:** An ad-free Material 3 YouTube client (NewPipe extractor lineage) with subscriptions, history, downloads, and recommendations. Requires internet.
**Key features:**
- Subscription feed with categories, auto-refresh (`SubscriptionFetchTask`), watch history + resume, playlists, and Watch Later
- Video playback via Media3/ExoPlayer with SABR streaming, SponsorBlock, chapters, background audio, and Picture-in-Picture
- Offline downloads (`DownloadManager`/`DownloadWorker`) with import/export, plus Cast-to-TV via `:sdk:cast`
- Local recommendations engine with tunable interests/keywords and impression tracking
**Architecture / data-flow:** Tabbed-pager `ui/` (`Main` tabs + `VideoPage`/`ChannelPage`/dialog routes) over `platform/YouPipeViewModel` → `data/` Room (`SubscriptionDatabase`, repository + per-entity stores) fed by `SubscriptionFetchTask` and the Rust InnerTube extractor (`rustNativeLib youpipe_extractor`) bridged via `nativeext/YouPipeNative` + `network/Extractor.kt`; playback runs through `platform/PlaybackService` + the `network/sabr/` session stack. `onCreateBeforeContent` touches the VM so its hourly task arms, then resolves the launch backstack (share-target → watch link → persisted default page).
**Entry points + reading order:** `Route` (`Main(tab)` / `VideoPage` / `ChannelPage` / `SubscriptionVideosPage` / `PlaylistDetail` / `Downloads` + dialogs) → `MainActivity.kt` (`resolveInitialBackStack`) → `ui/VideoPage.kt` → `platform/YouPipeViewModel.kt` → `network/Extractor.kt` → `data/SubscriptionRepository.kt`.
**Gotchas:**
- `resolveInitialBackStack` runs synchronously in `onCreate` (before `setContent`) — keep it side-effect free
- PiP close and activity destroy deliberately do NOT stop `PlaybackService`; audio keeps playing and switches to audio-only
- SABR attestation goes through the `network/sabr/` WebView/token stack — changes there break playback long before the player UI does

## Key files
### Root — entry + navigation
- com/vayunmathur/youpipe/MainActivity.kt — share/deep-link backstack, default page
- com/vayunmathur/youpipe/Route.kt
- com/vayunmathur/youpipe/Navigation.kt
- com/vayunmathur/youpipe/YouPipeApplication.kt
### data/ — subscriptions, playlists, recommendations (Room)
- com/vayunmathur/youpipe/data/SubscriptionDatabase.kt
- com/vayunmathur/youpipe/data/SubscriptionRepository.kt
- com/vayunmathur/youpipe/data/Subscription.kt
- com/vayunmathur/youpipe/data/SubscriptionVideo.kt
- com/vayunmathur/youpipe/data/SubscriptionCategory.kt
- com/vayunmathur/youpipe/data/SubscriptionCategoryStore.kt
- com/vayunmathur/youpipe/data/Playlist.kt
- com/vayunmathur/youpipe/data/PlaylistStore.kt
- com/vayunmathur/youpipe/data/HistoryVideo.kt
- com/vayunmathur/youpipe/data/DownloadedVideo.kt
- com/vayunmathur/youpipe/data/CachedRelatedVideo.kt
- com/vayunmathur/youpipe/data/ChannelPreference.kt
- com/vayunmathur/youpipe/data/KeywordPreference.kt
- com/vayunmathur/youpipe/data/RecommendationPreferences.kt
- com/vayunmathur/youpipe/data/RecommendationImpression.kt
- com/vayunmathur/youpipe/data/RecommendationStore.kt
### nativeext/ — Rust extractor bridge
- com/vayunmathur/youpipe/nativeext/YouPipeNative.kt
### network/ — extraction + SABR
- com/vayunmathur/youpipe/network/Extractor.kt
- com/vayunmathur/youpipe/network/NativeExtractor.kt
- com/vayunmathur/youpipe/network/MyDownloader.kt
### domain/ — recommendations
- com/vayunmathur/youpipe/domain/Recommender.kt
### ui/ — tabs, feed, search, library
- com/vayunmathur/youpipe/ui/VideoPage.kt
- com/vayunmathur/youpipe/ui/VideoPlayer.kt
- com/vayunmathur/youpipe/ui/VideoPlayerViewState.kt
- com/vayunmathur/youpipe/ui/VideoPlayerPlaybackState.kt
- com/vayunmathur/youpipe/ui/VideoPlayerPlaybackEffects.kt
- com/vayunmathur/youpipe/ui/VideoPlayerSeekRow.kt
- com/vayunmathur/youpipe/ui/VideoPlayerTopControls.kt
- com/vayunmathur/youpipe/ui/VideoPlayerChapterSheet.kt
- com/vayunmathur/youpipe/ui/VideoPlayerCasting.kt
- com/vayunmathur/youpipe/ui/VideoPlayerNotificationProgressBar.kt
- com/vayunmathur/youpipe/ui/VideoDetails.kt
- com/vayunmathur/youpipe/ui/VideoDetailScreen.kt
- com/vayunmathur/youpipe/ui/VideoFormatting.kt
- com/vayunmathur/youpipe/ui/VideoSections.kt
- com/vayunmathur/youpipe/ui/VideoRow.kt
- com/vayunmathur/youpipe/ui/FeedGrid.kt
- com/vayunmathur/youpipe/ui/SearchPage.kt
- com/vayunmathur/youpipe/ui/ChannelPage.kt
- com/vayunmathur/youpipe/ui/SubscriptionsPage.kt
- com/vayunmathur/youpipe/ui/SubscriptionVideosPage.kt
- com/vayunmathur/youpipe/ui/HistoryPage.kt
- com/vayunmathur/youpipe/ui/SavedPage.kt
- com/vayunmathur/youpipe/ui/PlaylistDetailPage.kt
- com/vayunmathur/youpipe/ui/DownloadedVideosPage.kt
- com/vayunmathur/youpipe/ui/LinkifiedText.kt
- com/vayunmathur/youpipe/ui/dialogs/AddToPlaylist.kt
- com/vayunmathur/youpipe/ui/dialogs/AddToWatchLater.kt
- com/vayunmathur/youpipe/ui/dialogs/CreatePlaylist.kt
- com/vayunmathur/youpipe/ui/dialogs/CreateSubscriptionCategory.kt
### ui/ — settings
- com/vayunmathur/youpipe/ui/SettingsPage.kt
- com/vayunmathur/youpipe/ui/GeneralSettingsPage.kt
- com/vayunmathur/youpipe/ui/SponsorBlockSettingsPage.kt
- com/vayunmathur/youpipe/ui/RecommendationsSettingsPage.kt
- com/vayunmathur/youpipe/ui/ManageInterestsPage.kt
- com/vayunmathur/youpipe/ui/DataSettingsPage.kt
### platform/ — ViewModel, playback, downloads, sync
- com/vayunmathur/youpipe/platform/YouPipeViewModel.kt
- com/vayunmathur/youpipe/platform/YouPipeUiContract.kt
- com/vayunmathur/youpipe/platform/YouPipeVideoLoading.kt
- com/vayunmathur/youpipe/platform/PlaybackService.kt
- com/vayunmathur/youpipe/platform/DownloadManager.kt
- com/vayunmathur/youpipe/platform/DownloadWorker.kt
- com/vayunmathur/youpipe/platform/SubscriptionFetchTask.kt
- com/vayunmathur/youpipe/platform/YouPipeRecommendations.kt
- com/vayunmathur/youpipe/platform/YouPipeRecommendationSettings.kt
- com/vayunmathur/youpipe/platform/YouPipePlaylistOps.kt
- com/vayunmathur/youpipe/platform/YouPipeImportExport.kt
- com/vayunmathur/youpipe/platform/CastPlayback.kt
### network/sabr/ — SABR session + attestation stack
- com/vayunmathur/youpipe/network/sabr/SabrNgSession.kt
- com/vayunmathur/youpipe/network/sabr/SabrNgSessionStore.kt
- com/vayunmathur/youpipe/network/sabr/SabrNgSourceSpec.kt
- com/vayunmathur/youpipe/network/sabr/SabrNgDashMediaSource.kt
- com/vayunmathur/youpipe/network/sabr/SabrNgDownloadHelper.kt
- com/vayunmathur/youpipe/network/sabr/SabrNgSegmentDataSource.kt
- com/vayunmathur/youpipe/network/sabr/SabrRequestCoordinator.kt
- com/vayunmathur/youpipe/network/sabr/SabrFfmpegMuxer.kt
- com/vayunmathur/youpipe/network/sabr/SabrAttestationRetryHandler.kt
- com/vayunmathur/youpipe/network/sabr/SabrDownloadException.kt
- com/vayunmathur/youpipe/network/sabr/LocalDomPoTokenProvider.kt
- com/vayunmathur/youpipe/network/sabr/LocalDomPoTokenGenerator.kt
- com/vayunmathur/youpipe/network/sabr/SabrLocalDomPoTokenUtil.kt
- com/vayunmathur/youpipe/network/sabr/SharedWebViewRuntime.kt
- com/vayunmathur/youpipe/network/sabr/YoutubePageAttestationBootstrap.kt

## Verify (this module only)
```
./gradlew :youpipe:compileDevKotlin
./gradlew :youpipe:lint
./gradlew :youpipe:checkMetadata
```
