package com.fintrack.app.domain.model

import java.time.Instant

data class Category(
    val id: String,
    val name: String,
    val type: CategoryType,
    val parentId: String?,
    val icon: String?,
    val color: String?,
    val isDefault: Boolean,
    /** Archived categories stay valid for existing transactions but are not offered for new ones. */
    val isArchived: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
