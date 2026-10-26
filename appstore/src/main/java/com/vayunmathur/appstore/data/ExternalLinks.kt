package com.vayunmathur.appstore.data

/**
 * Package named by a `market://` details intent or a Play/F-Droid listing URL.
 *
 * Pure Kotlin (no Android types) so plain JVM unit tests can cover it.
 */
object ExternalLinks {

    private val ID_PARAM = Regex("[?&]id=([^&]+)")

    fun parseExternalPackage(data: String?): String? {
        if (data == null) return null
        if (data.startsWith("market://")) return ID_PARAM.find(data)?.groupValues?.get(1)
        if (data.contains("play.google.com")) return ID_PARAM.find(data)?.groupValues?.get(1)
        if (data.contains("f-droid.org")) return parseFDroidPackage(data)
        return null
    }

    /**
     * F-Droid listing URLs carry the package as the path segment after `packages`
     * and end in a trailing slash (`.../packages/<pkg>/`), so taking the text
     * after the last `/` yields an empty string and the launch falls through
     * to the home page instead of the app's detail page.
     */
    private fun parseFDroidPackage(data: String): String? {
        val path = data.substringBefore('?').substringBefore('#')
        if (!path.contains("/packages/")) return null
        val segments = path.split('/').filter { it.isNotBlank() }
        val packagesIdx = segments.indexOfLast { it == "packages" }
        if (packagesIdx < 0 || packagesIdx + 1 >= segments.size) return null
        return segments[packagesIdx + 1]
    }
}
