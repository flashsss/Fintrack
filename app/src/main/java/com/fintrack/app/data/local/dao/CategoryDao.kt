package com.fintrack.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fintrack.app.data.local.entity.CategoryEntity
import com.fintrack.app.domain.model.CategoryType
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: CategoryEntity)

    @Update
    suspend fun update(category: CategoryEntity)

    /** Returns archived categories too, so historical transactions can still resolve them. */
    @Query("SELECT * FROM categories WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: String): CategoryEntity?

    /** Categories offered when creating a transaction: not archived and not deleted. */
    @Query(
        """
        SELECT * FROM categories
        WHERE type = :type AND deletedAt IS NULL AND isArchived = 0
        ORDER BY name COLLATE NOCASE
        """,
    )
    fun observeSelectable(type: CategoryType): Flow<List<CategoryEntity>>

    /** All non-deleted categories, including archived ones. */
    @Query("SELECT * FROM categories WHERE deletedAt IS NULL ORDER BY type, name COLLATE NOCASE")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM categories WHERE parentId = :id AND deletedAt IS NULL)")
    suspend fun hasChildren(id: String): Boolean
}
