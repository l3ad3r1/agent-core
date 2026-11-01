package com.hermes.agent.domain.tool

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether this app can ask the user about a background run's tool call (for example with a
 * notification that has Allow and Deny). Off by default: a background run then denies every tool
 * that needs approval at once, since nobody would see the question. An app turns it on only once
 * it actually surfaces [ToolConfirmationService.pendingRequest] outside its own screens, ideally
 * with a [check] that is live (notifications can be switched off at any time).
 *
 * Never-autonomous and interactive-only tools stay denied in background runs either way.
 */
@Singleton
class BackgroundApprovals @Inject constructor() {
    /** Asked on every background call that needs approval. */
    @Volatile
    var check: () -> Boolean = { false }

    var available: Boolean
        get() = check()
        set(value) {
            check = { value }
        }
}
