# AGENTS.md — contacts/ (':contacts')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `contacts/` + allowed shared modules. Do not root-scan.

_Install: ./install contacts (dev by default)._
- Gradle: :contacts / dir: contacts/
- Package roots present: ui, data, intents, auth
- Entry files: MainActivity.kt (no Route.kt — list/detail/edit flow with dialogs + pick activities)
- Deps: (none — contacts provider + account framework only)
- Metadata: metadata_data/contacts.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Offline system contacts app — Google Contacts replacement minus cloud sync; handles system pick/view/edit/create intents, vCard import/export, SIM contacts, groups.
**Key features:**
- List/detail/edit with groups, favorites, photo crop, birthdays/dates, per-account storage via `auth/Authenticator`
- vCard import/export + SIM import; quick actions into WhatsApp/Telegram; assistant read/add via `intents/`
- Optional calendar birthday sync (`CalendarSyncHelper` + `CalendarWorker`); everysync is the sync story, not this app
**Architecture / data-flow:** `ui/` list/detail/edit pages (+ `ui/dialogs/`) → `util/ContactViewModel` (+ split `Accounts`/`Draft` helpers, `ContactsUiContract`) → `data/` models over the system contacts provider (`Contact`/`ContactGroup`, `ContactPrefill`, `SimContacts`); persistence is the provider, not Room — there is no database here.
**Entry points + reading order:** `MainActivity.kt` → `ui/ContactListScreen.kt` → `ui/ContactDetailsPage.kt` → `ui/EditContactPage.kt` → `util/ContactViewModel.kt` → `data/Contact.kt`.
**Gotchas:**
- `util/` is NOT a closed package root — don't add more files there
- Write paths go through the contacts provider + account (`auth/`), not a local DB — account picker dialogs matter
- `ImportActivity` + `ImportVcfScreen` handle VIEW intents; `ContactListPick`/`ContactItemPick` serve picker callers

## Key files
### Root + auth/ — entry and account framework
- com/vayunmathur/contacts/MainActivity.kt
- com/vayunmathur/contacts/auth/Authenticator.kt
### data/ — provider-backed models (no Room)
- com/vayunmathur/contacts/data/Contact.kt
- com/vayunmathur/contacts/data/ContactGroup.kt
- com/vayunmathur/contacts/data/ContactPrefill.kt
- com/vayunmathur/contacts/data/SimContacts.kt
### util/ — ViewModel + helpers
- com/vayunmathur/contacts/util/ContactViewModel.kt
- com/vayunmathur/contacts/util/ContactViewModelAccounts.kt
- com/vayunmathur/contacts/util/ContactViewModelDraft.kt
- com/vayunmathur/contacts/util/ContactsUiContract.kt
- com/vayunmathur/contacts/util/ContactSorting.kt
- com/vayunmathur/contacts/util/VcfUtils.kt
- com/vayunmathur/contacts/util/CalendarSyncHelper.kt
- com/vayunmathur/contacts/util/CalendarWorker.kt
- com/vayunmathur/contacts/util/PackageUtils.kt
### ui/ + ui/dialogs/ — list/detail/edit/import
- com/vayunmathur/contacts/ui/ContactListScreen.kt
- com/vayunmathur/contacts/ui/ContactList.kt
- com/vayunmathur/contacts/ui/ContactItem.kt
- com/vayunmathur/contacts/ui/ContactListPick.kt
- com/vayunmathur/contacts/ui/ContactItemPick.kt
- com/vayunmathur/contacts/ui/GroupedContactSection.kt
- com/vayunmathur/contacts/ui/GroupsPage.kt
- com/vayunmathur/contacts/ui/FavoritesHeader.kt
- com/vayunmathur/contacts/ui/LetterHeader.kt
- com/vayunmathur/contacts/ui/AvatarColors.kt
- com/vayunmathur/contacts/ui/ContactUiHelpers.kt
- com/vayunmathur/contacts/ui/ContactDetailsPage.kt
- com/vayunmathur/contacts/ui/ContactDetailsHeader.kt
- com/vayunmathur/contacts/ui/ContactDetailsRows.kt
- com/vayunmathur/contacts/ui/ContactDetailsSections.kt
- com/vayunmathur/contacts/ui/ContactDetailsComms.kt
- com/vayunmathur/contacts/ui/ContactDetailsFormat.kt
- com/vayunmathur/contacts/ui/EditContactPage.kt
- com/vayunmathur/contacts/ui/EditContactNameSection.kt
- com/vayunmathur/contacts/ui/EditContactPhotoSection.kt
- com/vayunmathur/contacts/ui/EditContactAccountSection.kt
- com/vayunmathur/contacts/ui/EditContactBirthday.kt
- com/vayunmathur/contacts/ui/EditContactDateDetails.kt
- com/vayunmathur/contacts/ui/EditContactDetailGroups.kt
- com/vayunmathur/contacts/ui/EditContactGroupSection.kt
- com/vayunmathur/contacts/ui/CropPhotoScreen.kt
- com/vayunmathur/contacts/ui/CropPhotoSection.kt
- com/vayunmathur/contacts/ui/NamePrefixChooser.kt
- com/vayunmathur/contacts/ui/NameSuffixChooser.kt
- com/vayunmathur/contacts/ui/ImportActivity.kt
- com/vayunmathur/contacts/ui/ImportVcfScreen.kt
- com/vayunmathur/contacts/ui/InsertOrEditContactScreen.kt
- com/vayunmathur/contacts/ui/SettingsPage.kt
- com/vayunmathur/contacts/ui/dialogs/AddAccountDialog.kt
- com/vayunmathur/contacts/ui/dialogs/AddToGroupDialog.kt
- com/vayunmathur/contacts/ui/dialogs/EventDatePickerDialog.kt
- com/vayunmathur/contacts/ui/dialogs/EventDeleteConfirmDialog.kt
### intents/ — assistant IPC
- com/vayunmathur/contacts/intents/GetIntent.kt
- com/vayunmathur/contacts/intents/InsertIntent.kt

## Verify (this module only)
```
./gradlew :contacts:compileDevKotlin
./gradlew :contacts:lint
./gradlew :contacts:checkMetadata
```
