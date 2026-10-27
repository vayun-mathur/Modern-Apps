package com.vayunmathur.library.downloadservice

import android.content.Context
import java.io.File

/**
 * Centralized model URL config for supply-chain mitigation #1.
 * Models are served ONLY from the self-hosted mirror (no HuggingFace fallback).
 * The server must host each file under https://data.maproj.org/models/
 * and set Content-Length + ETag.
 *
 * Mirror contents (path = file served under MIRROR_BASE):
 *   models/gemma4/gemma4_text.maml, gemma4_embed.maml, gemma4_head.maml,
 *     gemma4_vision.maml, gemma4_audio.maml, gemma4_tokenizer.spm1
 */
object ModelUrls {
    const val MIRROR_BASE = "https://data.maproj.org/models/"

    /**
     * Gemma 4 E2B for `:library:ml`'s maml runtime (`Gemma4Handle` and its vision and audio
     * towers), downloaded into `gemma4/` under the app's external files directory.
     */
    object GemmaMaml {
        const val DIR = "gemma4"
        const val BASE = "${MIRROR_BASE}gemma4/"

        val FILES: List<ModelDownloadItem> = listOf(
            // 1,101,593,392 bytes.
            item(
                "gemma4_text.maml",
                "Text Model",
                "f990e8f99d61a127dd329cd4329b081f214067afefb1b3f4778922cc12d210d8",
            ),
            // 1,566,576,256 bytes.
            item(
                "gemma4_embed.maml",
                "Embeddings",
                "11b9e8b316dd8a2c628d58b98908e9a509764c6f63531731b6d94ccf6419a9bd",
            ),
            // 403,703,360 bytes.
            item(
                "gemma4_head.maml",
                "Output Head",
                "338021b6aa4d78eda55e47f5197d186087a73a8dade02001812aa95f0f550937",
            ),
            // 97,815,200 bytes.
            item(
                "gemma4_vision.maml",
                "Vision Tower",
                "4c53c0a8383f2ea99f8beed50bd5eac203565436313d0c2dd77b389ea1cf38e3",
            ),
            // 173,999,072 bytes.
            item(
                "gemma4_audio.maml",
                "Audio Tower",
                "be3ac4f968835fa257291f0845be642750f71e63e197a6e199ad5210316cb4f2",
            ),
            // 3,889,167 bytes.
            item(
                "gemma4_tokenizer.spm1",
                "Tokenizer",
                "e3e03941be666b885f6644cb3d7a0264949f842f8a7b97302589d9deff2334d7",
            ),
        )

        private fun item(name: String, description: String, sha256: String) =
            ModelDownloadItem("$BASE$name", "$DIR/$name", "Gemma 4 $description", sha256)

        /** Directory `Gemma4Handle.inDirectory` loads from. */
        fun modelDir(context: Context): File = File(context.getExternalFilesDir(null), DIR)

        /** True once every file is on disk. */
        fun isDownloaded(context: Context): Boolean {
            val root = context.getExternalFilesDir(null) ?: return false
            return FILES.all { File(root, it.fileName).isFile }
        }
    }

    /** Everything downloaded on OpenAssistant first launch. */
    val INITIAL = GemmaMaml.FILES
}
/** A single model file fetched from the self-hosted mirror, with optional SHA-256. */
data class ModelDownloadItem(
    val url: String,
    val fileName: String,
    val description: String,
    val sha256: String? = null,
)
