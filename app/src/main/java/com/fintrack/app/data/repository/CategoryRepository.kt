package com.fintrack.app.data.repository

import androidx.room.withTransaction
import com.fintrack.app.data.local.dao.CategoryDao
import com.fintrack.app.data.local.database.FinTrackDatabase
import com.fintrack.app.data.local.entity.CategoryEntity
import com.fintrack.app.data.local.entity.toDomain
import com.fintrack.app.domain.model.Category
import com.fintrack.app.domain.model.CategoryType
import com.fintrack.app.domain.model.SyncStatus
import com.fintrack.app.domain.model.WriteResult
import com.fintrack.app.domain.validation.CategoryValidationError
import com.fintrack.app.domain.validation.CategoryValidator
import com.fintrack.app.domain.validation.ParentCategoryRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Categories are never physically deleted; they are archived. Archived categories remain
 * resolvable for historical transactions but are excluded from [observeSelectableCategories].
 */
@Singleton
class CategoryRepository @Inject constructor(
    private val database: FinTrackDatabase,
    private val categoryDao: CategoryDao,
    private val clock: Clock,
) {

    fun observeSelectableCategories(type: CategoryType): Flow<List<Category>> =
        categoryDao.observeSelectable(type).map { categories -> categories.map { it.toDomain() } }

    fun observeAllCategories(): Flow<List<Category>> =
        categoryDao.observeAll().map { categories -> categories.map { it.toDomain() } }

    suspend fun getCategory(id: String): Category? = categoryDao.getById(id)?.toDomain()

    suspend fun createCategory(
        name: String,
        type: CategoryType,
        parentId: String?,
        icon: String?,
        color: String?,
    ): WriteResult<CategoryValidationError> = database.withTransaction {
        val errors = CategoryValidator.validate(
            name = name,
            type = type,
            parentId = parentId,
            parent = parentId?.let { loadParent(it) },
        )
        if (errors.isNotEmpty()) return@withTransaction WriteResult.Invalid(errors)

        val now = clock.millis()
        val category = CategoryEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            type = type,
            parentId = parentId,
            icon = icon,
            color = color,
            isDefault = false,
            isArchived = false,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            syncStatus = SyncStatus.PENDING,
        )
        categoryDao.insert(category)
        WriteResult.Success(category.id)
    }

    /** The category type is fixed after creation because existing transactions depend on it. */
    suspend fun updateCategory(
        id: String,
        name: String,
        parentId: String?,
        icon: String?,
        color: String?,
    ): WriteResult<CategoryValidationError> = database.withTransaction {
        val existing = categoryDao.getById(id) ?: return@withTransaction WriteResult.NotFound
        val errors = CategoryValidator.validate(
            name = name,
            type = existing.type,
            parentId = parentId,
            parent = parentId?.let { loadParent(it) },
            categoryId = id,
            hasChildren = parentId != null && categoryDao.hasChildren(id),
            currentParentId = existing.parentId,
        )
        if (errors.isNotEmpty()) return@withTransaction WriteResult.Invalid(errors)

        categoryDao.update(
            existing.copy(
                name = name.trim(),
                parentId = parentId,
                icon = icon,
                color = color,
                updatedAt = clock.millis(),
                syncStatus = SyncStatus.PENDING,
            ),
        )
        WriteResult.Success(id)
    }

    suspend fun archiveCategory(id: String): WriteResult<CategoryValidationError> =
        database.withTransaction {
            val existing = categoryDao.getById(id) ?: return@withTransaction WriteResult.NotFound
            categoryDao.update(
                existing.copy(isArchived = true, updatedAt = clock.millis(), syncStatus = SyncStatus.PENDING),
            )
            WriteResult.Success(id)
        }

    private suspend fun loadParent(parentId: String): ParentCategoryRef? =
        categoryDao.getById(parentId)?.let {
            ParentCategoryRef(id = it.id, type = it.type, parentId = it.parentId, isArchived = it.isArchived)
        }
}
