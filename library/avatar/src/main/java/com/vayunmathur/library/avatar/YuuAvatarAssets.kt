package com.vayunmathur.library.avatar

import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import android.os.ParcelFileDescriptor
import com.vayunmathur.library.log.Log

/**
 * Loads `models/base_upperbody.glb` from this module's assets and hands the
 * detached fd to native (ClipHandle/MaiaHandle pattern in `:library:ml`).
 * Best-effort: absent asset keeps the procedural fallback mesh.
 *
 * Packed-mesh contract (`models/README.md`): the glb holds the base body plus
 * named attachment nodes (`hair_00..11`, `glasses_01..06`, `beard_01..05`);
 * native parses once and assembles base + trait-selected attachments per
 * surface, so a hair/glasses/beard change re-assembles without re-parsing.
 */
object YuuAvatarAssets {
    /** Asset path of the rigged upper-body base mesh. */
    const val BASE_MESH_PATH = "models/base_upperbody.glb"

    /**
     * Adopt the bundled mesh, if present. Call once at startup (e.g. from
     * the first avatar surface) — the engine parses on next surface creation.
     */
    fun adoptBundledMesh(assets: AssetManager) {
        if (!YuuAvatarNative.isAvailable) return
        try {
            assets.openFd(BASE_MESH_PATH).use { afd -> handOff(afd) }
        } catch (e: Exception) {
            Log.status("YuuAvatarAssets", "no bundled mesh - procedural fallback (${e.message})")
        }
    }

    /**
     * Detaches [afd]'s fd into native. Split from [adoptBundledMesh] so the
     * fd ownership transfer can be exercised without a bundled mesh asset.
     */
    internal fun handOff(afd: AssetFileDescriptor) {
        val fd = afd.parcelFileDescriptor.detachFd()
        var handed = false
        try {
            YuuAvatarNative.setBundledMeshFd(fd, afd.startOffset, afd.length)
            handed = true
        } finally {
            if (!handed) closeFd(fd)
        }
    }

    private fun closeFd(fd: Int) {
        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
    }
}
