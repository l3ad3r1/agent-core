package com.hermes.agent.data.tools.browser

import android.content.Context
import com.hermes.agent.data.tools.ReadablePage
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A real browser for the agent, ported from upstream Hermes' browser tools.
 *
 * web_fetch reads the HTML a server sends, which for pages that render with
 * JavaScript is an empty shell. This drives a hidden WebView instead: open a
 * page, read what rendered, click a numbered element, type into a field, go
 * back. Each result is a snapshot: the page's readable text and its numbered
 * interactive elements.
 *
 * Opening a URL and reading needs no approval; every click and every typing
 * asks the user, since the page itself decides what a "link" does.
 * The session closes after [IDLE_CLOSE_MS] without use.
 */
@Singleton
class BrowserTool @Inject constructor(
    private val engines: BrowserEngineFactory,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Browse a web page in a real browser that runs JavaScript. Use it when web_fetch returns " +
            "little or nothing, or when you need to click, fill in a search box or go through pages. " +
            "Actions: open (needs url), snapshot (re-read the current page), click (needs ref), " +
            "type (needs ref and text; submit=true presses Enter), back, close. Every result lists the " +
            "page's interactive elements as [e1], [e2]...; pass that ref to click or type.",
        parameters = listOf(
            ToolParameter("action", ToolParameterType.STRING, "What to do.", required = true,
                enumValues = listOf("open", "snapshot", "click", "type", "back", "close")),
            ToolParameter("url", ToolParameterType.STRING, "For open: the http(s) URL."),
            ToolParameter("ref", ToolParameterType.STRING, "For click and type: an element ref such as e3."),
            ToolParameter("text", ToolParameterType.STRING, "For type: the text to enter."),
            ToolParameter("submit", ToolParameterType.BOOLEAN, "For type: press Enter / submit the form afterwards."),
        ),
        category = "information",
        capabilities = setOf("web", "browser"),
        maxResultSizeChars = 12_000,
    )

    @Serializable
    internal data class Element(
        val ref: String,
        val kind: String,
        val label: String = "",
        val href: String = "",
        val placeholder: String = "",
    )

    @Serializable
    internal data class Snapshot(
        val title: String = "",
        val url: String = "",
        val elements: List<Element> = emptyList(),
        val html: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: BrowserEngine? = null
    private var idleClose: Job? = null

    /** Elements of the last snapshot, by ref: what a click would actually hit. */
    @Volatile private var lastElements: Map<String, Element> = emptyMap()

    override fun requiresConfirmation(arguments: Map<String, JsonElement>): Boolean =
        when (arguments.string("action")) {
            // The page decides what looks like a link (an <a> can carry an onclick that
            // buys or posts), so no click is waved through.
            "type", "click" -> true
            else -> false
        }

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = lock.withLock {
        val start = System.currentTimeMillis()
        val result = runCatching { act(arguments) }.getOrElse { e ->
            ToolResult.error("Browser ${arguments.string("action") ?: ""} failed: ${e.message ?: e.javaClass.simpleName}")
        }
        scheduleIdleClose()
        if (result.success) result.copy(executionMs = System.currentTimeMillis() - start) else result
    }

    private suspend fun act(arguments: Map<String, JsonElement>): ToolResult {
        when (val action = arguments.string("action")) {
            "open" -> {
                val url = arguments.string("url")?.trim().orEmpty()
                if (!url.startsWith("https://") && !url.startsWith("http://")) {
                    return ToolResult.error("open needs an http:// or https:// url")
                }
                session().load(url, LOAD_TIMEOUT_MS)
            }
            "snapshot" -> requireOpen() ?: return notOpen()
            "click", "type" -> {
                val ref = arguments.string("ref")?.trim().orEmpty()
                val engine = requireOpen() ?: return notOpen()
                if (ref !in lastElements) {
                    return ToolResult.error("No element '$ref' on this page. Take a snapshot and use one of its refs.")
                }
                val script = if (action == "click") {
                    BrowserScripts.click(ref)
                } else {
                    val text = arguments.string("text") ?: return ToolResult.error("type needs text")
                    val submit = (arguments["submit"] as? JsonPrimitive)?.booleanOrNull ?: false
                    BrowserScripts.type(ref, text, submit)
                }
                if (!engine.evaluate(script).contains("\"ok\":true")) {
                    return ToolResult.error("Element '$ref' is gone; the page changed. Take a snapshot first.")
                }
                engine.settle(LOAD_TIMEOUT_MS)
            }
            "back" -> {
                val engine = requireOpen() ?: return notOpen()
                if (!engine.back(LOAD_TIMEOUT_MS)) return ToolResult.error("There is no earlier page.")
            }
            "close" -> {
                closeSession()
                return ToolResult.ok("Browser closed.")
            }
            else -> return ToolResult.error("Unknown action '$action'. Use open, snapshot, click, type, back or close.")
        }
        return snapshot()
    }

    private suspend fun snapshot(): ToolResult {
        val engine = engine ?: return notOpen()
        engine.takeBlockedNavigation()?.let { refused ->
            return ToolResult.error(
                "Refused to open $refused: the browser only goes to public http(s) sites, never the local network or other apps.",
            )
        }
        val snap = json.decodeFromString(Snapshot.serializer(), engine.evaluate(BrowserScripts.SNAPSHOT))
        lastElements = snap.elements.associateBy { it.ref }
        return ToolResult.ok(render(snap))
    }

    private fun session(): BrowserEngine = engine ?: engines.create().also { engine = it }

    private fun requireOpen(): BrowserEngine? = engine

    private fun notOpen() = ToolResult.error("No page is open. Use action=open with a url first.")

    private fun closeSession() {
        engine?.close()
        engine = null
        lastElements = emptyMap()
    }

    private fun scheduleIdleClose() {
        idleClose?.cancel()
        if (engine == null) return
        idleClose = scope.launch {
            delay(IDLE_CLOSE_MS)
            lock.withLock { closeSession() }
        }
    }

    private fun Map<String, JsonElement>.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val NAME = "browser"
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val IDLE_CLOSE_MS = 5 * 60_000L

        /** The model's view of a page: where it is, what it can act on, then what it says. */
        internal fun render(snap: Snapshot): String = buildString {
            append("URL: ").append(snap.url).append('\n')
            if (snap.title.isNotBlank()) append("Title: ").append(snap.title.trim()).append('\n')
            if (snap.elements.isNotEmpty()) {
                append("\nInteractive elements:\n")
                snap.elements.forEach { e ->
                    append('[').append(e.ref).append("] ").append(e.kind)
                    if (e.label.isNotBlank()) append(" \"").append(e.label).append('"')
                    if (e.placeholder.isNotBlank() && e.placeholder != e.label) append(" (").append(e.placeholder).append(')')
                    if (e.href.isNotBlank()) append(" -> ").append(e.href)
                    append('\n')
                }
            }
            val text = ReadablePage.extract(snap.html, snap.url.ifBlank { "https://invalid/" }, maxLinks = 0).text
            append("\nPage text:\n").append(text.ifBlank { "(no readable text)" })
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BrowserToolModule {
    @Binds
    @IntoSet
    abstract fun bindBrowserTool(tool: BrowserTool): Tool

    companion object {
        @Provides
        fun provideBrowserEngineFactory(@ApplicationContext context: Context): BrowserEngineFactory =
            BrowserEngineFactory { WebViewBrowserEngine(context) }
    }
}
