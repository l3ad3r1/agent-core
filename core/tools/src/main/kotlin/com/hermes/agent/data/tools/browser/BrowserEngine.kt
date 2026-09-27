package com.hermes.agent.data.tools.browser

/**
 * What [BrowserTool] needs from a browser. The real one is a hidden WebView
 * ([WebViewBrowserEngine]); tests use a fake, since a JVM test cannot run one.
 */
interface BrowserEngine {
    /** Navigates to [url] and returns once the page has settled or [timeoutMs] passed. */
    suspend fun load(url: String, timeoutMs: Long)

    /** Runs [script] in the page and returns its result as the JSON the script produced. */
    suspend fun evaluate(script: String): String

    /** Waits for any navigation or rendering an action started to settle. */
    suspend fun settle(timeoutMs: Long)

    /** Goes back one page; false when there is no history. */
    suspend fun back(timeoutMs: Long): Boolean

    /** A navigation the engine refused (private address, other scheme), since the last call. */
    fun takeBlockedNavigation(): String?

    /** Frees the browser. The engine is unusable afterwards. */
    fun close()
}

/** Creates a fresh [BrowserEngine]; one lives per browsing session. */
fun interface BrowserEngineFactory {
    fun create(): BrowserEngine
}
