package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.platform.ContactDetailsUiState
import com.vayunmathur.contacts.platform.ContactListUiState
import com.vayunmathur.contacts.platform.ContactsActions
import com.vayunmathur.contacts.platform.GroupWithContacts
import com.vayunmathur.contacts.platform.GroupsUiState
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconGroup
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import kotlinx.datetime.LocalDate

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Expanded desktop/tablet window: list main screen and the landscape detail header. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

private val FAMILY = ContactGroup(1, "Family")
private val FRIENDS = ContactGroup(2, "Friends")
private val WORK = ContactGroup(3, "Work")
private val GROUPS = listOf(FAMILY, FRIENDS, WORK)

/**
 * A sample contact. Every detail list the UI reads through [Contact.name], [Contact.org],
 * [Contact.note] and [Contact.nickname] must have an entry — those accessors take the first
 * element and would throw on an empty list.
 */
private fun contact(
    id: Long,
    firstName: String,
    lastName: String,
    company: String = "",
    phone: String? = null,
    email: String? = null,
    address: String? = null,
    birthday: LocalDate? = null,
    note: String = "",
    groups: List<ContactGroup> = emptyList(),
) = Contact(
    id = id,
    accountType = null,
    accountName = null,
    isFavorite = false,
    details = ContactDetails(
        phoneNumbers = listOfNotNull(phone?.let { PhoneNumber(id * 10, it, CDKPhone.TYPE_MOBILE) }),
        emails = listOfNotNull(email?.let { Email(id * 10 + 1, it, CDKEmail.TYPE_HOME) }),
        addresses = listOfNotNull(address?.let { Address(id * 10 + 2, it, CDKStructuredPostal.TYPE_HOME) }),
        dates = listOfNotNull(birthday?.let { Event(id * 10 + 3, it, CDKEvent.TYPE_BIRTHDAY) }),
        photos = emptyList(),
        names = listOf(Name(id * 10 + 4, "", firstName, "", lastName, "")),
        orgs = listOf(Organization(id * 10 + 5, company)),
        notes = listOf(Note(id * 10 + 6, note)),
        nicknames = listOf(Nickname(id * 10 + 7, "", CDKNickname.TYPE_DEFAULT)),
        groups = groups.mapIndexed { index, group -> GroupMembership(id * 100 + index, group.id) },
    ),
)

private val AISHA = contact(
    id = 2,
    firstName = "Aisha",
    lastName = "Rahman",
    company = "Acme Design Studio",
    phone = "+1 415 555 0102",
    email = "aisha.rahman@example.com",
    address = "1200 Market St, San Francisco, CA",
    birthday = LocalDate(1992, 3, 15),
    note = "Met at the design conference. Prefers email.",
    groups = listOf(FRIENDS, WORK),
)

private val SAMPLE_CONTACTS = listOf(
    contact(1, "Aaron", "Blake", phone = "+1 415 555 0142", groups = listOf(FAMILY)),
    AISHA,
    contact(3, "Amelia", "Brooks", phone = "+1 415 555 0173", groups = listOf(FAMILY, FRIENDS)),
    contact(4, "Benjamin", "Carter", company = "Northwind Logistics", phone = "+1 628 555 0119", groups = listOf(WORK)),
    contact(5, "Bianca", "Lopez", phone = "+1 510 555 0188", groups = listOf(FRIENDS)),
    contact(6, "Chloe", "Nguyen", phone = "+1 415 555 0164", groups = listOf(FRIENDS)),
    contact(7, "Daniel", "Osei", phone = "+1 650 555 0127"),
    contact(8, "Emma", "Rossi", company = "Northwind Logistics", phone = "+1 415 555 0150", groups = listOf(WORK)),
    contact(9, "Grace", "Kim", phone = "+1 408 555 0136", groups = listOf(FAMILY)),
)

private fun groupRow(group: ContactGroup) = GroupWithContacts(
    group,
    SAMPLE_CONTACTS.filter { c -> c.details.groups.any { it.groupId == group.id } },
)

