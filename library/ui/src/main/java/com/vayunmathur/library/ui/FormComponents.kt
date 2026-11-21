package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import com.vayunmathur.library.util.SharedEditableText
import com.vayunmathur.library.util.expandFromLine
import com.vayunmathur.library.util.sharedContainer
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation

/**
 * The building blocks of a form screen.
 *
 * Editing screens were each assembling the same shapes by hand: a padded
 * [Column] with `Spacer`s of drifting heights between fields, and an
 * `OutlinedTextField(..., modifier = Modifier.fillMaxWidth())` written out in
 * full every time. [FormSection] fixes the outer padding and the gap between
 * fields ([Spacing.lg] / [Spacing.md]); [LabeledTextField] is the full-width
 * field with a label that a form actually wants.
 *
 * Kept thin on purpose - a form is a stack of labelled fields in sections;
 * anything richer should still be written by hand.
 */

/**
 * A labelled text field that fills its width.
 *
 * The default [modifier] is `fillMaxWidth`, which is what every form field
 * wanted and had to spell out; pass a modifier to override it. This collapses
 * the repeated `OutlinedTextField(...) + Spacer(height)` pairs down to one call
 * inside a [FormSection].
 */
@Composable
fun LabeledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isError: Boolean = false,
    supportingText: String? = null,
    placeholder: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    /**
     * Pairs the *text inside* this field with the same key elsewhere, so a read-only value morphs into
     * the editable one.
     *
     * Deliberately not a modifier on the field. A key on the field itself pairs the other screen's text
     * with this whole box, so the text grows to the box's width and then snaps to its real size on
     * arrival. Reaching the inner text means driving the field from [BasicTextField] and supplying
     * Material's own decoration around it, which is what the keyed branch below does.
     */
    sharedTextKey: Any? = null,
) {
    if (sharedTextKey != null) {
        SharedTextLabeledField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            sharedTextKey = sharedTextKey,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            keyboardType = keyboardType,
            imeAction = imeAction,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            isError = isError,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
        )
        return
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().then(
            if (isError && supportingText != null) {
                Modifier.semantics { error(supportingText) }
            } else {
                Modifier
            },
        ),
        enabled = enabled,
        readOnly = readOnly,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = keyboardActions,
        singleLine = singleLine,
    )
}

/**
 * [LabeledTextField] with its inner text exposed as a shared element.
 *
 * A separate branch rather than the default one for everybody: this rebuilds the field from
 * [BasicTextField] plus [OutlinedTextFieldDefaults.DecorationBox], and while that is the documented way
 * to keep Material's outline, label and label-cutout, it is still a reimplementation. Fields that do
 * not morph keep using [OutlinedTextField] directly, so nothing changes for them.
 */
@Composable
private fun SharedTextLabeledField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    sharedTextKey: Any,
    modifier: Modifier,
    enabled: Boolean,
    readOnly: Boolean,
    singleLine: Boolean,
    keyboardType: KeyboardType,
    imeAction: ImeAction = ImeAction.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation,
    isError: Boolean,
    placeholder: String?,
    leadingIcon: (@Composable () -> Unit)?,
    trailingIcon: (@Composable () -> Unit)?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    ) { innerTextField ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = value,
            innerTextField = {
                SharedEditableText(key = sharedTextKey, value = value) { innerTextField() }
            },
            enabled = enabled,
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            isError = isError,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
        )
    }
}

/**
 * A repeatable list of typed detail rows, each with a value field, a type
 * dropdown and a remove button, plus an "add" affordance.
 *
 * This is the generic version of `contacts:DetailsSection` — promoted here so
 * contact-like editors can share the shape without copying the function.
 * Callers supply how to read/write the row ([value]/[typeLabel]/[isCustom])
 * and how to create or mutate it ([onValueChange]/[onTypeChange]/[onLabelChange]
 * /[onAdd]/[onRemove]); [Spacing] and the library primitives
 * ([OutlinedTextField]/[DropdownMenu]/[IconRemoveCircle]/[IconAdd]) are reused
 * so the look stays consistent with [FormSection]/[LabeledTextField].
 *
 * [optionLabel] formats each dropdown option; when null the row's [typeLabel]
 * is not used for options (callers that need localized type names should pass
 * it explicitly, e.g. via `ContactDetail.default<T>().withType(option)`).
 *
 * The type picker is single-choice: the row's current type renders selected
 * via [SelectableDropdownMenuItem], so [T] must expose its type through
 * [currentType].
 */
