package com.vayunmathur.code.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit tests for the pure [GitIgnore] matcher. */
class GitIgnoreTest {

    @Test
    fun literalNameIgnoredAtAnyDepth() {
        val gi = parseGitIgnore("node_modules")
        assertTrue(gi.isIgnored("node_modules", isDir = true))
        assertTrue(gi.isIgnored("src/node_modules", isDir = true))
        assertTrue(gi.isIgnored("node_modules/pkg/index.js", isDir = false))
        assertFalse(gi.isIgnored("src/main.kt", isDir = false))
    }

    @Test
    fun starMatchesExtension() {
        val gi = parseGitIgnore("*.log")
        assertTrue(gi.isIgnored("app.log", isDir = false))
        assertTrue(gi.isIgnored("logs/app.log", isDir = false))
        assertFalse(gi.isIgnored("app.txt", isDir = false))
    }

    @Test
    fun questionMarkMatchesSingleChar() {
        val gi = parseGitIgnore("file?.txt")
        assertTrue(gi.isIgnored("file1.txt", isDir = false))
        assertFalse(gi.isIgnored("file10.txt", isDir = false))
    }

    @Test
    fun trailingSlashMatchesDirectoriesOnly() {
        val gi = parseGitIgnore("build/")
        assertTrue(gi.isIgnored("build", isDir = true))
        assertFalse(gi.isIgnored("build", isDir = false))
        assertTrue(gi.isIgnored("build/out.o", isDir = false))
    }

    @Test
    fun leadingSlashAnchorsToRoot() {
        val gi = parseGitIgnore("/build")
        assertTrue(gi.isIgnored("build", isDir = true))
        assertFalse(gi.isIgnored("src/build", isDir = true))
    }

    @Test
    fun interiorSlashIsAnchored() {
        val gi = parseGitIgnore("src/generated")
        assertTrue(gi.isIgnored("src/generated", isDir = true))
        assertFalse(gi.isIgnored("app/src/generated", isDir = true))
    }

    @Test
    fun negationReincludes() {
        val gi = parseGitIgnore("*.log\n!keep.log")
        assertTrue(gi.isIgnored("app.log", isDir = false))
        assertFalse(gi.isIgnored("keep.log", isDir = false))
    }

    @Test
    fun commentsAndBlankLinesIgnored() {
        val gi = parseGitIgnore("# a comment\n\n*.tmp")
        assertTrue(gi.isIgnored("x.tmp", isDir = false))
        assertFalse(gi.isIgnored("x.kt", isDir = false))
    }

    @Test
    fun doubleStarMatchesAcrossDirectories() {
        val gi = parseGitIgnore("a/**/z")
        assertTrue(gi.isIgnored("a/z", isDir = false))
        assertTrue(gi.isIgnored("a/b/c/z", isDir = false))
        assertFalse(gi.isIgnored("x/z", isDir = false))
    }

    @Test
    fun emptyMatcherIgnoresNothing() {
        assertFalse(GitIgnore.EMPTY.isIgnored("anything", isDir = false))
    }

    @Test
    fun escapedLeadingHashAndBangAreLiteral() {
        val gi = parseGitIgnore("\\#literal\n\\!bang")
        assertTrue(gi.isIgnored("#literal", isDir = false))
        assertTrue(gi.isIgnored("!bang", isDir = false))
        assertFalse(gi.isIgnored("other", isDir = false))
    }

    @Test
    fun bareDoubleStarMatchesEverything() {
        val gi = parseGitIgnore("**")
        assertTrue(gi.isIgnored("a/b/c", isDir = false))
    }

    @Test
    fun specialRegexCharsAreLiteral() {
        val gi = parseGitIgnore("a+b (v1).log")
        assertTrue(gi.isIgnored("a+b (v1).log", isDir = false))
        assertFalse(gi.isIgnored("axb (v1).log", isDir = false))
    }
}
