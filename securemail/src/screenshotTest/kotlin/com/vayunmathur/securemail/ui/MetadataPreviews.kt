package com.vayunmathur.securemail.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.data.StoredMessage
import com.vayunmathur.securemail.platform.MailUiState

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store-listing images for `:securemail`, rendered from Compose previews.
 *
 * `./gradlew :securemail:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/securemail/`, where `release.sh` picks them up.
 *
 * Each preview must carry @PreviewTest as well as @Preview and be a member of a class
 * (not a top-level function) or the screenshot engine silently skips it. Everything is
 * a literal so the output is reproducible from a clean checkout.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-onboarding", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Onboarding() {
        DynamicTheme(darkTheme = true) {
            OnboardingScreen(
                state = MailUiState(),
                onClaim = { _, _ -> },
                onDismissMessages = {},
            )
        }
    }

    @Composable
    private fun WithBottomBar(tab: MailTab, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { content() }
            MailBottomBar(selected = tab, outboxCount = 2, onSelect = {})
        }
    }

    @Composable
    private fun InboxPreviewContent() {
        InboxScreen(
            state = MailUiState(
                address = "alice@maemail.org",
                socketConnected = true,
            ),
            folder = MailFolder.INBOX,
            actions = FolderActions(
                onOpen = {},
                onCompose = {},
                onSetRead = { _, _ -> },
                onTrash = {},
                onMoveTo = { _, _ -> },
                onRestore = {},
                onDeleteForever = {},
                onEmptyTrash = {},
                onConsumeMessage = {},
            ),
            messages = listOf(
                StoredMessage(
                    id = "m_1",
                    from = "bob@proton.me",
                    toJson = "alice@maemail.org",
                    dateMillis = 1_792_772_800_000L,
                    messageId = "m_1",
                    blobBase64 = "",
                    decryptedBody = "Lunch tomorrow?",
                    decryptedSubject = "Hello from Proton",
                    verifiedSigner = "A1B2C3D4E5F60718",
                ),
                StoredMessage(
                    id = "m_2",
                    from = "carol@maemail.org",
                    toJson = "alice@maemail.org",
                    dateMillis = 1_792_770_000_000L,
                    messageId = "m_2",
                    blobBase64 = "e30=",
                ),
            ),
        )
    }

    @PreviewTest
    @Preview(name = "2-inbox", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Inbox() {
        DynamicTheme(darkTheme = true) {
            WithBottomBar(MailTab.INBOX) { InboxPreviewContent() }
        }
    }

    @PreviewTest
    @Preview(name = "3-compose", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Compose() {
        DynamicTheme(darkTheme = true) {
            ComposeScreen(
                state = MailUiState(address = "alice@maemail.org"),
                draftTo = listOf("bob@proton.me"),
                draftCc = emptyList(),
                draftBcc = emptyList(),
                initialSubject = "Hello from SecureMail",
                initialBody = "This message is end-to-end encrypted with PGP.",
                onAddRecipient = { _, _ -> true },
                onRemoveRecipient = { _, _ -> },
                onSend = { _, _, _, _, _, _ -> },
                onBack = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-settings", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4Settings() {
        DynamicTheme(darkTheme = true) {
            WithBottomBar(MailTab.SETTINGS) {
                SettingsScreen(
                    state = MailUiState(
                        address = "alice@maemail.org",
                        fingerprintHex = "A1B2C3D4E5F60718293A4B5C6D7E8F90",
                        socketConnected = true,
                    ),
                    onExportPublicKey = {},
                    onForget = {},
                )
            }
        }
    }
}
