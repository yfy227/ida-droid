package dev.idadroid.util

import kotlinx.coroutines.CancellationException

/**
 * runCatching 的协程安全版本。
 *
 * Kotlin 的 runCatching {} 会捕获所有 Throwable 包括 CancellationException，
 * 导致协程取消信号被吞掉，父协程无法正确取消。
 *
 * 此扩展函数在 Result 为 CancellationException 时重新抛出，保留取消信号。
 *
 * 用法：
 * ```
 * suspend fun doSomething() = runCatchingSuspend {
 *     withContext(Dispatchers.IO) { ... }
 * }
 * ```
 */
suspend fun <T> runCatchingSuspend(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
