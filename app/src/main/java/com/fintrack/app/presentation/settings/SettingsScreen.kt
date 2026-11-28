package com.fintrack.app.presentation.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fintrack.app.R
import com.fintrack.app.presentation.common.AppCard
import com.fintrack.app.presentation.common.ScreenHeader
import com.fintrack.app.presentation.common.Spacing

private data class SettingsEntry(
    val title: Int,
    val description: Int,
    @DrawableRes val icon: Int,
    val onClick: () -> Unit,
)

@Composable
fun SettingsScreen(
    onOpenCategories: () -> Unit,
    onOpenPlanning: () -> Unit,
    onOpenImport: () -> Unit,
    /** Null when the build has no Supabase configuration: the item is hidden. */
    onOpenSync: (() -> Unit)? = null,
) {
    val entries = listOfNotNull(
        SettingsEntry(R.string.settings_categories, R.string.settings_categories_description, R.drawable.ic_category, onOpenCategories),
        SettingsEntry(R.string.planning_title, R.string.planning_description, R.drawable.ic_account_balance_wallet, onOpenPlanning),
        SettingsEntry(R.string.settings_import, R.string.settings_import_description, R.drawable.ic_receipt_long, onOpenImport),
        onOpenSync?.let { SettingsEntry(R.string.settings_sync, R.string.settings_sync_description, R.drawable.ic_settings, it) },
    )
    Scaffold(topBar = { ScreenHeader(stringResource(R.string.settings_title)) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = Spacing.screen)) {
            AppCard(contentPadding = 4.dp) {
                Column {
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        ListItem(
                            onClick = entry.onClick,
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent, leadingIconColor = MaterialTheme.colorScheme.primary),
                            leadingContent = { Icon(painterResource(entry.icon), contentDescription = null) },
                            supportingContent = { Text(stringResource(entry.description)) },
                        ) { Text(stringResource(entry.title), style = MaterialTheme.typography.titleSmall) }
                    }
                }
            }
        }
    }
}
