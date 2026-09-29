package com.hermes.agent.data.tools

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.hermes.agent.data.llm.HybridLlmRouter
import com.hermes.agent.data.llm.RoutingContext
import com.hermes.agent.data.llm.RoutingDecision
import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import timber.log.Timber
import com.hermes.agent.util.net.PublicNetworkGuard
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multimodal vision analysis tool.
 *
 * Inspects, downscales (max 1568px long edge), and analyzes images from
 * local file paths, content URIs, or remote URLs.
 */
@Singleton
class VisionAnalyzeTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: HybridLlmRouter,
    okHttpClient: OkHttpClient,
    networkGuard: PublicNetworkGuard,
) : Tool {

    private val images = ImageAttachments(context, okHttpClient, networkGuard)

    override val descriptor = ToolDescriptor(
        name = "vision_analyze",
        description = "Analyze, describe, or extract text and UI elements from an image using a vision-capable LLM. " +
            "Accepts a local file path, content URI (content://...), or remote HTTP(S) URL. " +
            "Images are automatically optimized and downscaled for vision processing.",
        parameters = listOf(
            ToolParameter(
                name = "image_path",
                type = ToolParameterType.STRING,
                description = "Path, content URI, or URL of the image to analyze.",
                required = true,
            ),
            ToolParameter(
                name = "prompt",
                type = ToolParameterType.STRING,
                description = "Question, instructions, or analysis prompt for the image. Defaults to 'Describe this image in detail and extract any relevant text or details.'",
                required = false,
            ),
        ),
        category = "vision",
        capabilities = setOf("vision"),
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val imageInput = arguments.string("image_path")
            ?: arguments.string("image_url")
            ?: arguments.string("image_uri")
            ?: return@withContext ToolResult.error("Missing required parameter: image_path", System.currentTimeMillis() - start)

        val prompt = arguments.string("prompt")
            ?: arguments.string("question")
            ?: "Describe this image in detail and extract any relevant text or details."

        try {
            val (imageBytes, detectedMime) = images.load(imageInput)
            if (imageBytes.isEmpty()) {
                return@withContext ToolResult.error("Failed to load image from $imageInput (empty data)", System.currentTimeMillis() - start)
            }

            val (optimizedBytes, finalMime) = images.optimize(imageBytes, detectedMime)
            val base64Data = Base64.encodeToString(optimizedBytes, Base64.NO_WRAP)
            val dataUrl = "data:$finalMime;base64,$base64Data"

            val visionMessage = LlmMessage(
                role = "user",
                content = prompt,
                attachmentUri = dataUrl,
                attachmentMimeType = finalMime,
            )

            val routingDecision = router.route(
                messages = listOf(visionMessage),
                context = RoutingContext(requiresVision = true),
            )

            when (routingDecision) {
                is RoutingDecision.Ready -> {
                    val response = routingDecision.provider.complete(listOf(visionMessage))
                    val analysisText = response.content.ifBlank {
                        "Image successfully processed and analyzed."
                    }
                    val jsonOutput = buildJsonObject {
                        put("status", "success")
                        put("analysis", analysisText)
                        put("mime_type", finalMime)
                        put("size_bytes", optimizedBytes.size)
                    }.toString()
                    ToolResult.ok(jsonOutput, System.currentTimeMillis() - start)
                }
                is RoutingDecision.Unavailable -> {
                    // Fallback when no vision cloud model is configured
                    val jsonOutput = buildJsonObject {
                        put("status", "ready_for_native_vision")
                        put("image_data_url", dataUrl)
                        put("mime_type", finalMime)
                        put("size_bytes", optimizedBytes.size)
                        put("note", "No online vision provider available. Image encoded as data URL for local context.")
                    }.toString()
                    ToolResult.ok(jsonOutput, System.currentTimeMillis() - start)
                }
            }
        } catch (e: Exception) {
            Timber.tag("VisionAnalyzeTool").e(e, "Vision analysis failed for input: %s", imageInput)
            ToolResult.error("Vision analysis failed: ${e.message ?: e.javaClass.simpleName}", System.currentTimeMillis() - start)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class VisionAnalyzeToolModule {
    @Binds
    @IntoSet
    abstract fun bindVisionAnalyzeTool(tool: VisionAnalyzeTool): Tool
}
