package com.vayunmathur.appstore.data

/** Builds Google Play web links for packages and searches. */
object PlayStoreLinks {

    const val PLAY_BASE = "https://play.google.com"

    fun playStoreUrl(pkg: String): String = "$PLAY_BASE/store/apps/details?id=$pkg"
}
