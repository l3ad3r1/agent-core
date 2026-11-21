package com.hermes.agent.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for coroutine code. Plain `runCatching` also catches [CancellationException], so a
 * cancelled coroutine (a stopped worker, a closed screen, an aborted agent turn) carries on as if
 * the call had merely failed — falling back, recording an error, returning a tool failure. This
 * rethrows cancellation and wraps every other failure, exactly like `runCatching` otherwise.
 *
 * Inline, so the block may call suspend functions when used inside one.
 */
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
