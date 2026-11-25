package com.fintrack.app.presentation.navigation

import android.database.SQLException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.app.data.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** App-level startup work. Seeding is idempotent, so running it on every launch is safe. */
@HiltViewModel
class AppViewModel @Inject constructor(categoryRepository: CategoryRepository) : ViewModel() {

    private val _seedFailed = MutableStateFlow(false)
    val seedFailed: StateFlow<Boolean> = _seedFailed.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                categoryRepository.seedDefaultCategories()
            } catch (error: SQLException) {
                _seedFailed.value = true
            }
        }
    }
}
