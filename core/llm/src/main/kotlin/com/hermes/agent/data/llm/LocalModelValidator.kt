package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import android.content.Context
import android.net.Uri
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.arm.aichat.gguf.InvalidFileFormatException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import timber.log.Timber

sealed class ModelValidation {
    data class Valid(
        val metadata: GgufMetadata,
        val summary: String,
    ) : ModelValidation()

    data class Rejected(
        val reason: String,
    ) : ModelValidation()
}

object LocalModelValidator {
    private const val MAX_TENSOR_COUNT = 1_000_000L
    private const val MAX_KV_COUNT = 100_000L

    suspend fun validate(
        file: File,
        expectedSizeBytes: Long? = null,
        reader: GgufMetadataReader = GgufMetadataReader.create(),
    ): ModelValidation {
        if (!file.exists() || !file.isFile) {
            return ModelValidation.Rejected("Model file not found at ${file.absolutePath}")
        }
        val actualSize = file.length()
        if (expectedSizeBytes != null && actualSize != expectedSizeBytes) {
            return ModelValidation.Rejected(
                "Model file size mismatch (expected $expectedSizeBytes bytes, found $actualSize bytes).",
            )
        }
        return try {
            FileInputStream(file).buffered().use { stream ->
                validateStream(stream, expectedSizeBytes, actualSize, reader)
            }
        } catch (e: InvalidFileFormatException) {
            ModelValidation.Rejected("Invalid GGUF format or unsupported version.")
        } catch (e: Exception) {
            Timber.w(e, "GGUF validation failed for file ${file.name}")
            ModelValidation.Rejected(e.message ?: "Failed to read GGUF metadata.")
        }
    }

    suspend fun validate(
        context: Context,
        uri: Uri,
        reader: GgufMetadataReader = GgufMetadataReader.create(),
    ): ModelValidation {
        return try {
            context.contentResolver.openInputStream(uri)?.buffered()?.use { stream ->
                validateStream(stream, expectedSizeBytes = null, actualSizeBytes = null, reader = reader)
            } ?: ModelValidation.Rejected("Cannot open custom model stream from URI.")
        } catch (e: InvalidFileFormatException) {
            ModelValidation.Rejected("Invalid GGUF format or unsupported version.")
        } catch (e: Exception) {
            Timber.w(e, "GGUF validation failed for URI $uri")
            ModelValidation.Rejected(e.message ?: "Failed to read GGUF metadata from URI.")
        }
    }

    suspend fun validateStream(
        input: InputStream,
        expectedSizeBytes: Long? = null,
        actualSizeBytes: Long? = null,
        reader: GgufMetadataReader = GgufMetadataReader.create(),
    ): ModelValidation {
        if (expectedSizeBytes != null && actualSizeBytes != null && actualSizeBytes != expectedSizeBytes) {
            return ModelValidation.Rejected(
                "Model file size mismatch (expected $expectedSizeBytes bytes, found $actualSizeBytes bytes).",
            )
        }

        val metadata = try {
            reader.readStructuredMetadata(input)
        } catch (e: InvalidFileFormatException) {
            return ModelValidation.Rejected("Invalid GGUF format or unsupported version.")
        } catch (e: Exception) {
            return ModelValidation.Rejected(e.message ?: "Corrupted GGUF file or header.")
        }

        // 1. Version check: GGUF v2 and v3 are accepted; v1 or unknown rejected.
        when (metadata.version) {
            GgufMetadata.GgufVersion.EXTENDED_V2,
            GgufMetadata.GgufVersion.VALIDATED_V3 -> Unit
            GgufMetadata.GgufVersion.LEGACY_V1 -> {
                return ModelValidation.Rejected("Legacy GGUF v1 is not supported. Please use a GGUF v2 or v3 model.")
            }
        }

        // 2. Header bounds sanity
        if (metadata.tensorCount <= 0 || metadata.tensorCount > MAX_TENSOR_COUNT) {
            return ModelValidation.Rejected(
                "Invalid tensor count (${metadata.tensorCount}). Must be between 1 and $MAX_TENSOR_COUNT.",
            )
        }
        if (metadata.kvCount <= 0 || metadata.kvCount > MAX_KV_COUNT) {
            return ModelValidation.Rejected(
                "Invalid metadata key-value count (${metadata.kvCount}). Must be between 1 and $MAX_KV_COUNT.",
            )
        }

        // 3. Architecture check
        val arch = metadata.architecture?.architecture
        if (arch.isNullOrBlank()) {
            return ModelValidation.Rejected("Missing or blank model architecture in GGUF metadata.")
        }

        // 4. Split shard check
        val splitCount = metadata.basic.splitCount
        if (splitCount != null && splitCount > 1) {
            return ModelValidation.Rejected(
                "Model is shard of an $splitCount-part split GGUF. Multi-file split models are not supported.",
            )
        }

        // 5. Chat template presence check
        val chatTemplate = metadata.tokenizer?.chatTemplate
        if (chatTemplate.isNullOrBlank()) {
            return ModelValidation.Rejected(
                "Model lacks a chat template in tokenizer metadata. Prompt builder cannot construct a valid transcript.",
            )
        }

        val ctx = metadata.dimensions?.contextLength ?: 0
        val summary = "GGUF ${metadata.version.label}, arch=$arch, tensors=${metadata.tensorCount}, context=$ctx"
        return ModelValidation.Valid(metadata, summary)
    }
}
