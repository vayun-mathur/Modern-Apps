package com.vayunmathur.library.network

/**
 * Reduced CA trust bundles. Each bundle maps to a set of DER roots shipped in
 * `assets/ca/`.  SYSTEM means "use platform default" (null factory) — needed for
 * user-supplied hosts (vpn, custom email servers, browser).
 *
 * - FIRST_PARTY: ~6 roots for api.maproj.org, data.maproj.org
 *   (findfamily.cc relay hosts migrated to api.maproj.org; same Cloudflare
 *   Universal SSL: ISRG X1/X2 + GTS R1-R4). SSL.com optional, add if observed.
 * - STANDARD: FIRST_PARTY + DigiCert G2/G3, Baltimore CyberTrust, Amazon Root CA 1-4,
 *   Sectigo AAA, USERTrust RSA, GoDaddy Root G2 — covers F-Droid (ISRG), GitHub
 *   (DigiCert), Play/Aurora (Google GTS), DuckDuckGo (DigiCert), open-meteo (ISRG),
 *   and the Cover Art Archive redirect chain (coverartarchive.org -> archive.org /
 *   ia*.us.archive.org, served on GoDaddy certs).
 * - EXTENDED: STANDARD + Microsoft RSA 2017, Apple Root G2/G3, Apple IST CA 2 G1 — for
 *   everysync (Google + Apple CalDAV) and legacy message transports.
 *
 * - SYSTEM: platform default; email/web/vpn dynamic hosts.
 * - ESIM: system roots PLUS the GSMA RSP2 CI root. SM-DP+ servers authenticate
 *   their TLS layer with certificates issued under the GSMA CI PKI (a closed
 *   eSIM-ecosystem PKI, e.g. Thales-hosted carrier endpoints chaining to
 *   "GSM Association - RSP2 Root CI1"), which no platform store carries — so
 *   system trust alone fails them with CertPathValidatorException while a
 *   blanket trust-all would drop the only transport check. Seeding from the
 *   platform issuers keeps every public-CA SM-DP+ working; the single extra
 *   root only admits the eSIM ecosystem PKI. The eUICC itself still performs
 *   the real mutual authentication (AuthenticateServer against its CI PKI), so
 *   this bundle never vouches for a server the eUICC would reject.
 */
enum class TrustBundle {
    FIRST_PARTY,
    STANDARD,
    EXTENDED,
    SYSTEM,
    ESIM,
    ;

    fun assetPaths(): List<String> = when (this) {
        ESIM -> listOf(
            "ca/gsma-rsp2-root-ci1.der",
        )
        FIRST_PARTY -> listOf(
            "ca/isrgrootx1.der",
            "ca/isrgrootx2.der",
            "ca/gts-root-r1.der",
            "ca/gts-root-r2.der",
            "ca/gts-root-r3.der",
            "ca/gts-root-r4.der",
        )
        STANDARD -> FIRST_PARTY.assetPaths() + listOf(
            "ca/digicert-global-g2.der",
            "ca/digicert-global-g3.der",
            "ca/baltimore-cybertrust.der",
            "ca/amazon-root-ca1.der",
            "ca/amazon-root-ca2.der",
            "ca/amazon-root-ca3.der",
            "ca/amazon-root-ca4.der",
            // aaa-cert (AAA Certificate Services) is retired from Mozilla bundle;
            // sectigo chain covered by usertrust-rsa. Kept optional via BundledTrust w/ warning if present.
            "ca/usertrust-rsa.der",
            // archive.org and ia*.us.archive.org (Cover Art Archive redirect targets)
            // serve GoDaddy-issued certs, so their root is required to fetch/embed cover art.
            "ca/godaddy-root-g2.der",
        )
        EXTENDED -> STANDARD.assetPaths() + listOf(
            "ca/microsoft-rsa-2017.der",
            "ca/apple-root-g2.der",
            "ca/apple-root-g3.der",
            "ca/apple-ist-ca2-g1.der",
        )
        SYSTEM -> emptyList()
    }

    /**
     * Whether the bundle starts from the platform's own accepted issuers before
     * adding [assetPaths]. Only ESIM does this: its hosts span arbitrary public
     * CAs plus the GSMA CI root, and enumerating every public root they might
     * use would just be a worse-maintained copy of the system store.
     */
    fun includeSystemIssuers(): Boolean = this == ESIM
}
