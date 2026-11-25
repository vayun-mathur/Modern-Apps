package com.vayunmathur.contacts.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.contacts.wear.data.WearSnapshot
import com.vayunmathur.contacts.wear.domain.WearContact
import com.vayunmathur.contacts.wear.platform.WearDeviceCapabilities

/** Round watch, 454x454px class (227dp at 320dpi). */
private const val ROUND = "spec:width=227dp,height=227dp,dpi=320"

private val fixtureContacts = listOf(
    WearContact(id = 1, name = "Ana Sol", phones = listOf("+1 555 0101"), emails = listOf("ana@example.com"), isFavorite = true),
    WearContact(id = 2, name = "Ben Carter", phones = listOf("+1 555 0102")),
    WearContact(id = 3, name = "Clara Diaz", phones = listOf("+1 555 0103"), emails = listOf("clara@example.com")),
)

/**
 * Store listing images for `:contacts-wear` — every top-level screen (list, detail).
 * Both screens are already stateless, so no Content extraction was needed.
 * `./gradlew :contacts-wear:metadata` copies renders into `metadata_data/photos/contacts-wear/wear/`.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-list-round", device = ROUND, group = "wear")
    @Composable
    fun Preview1ListRound() {
        RoundPreview {
            ContactListScreen(
                snapshot = WearSnapshot(contacts = fixtureContacts),
                query = "",
                onQueryChange = {},
                onContactClick = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-detail-round", device = ROUND, group = "wear")
    @Composable
    fun Preview2DetailRound() {
        RoundPreview {
            ContactDetailScreen(
                contact = fixtureContacts[0],
                capabilities = WearDeviceCapabilities(hasTelephony = true, hasDialer = true, hasSmsHandler = true, hasEmailHandler = true, phoneReachable = true),
            )
        }
    }
}

/** Round-watch framing: clip Layoutlib's rectangular canvas to the watch shape. */
@Composable
private fun RoundPreview(content: @Composable () -> Unit) {
    MaterialTheme {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colors.background)) { content() }
    }
}
