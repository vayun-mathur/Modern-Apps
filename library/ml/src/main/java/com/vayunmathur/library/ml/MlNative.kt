package com.vayunmathur.library.ml

/**
 * The JNI surface of the Vulkan compute runtime in `library/ml/src/main/rust`.
 *
 * Deliberately narrow: create a network from its weights, hand it a bitmap's pixels, get
 * a mask back, destroy it. Preprocessing, the whole forward pass and the readback happen
 * on the native side, so the boundary is crossed three times per inference rather than
 * once per pixel.
 *
 * [handle] values are opaque pointers owned by the native side. Every method tolerates
 * `0`, which is what the create functions return on failure — so a device without Vulkan
 * fp16 compute degrades to a disabled feature rather than a crash. See [isAvailable].
 *
 * Nothing here is thread-safe. Callers hold a lock across [segment] and [destroy]; see
 * [SelfieSegmenter] and [SubjectSegmenter], which do.
 *
 * One declaration per native entry point (create/encode/step/destroy per model family);
 * the count is inherent to the JNI surface. FileLength (fatal lint) still caps file size.
 */
@Suppress("TooManyFunctions")
internal object MlNative {

    /**
     * Whether `libmodelrunner.so` loaded.
     *
     * False on a device whose ABI we did not build for. It says nothing about whether
     * Vulkan or fp16 is available — that is only known once a create call is attempted,
     * because it needs a `VkDevice` to ask.
     */
    val isAvailable: Boolean = try {
        System.loadLibrary("modelrunner")
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Bring up MediaPipe Selfie Segmentation from a `.maml` blob. Returns 0 on failure.
     *
     * Failure is expected and normal on a device without `VK_KHR_shader_float16_int8`,
     * which is a Vulkan 1.2 promotion and so optional at the 1.1 floor minSdk 31
     * guarantees. The reason is written to logcat under the `ModelRunner` tag.
     */
    external fun createSelfie(weights: ByteArray): Long

    /** Bring up U²-Net portable. Returns 0 on failure, as [createSelfie]. */
    external fun createU2netp(weights: ByteArray): Long

    /**
     * Bring up MODNet photographic-portrait matting. Returns 0 on failure, as [createSelfie].
     *
     * Failure is expected and normal on a device without `VK_KHR_shader_float16_int8`,
     * like the other segmenters. The caller keeps the sharp frame.
     */
    external fun createModnet(weights: ByteArray): Long

    /** Bring up SCRFD 500M face detection. Returns 0 on failure, as [createSelfie]. */
    external fun createScrfd(weights: ByteArray): Long

    /** Bring up MobileFaceNet face embedding. Returns 0 on failure, as [createSelfie]. */
    external fun createMobilefacenet(weights: ByteArray): Long
    /**
     * Bring up both PP-OCRv5 networks and the character table. Returns 0 on failure.
     *
     * Three assets because they are three files: DBNet detection, CTC recognition, and the
     * 836-line dictionary the labels index. Detection runs at a fixed 960x960 square and
     * recognition at a fixed 48x320, so each records its command buffer once.
     *
     * The returned handle is **not** interchangeable with the others: it is freed by
     * [destroyOcr], not [destroy].
     */
    external fun createPpocr(detection: ByteArray, recognition: ByteArray, keys: String): Long
    /**
     * Recognise every line in [pixels] and return them tab-separated, or null on failure.
     *
     * One region per line of the result: the text, then eight quad coordinates in
     * source-bitmap pixels, then the confidence, then `1` or `0` for vertical - ten
     * tab-separated fields after the text.
     *
     * Detection, the rotated crops, recognition of each, the CTC collapse and reading
     * order all happen natively, so this crosses the boundary once per bitmap rather than
     * once per line. An empty string means no text, which is not a failure.
     *
     * Packing text and geometry into one string is safe rather than lucky: the dictionary
     * holds 836 single non-whitespace characters plus a space, so no decoded line can
     * contain a tab or a newline.
     */
    external fun recognizeText(
        handle: Long,
        pixels: IntArray,
        width: Int,
        height: Int,
    ): String?

    /**
     * Bring up Supertonic 3 from its four `.maml` files, the codepoint table and one voice.
     * Returns 0 on failure.
     *
     * No shape arguments: every Supertonic plan is utterance-shaped, so each net is re-recorded per
     * sentence rather than compiled once at a padded width. There is also no phoneme dictionary -
     * phoneme dictionary - the front end is [indexer], a flat 65,536-entry codepoint table, which is
     * why [synthesizeSupertonic] insists on NFKD.
     *
     * The four plans arrive as **file descriptors** rather than byte arrays. A `ByteArray` would
     * allocate the model three times over - the Java array, the `Vec<u8>` JNI hands Rust, and the
     * reader's own copy of the data section - which for a ~105 MB bundle is ~300 MB of transient
     * heap and an out-of-memory kill rather than a slow load. Native reads the header and tensor
     * table only, then streams the weights into the GPU through a fixed-size staging buffer.
     *
     * [fds], [offsets] and [lengths] are parallel and in a fixed order: duration predictor, text
     * encoder, sampler, vocoder. All three are needed because an `AssetFileDescriptor` describes a
     * *range of the APK* rather than a file of its own; for a file on disk the offset is 0 and the
     * length is the file's size.
     *
     * **Each descriptor must be detached.** Native takes ownership and closes it, on the failure
     * paths as much as the successful one, so a caller must not close them itself. An asset also has
     * to be stored uncompressed for `openFd` to work at all - `noCompress += "maml"`.
     *
     * [indexer] and [style] stay byte arrays: 128 KB and 25 KB, where streaming saves nothing.
     * [style] is separate from the plans and swappable through [setSupertonicVoice].
     *
     * Freed by [destroySupertonic], not [destroy] or [destroyOcr].
     */
    external fun createSupertonic(
        fds: IntArray,
        offsets: LongArray,
        lengths: LongArray,
        indexer: ByteArray,
        style: ByteArray,
    ): Long

    /**
     * Point a live handle at another voice, returning false on failure.
     *
     * Cheap, and the reason a voice change does not mean a new handle: the four networks stay
     * uploaded and only the two style tensors are replaced.
     */
    external fun setSupertonicVoice(handle: Long, style: ByteArray): Boolean

    /**
     * Synthesise [text] in [language] and return mono samples in `-1..1` at 44,100 Hz, or null on
     * failure.
     *
     * [text] **must already be NFKD**-decomposed - use `java.text.Normalizer.normalize(text,
     * Form.NFKD)`. The model has no precomposed accents: `é` is unmapped while `e` and the
     * combining acute are both first-class tokens, so precomposed text silently loses
     * characters. Doing the decomposition natively would mean carrying Unicode tables in the
     * APK when the platform already has them.
     *
     * NFKD is the only preparation the caller owes. Everything the upstream SDK does after it -
     * folding curly quotes and dashes, dropping emoji, expanding `e.g.,`, ending an
     * unpunctuated sentence - happens natively, so the host tests and this call see one front
     * end rather than two.
     *
     * [language] is the ISO-639-1 code, or `na` for one the model does not list. It is not a
     * hint. Supertonic 3 is the multilingual model and was trained with every utterance wrapped
     * in a `<en>...</en>` tag, so the wrong code reads the text in the wrong language and no
     * code at all produces fluent-sounding non-words rather than an error.
     *
     * Two calls with the same text differ, as flow matching starts from a sampled latent.
     */
    external fun synthesizeSupertonic(handle: Long, text: String, language: String): FloatArray?

    /**
     * Bring up NLLB-200-distilled-600M from its one `.maml` and its tokenizer table.
     * Returns 0 on failure.
     *
     * One graph, not two: the 256,206-row
     * embedding is **tied** - it is the encoder's input table, the decoder's input
     * table and the logits kernel - so an encoder file and a decoder file would upload
     * ~250 MiB of it twice. Native selects between the encoder pass, the decode step
     * and the logits projection by re-recording one net.
     *
     * The plan arrives as a **file descriptor** because it is a downloaded file, not a
     * bundled asset: [offset] is 0 and [length] the file's size; only an asset needs a
     * real range. Native reads the header and tensor table only, then streams the
     * weights into the GPU. It also keeps the descriptor open for the life of the
     * handle, because the tied embedding is gathered a row at a time on the host
     * rather than uploaded a second time.
     *
     * **The descriptor must be detached.** Native takes ownership and closes it, on the
     * failure paths as much as the successful one, so a caller must not close it
     * itself.
     *
     * [tokenizer] stays a byte array, where streaming saves nothing.
     *
     * [cacheBudgetBytes] is how much memory this device will spend on the KV cache. Native turns
     * it into positions - one costs 18 KB - and picks the largest tier that fits, so a 4 GB phone
     * holds a short conversation instead of failing to allocate a long one.
     *
     * The maml's graph id is 18 (`graph::NLLB`), agreed three-ways with model-eng
     * and rust-eng - native rejects any other file at load.
     *
     * Freed by [destroyNllb], not [destroy], [destroyOcr], [destroySupertonic]
     * or [destroyTinyclip].
     */
    external fun createNllb(
        fd: Int,
        offset: Long,
        length: Long,
        tokenizer: ByteArray,
    ): Long

    /**
     * Translate [text] from the language [sourceToken] names into the language
     * [targetToken] names, or null on failure.
     *
     * [text] **must already be NFKC** - use `java.text.Normalizer.normalize(text,
     * Form.NFKC)`. The model's normaliser is `nmt_nfkc` with a precompiled charsmap,
     * and reproducing that natively would mean carrying Unicode tables the platform
     * already has.
     *
     * BOTH tokens are required, which is the protocol flip vs the old SMaLL-100
     * target-only call:
     * the source token goes on the **source** side (`pieces + [EOS]`), and the target
     * token forced-BOSes the **decoder** rather than starting from `</s>`. Backwards
     * it produces fluent output in the wrong language rather than an error.
     * `NllbModel.tokenId` is the table: the 202 flores200 codes at 256001..256202,
     * which native validates in `post::translate::is_nllb_lang_token`.
     *
     * Greedy decoding, capped at 128 tokens. An empty string means the text had
     * nothing to translate, which is not a failure.
     */
    external fun translateNllb(
        handle: Long,
        text: String,
        sourceToken: Int,
        targetToken: Int,
    ): String?

    /**
     * Free NLLB's network, its open weights file and its tokenizer table.
     * Exactly once per non-zero handle from [createNllb].
     */
    external fun destroyNllb(handle: Long)

    /**
     * Bring up MADLAD400-3B-MT from its one `.maml` and its Unigram tokenizer table.
     * Returns 0 on failure.
     *
     * One graph, not two: the input table and the logits kernel are **untied** — separate
     * 256,000-row tables — so an encoder file and a decoder file would each carry their
     * own. Native selects between the encoder pass and a decode step by re-recording one
     * net, and reads both tables' rows on the host.
     *
     * The plan arrives as a **file descriptor** because it is a downloaded file, not a
     * bundled asset: [offset] is 0 and [length] the file's size; only an asset needs a
     * real range. Native reads the header and tensor table only, then streams the
     * weights into the GPU. It also keeps the descriptor open for the life of the
     * handle, because the input table is gathered a row at a time on the host
     * rather than uploaded a second time.
     *
     * **The descriptor must be detached.** Native takes ownership and closes it, on the
     * failure paths as much as the successful one, so a caller must not close it
     * itself.
     *
     * [tokenizer] stays a byte array, where streaming saves nothing.
     *
     * The maml's graph id is 25 (`graph::MADLAD`) — native rejects any other file at load.
     *
     * Freed by [destroyMadlad], not [destroy], [destroyOcr], [destroySupertonic],
     * [destroyTinyclip] or [destroyNllb].
     */
    external fun createMadlad(
        fd: Int,
        offset: Long,
        length: Long,
        tokenizer: ByteArray,
    ): Long

    /**
     * Translate [text] into the language [targetTag] names (e.g. `"<2en>"`),
     * or null on failure.
     *
     * [text] must already have space runs collapsed — use `text.replace(Regex(" {2,}"),
     * " ")`. The model's normaliser is the identity (unlike NLLB's `nmt_nfkc`), so
     * NFKC-normalising here would retokenise text the model was trained to see raw.
     *
     * ONE tag is required, which is the protocol difference vs NLLB's two flores ids:
     * the tag leads the **source** (`<2tgt> pieces [EOS]`) and the decoder starts
     * unforced from `<unk>`. Backwards it produces fluent output in the wrong language
     * rather than an error. `MadladModel.targetTag` is the table: the `<2xx>` codes,
     * which native validates against the Unigram table.
     *
     * Greedy decoding, capped at 128 tokens. An empty string means the text had
     * nothing to translate, which is not a failure.
     */
    external fun translateMadlad(
        handle: Long,
        text: String,
        targetTag: String,
    ): String?

    /**
     * Free MADLAD's network, its open weights file and its tokenizer table.
     * Exactly once per non-zero handle from [createMadlad].
     */
    external fun destroyMadlad(handle: Long)

    /**
     * Bring up TinyCLIP from its one `.maml`. Returns 0 on failure.
     *
     * One graph, not two: the image and text towers
     * share **no weights at all**, but they do share a 22.6 MiB file, so two handles would upload it
     * twice. Native selects between them by re-recording one net, which costs a `device_wait_idle` —
     * and an indexing run is the image tower throughout, so it pays for exactly one.
     *
     * The plan arrives as a **file descriptor** because it is a bundled asset: [offset] and [length]
     * are the `AssetFileDescriptor`'s, since an asset is a *range of the APK* rather than a file of
     * its own. `AssetManager.openFd` throws for a deflated entry, which is what `noCompress +=
     * "maml"` in `photos/build.gradle.kts` is for.
     *
     * Native keeps the descriptor open for the life of the handle, because the 12.6 MiB token
     * embedding is gathered a row at a time on the host rather than read from the upload.
     *
     * **The descriptor must be detached.** Native takes ownership and closes it, on the failure
     * paths as much as the successful one, so a caller must not close it itself.
     *
     * Freed by [destroyTinyclip], not [destroy], [destroyOcr] or [destroySupertonic].
     */
    external fun createTinyclip(fd: Int, offset: Long, length: Long): Long

    /**
     * The 512-d image embedding for [pixels], or null on failure.
     *
     * [pixels] is `3 * 224 * 224` floats, NCHW and RGB, already resized to the shortest edge,
     * centre-cropped and normalised by CLIP's mean and standard deviation. That work stays in
     * Kotlin — see `ClipEmbedder.preprocess` — because it is bitmap handling the platform does
     * better.
     *
     * **Not L2-normalised.** `ClipEmbedder.l2Normalize` does that for both towers, so the stored
     * vector format is decided in one place.
     */
    external fun tinyclipImage(handle: Long, pixels: FloatArray): FloatArray?

    /**
     * The 512-d text embedding for [ids], or null on failure.
     *
     * [ids] must be the query's tokens **up to and including `<|endoftext|>`**, with the tokenizer's
     * padding trimmed off. CLIP pools at the end-of-text position, so the trim is what decides which
     * position is pooled — and because the tower is causal, the trimmed sequence gives the identical
     * vector to the padded 77 for a fraction of the work.
     *
     * Not L2-normalised, as [tinyclipImage] is not.
     */
    external fun tinyclipText(handle: Long, ids: IntArray): FloatArray?

    /**
     * Free TinyCLIP's network and its open weights file.
     * Exactly once per non-zero handle from [createTinyclip].
     */
    external fun destroyTinyclip(handle: Long)

    /**
     * Bring up Maia3-5M from its one bundled `.maml`. Returns 0 on failure.
     *
     * One forward pass per move and no search, so unlike [createTinyclip] there is only ever
     * one plan and native never re-records.
     *
     * The plan arrives as a **file descriptor** because it is a bundled asset: [offset] and
     * [length] are the `AssetFileDescriptor`'s, since an asset is a *range of the APK* rather
     * than a file of its own. `AssetManager.openFd` throws for a deflated entry, which is what
     * `noCompress += "maml"` in `games/chess/build.gradle.kts` is for.
     *
     * Native keeps the descriptor open for the life of the handle, because the two elo
     * embeddings are blended on the host — the blend weight is an input, so it cannot be
     * folded into the upload.
     *
     * **The descriptor must be detached.** Native takes ownership and closes it, on the failure
     * paths as much as the successful one, so a caller must not close it itself.
     *
     * Freed by [destroyMaia], not [destroy], [destroyOcr] or [destroySupertonic].
     */
    external fun createMaia(fd: Int, offset: Long, length: Long): Long

    /**
     * The 4352 move logits for a board, or null on failure.
     *
     * [planes] is `12 * 64` floats, plane-major, with square `rank * 8 + file` so a1 is 0 and
     * h8 is 63. The planes are white P, N, B, R, Q, K then black P, N, B, R, Q, K.
     *
     * **The board must already be from the mover's side.** When black is to move the caller
     * mirrors it vertically and swaps the colours, and un-mirrors the move it picks. The model
     * was trained that way and the move vocabulary has no black promotions at all, so passing
     * an unmirrored black position produces legal-looking nonsense rather than an error.
     *
     * [selfElo] and [oppoElo] are 0..5000 and are clamped, not rejected. They are what makes
     * one weights file cover every difficulty.
     *
     * The logits are **raw**: indices 0..4095 are `from * 64 + to` and 4096..4351 are
     * `4096 + fromFile * 32 + toFile * 4 + piece` for q, r, b, n. Legal masking, temperature
     * and sampling all need the caller's move list and stay in Kotlin.
     */
    external fun maiaLogits(
        handle: Long,
        planes: FloatArray,
        selfElo: Int,
        oppoElo: Int,
    ): FloatArray?

    /**
     * Free Maia3's network and its open weights file.
     * Exactly once per non-zero handle from [createMaia].
     */
    external fun destroyMaia(handle: Long)

    /**
     * Bring up the Now Playing audio fingerprinter from its one bundled `.maml`. Returns 0 on
     * failure.
     *
     * One forward pass per window and no state, so like [createMaia] there is only ever one
     * plan and native never re-records.
     *
     * The plan arrives as a **file descriptor** because it is a bundled asset: [offset] and
     * [length] are the `AssetFileDescriptor`'s, since an asset is a *range of the APK* rather
     * than a file of its own. `AssetManager.openFd` throws for a deflated entry, which is what
     * `noCompress += "maml"` in the app's `build.gradle.kts` is for.
     *
     * Unlike [createMaia] native does **not** keep the descriptor open: nothing in this network
     * is read on the host, so the whole file goes to the device at construction and the
     * descriptor is closed there and then.
     *
     * **The descriptor must be detached.** Native takes ownership and closes it, on the failure
     * paths as much as the successful one, so a caller must not close it itself.
     *
     * Freed by [destroyNnfp], not [destroy], [destroyOcr] or [destroySupertonic].
     */
    external fun createNnfp(fd: Int, offset: Long, length: Long): Long

    /**
     * The 64-value fingerprint for one window of audio, or null on failure.
     *
     * [pcm] is exactly [NnfpHandle.WINDOW_SAMPLES] mono 16-bit samples at 16 kHz — 415 ms, the
     * network's whole receptive field. `ShortArray` rather than `FloatArray` because that is
     * what `AudioRecord.read` produces and what the fixed-point log-mel front end wants;
     * routing it through float would cost precision for nothing. A wrong length is an error
     * rather than a pad or a truncate.
     *
     * Native runs the front end itself, so there is no separate feature step to keep in sync.
     * Two calls with the same samples give the same answer: the streaming graph's circular
     * buffers are unrolled into the fixed window, so there is no carried state and no warm-up.
     *
     * The embedding is **not** normalised — whether the descriptor is matched by cosine, by L2
     * or through a product quantiser could not be recovered from the model, so that is the
     * caller's decision rather than an invented convention.
     */
    external fun nnfpEmbed(handle: Long, pcm: ShortArray): FloatArray?

    /**
     * Free the fingerprinter's network.
     * Exactly once per non-zero handle from [createNnfp].
     */
    external fun destroyNnfp(handle: Long)

    /**
     * The music gate, Now Playing's always-on "is this music?" classifier.
     *
     * Never returns 0 in practice: there is no device, no plan and no asset behind this
     * one - the 8,200 int8 parameters are in the binary and it runs on the CPU. A 0 means
     * the native library is a build that does not have it.
     *
     * Freed by [destroyMusicGate], not [destroyNnfp] or any other destroy.
     */
    external fun createMusicGate(): Long

    /**
     * Feed [count] mono 16 kHz samples from the front of [pcm] and get one music
     * probability per completed 10 ms hop, oldest first.
     *
     * Empty rather than null when a call completes no hop or the gate is still inside its
     * 230 ms warm-up; both are ordinary. Null means the call actually failed. The gate is
     * stateful across calls - [resetMusicGate] starts a fresh session.
     */
    external fun musicGatePush(handle: Long, pcm: ShortArray, count: Int): FloatArray?

    /** Discard the gate's streaming state. */
    external fun resetMusicGate(handle: Long)

    /** Free the gate. Exactly once per non-zero handle from [createMusicGate]. */
    external fun destroyMusicGate(handle: Long)

    /**
     * Bring up whisper-base from its one `.maml` and the ids read from `generation_config.json`.
     * Returns 0 on failure.
     *
     * One graph, not two: the 51,865-row embedding is **tied** — the
     * decoder's input table and the logits kernel — so an encoder file and a decoder file would
     * upload 26.6 MB of it twice. Native selects between the encoder pass and a decode step by
     * re-recording one net.
     *
     * The plan arrives as a **file descriptor** because it is a bundled asset: [offset] and [length]
     * are the `AssetFileDescriptor`'s, since an asset is a *range of the APK* rather than a file of
     * its own. `AssetManager.openFd` throws for a deflated entry, which is what `noCompress +=
     * "maml"` in `speech/build.gradle.kts` is for. Native keeps the descriptor open for the life of
     * the handle, because the tied embedding is gathered a row at a time on the host.
     *
     * **The descriptor must be detached.** Native takes ownership and closes it, on the failure
     * paths as much as the successful one.
     *
     * The four id arrays come straight out of `generation_config.json`, because that file is the
     * model's and hardcoding its ids here would be a second place to get them wrong:
     * - [special] is exactly five values: `decoder_start_token_id`, `eos_token_id`,
     *   `task_to_id.transcribe`, `no_timestamps_token_id`, `max_length`.
     * - [languages] is the 99 `<|xx|>` ids from `lang_to_id`, used to detect a language when none is
     *   named. The **code to id** mapping stays in Kotlin.
     * - [suppress] is `suppress_tokens` and [suppressAtBegin] is `begin_suppress_tokens`.
     *
     * All four are validated here rather than per transcription, so a broken asset reports an
     * unavailable recogniser instead of producing wrong text.
     *
     * Freed by [destroyWhisper], not [destroy], [destroyOcr], [destroySupertonic]
     * or [destroyTinyclip].
     */
    external fun createWhisper(
        fd: Int,
        offset: Long,
        length: Long,
        special: IntArray,
        languages: IntArray,
        suppress: IntArray,
        suppressAtBegin: IntArray,
    ): Long

    /**
     * Transcribe one log-mel window into token ids, or null on failure.
     *
     * [mel] is `80 * 3000` floats row-major, from `WhisperFeatures.logMel` — 30 seconds at a
     * 160-sample hop, padded or truncated. [languageToken] is a `<|xx|>` id the caller resolved from
     * a code, or **negative** to let the model detect it.
     *
     * The ids come back **raw**, including any special or timestamp token the model emitted:
     * `WhisperTokenizer` is what skips the ids at or above 50,257 that have no text form. An empty
     * array means the window held nothing to transcribe, which is not a failure.
     *
     * Greedy decoding, capped at 224 tokens. Every step re-records the network, so this is slow
     * relative to a single forward pass and must not be called concurrently on one handle.
     */
    external fun transcribeWhisper(handle: Long, mel: FloatArray, languageToken: Int): IntArray?

    /**
     * Free whisper's network, its open weights file and both of its caches.
     * Exactly once per non-zero handle from [createWhisper].
     */
    external fun destroyWhisper(handle: Long)

    /**
     * Detect faces in [pixels] and return them flattened, nine floats per face.
     *
     * The layout per face is `left, top, right, bottom, leftEyeX, leftEyeY, rightEyeX,
     * rightEyeY, score`, every coordinate a fraction of the bitmap. Letterboxing, the
     * forward pass, the anchor decode and non-maximum suppression all happen natively, so
     * this crosses the boundary once per bitmap rather than once per proposal.
     *
     * An empty array means no faces — the common case, and not a failure. Null is a
     * failure.
     */
    external fun detectFaces(
        handle: Long,
        pixels: IntArray,
        width: Int,
        height: Int,
    ): FloatArray?

    /**
     * Run the network over [pixels] and return the mask, or null on failure.
     *
     * [pixels] is `ARGB_8888` as `Bitmap.getPixels` produces it, [width] × [height] long.
     * The bitmap is resized and normalised natively, so any size is accepted; the
     * returned mask is always [maskWidth] × [maskHeight], row-major, in `0..1`.
     */
    external fun segment(handle: Long, pixels: IntArray, width: Int, height: Int): FloatArray?

    /** The mask's width, so callers need not know either network's input size. */
    external fun maskWidth(handle: Long): Int

    /** The mask's height. */
    external fun maskHeight(handle: Long): Int

    /**
     * Free everything the handle owns, after waiting for the GPU to go idle.
     *
     * Exactly once per non-zero handle. When it is the last live network the shared
     * `VkDevice` is destroyed with it, so nothing stays resident once the feature is
     * closed.
     */
    external fun destroy(handle: Long)
    /**
     * Free both PP-OCRv5 networks and the dictionary. Exactly once per non-zero handle
     * from [createPpocr].
     *
     * Separate from [destroy] because the handle is a different native type. Passing one to
     * the other is undefined, which is why there are two functions rather than one that
     * guesses.
     */
    external fun destroyOcr(handle: Long)

    /**
     * Free Supertonic's four networks, its conditioning, its codepoint table and its voice.
     * Exactly once per non-zero handle from [createSupertonic].
     *
     * A third destroy function rather than one that guesses: the handle types own different things
     * and passing one to the wrong destroy is undefined.
     */
    external fun destroySupertonic(handle: Long)

    /**
     * Bring up Gemma 4 E2B from its `.maml`s and its tokenizer table. Returns 0 on failure.
     *
     * The decoder and the embedding are separate files (graph ids 20 and 21) because nothing on
     * the device reads the embedding: a decode step needs one row of a 262144-row table, so it
     * is gathered on the host and streamed, exactly as NLLB's tied embedding is. The GPU
     * logits head is an optional third file (graph id 26, `gemma4_head.maml`): pass -1/0/0
     * when it is absent and native falls back to the host head. Native adopts **all**
     * descriptors and closes them on every path including failure, so the caller must
     * detach all and must not close any. [tokenizer] stays a byte array.
     *
     * Freed by [destroyGemma4], not [destroy], [destroyOcr], [destroySupertonic],
     * [destroyTinyclip] or [destroyNllb].
     */
    external fun createGemma4(
        textFd: Int,
        textOffset: Long,
        textLength: Long,
        embedFd: Int,
        embedOffset: Long,
        embedLength: Long,
        headFd: Int,
        headOffset: Long,
        headLength: Long,
        tokenizer: ByteArray,
        cacheBudgetBytes: Long,
    ): Long

    /**
     * Token ids for [text], with [specials] matched literally rather than merged into.
     *
     * [specials] are the chat markers a template inserts - `<bos>`, the turn tags, the tool tags.
     * They are passed rather than hardcoded native-side because which markers a prompt may
     * contain is a policy question: a model that let a user's text spell a turn boundary would
     * let them forge one, so the *caller* decides, and user text is encoded with an empty list.
     *
     * Null on failure.
     */
    external fun encodeGemma4(handle: Long, text: String, specials: Array<String>): IntArray?

    /** Text for a run of token ids, fusing byte pieces back into characters. Null on failure. */
    external fun decodeGemma4(handle: Long, tokens: IntArray): String?

    /**
     * Feed [tokens] into the KV cache without generating. Returns the new position, or -1.
     *
     * The prompt path. Every token but the last only fills the cache, so this keeps a 500-token
     * prompt to one JNI crossing instead of 500.
     */
    external fun pushGemma4(handle: Long, tokens: IntArray): Int

    /**
     * Feed one token and return the most likely next one. -1 on failure.
     *
     * Greedy, with no sampling: replies are deterministic. For sampled decoding see
     * [logitsGemma4], which runs this same step and returns the logits unreduced.
     */
    external fun stepGemma4(handle: Long, token: Int): Int

    /**
     * Feed one token and return its 262,144 logits. Null on failure.
     *
     * Sampling (top_k, top_p, temperature) happens in Kotlin on top of this. Prefer
     * [stepGemma4] when the greedy token is all that is wanted: this returns a 1 MB array
     * per call.
     *
     * The head is tied: logits are `hidden @ H^T` over the raw-scale head table
     * (S10's `embedder.decode` composite), softcapped at 30.0 exactly as the
     * reference's `decode_softmax` does. The working embedding table's S2
     * signature gain (x39.25) is gather-side only and never enters the logits.
     */
    external fun logitsGemma4(handle: Long, token: Int): FloatArray?

    /**
     * Feed one token and return its [k] most likely next tokens as `[logit, id, logit, id, ...]`,
     * highest first, ties by lower id - the order [sampleGemma4Logits] sorts the full logits into,
     * so [sampleGemma4Candidates] draws the same token whenever `topK <= k`. 2k floats instead of
     * [logitsGemma4]'s 1 MB. Null on failure.
     */
    external fun topKGemma4(handle: Long, token: Int, k: Int): FloatArray?

    /** Positions currently in the KV cache, or -1. */
    external fun positionGemma4(handle: Long): Int

    /**
     * Start a new conversation on the same handle.
     *
     * Only the position resets. Attention reads `[windowStart, prefix]`, so cache rows past the
     * new prefix are never read again - clearing them would be a gigabyte of pointless writes
     * between turns.
     */
    external fun resetGemma4(handle: Long)

    /**
     * Rewind the cache to [position], keeping everything below it. Returns it, or -1.
     *
     * The other half of [pushGemma4]. A turn's prompt is almost all of the previous turn's
     * prompt, and the cache for that prefix is still correct - so the caller seeks to the length
     * of the unchanged part and pushes only what is new.
     *
     * **The caller is responsible for the prefix actually being unchanged.** Native cannot check
     * it, and a wrong seek does not fail: the model answers a conversation that never happened.
     * Seeking forward is refused, because those rows hold whatever the arena last put there.
     */
    external fun seekGemma4(handle: Long, position: Int): Int

    /** Prefill what [pushGemma4] deferred, now rather than with the next step. Position, or -1. */
    external fun flushGemma4(handle: Long): Int

    /**
     * Time a decode pass with and without the logits head, and log both.
     *
     * The head is 4 of the pass's 1,094 dispatches but 402 MB of its 1.30 GB of weights, so the
     * gap between the two says whether this runtime is limited by memory or by dispatch count -
     * which decides whether the work is better kernels or fewer of them.
     */
    external fun benchmarkGemma4(handle: Long): Int

    /**
     * Load a precomputed KV cache for the fixed prompt prefix. Returns the positions, or -1.
     *
     * The caller must have the tokens that produced [cache] and record them as the cache's
     * contents, or the next turn re-feeds the prefix and the saving is lost. Native checks only
     * the byte count - it cannot know which tokens these keys and values came from.
     */
    external fun loadPrefixGemma4(handle: Long, positions: Int, cache: ByteArray): Int

    /** Log a storage-buffer read against a texel-buffer read of the same bytes. */
    external fun imageProbeGemma4(): Int

    /** Log how a gemv's achieved bandwidth varies with the size of the dispatch. */
    external fun scalingGemma4(): Int

    /** Log which GPU features this device offers that the shaders do not yet use. */
    external fun capabilitiesGemma4(): Int

    /** Positions the KV cache currently holds, or -1. */
    external fun capacityGemma4(handle: Long): Int

    /**
     * Grow the cache so [needed] positions fit. Returns the new capacity, or -1 if the device
     * cannot afford it.
     *
     * **This carries the cache.** A larger arena is a different allocation, so the contents are
     * copied out and back through a staging buffer - tens of milliseconds against the tens of
     * seconds a re-prefill would cost. The position is kept with it. Tiers double, so a long
     * conversation pays this a handful of times rather than on every message.
     */
    external fun growGemma4(handle: Long, needed: Int): Int

    /** Release a [createGemma4] handle. */
    external fun destroyGemma4(handle: Long)

    /**
     * Feed soft tokens - an encoder's output - into the KV cache. Returns how many, or -1.
     *
     * [embeddings] is `n * 1536` floats, one row per soft token, and the position advances by
     * `n`. This is how an image reaches a decoder that is otherwise text-only: there is no token
     * id to gather an embedding for, so the vision tower's rows are pushed as the embedding.
     *
     * Pass [Gemma4VisionHandle.encode]'s output unchanged. It is deliberately not scaled.
     */
    external fun pushSoftGemma4(handle: Long, embeddings: FloatArray): Int

    /**
     * Bring up Gemma 4's vision tower from its own `.maml`. 0 on failure.
     *
     * A third file and a third graph id, separate from [createGemma4] because it is optional: a
     * device that never sends an image never downloads it, and a tower that fails to load leaves
     * the assistant answering text.
     *
     * Freed by [destroyGemma4Vision].
     */
    external fun createGemma4Vision(fd: Int, offset: Long, length: Long): Long

    /**
     * The `[width, height]` in pixels an image of [width] x [height] must be resized to. Null if
     * there is no grid for it.
     *
     * The resize itself is Kotlin's, but the size is not a free choice: it is the reference
     * preprocessor's aspect-ratio-preserving fit to a patch budget, and being one 48-pixel block
     * out changes the number of soft tokens the tower emits. [softTokens] must be one of 70, 140,
     * 280, 560 or 1120.
     */
    external fun gemma4VisionSize(width: Int, height: Int, softTokens: Int): IntArray?

    /**
     * Encode one image to soft tokens: `n * 1536` floats, or null.
     *
     * [pixels] is ARGB_8888 at exactly the size [gemma4VisionSize] returned.
     */
    external fun encodeImageGemma4(
        handle: Long,
        pixels: IntArray,
        width: Int,
        height: Int,
    ): FloatArray?

    /** Release a [createGemma4Vision] handle. */
    external fun destroyGemma4Vision(handle: Long)

    /**
     * Bring up Gemma 4's audio tower from its own `.maml`. 0 on failure.
     *
     * A fourth file and a fourth graph id, optional on the same terms as [createGemma4Vision]: a
     * device that never sends audio never downloads its 166 MB, and a tower that fails to load
     * leaves the assistant answering without sound.
     *
     * Freed by [destroyGemma4Audio].
     */
    external fun createGemma4Audio(fd: Int, offset: Long, length: Long): Long

    /**
     * Encode one clip to soft tokens: `n * 1536` floats, or null.
     *
     * [samples] is 16 kHz mono in roughly -1..1. Native runs the log-mel front end, truncates to
     * thirty seconds, and returns one row per 40 ms of audio. Unlike [encodeImageGemma4] there is
     * no size to negotiate first: the clip's own length decides the token count.
     *
     * Null when the clip is too short for the tower's 12-wide attention band - about 0.45 s.
     */
    external fun encodeAudioGemma4(handle: Long, samples: FloatArray): FloatArray?

    /** Release a [createGemma4Audio] handle. */
    external fun destroyGemma4Audio(handle: Long)
}
