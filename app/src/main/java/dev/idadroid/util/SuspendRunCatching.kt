package dev.idadroid.util

import kotlinx.coroutines.CancellationException

/**
 * Runs [block] and catches any exception, EXCEPT [CancellationException].
 *
 * Unlike `runCatching`, this re-throws CancellationException so that coroutine
 * cancellation signals propagate correctly. Use this instead of `runCatching`
 * whenever [block] is (or may call) a suspend function.
 *
 * Standard `runCatching` swallows CancellationException, which breaks structured
 * concurrency: a cancelled parent coroutine's child keeps running instead of
 * being cancelled, wasting resources and potentially updating stale state.
 */
suspend fun <T> runCatchingSuspending(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
