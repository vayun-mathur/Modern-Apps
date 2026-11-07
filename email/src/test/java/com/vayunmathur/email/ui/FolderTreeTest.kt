package com.vayunmathur.email.ui

import com.vayunmathur.email.data.EmailFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins folder-tree construction from flat IMAP folder rows.
 *
 * The drawer renders exactly this tree: a folder whose parent link is wrong
 * vanishes from navigation (orphaned), and a cycle would recurse forever —
 * each test asserts the resulting root/child shape.
 */
class FolderTreeTest {

    private fun folder(name: String, parent: String? = null) = EmailFolder(
        accountEmail = "me@x.com", fullName = name, name = name.substringAfterLast('/'),
        parentFullName = parent,
    )

    @Test
    fun `roots are folders without parents`() {
        val tree = buildFolderTree(
            listOf(folder("INBOX"), folder("Sent"))
        )
        assertEquals(setOf("INBOX", "Sent"), tree.map { it.folder.fullName }.toSet())
        assertTrue(tree.all { it.children.isEmpty() })
    }

    @Test
    fun `children nest under their parent`() {
        val tree = buildFolderTree(
            listOf(
                folder("INBOX"),
                folder("INBOX/Work", parent = "INBOX"),
                folder("INBOX/Work/Deep", parent = "INBOX/Work"),
            )
        )
        assertEquals(1, tree.size)
        val work = tree.single().children.single()
        assertEquals("INBOX/Work", work.folder.fullName)
        assertEquals("INBOX/Work/Deep", work.children.single().folder.fullName)
    }

    @Test
    fun `empty input yields an empty tree`() {
        assertEquals(emptyList(), buildFolderTree(emptyList()))
    }

    @Test
    fun `orphans with unknown parents are dropped from roots`() {
        val tree = buildFolderTree(listOf(folder("Orphan", parent = "Missing")))
        assertEquals(emptyList(), tree)
    }
}
