package com.vayunmathur.findfamily.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Instant

/**
 * A link id is the server-side *recipient id* — it is what `/api/location/publish` addresses
 * and what the share-link `/view/<id>` URL resolves to — and it shares that namespace with
 * [com.vayunmathur.findfamily.data.User] ids, which are random 64-bit values.
 *
 * So it must be globally unique, not a per-device row number: with Room's `autoGenerate` every
 * device's first link was id 1, so every first `/view/1` URL pointed at the same server queue
 * and every device published its encrypted location into it.
 *
 * Positive-only, matching the userid generation in `Networking.init` — the server stores these
 * as ULong and a negative value round-trips inconsistently.
 */
fun newTemporaryLinkId(): Long = Random.nextLong(from = 1, until = Long.MAX_VALUE)

/**
 * An anonymous location-sharing link. Post-quantum only: there is no RSA keypair and no
 * classic fallback, so a link either has a usable ML-KEM bundle or it is not created at all.
 */
@Serializable
@Entity
data class TemporaryLink(
    val name: String,
    val deleteAt: Instant,

    /** PQC ephemeral public bundle (base64 [4B kemLen][kemPub][dsaPub]) — what we encrypt to. */
    val pqcPublicKey: String,
    /**
     * Legacy: the full PQC private bundle (base64 [4B kemLen][kemPriv][dsaPriv]) that older
     * builds put in the URL fragment as `#pqc_key=`. Only set on links created before links
     * moved to [pqcSeed]; those URLs are already in the wild, so it is kept so they can still
     * be re-copied. Null on every new link.
     */
    val pqcKey: String? = null,
    /**
     * The 32-byte ML-KEM link seed, base64url without padding — what goes in the URL fragment
     * as `#s=`. This is the link's entire secret: the recipient's browser expands it to an
     * ML-KEM keypair, so nothing else needs storing or transmitting. Null on legacy links.
     */
    val pqcSeed: String? = null,

    @PrimaryKey override val id: Long = newTemporaryLinkId(),
): DatabaseItem
