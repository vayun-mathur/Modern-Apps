// Procedural-audio tuning table: frequencies, durations and gains are the
// instrument design, not unexplained literals. Other rules still apply here.
@file:Suppress("MagicNumber")

package com.vayunmathur.games.voxels.platform

import android.content.Context
import android.media.SoundPool
import java.io.File
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

// Lightweight procedural SFX: synthesizes short break/place blips into cached WAVs and plays them via
// SoundPool. No audio assets required. All calls are best-effort (never throw into the UI).
object SoundFx {
    private const val SR = 22050
    private var pool: SoundPool? = null
    private var breakId = 0
    private var placeId = 0
    private var hurtId = 0
    private var eatId = 0
    private var explodeId = 0
    // One per surface class (see ambience.rs STEP_*), plus the two atmosphere one-shots.
    private val stepIds = IntArray(6)
    private var caveId = 0
    private var stalkId = 0
    /** Effects volume from the settings sheet, 0..1. */
    @Volatile var volume = 1f
        set(v) { field = v.coerceIn(0f, 1f) }

    fun init(ctx: Context) {
        if (pool != null) return
        try {
            val p = SoundPool.Builder().setMaxStreams(6).build()
            breakId = p.load(wav(ctx, "vox_break.wav", gen(150.0, 0.14, 0.55, true)), 1)
            placeId = p.load(wav(ctx, "vox_place.wav", gen(520.0, 0.07, 0.4, false)), 1)
            hurtId = p.load(wav(ctx, "vox_hurt.wav", gen(90.0, 0.22, 0.7, true)), 1)
            eatId = p.load(wav(ctx, "vox_eat.wav", gen(200.0, 0.10, 0.4, true)), 1)
            explodeId = p.load(wav(ctx, "vox_explode.wav", gen(60.0, 0.5, 0.9, true)), 1)
            for (m in STEP_TIMBRES.indices) {
                val (freq, dur, amp, decay) = STEP_TIMBRES[m]
                stepIds[m] = p.load(wav(ctx, "vox_step$m.wav", step(freq, dur, amp, decay)), 1)
            }
            // Cave ambience: a low, slowly-swelling drone. Stalking: the same idea an octave down and
            // longer, so it reads as something large rather than something dripping.
            caveId = p.load(wav(ctx, "vox_cave.wav", drone(58.0, 2.4, 0.30)), 1)
            stalkId = p.load(wav(ctx, "vox_stalk.wav", drone(31.0, 4.0, 0.42)), 1)
            pool = p
        } catch (_: Throwable) {}
    }

    fun playBreak() { try { pool?.play(breakId, 0.6f * volume, 0.6f * volume, 1, 0, 1f) } catch (_: Throwable) {} }
    fun playPlace() { try { pool?.play(placeId, 0.6f * volume, 0.6f * volume, 1, 0, 1f) } catch (_: Throwable) {} }
    fun playHurt() { try { pool?.play(hurtId, 0.7f * volume, 0.7f * volume, 1, 0, 1f) } catch (_: Throwable) {} }
    fun playEat() { try { pool?.play(eatId, 0.5f * volume, 0.5f * volume, 1, 0, 1f) } catch (_: Throwable) {} }
    fun playExplode() { try { pool?.play(explodeId, 0.9f * volume, 0.9f * volume, 1, 0, 1f) } catch (_: Throwable) {} }
    /// `mat` is an ambience.rs STEP_* class. Each footfall is pitched slightly differently so a walk
    /// doesn't turn into a metronome.
    fun playStep(mat: Int) {
        val id = stepIds.getOrNull(mat) ?: return
        val rate = 0.92f + Random.nextFloat() * 0.16f
        try { pool?.play(id, 0.22f * volume, 0.22f * volume, 0, 0, rate) } catch (_: Throwable) {}
    }
    fun playCave() {
        val v = 0.35f * volume
        try { pool?.play(caveId, v, v, 0, 0, 0.9f + Random.nextFloat() * 0.2f) } catch (_: Throwable) {}
    }
    fun playStalk() {
        val v = 0.5f * volume
        try { pool?.play(stalkId, v, v, 0, 0, 0.85f + Random.nextFloat() * 0.3f) } catch (_: Throwable) {}
    }

