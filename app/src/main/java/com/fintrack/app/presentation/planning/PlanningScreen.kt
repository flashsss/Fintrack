package com.fintrack.app.presentation.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.app.R
import com.fintrack.app.analytics.metrics.BudgetLevel
import com.fintrack.app.analytics.metrics.BudgetProgress
import com.fintrack.app.analytics.metrics.GoalProgress
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.core.util.formatPercent
import com.fintrack.app.core.util.formatRupiah
import com.fintrack.app.presentation.common.AppCard
import com.fintrack.app.presentation.common.FinTopBar
import com.fintrack.app.presentation.common.LoadingState
import com.fintrack.app.presentation.common.MessageState
import com.fintrack.app.presentation.common.NoteText
import com.fintrack.app.presentation.common.ProgressBar
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
            else -> Column(
                modifier.verticalScroll(rememberScrollState()).padding(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.field * 2),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppCard { BudgetNotificationRow() }
                SectionHeader(
                    stringResource(R.string.budget_section, state.month?.let { JakartaTime.formatMonth(it.atDay(1)) }.orEmpty()),
                    stringResource(R.string.budget_add),
                    onAddBudget,
                )
                if (state.budgets.isEmpty()) AppCard { NoteText(stringResource(R.string.budget_empty)) }
                state.budgets.forEach { progress ->
                    val label = progress.budget.categoryId?.let { id -> state.categoriesById[id]?.let { categoryLabel(it, state.categoriesById) } }
                        ?: stringResource(R.string.budget_all_expenses)
                    BudgetItem(label, progress) { onEditBudget(progress.budget.id) }
                }
                NoteText(stringResource(R.string.budget_note), Modifier.padding(horizontal = 4.dp))

                SectionHeader(stringResource(R.string.goal_section), stringResource(R.string.goal_add), onAddGoal)
                if (state.goals.isEmpty()) AppCard { NoteText(stringResource(R.string.goal_empty)) }
                state.goals.forEach { GoalItem(it) { onEditGoal(it.goal.id) } }
                NoteText(stringResource(R.string.goal_note), Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = Spacing.small), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun BudgetItem(label: String, progress: BudgetProgress, onClick: () -> Unit) {
    val barColor = when (progress.level) {
        BudgetLevel.OK -> MaterialTheme.colorScheme.primary
        BudgetLevel.NEAR_LIMIT -> MaterialTheme.colorScheme.tertiary
        BudgetLevel.OVER_LIMIT -> MaterialTheme.colorScheme.error
    }
    AppCard(onClick = onClick) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            when (progress.level) {
                BudgetLevel.OK -> Unit
                BudgetLevel.NEAR_LIMIT -> StatusBadge(stringResource(R.string.budget_level_near), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                BudgetLevel.OVER_LIMIT -> StatusBadge(stringResource(R.string.budget_level_over), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Text(
            stringResource(R.string.budget_spent_of, formatRupiah(progress.spent), formatRupiah(progress.budget.amount), formatPercent(progress.utilizationPercent)),
            style = MaterialTheme.typography.bodyMedium,
        )
        ProgressBar((progress.utilizationPercent / 100).toFloat(), barColor)
        NoteText(
            if (progress.remaining >= 0) stringResource(R.string.budget_remaining, formatRupiah(progress.remaining))
            else stringResource(R.string.budget_over, formatRupiah(-progress.remaining)),
        )
    }
}

@Composable
private fun GoalItem(progress: GoalProgress, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(progress.goal.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            when {
                progress.isAchieved -> StatusBadge(stringResource(R.string.goal_achieved), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                progress.isOverdue -> StatusBadge(
                    stringResource(R.string.goal_overdue, JakartaTime.formatDate(progress.targetDay!!)),
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Text(
            stringResource(R.string.goal_progress, formatRupiah(progress.goal.currentAmount), formatRupiah(progress.goal.targetAmount), formatPercent(progress.percent)),
            style = MaterialTheme.typography.bodyMedium,
        )
        ProgressBar((progress.percent / 100).toFloat(), MaterialTheme.colorScheme.primary)
        val required = progress.requiredPerMonth
        val note = when {
            required != null -> stringResource(R.string.goal_required, formatRupiah(required), JakartaTime.formatDate(progress.targetDay!!))
            progress.targetDay == null -> stringResource(R.string.goal_no_deadline)
            else -> null
        }
        note?.let { NoteText(it) }
    }
}

@Composable
private fun StatusBadge(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
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
    Row(Modifier.fillMaxWidth()) {
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

