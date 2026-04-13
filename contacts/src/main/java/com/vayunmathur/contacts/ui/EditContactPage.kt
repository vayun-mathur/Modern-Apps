package com.vayunmathur.contacts.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import com.vayunmathur.library.util.NavBackStack
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.util.ContactAccount
import com.vayunmathur.contacts.data.ContactDetail
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.Photo
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.util.ResultEffect
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import okio.Buffer
import kotlin.io.encoding.Base64

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditContactPage(backStack: NavBackStack<Route>, viewModel: ContactViewModel, contactId: Long?) {
    val contact = remember { contactId?.let { viewModel.getContact(it) } }
    val details = contact?.details
    val context = LocalContext.current

    var namePrefix by remember { mutableStateOf(contact?.name?.namePrefix ?: "") }
    var firstName by remember { mutableStateOf(contact?.name?.firstName ?: "") }
    var middleName by remember { mutableStateOf(contact?.name?.middleName ?: "") }
    var lastName by remember { mutableStateOf(contact?.name?.lastName ?: "") }
    var nameSuffix by remember { mutableStateOf(contact?.name?.nameSuffix ?: "") }
    var company by remember { mutableStateOf(contact?.org?.company ?: "") }
    var noteContent by remember { mutableStateOf(contact?.note?.content ?: "") }
    var nickname by remember { mutableStateOf(contact?.nickname?.nickname ?: "") }
    var photo by remember { mutableStateOf(contact?.photo) }
    var birthday by remember { mutableStateOf(contact?.birthday?.startDate) }
    
    var accountName by remember { mutableStateOf(contact?.accountName ?: "") }
    var accountType by remember { mutableStateOf(contact?.accountType ?: "com.vayunmathur.contacts.local") }

    val accounts by viewModel.accounts.collectAsState()

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream).scale(500, 500)
            val baos = Buffer()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos.outputStream())
            val value = Base64.encode(baos.readByteArray())
            photo = photo?.withValue(value) ?: Photo(0, value)
        }
    }
    val phoneNumbers = remember { mutableStateListOf(*details?.phoneNumbers?.toTypedArray()?:emptyArray()) }
    val emails = remember { mutableStateListOf(*details?.emails?.toTypedArray()?:emptyArray()) }
    val dates = remember { mutableStateListOf(*details?.dates?.toTypedArray()?:emptyArray()) }
    val addresses = remember { mutableStateListOf(*details?.addresses?.toTypedArray()?:emptyArray()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val pageTitle = if (contact == null) stringResource(R.string.add_contact) else stringResource(R.string.edit_contact)
                    Text(pageTitle)
                },
                navigationIcon = {
                    IconButton(onClick = { backStack.pop() }) {
                        IconClose()
                    }
                },
                actions = {
                    Button(onClick = {
                        val birthdayID = contact?.birthday?.id
                        val dates2 = dates.filter { it.type != CDKEvent.TYPE_BIRTHDAY }.toMutableList()
                        birthday?.let { birthday -> dates2 += Event(
                            birthdayID ?: 0,
                            birthday,
                            CDKEvent.TYPE_BIRTHDAY
                        )
                        }
                        val details = ContactDetails(
                            phoneNumbers,
                            emails,
                            addresses,
                            dates2,
                            listOfNotNull(photo),
                            listOf(
                                Name(
                                    contact?.name?.id ?: 0,
                                    namePrefix,
                                    firstName,
                                    middleName,
                                    lastName,
                                    nameSuffix
                                )
                            ),
                            listOf(Organization(contact?.org?.id ?: 0, company)),
                            listOf(Note(contact?.note?.id ?: 0, noteContent)),
                            listOf(
                                Nickname(
                                    contact?.nickname?.id ?: 0,
                                    nickname,
                                    CDKNickname.TYPE_DEFAULT
                                )
                            )
                        )
                        val newContact = contact?.copy(details = details) ?: Contact(
                            0,
                            accountType.ifEmpty { null },
                            accountName.ifEmpty { null },
                            false,
                            details = details
                        )
                        viewModel.saveContact(newContact)
                        backStack.pop()
                    }) {
                        Text(stringResource(R.string.save))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            AddPictureSection(photo?.photo, {
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, {
                photo = null
            })
            Spacer(Modifier.height(24.dp))
            
            if (contact == null) {
                AccountChooser(accountName, accountType, accounts) { name, type ->
                    accountName = name
                    accountType = type
                }
                Spacer(Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = firstName,
                onValueChange = { firstName = it },
                label = { Text(stringResource(R.string.first_name)) },
                leadingIcon = { NamePrefixChooser(namePrefix) { namePrefix = it } },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = middleName,
                onValueChange = { middleName = it },
                label = { Text(stringResource(R.string.middle_name)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = lastName,
                onValueChange = { lastName = it },
                label = { Text(stringResource(R.string.last_name)) },
                trailingIcon = { NameSuffixChooser(nameSuffix) { nameSuffix = it } },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text(stringResource(R.string.nickname)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = company,
                onValueChange = { company = it },
                label = { Text(stringResource(R.string.company)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            DetailsSection(
                stringResource(R.string.phone),
                phoneNumbers,
                painterResource(R.drawable.outline_call_24),
                KeyboardType.Phone,
                VisualTransformation.None,
                listOf(CDKPhone.TYPE_MOBILE, CDKPhone.TYPE_HOME, CDKPhone.TYPE_WORK, CDKPhone.TYPE_OTHER)
            )
            Spacer(Modifier.height(8.dp))

            DetailsSection(
                stringResource(R.string.email),
                emails,
                painterResource(R.drawable.outline_mail_24),
                KeyboardType.Email,
                VisualTransformation.None,
                listOf(CDKEmail.TYPE_HOME, CDKEmail.TYPE_WORK, CDKEmail.TYPE_OTHER, CDKEmail.TYPE_MOBILE)
            )

            Spacer(Modifier.height(16.dp))

            Birthday(backStack, birthday) { birthday = it }

            DateDetailsSection(
                backStack,
                dates,
                painterResource(R.drawable.outline_event_24),
                listOf(CDKEvent.TYPE_ANNIVERSARY, CDKEvent.TYPE_OTHER)
            )

            Spacer(Modifier.height(12.dp))

            DetailsSection(
                stringResource(R.string.addresses),
                addresses,
                painterResource(R.drawable.outline_event_24),
                KeyboardType.Text,
                VisualTransformation.None,
                listOf(CDKStructuredPostal.TYPE_HOME, CDKStructuredPostal.TYPE_WORK, CDKStructuredPostal.TYPE_OTHER)
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = noteContent,
                onValueChange = { noteContent = it },
                label = { Text(stringResource(R.string.note)) },
                leadingIcon = {
                    IconEdit()
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun AccountChooser(
    accountName: String,
    accountType: String,
    accounts: List<ContactAccount>,
    onAccountChange: (String, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val onDevice = stringResource(R.string.on_device)
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = if (accountName.isEmpty()) onDevice else accountName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.account)) },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(painterResource(R.drawable.baseline_arrow_drop_down_24), stringResource(R.string.choose_account))
                }
            }
        )
        DropdownMenu(expanded, { expanded = false }) {
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.name.ifEmpty { onDevice } + " (${account.type})") },
                    onClick = {
                        onAccountChange(account.name, account.type)
                        expanded = false
                    }
                )
            }
        }
        Box(Modifier.matchParentSize().clickable { expanded = true })
    }
}

private fun getCountryFlagEmoji(phoneNumber: String): String {
    val phoneUtil = PhoneNumberUtil.getInstance()
    return try {
        val numberProto = phoneUtil.parse(phoneNumber, "")
        val regionCode = phoneUtil.getRegionCodeForNumber(numberProto)
        val firstLetter = Character.codePointAt(regionCode, 0) - 0x41 + 0x1F1E6
        val secondLetter = Character.codePointAt(regionCode, 1) - 0x41 + 0x1F1E6
        String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
    } catch (_: Exception) {
        ""
    }
}

val namePrefixes = listOf("None", "Dr", "Mr", "Mrs", "Ms")
val nameSuffixes = listOf("None", "Jr", "Sr", "I", "II", "III", "IV", "V")

@Composable
fun NamePrefixChooser(namePrefix: String, onNamePrefixChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) {
        Text(namePrefix)
        Icon(
            painterResource(R.drawable.baseline_arrow_drop_down_24),
            contentDescription = null
        )
        DropdownMenu(expanded, { expanded = false }) {
            namePrefixes.forEach { prefix ->
                DropdownMenuItem(text = { Text(prefix) }, onClick = {
                    onNamePrefixChange(if(prefix == "None") "" else prefix)
                    expanded = false
                })
            }
        }
    }
}

@Composable
fun NameSuffixChooser(nameSuffix: String, onNameSuffixChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) {
        Text(nameSuffix)
        Icon(painterResource(R.drawable.baseline_arrow_drop_down_24), null)
        DropdownMenu(expanded, { expanded = false }) {
            nameSuffixes.forEach { suffix ->
                DropdownMenuItem(text = { Text(suffix) }, onClick = {
                    onNameSuffixChange(suffix)
                    expanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Birthday(
    backStack: NavBackStack<Route>,
    birthday: LocalDate?,
    setBirthday: (LocalDate?) -> Unit
) {
    ResultEffect<LocalDate>("birthday") {
        setBirthday(it)
    }
    Box {
        OutlinedTextField(
            value = birthday?.format(LocalDate.Format {
                monthName(MonthNames.ENGLISH_FULL)
                chars(" ")
                day()
                chars(", ")
                year()
            }) ?: "",
            onValueChange = { },
            readOnly = true,
            label = {Text(stringResource(R.string.birthday))},
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { setBirthday(null) }) {
                    Icon(
                        painterResource(R.drawable.baseline_remove_circle_outline_24),
                        stringResource(R.string.remove_birthday)
                    )
                }
            }
        )
        Box(
            modifier = Modifier
                .matchParentSize()
        ) {
            Box(Modifier.fillMaxWidth(0.9f).fillMaxHeight()
                .clickable { backStack.add(Route.EventDatePickerDialog("birthday",birthday)) }){}
        }
    }

    Spacer(Modifier.height(8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.DateDetailsSection(
    backStack: NavBackStack<Route>,
    details: SnapshotStateList<Event>,
    icon: Painter,
    options: List<Int>
) {
    val detailType = stringResource(R.string.dates)
    val context = LocalContext.current
    details.forEachIndexed { index, detail ->
        if(detail.type == CDKEvent.TYPE_BIRTHDAY) return@forEachIndexed
        Box {
            ResultEffect<LocalDate>(detail.id.toString()) {
                details[index] = detail.withValue(it.toString())
            }
            OutlinedTextField(
                value = detail.startDate.format(LocalDate.Format {
                    monthName(MonthNames.ENGLISH_FULL)
                    chars(" ")
                    day()
                    chars(", ")
                    year()
                }),
                onValueChange = { },
                readOnly = true,
                label = { Text(detailType) },
                trailingIcon = {
                    Row {
                        var dropdownExpanded by remember { mutableStateOf(false) }
                        TextButton({ dropdownExpanded = true }) {
                            Text(detail.typeString(context))
                            Icon(
                                painterResource(R.drawable.baseline_arrow_drop_down_24),
                                contentDescription = null
                            )
                        }
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }) {
                            options.forEach { option ->
                                DropdownMenuItem(
                                    onClick = {
                                        details[index] = detail.withType(option)
                                        dropdownExpanded = false
                                    },
                                    text = { Text(ContactDetail.default<Event>().withType(option).typeString(context)) }
                                )
                            }
                        }
                        IconButton(onClick = { details.removeAt(index) }) {
                            Icon(
                                painterResource(R.drawable.baseline_remove_circle_outline_24),
                                stringResource(R.string.remove_detail_type, detailType)
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
            ) {
                Box(Modifier.fillMaxWidth(0.6f).fillMaxHeight()
                    .clickable { backStack.add(Route.EventDatePickerDialog(detail.id.toString(),detail.startDate)) }) {}
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (details.none { it.type != CDKEvent.TYPE_BIRTHDAY }) {
        FilledTonalButton(
            onClick = { details += ContactDetail.default<Event>() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_detail_type, detailType))
        }
    } else {
        TextButton(
            onClick = { details += ContactDetail.default<Event>() },
            modifier = Modifier.align(Alignment.Start)
        ) {
            Text(stringResource(R.string.add_detail_type, detailType))
        }
    }
}

@Composable
private inline fun <reified T : ContactDetail<T>> ColumnScope.DetailsSection(
    detailType: String,
    details: SnapshotStateList<T>,
    icon: Painter,
    keyboardType: KeyboardType,
    visualTransformation: VisualTransformation,
    options: List<Int>
) {
    val context = LocalContext.current
    details.forEachIndexed { index, detail ->
        OutlinedTextField(
            value = detail.value,
            onValueChange = { newNumber ->
                details[index] = detail.withValue(newNumber)
            },
            visualTransformation = visualTransformation,
            label = { Text(detailType) },
            leadingIcon = {
                if (detail is PhoneNumber) {
                    Text(getCountryFlagEmoji(detail.value))
                }
            },
            trailingIcon = {
                Row {
                    var dropdownExpanded by remember { mutableStateOf(false) }
                    TextButton({ dropdownExpanded = true }) {
                        Text(detail.typeString(context))
                        Icon(
                            painterResource(R.drawable.baseline_arrow_drop_down_24),
                            contentDescription = null
                        )
                    }
                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }) {
                        options.forEach { option ->
                            DropdownMenuItem(
                                onClick = {
                                    details[index] = detail.withType(option)
                                    dropdownExpanded = false
                                },
                                text = { Text(ContactDetail.default<T>().withType(option).typeString(context)) }
                            )
                        }
                    }
                    IconButton(onClick = { details.removeAt(index) }) {
                        Icon(
                            painterResource(R.drawable.baseline_remove_circle_outline_24),
                            stringResource(R.string.remove_detail_type, detailType)
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
    }
    if(details.isEmpty()) {
        FilledTonalButton(
            onClick = { details += ContactDetail.default<T>() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_detail_type, detailType))
        }
    } else {
        TextButton(
            onClick = { details += ContactDetail.default<T>() },
            modifier = Modifier.align(Alignment.Start)
        ) {
            Text(stringResource(R.string.add_detail_type, detailType))
        }
    }
}

@Composable
private fun AddPictureSection(photo: String?, onClick: () -> Unit, removePhoto: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (photo != null) {
                val decoded = Base64.decode(photo)
                val bitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.contact_photo),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    painterResource(R.drawable.outline_add_photo_alternate_24),
                    contentDescription = stringResource(R.string.add_picture),
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            val pictureLabel = if (photo != null) stringResource(R.string.change_picture) else stringResource(R.string.add_picture)
            TextButton(onClick) {
                Text(
                    text = pictureLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (photo != null) {
                TextButton(removePhoto) {
                    Text(
                        text = stringResource(R.string.remove_picture),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
