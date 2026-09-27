package com.hermes.agent.data.tools

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
                description = "Maximum characters of readable text to return (default 8000, max 32000).",
                required = false,
            ),
        ),
        category = "information",
        capabilities = setOf("web"),
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
            val text = render(fetched.body, fetched.contentType, fetched.finalUrl, maxChars)
            if (text.isBlank()) return@withContext ToolResult.error("No readable content found at $url")

            ToolResult.ok("URL: $url\n$text", System.currentTimeMillis() - start)
        } catch (e: Exception) {
            Timber.e(e, "WebFetchTool failed: $url")
            ToolResult.error("Failed to fetch $url: ${e.message}")
        }
    }

    /**
     * JSON and plain text are returned as they are. HTML is reduced to its main
     * content by [ReadablePage], with the page's links listed after it; the links
     * get at most a fifth of the budget so the text always comes first.
     */
    private fun render(raw: String, contentType: String, url: String, maxChars: Int): String {
        val html = contentType.isEmpty() || "html" in contentType || "xml" in contentType
        if (!html) return "\n" + cap(raw.trim(), maxChars)

        val page = ReadablePage.extract(raw, url)
        val links = buildString {
            if (page.links.isNotEmpty()) {
                append("\n\nLinks:")
                page.links.forEachIndexed { i, link -> append("\n[${i + 1}] ${link.text} - ${link.url}") }
            }
        }.take(maxChars / 5)
        val header = if (page.title.isNotEmpty()) "Title: ${page.title}\n\n" else "\n"
        return header + cap(page.text, maxChars - links.length) + links
    }

    private class Fetched(val body: String, val contentType: String, val finalUrl: String)

    private fun cap(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars) + "\n…[truncated at $maxChars chars]"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WebFetchToolModule {
    @Binds
    @IntoSet
    abstract fun bindWebFetchTool(tool: WebFetchTool): Tool
}
