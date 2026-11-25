package com.fintrack.app.presentation.categories

import android.database.SQLException
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintrack.app.R
import com.fintrack.app.data.repository.CategoryRepository
import com.fintrack.app.domain.model.Category
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.WriteResult
import com.fintrack.app.domain.validation.CategoryValidationError
import com.fintrack.app.presentation.common.SelectOption
import com.fintrack.app.presentation.common.messageRes
import com.fintrack.app.presentation.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryFormState(
    val isLoading: Boolean = false,
    val isEdit: Boolean = false,
    val notFound: Boolean = false,
    val name: String = "",
    val type: CategoryType = CategoryType.EXPENSE,
    val parentId: String? = null,
    val isArchived: Boolean = false,
    @StringRes val nameError: Int? = null,
    @StringRes val parentError: Int? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val isArchivedNow: Boolean = false,
    @StringRes val generalError: Int? = null,
)

@HiltViewModel
class CategoryFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    private val categoryId: String? = savedStateHandle[Routes.ARG_CATEGORY_ID]
    private val originalParentId = MutableStateFlow<String?>(null)

    private val _state = MutableStateFlow(
        CategoryFormState(
            isEdit = categoryId != null,
            isLoading = categoryId != null,
            type = savedStateHandle.get<String>(Routes.ARG_TYPE)
                ?.let { name -> CategoryType.entries.firstOrNull { it.name == name } }
                ?: CategoryType.EXPENSE,
        ),
    )
    val state: StateFlow<CategoryFormState> = _state.asStateFlow()

    /** Possible parents: top-level categories of the same type, excluding this one. */
    val parentOptions: StateFlow<List<SelectOption>> = combine(
        categoryRepository.observeAllCategories(),
        _state.map { it.type }.distinctUntilChanged(),
        originalParentId,
    ) { categories, type, keepParent ->
        parentOptions(categories, type, categoryId, keepParent)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (categoryId != null) load(categoryId)
    }

    private fun load(id: String) = viewModelScope.launch {
        val category = try {
            categoryRepository.getCategory(id)
        } catch (error: SQLException) {
            _state.update { it.copy(isLoading = false, generalError = R.string.error_load_failed) }
            return@launch
        }
        if (category == null) {
            _state.update { it.copy(isLoading = false, notFound = true) }
            return@launch
        }
        originalParentId.value = category.parentId
        _state.update {
            it.copy(
                isLoading = false,
                name = category.name,
                type = category.type,
                parentId = category.parentId,
                isArchived = category.isArchived,
            )
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value, nameError = null) }

    /** Only allowed while creating; the type is fixed afterwards. */
    fun onTypeChange(value: CategoryType) = _state.update {
        if (it.isEdit || it.type == value) it else it.copy(type = value, parentId = null, parentError = null)
    }

    /** [value] null means "no parent". */
    fun onParentChange(value: String?) = _state.update { it.copy(parentId = value, parentError = null) }

    fun save() {
        val form = _state.value
        if (form.isLoading || form.isSaving || form.isSaved || form.notFound) return
        _state.update { it.copy(isSaving = true, generalError = null) }
        viewModelScope.launch {
            try {
                val result = if (categoryId == null) {
                    categoryRepository.createCategory(form.name, form.type, form.parentId, icon = null, color = null)
                } else {
                    val existing = categoryRepository.getCategory(categoryId)
                    categoryRepository.updateCategory(
                        categoryId, form.name, form.parentId, icon = existing?.icon, color = existing?.color,
                    )
                }
                _state.update { current ->
                    when (result) {
                        is WriteResult.Success -> current.copy(isSaving = false, isSaved = true)
                        is WriteResult.Invalid -> current.copy(
                            isSaving = false,
                            nameError = result.errors.firstOrNull { it == CategoryValidationError.NameBlank }?.messageRes,
                            parentError = result.errors.firstOrNull { it != CategoryValidationError.NameBlank }?.messageRes,
                        )
                        WriteResult.NotFound -> current.copy(isSaving = false, notFound = true)
                    }
                }
            } catch (error: SQLException) {
                _state.update { it.copy(isSaving = false, generalError = R.string.error_save_failed) }
            }
        }
    }

    fun archive() {
        val id = categoryId ?: return
        viewModelScope.launch {
            try {
                when (categoryRepository.archiveCategory(id)) {
                    is WriteResult.Success -> _state.update { it.copy(isArchived = true, isArchivedNow = true) }
                    WriteResult.NotFound, is WriteResult.Invalid -> _state.update { it.copy(notFound = true) }
                }
            } catch (error: SQLException) {
                _state.update { it.copy(generalError = R.string.error_save_failed) }
            }
        }
    }

    companion object {
        /**
         * Active top-level categories of [type], excluding [selfId]. The current parent stays
         * available even if archived, mirroring the validator's rule for edits.
         */
        fun parentOptions(
            categories: List<Category>,
            type: CategoryType,
            selfId: String?,
            keepParentId: String?,
        ): List<SelectOption> =
            categories
                .filter { it.type == type && it.parentId == null && it.id != selfId }
                .filter { !it.isArchived || it.id == keepParentId }
                .sortedBy { it.name.lowercase() }
                .map { SelectOption(it.id, it.name, isArchived = it.isArchived) }
    }
}
