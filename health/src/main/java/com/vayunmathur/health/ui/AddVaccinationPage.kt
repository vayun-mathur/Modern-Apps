package com.vayunmathur.health.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.platform.saveVaccinationDraft
import com.vayunmathur.health.ui.components.PickerField
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FormSection
import com.vayunmathur.library.ui.IconAttachment
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.LabeledTextField
import com.vayunmathur.library.ui.OutlinedCard
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.IntentHelper
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import kotlinx.datetime.LocalDate

/** Result key for the date dialog. Namespaced so two forms cannot collide. */
private const val DATE_KEY = "add-vaccination/date"

private val ATTACHMENT_MIME_TYPES = arrayOf("application/pdf", "image/*")

/**
 * The form for adding or editing a vaccination.
 *
 * The vaccine field is read-only and opens [CatalogPickerPage]; picking there fills in both the
 * display name and the CVX code, so a complete `Immunization.vaccineCode` comes out of one tap and
 * the user never sees a code. Everything below it is optional and maps to an optional FHIR field.
 *
 * All of it lives in the ViewModel's draft rather than in `remember`, because opening the picker
 * disposes this screen — see `MedicalViewModel.VaccinationDraft`. The date dialog is the exception
 * that can still use `ResultEffect`: dialogs are drawn over this screen rather than replacing it, so
 * the collector is still alive when the result arrives.
 *
 * Attachments are held as Uris and only copied into app storage on save, so backing out of a
 * half-filled form cannot leave orphaned files in `filesDir`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddVaccinationPage(backStack: NavBackStack<Route>, viewModel: MedicalViewModel) {
    val context = LocalContext.current
    val draft by viewModel.vaccinationDraft.collectAsState()

    ResultEffect<LocalDate>(DATE_KEY) { picked ->
        viewModel.editVaccinationDraft { it.copy(occurredOn = picked) }
    }

    // OpenDocument rather than PickVisualMedia: a vaccination card is as likely to be a PDF from a
    // clinic portal as a photo, and PickVisualMedia only offers images and video.
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.editVaccinationDraft { it.copy(attachmentUris = it.attachmentUris + uri.toString()) }
        }
    }

    val fallbackName = stringResource(R.string.attachment_untitled)

    DetailScaffold(
        title = stringResource(
            if (draft.editingId == null) R.string.add_vaccination else R.string.edit_vaccination
        ),
        backStack = backStack,
        actions = {
            TextButton(
                enabled = draft.displayName.isNotBlank(),
                onClick = {
                    viewModel.saveVaccinationDraft(fallbackName)
                    backStack.pop()
                },
            ) { Text(stringResource(UiR.string.save)) }
        },
    ) {
        FormSection(title = stringResource(R.string.section_what)) {
            PickerField(
                label = stringResource(R.string.field_vaccine),
                value = draft.displayName,
                placeholder = stringResource(R.string.choose_a_vaccine),
                onClick = { backStack.add(Route.CatalogPicker(CatalogKind.Vaccine)) },
            )
        }

        FormSection(title = stringResource(R.string.section_when)) {
            PickerField(
                label = stringResource(R.string.field_date_given),
                value = medicalDateString(draft.occurredOn.toInstant()),
                placeholder = "",
                onClick = { backStack.add(Route.MedicalDatePicker(DATE_KEY, draft.occurredOn)) },
            )
        }

        FormSection(title = stringResource(R.string.section_details)) {
            LabeledTextField(
                draft.lotNumber,
                { value -> viewModel.editVaccinationDraft { it.copy(lotNumber = value) } },
                stringResource(R.string.field_lot_number),
            )
            LabeledTextField(
                draft.site,
                { value -> viewModel.editVaccinationDraft { it.copy(site = value) } },
                stringResource(R.string.field_site),
            )
            LabeledTextField(
                draft.route,
                { value -> viewModel.editVaccinationDraft { it.copy(route = value) } },
                stringResource(R.string.field_route),
            )
            LabeledTextField(
                draft.dose,
                { value -> viewModel.editVaccinationDraft { it.copy(dose = value) } },
                stringResource(R.string.field_dose),
            )
            LabeledTextField(
                draft.performer,
                { value -> viewModel.editVaccinationDraft { it.copy(performer = value) } },
                stringResource(R.string.field_performer),
            )
            LabeledTextField(
                draft.note,
                { value -> viewModel.editVaccinationDraft { it.copy(note = value) } },
                stringResource(R.string.field_note),
                singleLine = false,
            )
        }

        FormSection(title = stringResource(R.string.section_attachments)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Already saved. Removing one only marks it; the file goes when the form is saved,
                // so backing out cannot destroy a file the stored record still points at.
                draft.visibleSavedAttachments.forEach { attachment ->
                    OutlinedCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 12.dp),
                        ) {
                            Text(attachment.displayName)
                            IconButton(
                                onClick = {
                                    viewModel.editVaccinationDraft {
                                        it.copy(
                                            removedAttachmentIds =
                                                it.removedAttachmentIds + attachment.id
                                        )
                                    }
                                }
                            ) { IconClose() }
                        }
                    }
                }
                draft.attachmentUris.forEach { uri ->
                    OutlinedCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 12.dp),
                        ) {
                            Text(IntentHelper.getFileName(context, uri.toUri()) ?: fallbackName)
                            IconButton(
                                onClick = {
                                    viewModel.editVaccinationDraft {
                                        it.copy(attachmentUris = it.attachmentUris - uri)
                                    }
                                }
                            ) { IconClose() }
                        }
                    }
                }
            }
            Button(onClick = { pickFile.launch(ATTACHMENT_MIME_TYPES) }) {
                IconAttachment()
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.attach_file))
            }
        }
    }
}
