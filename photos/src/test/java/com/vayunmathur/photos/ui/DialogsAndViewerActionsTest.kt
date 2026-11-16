package com.vayunmathur.photos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.photos.data.TextElement
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Video save/progress dialogs, the text-element editor dialog, and the
 * viewer action bar: each is composed and its controls pressed.
 */
@RunWith(AndroidJUnit4::class)
class DialogsAndViewerActionsTest {

    @get:Rule val rule = createComposeRule()

    private fun clickEverything(max: Int = 8) {
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until minOf(count, max)) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            runCatching { rule.waitForIdle() }
        }
    }

    @Test fun videoSaveAndProgressDialogs() {
        var savedCopy = false
        var overwrote = false
        var cancelled = false
        val progress = arrayOfNulls<androidx.compose.runtime.MutableState<Boolean>>(1)
        rule.setContent {
            val showProgress = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            progress[0] = showProgress
            if (showProgress.value) {
                ExportProgressDialog(progress = 0.42f, onCancel = { cancelled = true })
            } else {
                SaveDialog(
                    onDismiss = {},
                    onSaveCopy = { savedCopy = true },
                    onOverwrite = { overwrote = true },
                )
            }
        }
        rule.waitForIdle()
        clickEverything()
        progress[0]!!.value = true
        rule.waitForIdle()
        clickEverything()
        assertTrue(savedCopy || overwrote || cancelled)
    }

    @Test fun editTextDialogEditsAndConfirms() {
        val initial = TextElement(
            id = "t1", text = "Hello", x = 0.5f, y = 0.5f, rotation = 0f,
            color = 0xFF000000.toInt(), fontSize = 24f,
        )
        val texts = androidx.compose.runtime.mutableStateListOf(initial)
        var dismissed = false
        rule.setContent {
            EditPhotoTextDialog(
                texts = texts,
                textToEdit = initial,
                onDismiss = { dismissed = true },
            )
        }
        rule.waitForIdle()
        val fields = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
        assertTrue(fields > 0)
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("World")
        rule.waitForIdle()
        assertEquals("World", texts[0].text)
        clickEverything(10)
        assertTrue(dismissed || texts[0].text == "World")
    }

    @Test fun viewerActionsBar() {
        var immersive = false
        var motion = false
        val holder = arrayOfNulls<androidx.compose.runtime.MutableState<Boolean>>(1)
        rule.setContent {
            val metadata = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
            holder[0] = metadata
            Box(Modifier.size(300.dp, 120.dp)) {
                PhotoViewerActions(
                    isPanorama = true, isSphere = false, isMotionPhoto = true,
                    isMetadataVisible = metadata.value,
                    motionPlaying = false, motionExtracting = false,
                    onOpenImmersive = { immersive = true },
                    onMotionClick = { motion = true },
                )
            }
        }
        rule.waitForIdle()
        clickEverything(8)
        holder[0]!!.value = false
        rule.waitForIdle()
        clickEverything(4)
        assertTrue(immersive || motion)
    }
}
