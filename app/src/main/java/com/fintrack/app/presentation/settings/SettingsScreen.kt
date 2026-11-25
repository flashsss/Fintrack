package com.fintrack.app.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.fintrack.app.R
import com.fintrack.app.presentation.common.FinTopBar

@Composable
fun SettingsScreen(onOpenCategories: () -> Unit) {
    Scaffold(topBar = { FinTopBar(stringResource(R.string.settings_title)) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ListItem(
                onClick = onOpenCategories,
                leadingContent = { Icon(painterResource(R.drawable.ic_category), contentDescription = null) },
                supportingContent = { Text(stringResource(R.string.settings_categories_description)) },
            ) { Text(stringResource(R.string.settings_categories)) }
            HorizontalDivider()
        }
    }
}
