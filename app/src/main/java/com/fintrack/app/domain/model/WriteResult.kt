package com.fintrack.app.domain.model

/** Outcome of a repository write. Validation failures are values, not exceptions. */
sealed interface WriteResult<out E> {
    data class Success(val id: String) : WriteResult<Nothing>
    data class Invalid<E>(val errors: List<E>) : WriteResult<E>
    /** The target record does not exist or was already deleted. */
    data object NotFound : WriteResult<Nothing>
}
