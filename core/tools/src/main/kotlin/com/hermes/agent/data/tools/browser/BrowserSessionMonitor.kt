package com.hermes.agent.data.tools.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** What the user can see of the agent's browser right now. Null when no session is open. */
data class BrowserSessionState(
    val url: String = "",
    val title: String = "",
    /** True while the user has taken control: the agent's browser actions wait until it is handed back. */
    val userInControl: Boolean = false,
)

/**
 * Shares the agent's live browser with the app, so the user can watch the same page the agent reads
 * and take it over (to sign in, pass a check, or just steer) without the agent seeing what they type.
 *
 * [view] is the engine's WebView itself (typed [Any] so this class stays testable on the JVM); an app
 * screen may show it while it exists. The engine publishes and clears it; nothing else owns it.
 */
@Singleton
class BrowserSessionMonitor @Inject constructor() {

    private val _state = MutableStateFlow<BrowserSessionState?>(null)
    val state: StateFlow<BrowserSessionState?> = _state.asStateFlow()

    private val _view = MutableStateFlow<Any?>(null)
    val view: StateFlow<Any?> = _view.asStateFlow()

    /** Bumped on every hand-back, so the tool knows the page may have changed under it. */
    @Volatile var handBacks: Int = 0
        private set

    val userInControl: Boolean get() = _state.value?.userInControl == true

    /** A session opened (or its view was recreated). */
    fun opened(view: Any) {
        _view.value = view
        _state.update { it ?: BrowserSessionState() }
    }

    fun pageChanged(url: String, title: String) {
        _state.update { (it ?: BrowserSessionState()).copy(url = url, title = title) }
    }

    /** The session closed; the view is about to be destroyed and must not be shown any more. */
    fun closed() {
        _view.value = null
        _state.value = null
    }

    /** The user takes over. No-op without a session. */
    fun takeControl() {
        _state.update { it?.copy(userInControl = true) }
    }

    /** The user hands the browser back to the agent. */
    fun handBack() {
        if (_state.value?.userInControl != true) return
        handBacks++
        _state.update { it?.copy(userInControl = false) }
    }
}
