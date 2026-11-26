package com.vayunmathur.openassistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.openassistant.data.Memory
import com.vayunmathur.openassistant.data.Message
import com.vayunmathur.openassistant.ui.ChatActions
import com.vayunmathur.openassistant.ui.ChatUiState
import com.vayunmathur.openassistant.ui.SettingsActions
import com.vayunmathur.openassistant.ui.SettingsUiState

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Desktop-shaped: exercises the settings detail pane beside the permanent history drawer. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/**
 * Store listing images for `:openassistant`. See `common-conventions-preview-metadata`.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 *
 * The conversations below are literals rather than real model output: previews run with no
 * model file, no inference service and no database, which is also what makes the images
 * reproducible from a clean checkout. Message ids are spelled out because the list is keyed
 * by them.
 */
class MetadataPreviews {

    /** A message as the inference service writes it: [role] is "user" or "assistant". */
    private fun message(id: Long, role: String, text: String) =
        Message(conversationId = 1, text = text, role = role, id = id)

    @PreviewTest
    @Preview(name = "1-conversation-page", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Chat() {
        DynamicTheme(darkTheme = true) {
            ChatScreen(
                state = ChatUiState(
                    title = "Weeknight dinner",
                    messages = listOf(
                        message(1, "user", "20 minutes, and I have eggs, spinach and feta. What should I make?"),
                        message(
                            2, "assistant",
                            """
                                **Spinach and feta scramble** — about 12 minutes.

                                - Wilt a big handful of spinach in olive oil, then set it aside
                                - Beat 3 eggs with a pinch of salt and cook them low and slow
                                - Fold the spinach back in and crumble the feta over at the end

                                Toast is optional but recommended.
                            """.trimIndent(),
                        ),
                        message(3, "user", "Anything I can add for a bit of heat?"),
                        message(
                            4, "assistant",
                            "A pinch of Aleppo pepper, or a spoon of harissa stirred into the eggs before they set.",
                        ),
                    ),
                    showConversationsButton = true,
                    showNewChatButton = true,
                ),
                actions = ChatActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-answer", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Answer() {
        DynamicTheme(darkTheme = true) {
            ChatScreen(
                state = ChatUiState(
                    title = "Trip to Lisbon",
                    messages = listOf(
                        message(1, "user", "Three days in Lisbon in October — what should I plan around?"),
                        message(
                            2, "assistant",
                            """
                                October is mild and quiet, so the hills are actually pleasant.

                                **Day 1** — Alfama, the castle at opening time, then sunset at Portas do Sol.
                                **Day 2** — Belém: the monastery, the tower, and pastéis straight from the oven.
                                **Day 3** — Sintra by train, or LX Factory if you would rather stay in the city.

                                Pack a light rain jacket; October brings the first showers.
                            """.trimIndent(),
                        ),
                    ),
                    inputText = "Where should I stay near Alfama?",
                    showConversationsButton = true,
                    showNewChatButton = true,
                ),
                actions = ChatActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-settings-page", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Memories() {
        DynamicTheme(darkTheme = true) {
            SettingsScreen(
                state = SettingsUiState(
                    memories = listOf(
                        Memory(content = "Vegetarian, but eats fish.", id = 1),
                        Memory(content = "Lives in Lisbon; commutes by bike.", id = 2),
                        Memory(content = "Prefers metric units and 24-hour time.", id = 3),
                        Memory(content = "Is learning Portuguese — likes short practice prompts.", id = 4),
                    ),
                ),
                actions = SettingsActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview4Expanded() {
        // The settings detail pane at desktop width, beside the permanent history drawer.
        DynamicTheme(darkTheme = true) {
            SettingsScreen(
                state = SettingsUiState(
                    memories = listOf(
                        Memory(content = "Vegetarian, but eats fish.", id = 1),
                        Memory(content = "Lives in Lisbon; commutes by bike.", id = 2),
                        Memory(content = "Prefers metric units and 24-hour time.", id = 3),
                        Memory(content = "Is learning Portuguese — likes short practice prompts.", id = 4),
                    ),
                ),
                actions = SettingsActions.Noop,
            )
        }
    }
}
