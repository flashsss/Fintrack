package com.fintrack.app.presentation.categories

import android.database.SQLException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.app.data.repository.CategoryRepository
import com.fintrack.app.domain.model.Category
import com.fintrack.app.domain.model.CategoryType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class CategoryRow(
    val id: String,
    val name: String,
    val isChild: Boolean,
)

data class CategoryListState(
    val isLoading: Boolean = true,
    val type: CategoryType = CategoryType.EXPENSE,
    /** Active categories, each parent followed by its active children. */
    val active: List<CategoryRow> = emptyList(),
    val archived: List<CategoryRow> = emptyList(),
    val loadFailed: Boolean = false,
)

@HiltViewModel
class CategoryListViewModel @Inject constructor(categoryRepository: CategoryRepository) : ViewModel() {

    private val selectedType = MutableStateFlow(CategoryType.EXPENSE)
    val type: StateFlow<CategoryType> = selectedType.asStateFlow()

    val state: StateFlow<CategoryListState> = combine(
        categoryRepository.observeAllCategories(),
        selectedType,
    ) { categories, type ->
        CategoryListState(
            isLoading = false,
            type = type,
            active = hierarchy(categories.filter { it.type == type && !it.isArchived }),
            archived = categories.filter { it.type == type && it.isArchived }
                .sortedBy { it.name.lowercase() }
                .map { CategoryRow(it.id, it.name, isChild = it.parentId != null) },
        )
    }
        .catch { error ->
            if (error !is SQLException) throw error
            emit(CategoryListState(isLoading = false, loadFailed = true))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryListState())

    fun onTypeChange(type: CategoryType) {
        selectedType.value = type
    }

    companion object {
        /**
         * Orders categories as parent, then its children. A child whose parent is not in [categories]
         * (because the parent is archived) is listed at top level so it stays reachable.
         */
        fun hierarchy(categories: List<Category>): List<CategoryRow> {
            val ids = categories.map { it.id }.toSet()
            val childrenByParent = categories.filter { it.parentId in ids }.groupBy { it.parentId }
            return categories
                .filter { it.parentId == null || it.parentId !in ids }
                .sortedBy { it.name.lowercase() }
                .flatMap { parent ->
                    listOf(CategoryRow(parent.id, parent.name, isChild = false)) +
                        childrenByParent[parent.id].orEmpty().sortedBy { it.name.lowercase() }
                            .map { CategoryRow(it.id, it.name, isChild = true) }
                }
        }
    }
}
