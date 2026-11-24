package com.fintrack.app.data

import com.fintrack.app.domain.model.WriteResult

/** Returns the created id, failing the test with the actual result otherwise. */
fun WriteResult<*>.requireId(): String = when (this) {
    is WriteResult.Success -> id
    else -> throw AssertionError("Expected Success but was $this")
}
