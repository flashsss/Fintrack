package com.fintrack.app.presentation.common

import androidx.annotation.StringRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fintrack.app.R
import com.fintrack.app.core.util.AmountInput
import com.fintrack.app.core.util.JakartaTime
import java.time.LocalDate

/** Shared spacing so every screen uses the same rhythm. */
object Spacing {
    val screen = 16.dp
    val field = 16.dp
    val small = 8.dp
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                }
            }
        },
        actions = actions,
    )
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Centered message used for empty, error and not-found states. */
@Composable
fun MessageState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Rupiah text with tabular figures so amounts line up in lists. */
@Composable
fun MoneyText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
) {
    Text(text = text, modifier = modifier, style = style.copy(fontFeatureSettings = "tnum"), color = color)
}

@Composable
fun ArchivedLabel(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.state_archived),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Rupiah amount input. Keeps only digits, regroups them with '.' on every change and keeps the
 * cursor at the end, which avoids cursor jumps while separators are inserted.
 */
@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    @StringRes errorRes: Int?,
    modifier: Modifier = Modifier,
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (fieldValue.text != value) fieldValue = TextFieldValue(value, TextRange(value.length))

    OutlinedTextField(
        value = fieldValue,
        onValueChange = { changed ->
            val formatted = AmountInput.formatForEditing(changed.text)
            fieldValue = TextFieldValue(formatted, TextRange(formatted.length))
            onValueChange(formatted)
        },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        prefix = { Text("Rp") },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = errorRes != null,
        supportingText = errorRes?.let { { Text(stringResource(it)) } },
    )
}

@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    @StringRes errorRes: Int? = null,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        isError = errorRes != null,
        supportingText = errorRes?.let { { Text(stringResource(it)) } },
    )
}

/** Option in a [SelectionField]. Archived options are only present when editing history. */
data class SelectOption(val id: String, val label: String, val isArchived: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionField(
    label: String,
    selectedId: String?,
    options: List<SelectOption>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    @StringRes errorRes: Int? = null,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val archivedSuffix = stringResource(R.string.state_archived)
    fun display(option: SelectOption) =
        if (option.isArchived) "${option.label} ($archivedSuffix)" else option.label

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.id == selectedId }?.let(::display).orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            placeholder = { Text(stringResource(R.string.action_select)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = errorRes != null,
            supportingText = when {
                errorRes != null -> { { Text(stringResource(errorRes)) } }
                supportingText != null -> { { Text(supportingText) } }
                else -> null
            },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option.id)
                    },
                )
            }
        }
    }
}

/** Read-only date field that opens a Material date picker. Dates are Asia/Jakarta calendar days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    date: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(pressed) { if (pressed) showPicker = true }

    OutlinedTextField(
        value = JakartaTime.formatDate(date),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = { showPicker = true }) {
                Icon(painterResource(R.drawable.ic_calendar_month), stringResource(R.string.action_select))
            }
        },
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth(),
    )

    if (showPicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = JakartaTime.dateToPickerMillis(date))
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { onDateSelected(JakartaTime.pickerMillisToDate(it)) }
                        showPicker = false
                    },
                ) { Text(stringResource(R.string.action_select)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** Vertical form layout with consistent spacing. */
@Composable
fun FormColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        content()
    }
}
