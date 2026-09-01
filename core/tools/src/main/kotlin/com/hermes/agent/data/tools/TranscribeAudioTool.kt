package com.hermes.agent.data.tools

import android.content.Context
import android.net.Uri
import com.hermes.agent.domain.settings.SettingsRepository
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transcribe an audio file (voice note, recording) to text. Ported from
 * hermes-agent's `transcription_tools.py`; upstream picks from six STT
 * backends, but on Android we only ever have the one cloud endpoint the app
 * already talks to for chat (Settings → Cloud) — same pattern as
 * [ImageGenerationTool] for `/images/generations`, here calling the
 * OpenAI-compatible `/audio/transcriptions` endpoint. That covers OpenAI
 * Whisper and any compatible gateway (Groq, OpenRouter, a self-hosted proxy,
 * …) without a dedicated settings screen.
 */
@Singleton
class TranscribeAudioTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val settings: SettingsRepository,
    private val json: Json,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "transcribe_audio",
        description = "Transcribe speech in an audio file to text using a cloud speech-to-text model. " +
            "Accepts a local file path, content URI (content://...), or remote HTTP(S) URL. Use this " +
            "when the user shares a voice note, recording, or audio file and wants it converted to " +
            "text. Requires cloud access to be configured (Settings → Cloud). If transcription fails " +
            "with a model-not-found error, retry with a `model` value that matches the configured " +
            "provider (e.g. Groq uses 'whisper-large-v3-turbo', OpenAI uses 'whisper-1').",
        parameters = listOf(
            ToolParameter(
                name = "audio_path",
                type = ToolParameterType.STRING,
                description = "Path, content URI, or URL of the audio file to transcribe.",
                required = true,
            ),
            ToolParameter(
                name = "language",
                type = ToolParameterType.STRING,
                description = "ISO-639-1 language code hint (e.g. 'en'). Improves accuracy; leave blank to auto-detect.",
                required = false,
            ),
            ToolParameter(
                name = "prompt",
                type = ToolParameterType.STRING,
                description = "Optional context or vocabulary hint to guide transcription (e.g. names, jargon).",
                required = false,
            ),
            ToolParameter(
                name = "model",
                type = ToolParameterType.STRING,
                description = "Speech-to-text model id. Defaults to 'whisper-1' (OpenAI). Override for other providers.",
                required = false,
            ),
        ),
        category = "communication",
        capabilities = setOf("voice", "deferrable"),
        requiresEnv = listOf("cloudApiKey"),
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val audioInput = arguments.string("audio_path") ?: arguments.string("audio_url")
        if (audioInput.isNullOrEmpty()) {
            return@withContext ToolResult.error("missing required parameter: audio_path", System.currentTimeMillis() - start)
        }

        val s = settings.current()
        if (!s.cloudEnabled || s.cloudApiKey.isBlank()) {
            return@withContext ToolResult.error(
                "Cloud access isn't configured. Enable cloud and add an API key in Settings.",
                System.currentTimeMillis() - start,
            )
        }

        val model = arguments.string("model") ?: "whisper-1"
        val language = arguments.string("language")
        val prompt = arguments.string("prompt")

        try {
            val (audioBytes, mime, filename) = loadAudioBytes(audioInput)
            if (audioBytes.isEmpty()) {
                return@withContext ToolResult.error("Failed to load audio from $audioInput (empty data)", System.currentTimeMillis() - start)
            }
            if (audioBytes.size > MAX_AUDIO_BYTES) {
                return@withContext ToolResult.error(
                    "Audio file is too large (${audioBytes.size / (1024 * 1024)} MB). Most providers cap uploads at 25 MB.",
                    System.currentTimeMillis() - start,
                )
            }

            val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart(
                    "file",
                    filename,
                    audioBytes.toRequestBody((mime.toMediaTypeOrNull())),
                )
            language?.let { bodyBuilder.addFormDataPart("language", it) }
            prompt?.let { bodyBuilder.addFormDataPart("prompt", it) }

            val endpoint = s.cloudBaseUrl.trimEnd('/') + "/audio/transcriptions"
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer ${s.cloudApiKey}")
                .post(bodyBuilder.build())
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        404 -> "This provider has no speech-to-text endpoint at $endpoint, or the model id " +
                            "'$model' isn't recognized. Try again with a `model` value that matches the " +
                            "configured provider."
                        401, 403 -> "Authentication failed (HTTP ${response.code}). The cloud API key may " +
                            "be invalid or lack audio-transcription permissions."
                        else -> "Transcription API HTTP ${response.code}: ${raw.take(200)}"
                    }
                    return@withContext ToolResult.error(reason, System.currentTimeMillis() - start)
                }
                val text = parseTranscript(raw)
                if (text.isNullOrBlank()) {
                    ToolResult.error(
                        "Transcription succeeded but returned no text.",
                        System.currentTimeMillis() - start,
                    )
                } else {
                    ToolResult.ok(text, System.currentTimeMillis() - start)
                }
            }
        } catch (e: Exception) {
            ToolResult.error(e.message ?: "audio transcription request failed", System.currentTimeMillis() - start)
        }
    }

    /** Load raw bytes for a local path, content URI, or remote URL, plus a mime type and filename. */
    private fun loadAudioBytes(input: String): Triple<ByteArray, String, String> {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                val request = Request.Builder().url(trimmed).build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code} downloading audio from $trimmed")
                }
                val mime = response.header("Content-Type")?.substringBefore(";")?.trim() ?: "audio/mpeg"
                val bytes = response.body?.bytes() ?: throw IllegalStateException("Empty response body from $trimmed")
                val name = trimmed.substringAfterLast('/').substringBefore('?').ifBlank { "audio" + extensionFor(mime) }
                Triple(bytes, mime, name)
            }
            trimmed.startsWith("content://") -> {
                val uri = Uri.parse(trimmed)
                val mime = context.contentResolver.getType(uri) ?: "audio/mpeg"
                val stream: InputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("Cannot open content URI: $trimmed")
                val bytes = stream.use { it.readBytes() }
                Triple(bytes, mime, "audio" + extensionFor(mime))
            }
            else -> {
                val filePath = trimmed.removePrefix("file://")
                val file = File(filePath)
                if (!file.exists() || !file.canRead()) {
                    throw IllegalArgumentException("File not found or unreadable: $filePath")
                }
                val mime = mimeForExtension(file.extension.lowercase())
                Triple(file.readBytes(), mime, file.name)
            }
        }
    }

    private fun mimeForExtension(ext: String): String = when (ext) {
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "webm" -> "audio/webm"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        else -> "audio/mpeg"
    }

    private fun extensionFor(mime: String): String = when {
        mime.contains("wav") -> ".wav"
        mime.contains("mp4") || mime.contains("m4a") -> ".m4a"
        mime.contains("ogg") -> ".ogg"
        mime.contains("webm") -> ".webm"
        mime.contains("flac") -> ".flac"
        mime.contains("aac") -> ".aac"
        else -> ".mp3"
    }

    /** Pull `text` from an OpenAI-compatible transcription response. */
    private fun parseTranscript(raw: String): String? = runCatching {
        json.parseToJsonElement(raw).jsonObject["text"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private companion object {
        const val MAX_AUDIO_BYTES = 25 * 1024 * 1024
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TranscribeAudioToolModule {
    @Binds
    @IntoSet
    abstract fun bindTranscribeAudioTool(tool: TranscribeAudioTool): Tool
}
