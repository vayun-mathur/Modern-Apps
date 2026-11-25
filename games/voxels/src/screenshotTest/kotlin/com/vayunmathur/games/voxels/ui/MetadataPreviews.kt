package com.vayunmathur.games.voxels.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.games.voxels.data.GameSettings
import com.vayunmathur.games.voxels.data.WorldInfo
import com.vayunmathur.games.voxels.data.WorldMeta

/** Landscape phone (the game's orientation), roughly 2340x1080 at xxhdpi. */
private const val PHONE_LANDSCAPE = "spec:width=891dp,height=411dp,dpi=420"

private fun fixtureWorld(name: String) = WorldInfo(
    id = "w_$name",
    dir = "/tmp/w_$name",
    meta = WorldMeta(name = name, seed = 42, created = 1_700_000_000_000L, lastPlayed = 1_795_617_660_000L),
)

private const val FIXTURE_INVENTORY =
    """{"selected":0,"slots":[{"id":1,"count":3},{"id":1056,"count":1}],"armor":[{"id":1067,"count":50}]}"""
private const val FIXTURE_RECIPES =
    """[{"in":2,"inN":1,"out":10,"outN":4,"cat":"block","known":true}]"""

/**
 * Store listing images for `:games:voxels` in the `phone` bucket — ONLY the
 * Layoutlib-renderable Compose screens: world menu, world creator, inventory,
 * furnace, chest, stonecutter, trade and settings overlays, all over literal
 * JSON fixtures (the same shapes the unit tests compose).
 *
 * Vulkan exception (documented, not faked): `Route.Game` is a Rust/Vulkan
 * `VoxelSurfaceView` with Compose only as HUD chrome on top. Layoutlib has no
 * Vulkan surface, so the game world is NOT captured here and no synthetic
 * game render is invented; in-game listing images remain hand-captured on a
 * device. The overlays above normally sit over that surface — here they
 * render over their own dim background exactly as their Compose draws it.
 * `./gradlew :games:voxels:metadata` copies renders into `metadata_data/photos/games-voxels/phone/`.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-worlds", device = PHONE_LANDSCAPE, group = "phone")
    @Composable
    fun Preview1Worlds() {
        VoxelsTheme {
            MenuScreen(
                worlds = listOf(fixtureWorld("Alpha"), fixtureWorld("Beta")),
                onlineWorlds = emptyList(),
                deviceId = "device-1",
                isOnline = true,
                onPlay = {},
                onDelete = {},
                onCreate = {},
                onHostOnline = {},
                onRefresh = {},
                onCopyDeviceId = {},
                onShare = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-create-world", device = PHONE_LANDSCAPE, group = "phone")
    @Composable
    fun Preview2CreateWorld() {
        VoxelsTheme { WorldCreatorScreen(onBack = {}, onCreate = { _, _, _ -> }) }
    }

    @PreviewTest
    @Preview(name = "3-inventory", device = PHONE_LANDSCAPE, group = "phone")
    @Composable
    fun Preview3Inventory() {
        VoxelsTheme {
            InventoryOverlay(inventoryJson = FIXTURE_INVENTORY, recipesJson = FIXTURE_RECIPES, blessingsJson = "{}", blessingCatalogJson = "[]", onClose = {})
        }
    }

    @PreviewTest
    @Preview(name = "4-furnace", device = PHONE_LANDSCAPE, group = "phone")
    @Composable
    fun Preview4Furnace() {
        VoxelsTheme {
            FurnaceOverlay(
                smeltingJson = """[{"in":1,"inN":1,"out":3,"outN":1,"secs":1.0},{"in":4,"inN":1,"out":6,"outN":1,"secs":1.0,"blast":true}]""",
                smeltJson = """{"active":true,"recipe":0,"progress":0.5,"fuel":0.25}""",
                isBlast = false,
                onClose = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "5-chest", device = PHONE_LANDSCAPE, group = "phone")
    @Composable
    fun Preview5Chest() {
        VoxelsTheme {
            ChestOverlay(containerJson = """{"slots":[{"id":1,"count":3},{"id":1059,"count":30}]}""", inventoryJson = """{"selected":0,"slots":[{"id":2,"count":5}]}""", onClose = {})
        }
    }

    @PreviewTest
    @Preview(name = "6-stonecutter", device = PHONE_LANDSCAPE, group = "phone")
    @Composable
    fun Preview6Stonecutter() {
        VoxelsTheme {
            StonecutterOverlay(cutsJson = """[{"in":1,"out":102,"outN":2},{"in":1,"out":103},{"in":8,"out":104,"outN":3}]""", inventoryJson = """{"selected":0,"slots":[{"id":1,"count":5},{"id":8,"count":2}]}""", onClose = {})
        }
    }

    @PreviewTest
    @Preview(name = "7-trade", device = PHONE_LANDSCAPE, group = "phone")
    @Composable
    fun Preview7Trade() {
        VoxelsTheme {
            TradeOverlay(tradesJson = """{"prof":"Farmer","level":1,"maxLevel":2,"trades":[{"cost":1026,"costN":2,"give":1027,"giveN":1,"level":1}]}""", onClose = {})
        }
    }

    @PreviewTest
    @Preview(name = "8-settings", device = PHONE_LANDSCAPE, group = "phone")
    @Composable
    fun Preview8Settings() {
        VoxelsTheme {
            SettingsSheet(settings = GameSettings(), onChange = {}, onClose = {})
        }
    }
}