/**
 * Store listing images for `:contacts`, rendered from Compose previews instead of from an
 * instrumented test on a device.
 *
 * `./gradlew :contacts:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/contacts/`, where `release.sh` picks them up.
 *
 * Things to keep in mind when editing:
 *
 *  - Order matters, and it comes from the function names. The generated PNG filenames
 *    embed the function name, so `Preview1List`/`Preview2Details`/... sort into listing
 *    order. Renumber the functions if you reorder the listing.
 *  - Everything must be a literal. These screens normally read the system
 *    ContactsProvider; here the whole input is the state above, which is also what makes
 *    the output reproducible from a clean checkout.
 *  - Each preview needs @PreviewTest as well as @Preview. @Preview alone renders in
 *    Studio but is not collected as a screenshot test, and the build fails with the
 *    unhelpful "did not discover any tests".
 *  - The previews must be members of a class. Top-level previews land in a synthetic
 *    `…Kt` facade that the screenshot engine silently skips.
 *
 * Rendering goes through the app's real [DynamicTheme] with `darkTheme = true`, matching
 * the `cmd uimode night yes` the old on-device generator used. Material You sources its
 * palette from the wallpaper, which does not exist here, so these render with the fallback
 * scheme rather than a user's accent colour. Contact photos are absent too — an avatar is
 * decoded in a coroutine, and a preview never runs one — so every row shows its initial.
 *
 * Coverage: `ContactsList`, `GroupsList` and `ContactDetail` on phone, plus the list
 * (main) and detail (landscape-header wide layout) on large-tablet.
 * Exceptions (no stateless slice; ViewModel + contacts-provider / decoded-bitmap state):
 * `Settings`, `EditContact`/`InsertOrEditContact` and `ImportVcf` are not
 * preview-rendered, and `CropPhoto` needs a decoded bitmap from a content URI which
 * Layoutlib cannot load. Dialog routes are dialogs, not full screens.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-list", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1List() {
        ChromePreview(selectedTab = 0)
    }

    @PreviewTest
    @Preview(name = "2-details", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Details() {
        DynamicTheme(darkTheme = true) {
            ContactDetailsScreen(
                state = ContactDetailsUiState(
                    contact = AISHA,
                    groups = GROUPS,
                ),
                actions = ContactsActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-groups", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Groups() {
        ChromePreview(selectedTab = 1)
    }

    @PreviewTest
    @Preview(name = "4-list-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview4ListExpanded() {
        ChromePreview(selectedTab = 0)
    }

    @PreviewTest
    @Preview(name = "5-details-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview5DetailsExpanded() {
        DynamicTheme(darkTheme = true) {
            ContactDetailsScreen(
                state = ContactDetailsUiState(
                    contact = AISHA,
                    groups = GROUPS,
                ),
                actions = ContactsActions.Noop,
            )
        }
    }
}

@Composable
private fun ListContent() {
    ContactListScreen(
        state = ContactListUiState(
            contacts = SAMPLE_CONTACTS,
            groups = GROUPS,
        ),
        actions = ContactsActions.Noop,
    )
}

@Composable
private fun GroupsContent() {
    GroupsScreen(
        state = GroupsUiState(groups = GROUPS.map { groupRow(it) }),
        actions = ContactsActions.Noop,
        // Friends starts expanded, so the shot shows what a group contains.
        expandGroupId = FRIENDS.id,
    )
}

/**
 * The app's real tab chrome around preview screen content: the same
 * [TabbedPagerScaffold] with the same three tabs the activity declares. The
 * Settings tab is ViewModel-coupled with no stateless slice, so it shows an
 * empty page (never visible — only the selected tab renders); list and groups
 * are the real screens with literal state.
 */
@Composable
private fun ChromePreview(selectedTab: Int) {
    DynamicTheme(darkTheme = true) {
        val tabs = listOf(
            PagerTab("Contacts", { IconPerson() }) { ListContent() },
            PagerTab("Groups", { IconGroup() }) { GroupsContent() },
            PagerTab("Settings", { IconSettings() }) { Box(Modifier.fillMaxSize()) },
        )
        TabbedPagerScaffold(
            tabs = tabs,
            pagerState = rememberPagerState(initialPage = selectedTab, pageCount = { tabs.size }),
            tabStyle = TabStyle.BottomNav,
        )
    }
}
