package com.fintrack.app.presentation.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.app.R
import com.fintrack.app.analytics.metrics.BudgetLevel
import com.fintrack.app.analytics.metrics.BudgetProgress
import com.fintrack.app.analytics.metrics.GoalProgress
import com.fintrack.app.core.ui.theme.LocalChartColors
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.core.util.formatPercent
import com.fintrack.app.core.util.formatRupiah
import com.fintrack.app.presentation.common.FinTopBar
import com.fintrack.app.presentation.common.LoadingState
import com.fintrack.app.presentation.common.MessageState
import com.fintrack.app.presentation.common.NoteText
import com.fintrack.app.presentation.common.ProportionBar
import com.fintrack.app.presentation.common.Spacing
import com.fintrack.app.presentation.common.categoryLabel

@Composable
fun PlanningScreen(
    onBack: () -> Unit,
    onAddBudget: () -> Unit,
    onEditBudget: (String) -> Unit,
    onAddGoal: () -> Unit,
    onEditGoal: (String) -> Unit,
    viewModel: PlanningViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { FinTopBar(stringResource(R.string.planning_title), onBack = onBack) }) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when {
            state.isLoading -> LoadingState(modifier)
            state.loadFailed -> MessageState(stringResource(R.string.error_load_failed), modifier)
            else -> Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                BudgetNotificationRow()
                SectionHeader(
                    stringResource(R.string.budget_section, state.month?.let { JakartaTime.formatMonth(it.atDay(1)) }.orEmpty()),
                    stringResource(R.string.budget_add),
                    onAddBudget,
                )
                if (state.budgets.isEmpty()) NoteText(stringResource(R.string.budget_empty), Modifier.padding(horizontal = Spacing.screen))
                state.budgets.forEach { progress ->
                    val label = progress.budget.categoryId?.let { id -> state.categoriesById[id]?.let { categoryLabel(it, state.categoriesById) } }
                        ?: stringResource(R.string.budget_all_expenses)
                    BudgetItem(label, progress) { onEditBudget(progress.budget.id) }
                }
                NoteText(stringResource(R.string.budget_note), Modifier.padding(horizontal = Spacing.screen))

                HorizontalDivider(Modifier.padding(top = Spacing.field))
                SectionHeader(stringResource(R.string.goal_section), stringResource(R.string.goal_add), onAddGoal)
                if (state.goals.isEmpty()) NoteText(stringResource(R.string.goal_empty), Modifier.padding(horizontal = Spacing.screen))
                state.goals.forEach { GoalItem(it) { onEditGoal(it.goal.id) } }
                NoteText(stringResource(R.string.goal_note), Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.small))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = Spacing.screen, end = Spacing.small, top = Spacing.small)) {
        Text(title, Modifier.weight(1f).padding(top = Spacing.small), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun BudgetItem(label: String, progress: BudgetProgress, onClick: () -> Unit) {
    val colors = LocalChartColors.current
    ListItem(
        onClick = onClick,
        overlineContent = when (progress.level) {
            BudgetLevel.OK -> null
            BudgetLevel.NEAR_LIMIT -> { { Text(stringResource(R.string.budget_level_near), color = MaterialTheme.colorScheme.tertiary) } }
            BudgetLevel.OVER_LIMIT -> { { Text(stringResource(R.string.budget_level_over), color = MaterialTheme.colorScheme.error) } }
        },
        supportingContent = {
            Column {
                Text(stringResource(R.string.budget_spent_of, formatRupiah(progress.spent), formatRupiah(progress.budget.amount), formatPercent(progress.utilizationPercent)))
                ProportionBar(progress.utilizationPercent, if (progress.level == BudgetLevel.OVER_LIMIT) MaterialTheme.colorScheme.error else colors.income)
                Text(
                    if (progress.remaining >= 0) stringResource(R.string.budget_remaining, formatRupiah(progress.remaining))
                    else stringResource(R.string.budget_over, formatRupiah(-progress.remaining)),
                )
            }
        },
    ) { Text(label) }
}

@Composable
private fun GoalItem(progress: GoalProgress, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        overlineContent = when {
            progress.isAchieved -> { { Text(stringResource(R.string.goal_achieved), color = MaterialTheme.colorScheme.primary) } }
            progress.isOverdue -> { { Text(stringResource(R.string.goal_overdue, JakartaTime.formatDate(progress.targetDay!!)), color = MaterialTheme.colorScheme.error) } }
            else -> null
        },
        supportingContent = {
            Column {
                Text(stringResource(R.string.goal_progress, formatRupiah(progress.goal.currentAmount), formatRupiah(progress.goal.targetAmount), formatPercent(progress.percent)))
                ProportionBar(progress.percent, LocalChartColors.current.income)
                val required = progress.requiredPerMonth
                Text(
                    when {
                        required != null -> stringResource(R.string.goal_required, formatRupiah(required), JakartaTime.formatDate(progress.targetDay!!))
                        progress.targetDay == null -> stringResource(R.string.goal_no_deadline)
                        else -> ""
                    },
                )
            }
        },
    ) { Text(progress.goal.name) }
}

/** Shows whether budget notifications can be posted and, on Android 13+, asks for the permission. */
@Composable
private fun BudgetNotificationRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    fun granted() = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
    var enabled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(granted()) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { enabled = granted() }
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.small)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.budget_notifications_title), style = MaterialTheme.typography.titleSmall)
            NoteText(stringResource(if (enabled) R.string.budget_notifications_on else R.string.budget_notifications_off))
        }
        if (!enabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            TextButton(onClick = { launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) {
                Text(stringResource(R.string.budget_notifications_allow))
            }
        }
    }
}

