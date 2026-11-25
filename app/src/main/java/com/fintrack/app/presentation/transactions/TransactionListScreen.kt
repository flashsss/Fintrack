package com.fintrack.app.presentation.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.app.R
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.presentation.common.FinTopBar
import com.fintrack.app.presentation.common.LoadingState
import com.fintrack.app.presentation.common.MessageState
import com.fintrack.app.presentation.common.Spacing

@Composable
fun TransactionListScreen(
    onAddTransaction: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    viewModel: TransactionListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { FinTopBar(stringResource(R.string.transactions_title)) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddTransaction,
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.transactions_add)) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(query, viewModel::onQueryChange)
            PeriodChips(state.period, viewModel::onPeriodChange)
            when {
                state.isLoading -> LoadingState()
                state.loadFailed -> MessageState(stringResource(R.string.error_load_failed))
                state.groups.isEmpty() -> MessageState(
                    stringResource(
                        if (state.isFiltered) R.string.transactions_empty_filtered else R.string.transactions_empty,
                    ),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Keeps the last row reachable above the floating action button.
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    transactionGroups(state.groups, onOpenTransaction)
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
        placeholder = { Text(stringResource(R.string.transactions_search)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_clear))
                }
            }
        },
        singleLine = true,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodChips(period: PeriodFilter, onPeriodChange: (PeriodFilter) -> Unit) {
    var showRangePicker by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screen, vertical = Spacing.small),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        FilterChip(
            selected = period == PeriodFilter.All,
            onClick = { onPeriodChange(PeriodFilter.All) },
            label = { Text(stringResource(R.string.period_all)) },
        )
        FilterChip(
            selected = period == PeriodFilter.ThisMonth,
            onClick = { onPeriodChange(PeriodFilter.ThisMonth) },
            label = { Text(stringResource(R.string.period_this_month)) },
        )
        FilterChip(
            selected = period == PeriodFilter.LastMonth,
            onClick = { onPeriodChange(PeriodFilter.LastMonth) },
            label = { Text(stringResource(R.string.period_last_month)) },
        )
        FilterChip(
            selected = period is PeriodFilter.Custom,
            onClick = { showRangePicker = true },
            leadingIcon = { Icon(painterResource(R.drawable.ic_calendar_month), contentDescription = null) },
            label = {
                Text(
                    if (period is PeriodFilter.Custom) {
                        stringResource(
                            R.string.period_custom_range,
                            JakartaTime.formatDate(period.first),
                            JakartaTime.formatDate(period.last),
                        )
                    } else {
                        stringResource(R.string.period_custom)
                    },
                )
            },
        )
    }

    if (showRangePicker) {
        val pickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedStartDateMillis != null,
                    onClick = {
                        val start = pickerState.selectedStartDateMillis
                        if (start != null) {
                            val end = pickerState.selectedEndDateMillis ?: start
                            onPeriodChange(
                                PeriodFilter.Custom(
                                    JakartaTime.pickerMillisToDate(start),
                                    JakartaTime.pickerMillisToDate(end),
                                ),
                            )
                        }
                        showRangePicker = false
                    },
                ) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { showRangePicker = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        ) {
            DateRangePicker(
                state = pickerState,
                title = {
                    Text(
                        stringResource(R.string.period_picker_title),
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
