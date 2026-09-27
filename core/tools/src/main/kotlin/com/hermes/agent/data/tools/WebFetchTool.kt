package com.hermes.agent.data.tools

import com.hermes.agent.data.tool.ToolResultStore
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import com.hermes.agent.util.net.PublicNetworkGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Fetches a URL and returns the page's readable text content.
 * Strips HTML tags, collapses whitespace, and truncates to a sensible limit
 * so the LLM gets clean context without token-bloat.
 */
@Singleton
class WebFetchTool @Inject constructor(
    okHttpClient: OkHttpClient,
    networkGuard: PublicNetworkGuard,
    private val resultStore: ToolResultStore = ToolResultStore(),
) : Tool {

    /** The URL comes from the model, so it may only reach the public internet. */
    private val okHttpClient: OkHttpClient = networkGuard.restrict(okHttpClient)

    override val descriptor = ToolDescriptor(
        name = "web_fetch",
        description = "Fetch the text content of a web page at a given URL. " +
            "Use this when you have a specific URL (from web_search results or user input) " +
            "and need to read its full content.",
        parameters = listOf(
            ToolParameter(
                name = "url",
                type = ToolParameterType.STRING,
                description = "The HTTP or HTTPS URL to fetch.",
                required = true,
            ),
            ToolParameter(
                name = "max_chars",
                type = ToolParameterType.INTEGER,
                description = "How many characters to return now (default 8000, max 32000). " +
                    "A longer page is kept and can be read on with read_tool_result.",
                required = false,
            ),
        ),
        category = "information",
        capabilities = setOf("web"),
        // The tool pages long results itself at max_chars; the executor must not cut again.
        maxResultSizeChars = 33_000,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val url = (arguments["url"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: return@withContext ToolResult.error("missing required parameter: url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ToolResult.error("url must start with http:// or https://")
        }
        val maxChars = (arguments["max_chars"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?.coerceIn(500, 32_000) ?: 8_000

        return@withContext try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) AgentCore/1.0")
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
                .build()

            val fetched = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("HTTP ${response.code} fetching $url")
                }
                val body = response.body ?: throw java.io.IOException("Empty response body from $url")
                Fetched(
                    body.string(),
                    body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty(),
                    // After redirects: relative links resolve against where the page really is.
                    response.request.url.toString(),
                )
            }
            val text = render(fetched.body, fetched.contentType, fetched.finalUrl)
            if (text.isBlank()) return@withContext ToolResult.error("No readable content found at $url")

            // A page past max_chars is kept whole; the model reads on with read_tool_result.
            val full = "URL: $url\n$text"
            val output = if (full.length <= maxChars) full else resultStore.overflow(descriptor.name, full, maxChars)
            ToolResult.ok(output, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            Timber.e(e, "WebFetchTool failed: $url")
            ToolResult.error("Failed to fetch $url: ${e.message}")
        }
    }

    /**
     * JSON and plain text are returned as they are. HTML is reduced to its main
     * content by [ReadablePage], with the page's links listed after it.
     */
    private fun render(raw: String, contentType: String, url: String): String {
        val html = contentType.isEmpty() || "html" in contentType || "xml" in contentType
        if (!html) return "\n" + raw.trim().take(MAX_KEPT_CHARS)

        val page = ReadablePage.extract(raw, url)
        val links = buildString {
            if (page.links.isNotEmpty()) {
                append("\n\nLinks:")
                page.links.forEachIndexed { i, link -> append("\n[${i + 1}] ${link.text} - ${link.url}") }
            }
        }
        val header = if (page.title.isNotEmpty()) "Title: ${page.title}\n\n" else "\n"
        return header + page.text.take(MAX_KEPT_CHARS) + links
    }

    private class Fetched(val body: String, val contentType: String, val finalUrl: String)

    private companion object {
        /** Bound on what is kept of one page; protects memory, not the model's context. */
        const val MAX_KEPT_CHARS = 400_000
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WebFetchToolModule {
    @Binds
    @IntoSet
    abstract fun bindWebFetchTool(tool: WebFetchTool): Tool
}
