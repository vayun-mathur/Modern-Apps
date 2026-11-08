package com.vayunmathur.appstore.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The reproducibility feed parsers over real files: F-Droid's verified.json
 * (badge) and Izzy's rbtlog indexes (gate) only admit a build on an explicit
 * positive verdict, accept the feeds' lenient shapes (string version codes,
 * string booleans, null verdicts), and survive malformed entries without
 * losing the records around them.
 */
@RunWith(RobolectricTestRunner::class)
class ReproducibleParseTest {

    private fun tempJson(content: String): File =
        File.createTempFile("feed", ".json").apply {
            writeText(content)
            deleteOnExit()
        }

    @Test
    fun `fdroid verified builds are keyed by package and version`() {
        val file = tempJson(
            """
            {"other": {"x": 1},
             "packages": {
               "com.example.a": [
                 {"verified": true, "local": {"packageName": "com.example.a", "versionCode": 42}},
                 {"verified": false, "local": {"versionCode": 43}},
                 {"verified": "true", "local": {"versionCode": "44"}},
                 {"verified": true, "remote": {"versionCode": 45}},
                 {"verified": true, "local": {"versionCode": "not-a-number"}},
                 {"verified": true, "local": "oops"}
               ],
               "com.example.b": "not-an-array",
               "com.example.c": [
                 {"verified": true, "local": {"versionCode": 7, "extra": [1, 2]}}
               ]
             }}
            """.trimIndent(),
        )
        val verified = ReproducibleBuilds.parse(file)
        assertTrue(verified.contains("com.example.a", 42L))
        assertFalse(verified.contains("com.example.a", 43L))
        assertTrue(verified.contains("com.example.a", 44L), "string boolean + string code must count")
        assertFalse(verified.contains("com.example.a", 45L), "remote-only identity must not count")
        assertTrue(verified.contains("com.example.c", 7L))
        assertEquals(3, verified.size)
    }

    @Test
    fun `fdroid empty and missing packages yield an empty set`() {
        assertEquals(0, ReproducibleBuilds.parse(tempJson("""{"packages": {}}""")).size)
        assertEquals(0, ReproducibleBuilds.parse(tempJson("""{}""")).size)
    }

    @Test
    fun `izzy records admit only explicit reproducible verdicts`() {
        val keys = mutableSetOf<String>()
        IzzyReproducibleBuilds.parseInto(
            tempJson(
                """
                {"hash-one": [
                   {"appid": "com.example.a", "version_code": 10, "reproducible": true},
                   {"appid": "com.example.b", "version_code": 11, "reproducible": false},
                   {"appid": "com.example.c", "version_code": 12, "reproducible": null},
                   {"appid": "com.example.d", "version_code": 13, "reproducible": "yes"},
                   {"appid": "com.example.e", "reproducible": true},
                   {"version_code": 15, "reproducible": true},
                   {"appid": "com.example.f", "version_code": "16", "reproducible": true},
                   "not-a-record",
                   {"appid": "com.example.g", "version_code": 17, "reproducible": true, "extra": {"n": 1}}
                 ],
                 "hash-two": {"not": "an array"},
                 "hash-three": [
                   {"appid": "com.example.a", "version_code": 10, "reproducible": true}
                 ]}
                """.trimIndent(),
            ),
            keys,
        )
        val verified = IzzyReproducibleBuilds.Verified(keys)
        assertTrue(IzzyReproducibleBuilds.admits(verified, "com.example.a", 10L))
        assertTrue(IzzyReproducibleBuilds.admits(verified, "com.example.g", 17L))
        assertFalse(IzzyReproducibleBuilds.admits(verified, "com.example.b", 11L))
        assertFalse(IzzyReproducibleBuilds.admits(verified, "com.example.c", 12L))
        assertFalse(IzzyReproducibleBuilds.admits(verified, "com.example.d", 13L))
        assertTrue(
            IzzyReproducibleBuilds.admits(verified, "com.example.f", 16L),
            "string version codes parse leniently",
        )
        assertEquals(setOf("com.example.a:10", "com.example.f:16", "com.example.g:17"), keys)
    }
}
