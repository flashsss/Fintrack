package com.fintrack.app.presentation.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fintrack.app.R

/**
 * Shown for primary destinations whose feature is not built yet. It states that plainly and shows
 * no numbers, charts or controls, so nothing pretends to work.
 */
@Composable
fun UnavailableScreen(@StringRes titleRes: Int, @StringRes messageRes: Int) {
    Scaffold(topBar = { FinTopBar(stringResource(titleRes)) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.small, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.unavailable_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
