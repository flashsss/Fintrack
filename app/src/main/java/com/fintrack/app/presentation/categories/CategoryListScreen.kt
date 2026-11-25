package com.fintrack.app.presentation.categories

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.app.R
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.presentation.common.AddFab
import com.fintrack.app.presentation.common.FinTopBar
import com.fintrack.app.presentation.common.LoadingState
import com.fintrack.app.presentation.common.MessageState
import com.fintrack.app.presentation.common.Spacing
import com.fintrack.app.presentation.common.labelRes

@Composable
fun CategoryListScreen(
    onBack: () -> Unit,
    onAddCategory: (CategoryType) -> Unit,
    onOpenCategory: (String) -> Unit,
    viewModel: CategoryListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val type by viewModel.type.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { FinTopBar(stringResource(R.string.categories_title), onBack = onBack) },
        floatingActionButton = {
            AddFab(stringResource(R.string.categories_add)) { onAddCategory(type) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val types = listOf(CategoryType.EXPENSE, CategoryType.INCOME)
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.small),
            ) {
                types.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = type == option,
                        onClick = { viewModel.onTypeChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, types.size),
                    ) { Text(stringResource(option.labelRes)) }
                }
            }
            when {
                state.isLoading -> LoadingState()
                state.loadFailed -> MessageState(stringResource(R.string.error_load_failed))
                state.active.isEmpty() && state.archived.isEmpty() ->
                    MessageState(stringResource(R.string.categories_empty))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(state.active, key = { it.id }) { CategoryRowItem(it, onClick = { onOpenCategory(it.id) }) }
                    if (state.archived.isNotEmpty()) {
                        item(key = "archived-header") {
                            Text(
                                stringResource(R.string.categories_archived_section),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = Spacing.screen, top = Spacing.field * 2, bottom = Spacing.small),
                            )
                        }
                        items(state.archived, key = { it.id }) { CategoryRowItem(it, onClick = { onOpenCategory(it.id) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRowItem(row: CategoryRow, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        // Child categories are indented under their parent.
        modifier = if (row.isChild) Modifier.padding(start = 24.dp) else Modifier,
    ) { Text(row.name) }
    HorizontalDivider()
}
