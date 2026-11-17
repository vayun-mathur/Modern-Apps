package com.vayunmathur.findfamily.ui.dialogs

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.findfamily.R
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExposedDropdownMenuDefaults
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(value: String, setValue: (String) -> Unit, options: Collection<String>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value, {},
            interactionSource = interactionSourceClickable { expanded = true },
            readOnly = true,
            label = { Text(stringResource(R.string.expiry_time_label)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = expanded
                )
            },
        )
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { selectionOption ->
                DropdownMenuItem({Text(text = selectionOption)}, {
                    setValue(selectionOption)
                    expanded = false
                })
            }
        }
    }
}
