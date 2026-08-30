package com.hermes.agent.data.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
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
) : Tool {

    private val httpClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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
            val (imageBytes, detectedMime) = loadImageBytes(imageInput)
            if (imageBytes.isEmpty()) {
                return@withContext ToolResult.error("Failed to load image from $imageInput (empty data)", System.currentTimeMillis() - start)
            }

            val (optimizedBytes, finalMime) = optimizeImage(imageBytes, detectedMime)
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

    private suspend fun loadImageBytes(input: String): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        when {
            trimmed.startsWith("data:") -> {
                val commaIndex = trimmed.indexOf(',')
                val header = if (commaIndex > 0) trimmed.substring(0, commaIndex) else ""
                val mime = header.substringAfter("data:").substringBefore(";").takeIf { it.isNotBlank() } ?: "image/jpeg"
                val b64 = if (commaIndex > 0) trimmed.substring(commaIndex + 1) else trimmed
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                bytes to mime
            }
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                val request = Request.Builder().url(trimmed).build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code} downloading image from $trimmed")
                }
                val mime = response.header("Content-Type")?.substringBefore(";")?.trim() ?: "image/jpeg"
                val bytes = response.body?.bytes() ?: throw IllegalStateException("Empty response body from $trimmed")
                bytes to mime
            }
            trimmed.startsWith("content://") -> {
                val uri = Uri.parse(trimmed)
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val stream: InputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("Cannot open content URI: $trimmed")
                val bytes = stream.use { it.readBytes() }
                bytes to mime
            }
            else -> {
                val filePath = trimmed.removePrefix("file://")
                val file = File(filePath)
                if (!file.exists() || !file.canRead()) {
                    throw IllegalArgumentException("File not found or unreadable: $filePath")
                }
                val mime = when (file.extension.lowercase()) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    else -> "image/jpeg"
                }
                val bytes = file.readBytes()
                bytes to mime
            }
        }
    }

    private fun optimizeImage(bytes: ByteArray, mimeType: String): Pair<ByteArray, String> {
        val maxDimension = 1568
        val maxByteLimit = 4 * 1024 * 1024 // 4 MB

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

        val origWidth = options.outWidth
        val origHeight = options.outHeight

        if (origWidth <= 0 || origHeight <= 0) {
            // Not a decodable image bitmap (or raw bytes), return as-is
            return bytes to mimeType
        }

        val longestSide = maxOf(origWidth, origHeight)
        val needsDownscale = longestSide > maxDimension || bytes.size > maxByteLimit

        if (!needsDownscale) {
            return bytes to mimeType
        }

        var sampleSize = 1
        while ((longestSide / sampleSize) > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            ?: return bytes to mimeType

        val scaled = if (maxOf(decoded.width, decoded.height) > maxDimension) {
            val scale = maxDimension.toFloat() / maxOf(decoded.width, decoded.height)
            val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
            val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
        } else {
            decoded
        }

        val outputStream = ByteArrayOutputStream()
        val format = if (mimeType.contains("png", ignoreCase = true)) {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }
        scaled.compress(format, 85, outputStream)
        val compressedBytes = outputStream.toByteArray()
        val outMime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"

        return compressedBytes to outMime
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class VisionAnalyzeToolModule {
    @Binds
    @IntoSet
    abstract fun bindVisionAnalyzeTool(tool: VisionAnalyzeTool): Tool
}
