package com.fintrack.app.presentation.transactions

import android.database.SQLException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.app.core.util.JakartaTime
import com.fintrack.app.data.repository.CategoryRepository
import com.fintrack.app.data.repository.TransactionRepository
import com.fintrack.app.data.repository.WalletRepository
import com.fintrack.app.domain.model.DateRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** Period filter for the transaction history. Months and days are Asia/Jakarta calendar units. */
sealed interface PeriodFilter {
    data object All : PeriodFilter
    data object ThisMonth : PeriodFilter
    data object LastMonth : PeriodFilter
    data class Custom(val first: LocalDate, val last: LocalDate) : PeriodFilter

    fun toRange(clock: Clock): DateRange? = when (this) {
        All -> null
        ThisMonth -> JakartaTime.month(YearMonth.from(JakartaTime.today(clock)))
        LastMonth -> JakartaTime.month(YearMonth.from(JakartaTime.today(clock)).minusMonths(1))
        is Custom -> JakartaTime.range(first, last)
    }
}

data class TransactionListState(
    val isLoading: Boolean = true,
    val query: String = "",
    val period: PeriodFilter = PeriodFilter.All,
    val groups: List<TransactionDayGroup> = emptyList(),
    val loadFailed: Boolean = false,
) {
    val isFiltered: Boolean get() = query.isNotBlank() || period != PeriodFilter.All
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionListViewModel @Inject constructor(
    transactionRepository: TransactionRepository,
    walletRepository: WalletRepository,
    categoryRepository: CategoryRepository,
    private val clock: Clock,
) : ViewModel() {

    private data class Filter(val query: String, val period: PeriodFilter)

    private val _query = MutableStateFlow("")
    private val period = MutableStateFlow<PeriodFilter>(PeriodFilter.All)

    /** Search field value, updated synchronously so typing never waits for the database. */
    val query: StateFlow<String> = _query.asStateFlow()

    val state: StateFlow<TransactionListState> = combine(_query, period, ::Filter)
        .flatMapLatest { current ->
            combine(
                transactionRepository.observeTransactions(current.query, current.period.toRange(clock)),
                walletRepository.observeAllWallets(),
                categoryRepository.observeAllCategories(),
            ) { transactions, wallets, categories ->
                TransactionListState(
                    isLoading = false,
                    query = current.query,
                    period = current.period,
                    groups = TransactionRows.group(
                        transactions,
                        wallets.associateBy { it.id },
                        categories.associateBy { it.id },
                    ),
                )
            }
                .catch { error ->
                    if (error !is SQLException) throw error
                    emit(TransactionListState(isLoading = false, query = current.query, period = current.period, loadFailed = true))
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TransactionListState())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun onPeriodChange(value: PeriodFilter) {
        period.value = value
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
