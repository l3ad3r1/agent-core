package com.hermes.agent.data.appagent

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Grants short-lived UI-control authority to the app approved through app_launch. */
@Singleton
class AppInteractionSession(
    /** Injectable for tests; elapsed real time on a device. */
    private val clock: () -> Long,
) {
    @Inject constructor() : this({ SystemClock.elapsedRealtime() })

    private var authorizedPackage: String? = null
    private var expiresAtElapsedMs: Long = 0L

    private val _driving = MutableStateFlow<String?>(null)

    /**
     * The package Jeeves may drive right now, or null. Shown as a "Jeeves is driving" overlay with
     * Stop; it can lag the expiry until the next action checks it, so the overlay also re-checks.
     */
    val driving: StateFlow<String?> = _driving.asStateFlow()

    @Synchronized
    fun authorize(packageName: String) {
        authorizedPackage = packageName
        expiresAtElapsedMs = clock() + SESSION_TTL_MS
        _driving.value = packageName
    }

    /** Ends the session at once; the next app action is refused until the app is launched (and approved) again. */
    @Synchronized
    fun clear() {
        authorizedPackage = null
        expiresAtElapsedMs = 0L
        _driving.value = null
    }

    /** True while a session is authorised and not yet expired. */
    @Synchronized
    fun isActive(): Boolean {
        if (authorizedPackage != null && clock() > expiresAtElapsedMs) clear()
        return authorizedPackage != null
    }

    @Synchronized
    fun rejectionReason(visiblePackage: String): String? {
        val expected = authorizedPackage
            ?: return "Launch and approve the target app with app_launch before analyzing it."
        if (clock() > expiresAtElapsedMs) {
            clear()
            return "The approved app-control session expired. Launch the target app again."
        }
        if (visiblePackage.isEmpty() || visiblePackage != expected) {
            return "The visible app ($visiblePackage) is not the approved app ($expected). " +
                "Launch the target app again before interacting."
        }
        return null
    }

    companion object {
        const val SESSION_TTL_MS = 10 * 60 * 1000L
    }
}