@Composable
fun <T> FormDetailGroup(
    items: List<T>,
    label: String,
    addLabel: String,
    typeOptions: List<Int>,
    value: (T) -> String,
    onValueChange: (Int, String) -> Unit,
    typeLabel: (T) -> String,
    onTypeChange: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    currentType: (T) -> Int,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isCustom: (T) -> Boolean = { false },
    customLabel: (T) -> String = { "" },
    onLabelChange: ((Int, String) -> Unit)? = null,
    customLabelText: String? = null,
    customPlaceholder: String? = null,
    optionLabel: ((Int) -> String)? = null,
    leadingIcon: (@Composable (T) -> Unit)? = null,
    addIcon: (@Composable () -> Unit)? = null,
    /**
     * Per-item key pairing this field with the read-only row that shows the same value elsewhere, so
     * the two morph into one another. Return null for an item with no counterpart - a row the user
     * has just added has nothing to morph from, and two rows sharing a key would collide.
     */
    sharedKey: ((T) -> Any?)? = null,
    /**
     * Rows that must always exist, by index. Such a row keeps its place when emptied rather than
     * disappearing, so a contact always offers somewhere to put its primary number or address.
     *
     * Only affects this row's own button. The type it uses stays available to every other row, so a
     * contact can still have a second mobile number.
     */
    isMandatory: (Int) -> Boolean = { false },
    /**
     * Makes each row unfurl from a flat line on arrival instead of sliding into place.
     *
     * Per row rather than per group on purpose: expanding the whole group as one block reads as a
     * single slab growing, which does not match a list of separate fields.
     */
    expandOnEnter: Boolean = false,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        // Hoisted out of the row loop so the add button below can unfurl the same way the rows do.
        val enterModifier = if (expandOnEnter) Modifier.expandFromLine() else Modifier
        items.forEachIndexed { index, item ->
            val rowKey = sharedKey?.invoke(item)
            val rowModifier = if (rowKey == null) Modifier else Modifier.sharedContainer(rowKey)
            // Hoisted so both branches below draw the same trailing controls.
            val rowTrailing: @Composable () -> Unit = {
                Row {
                    var expanded by remember { mutableStateOf(false) }
                    TextButton({ expanded = true }) {
                        Text(typeLabel(item))
                        IconArrowDropDown()
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        typeOptions.forEach { option ->
                            SelectableDropdownMenuItem(
                                selected = currentType(item) == option,
                                onClick = {
                                    onTypeChange(index, option)
                                    expanded = false
                                },
                                text = { Text(optionLabel?.invoke(option) ?: option.toString()) },
                                selectedLeadingIcon = { IconCheck() },
                            )
                        }
                    }
                    // A mandatory row cannot be taken away, so its button clears the value instead
                    // - and only appears when there is something to clear, since an always-present
                    // button on an empty row invites a tap that would do nothing.
                    if (isMandatory(index)) {
                        if (value(item).isNotEmpty()) {
                            IconButton(onClick = { onValueChange(index, "") }) {
                                IconRemoveCircle()
                            }
                        }
                    } else {
                        IconButton(onClick = { onRemove(index) }) {
                            IconRemoveCircle()
                        }
                    }
                }
            }
            if (rowKey == null) {
                OutlinedTextField(
                    value = value(item),
                    onValueChange = { v: String -> onValueChange(index, v) },
                    label = { Text(label) },
                    visualTransformation = visualTransformation,
                    leadingIcon = leadingIcon?.let { { it(item) } },
                    trailingIcon = rowTrailing,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    singleLine = true,
                    modifier = enterModifier.fillMaxWidth(),
                )
            } else {
                // Two pairings at once: the field's box morphs from the read-only row's box, and the
                // text inside it morphs from the text inside that row. Keying only the box would drag
                // the old row's text along as cargo and crossfade it out in the wrong place.
                SharedTextLabeledField(
                    value = value(item),
                    onValueChange = { v: String -> onValueChange(index, v) },
                    label = label,
                    sharedTextKey = "$rowKey-text",
                    modifier = rowModifier.then(enterModifier).fillMaxWidth(),
                    enabled = true,
                    readOnly = false,
                    singleLine = true,
                    keyboardType = keyboardType,
                    visualTransformation = visualTransformation,
                    isError = false,
                    placeholder = null,
                    leadingIcon = leadingIcon?.let { { it(item) } },
                    trailingIcon = rowTrailing,
                )
            }
            if (isCustom(item) && onLabelChange != null) {
                Spacer(Modifier.height(Spacing.xs))
                OutlinedTextField(
                    value = customLabel(item),
                    onValueChange = { v -> onLabelChange(index, v) },
                    label = { Text(customLabelText ?: label) },
                    placeholder = customPlaceholder?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(Spacing.xs))
        }
        FilledTonalButton(
            onClick = onAdd,
            modifier = enterModifier.fillMaxWidth(),
        ) {
            addIcon?.invoke() ?: IconAdd()
            Spacer(Modifier.width(Spacing.sm))
            Text(addLabel)
        }
    }
}
