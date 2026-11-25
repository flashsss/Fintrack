package com.fintrack.app.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Waits for the first value matching [predicate]. Room completes work on its own threads, so the
 * wait uses real time (on Dispatchers.Default) instead of runTest's virtual clock.
 */
suspend fun <T> Flow<T>.awaitValue(predicate: (T) -> Boolean): T =
    withContext(Dispatchers.Default) { withTimeout(5_000) { first(predicate) } }