    // Per-surface footstep timbre: (base frequency, duration, amplitude, envelope decay). Hard
    // surfaces are short and bright; soft ones are duller and longer.
    private data class Timbre(val freq: Double, val dur: Double, val amp: Double, val decay: Double)
    private val STEP_TIMBRES = arrayOf(
        Timbre(240.0, 0.07, 0.55, 60.0),  // stone
        Timbre(170.0, 0.09, 0.50, 45.0),  // wood
        Timbre(120.0, 0.11, 0.38, 34.0),  // grass
        Timbre(320.0, 0.12, 0.30, 26.0),  // sand
        Timbre(420.0, 0.13, 0.26, 22.0),  // snow
        Timbre(150.0, 0.16, 0.34, 18.0),  // water
    )

    // A footstep is a filtered noise burst with a touch of tone, so surfaces stay distinguishable
    // without sounding like a musical instrument.
    private fun step(freq: Double, dur: Double, amp: Double, decay: Double): ShortArray {
        val n = (SR * dur).toInt()
        val out = ShortArray(n)
        var lp = 0.0
        // Lower base frequency -> heavier low-pass, which is what makes grass sound soft next to stone.
        val k = (freq / 900.0).coerceIn(0.06, 0.7)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val noise = Random.nextDouble() * 2.0 - 1.0
            lp += k * (noise - lp)
            val body = lp * 0.85 + sin(2.0 * Math.PI * freq * t) * 0.15
            val env = exp(-t * decay)
            out[i] = (body * env * amp * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    // A slow swell: two detuned low sines under a band of noise, faded in and out so it has no attack.
    private fun drone(freq: Double, dur: Double, amp: Double): ShortArray {
        val n = (SR * dur).toInt()
        val out = ShortArray(n)
        var lp = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val u = t / dur
            // Raised-cosine envelope: no click at either end.
            val env = 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * u)
            lp += 0.02 * ((Random.nextDouble() * 2.0 - 1.0) - lp)
            val tone = sin(2.0 * Math.PI * freq * t) * 0.6 + sin(2.0 * Math.PI * freq * 1.013 * t) * 0.4
            out[i] = ((tone * 0.7 + lp * 2.5) * env * amp * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    // Decaying tone (or noise burst); mono 16-bit PCM.
    private fun gen(freq: Double, dur: Double, amp: Double, noise: Boolean): ShortArray {
        val n = (SR * dur).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = exp(-t * 20.0)
            val s = if (noise) (Random.nextDouble() * 2.0 - 1.0) else sin(2.0 * Math.PI * freq * t)
            out[i] = (s * env * amp * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun wav(ctx: Context, name: String, pcm: ShortArray): String {
        val f = File(ctx.cacheDir, name)
        val dataLen = pcm.size * 2
        val bytes = ByteArray(44 + dataLen)
        fun le32(off: Int, v: Int) {
            bytes[off] = (v and 0xff).toByte()
            bytes[off + 1] = ((v shr 8) and 0xff).toByte()
            bytes[off + 2] = ((v shr 16) and 0xff).toByte()
            bytes[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun le16(off: Int, v: Int) {
            bytes[off] = (v and 0xff).toByte()
            bytes[off + 1] = ((v shr 8) and 0xff).toByte()
        }
        "RIFF".toByteArray().copyInto(bytes, 0)
        le32(4, 36 + dataLen)
        "WAVE".toByteArray().copyInto(bytes, 8)
        "fmt ".toByteArray().copyInto(bytes, 12)
        le32(16, 16); le16(20, 1); le16(22, 1)
        le32(24, SR); le32(28, SR * 2); le16(32, 2); le16(34, 16)
        "data".toByteArray().copyInto(bytes, 36)
        le32(40, dataLen)
        for (i in pcm.indices) le16(44 + i * 2, pcm[i].toInt())
        f.writeBytes(bytes)
        return f.absolutePath
    }
}
