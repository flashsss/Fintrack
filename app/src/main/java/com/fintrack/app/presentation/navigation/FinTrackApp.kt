package com.fintrack.app.presentation.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fintrack.app.R
import com.fintrack.app.presentation.categories.CategoryFormScreen
import com.fintrack.app.presentation.categories.CategoryListScreen
import com.fintrack.app.presentation.common.UnavailableScreen
import com.fintrack.app.presentation.dashboard.DashboardScreen
import com.fintrack.app.presentation.settings.SettingsScreen
import com.fintrack.app.presentation.transactions.TransactionDetailScreen
import com.fintrack.app.presentation.transactions.TransactionFormScreen
import com.fintrack.app.presentation.transactions.TransactionListScreen
import com.fintrack.app.presentation.wallets.WalletDetailScreen
import com.fintrack.app.presentation.wallets.WalletFormScreen
import com.fintrack.app.presentation.wallets.WalletListScreen

private data class TopLevelDestination(val route: String, @StringRes val label: Int, @DrawableRes val icon: Int)

private val topLevelDestinations = listOf(
    TopLevelDestination(Routes.HOME, R.string.nav_home, R.drawable.ic_home),
    TopLevelDestination(Routes.TRANSACTIONS, R.string.nav_transactions, R.drawable.ic_receipt_long),
    TopLevelDestination(Routes.ANALYTICS, R.string.nav_analytics, R.drawable.ic_bar_chart),
    TopLevelDestination(Routes.WALLETS, R.string.nav_wallets, R.drawable.ic_account_balance_wallet),
    TopLevelDestination(Routes.SETTINGS, R.string.nav_settings, R.drawable.ic_settings),
)

@Composable
fun FinTrackApp(appViewModel: AppViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = topLevelDestinations.any { it.route == currentRoute }

    val seedFailed by appViewModel.seedFailed.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val seedFailedMessage = stringResource(R.string.error_seed_failed)
    LaunchedEffect(seedFailed) { if (seedFailed) snackbarHostState.showSnackbar(seedFailedMessage) }

    Scaffold(
        // Screens draw their own top bars and insets; this scaffold only adds the bottom bar.
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevelDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navController.navigateTopLevel(destination.route) },
                            icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                            label = { Text(stringResource(destination.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        FinTrackNavHost(navController, Modifier.padding(padding).consumeWindowInsets(padding))
    }
}

private fun NavHostController.navigateTopLevel(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun FinTrackNavHost(navController: NavHostController, modifier: Modifier) {
    val back: () -> Unit = { navController.popBackStack() }
    val idArgument = { name: String -> navArgument(name) { type = NavType.StringType } }
    val optionalTypeArgument = navArgument(Routes.ARG_TYPE) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    }

    NavHost(navController, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.HOME) {
            DashboardScreen(
                onOpenTransaction = { navController.navigate(Routes.transactionDetail(it)) },
                onOpenWallet = { navController.navigate(Routes.walletDetail(it)) },
                onSeeAllTransactions = { navController.navigateTopLevel(Routes.TRANSACTIONS) },
            )
        }
        composable(Routes.ANALYTICS) { UnavailableScreen(R.string.nav_analytics, R.string.unavailable_analytics) }

        composable(Routes.TRANSACTIONS) {
            TransactionListScreen(
                onAddTransaction = { navController.navigate(Routes.transactionNew()) },
                onOpenTransaction = { navController.navigate(Routes.transactionDetail(it)) },
            )
        }
        composable(Routes.TRANSACTION_NEW, arguments = listOf(optionalTypeArgument)) {
            TransactionFormScreen(onBack = back, onSaved = back)
        }
        composable(Routes.TRANSACTION_DETAIL, arguments = listOf(idArgument(Routes.ARG_TRANSACTION_ID))) { entry ->
            val id = entry.arguments?.getString(Routes.ARG_TRANSACTION_ID).orEmpty()
            TransactionDetailScreen(onBack = back, onEdit = { navController.navigate(Routes.transactionEdit(id)) })
        }
        composable(Routes.TRANSACTION_EDIT, arguments = listOf(idArgument(Routes.ARG_TRANSACTION_ID))) {
            TransactionFormScreen(onBack = back, onSaved = back)
        }

        composable(Routes.WALLETS) {
            WalletListScreen(
                onAddWallet = { navController.navigate(Routes.WALLET_NEW) },
                onOpenWallet = { navController.navigate(Routes.walletDetail(it)) },
            )
        }
        composable(Routes.WALLET_NEW) { WalletFormScreen(onBack = back, onSaved = back) }
        composable(Routes.WALLET_DETAIL, arguments = listOf(idArgument(Routes.ARG_WALLET_ID))) { entry ->
            val id = entry.arguments?.getString(Routes.ARG_WALLET_ID).orEmpty()
            WalletDetailScreen(
                onBack = back,
                onEdit = { navController.navigate(Routes.walletEdit(id)) },
                onOpenTransaction = { navController.navigate(Routes.transactionDetail(it)) },
            )
        }
        composable(Routes.WALLET_EDIT, arguments = listOf(idArgument(Routes.ARG_WALLET_ID))) {
            WalletFormScreen(onBack = back, onSaved = back)
        }

        composable(Routes.SETTINGS) { SettingsScreen(onOpenCategories = { navController.navigate(Routes.CATEGORIES) }) }
        composable(Routes.CATEGORIES) {
            CategoryListScreen(
                onBack = back,
                onAddCategory = { navController.navigate(Routes.categoryNew(it)) },
                onOpenCategory = { navController.navigate(Routes.categoryEdit(it)) },
            )
        }
        composable(Routes.CATEGORY_NEW, arguments = listOf(optionalTypeArgument)) {
            CategoryFormScreen(onBack = back, onDone = back)
        }
        composable(Routes.CATEGORY_EDIT, arguments = listOf(idArgument(Routes.ARG_CATEGORY_ID))) {
            CategoryFormScreen(onBack = back, onDone = back)
        }
    }
}
