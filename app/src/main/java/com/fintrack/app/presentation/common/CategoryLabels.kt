package com.fintrack.app.presentation.common

import com.fintrack.app.domain.model.Category

/** "Induk › Anak" for child categories, the plain name otherwise. */
fun categoryLabel(category: Category, categoriesById: Map<String, Category>): String =
    category.parentId?.let(categoriesById::get)?.let { "${it.name} › ${category.name}" } ?: category.name
