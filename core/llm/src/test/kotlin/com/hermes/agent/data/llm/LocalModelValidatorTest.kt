package com.hermes.agent.data.llm

import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalModelValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun buildGgufHeader(
        magic: ByteArray = byteArrayOf(0x47, 0x47, 0x55, 0x46), // "GGUF"
        version: Int = 3,
        tensorCount: Long = 100L,
        kvCount: Long = 2L,
        metadataPairs: List<Pair<String, Any>> = listOf(
            "general.architecture" to "llama",
            "tokenizer.chat_template" to "{{ bos_token }}{{ messages }}",
        ),
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // 1. Magic (4 bytes)
        out.write(magic)

        // 2. Version (4 bytes little-endian)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(version).array())

        // 3. Tensor count (8 bytes little-endian)
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(tensorCount).array())

        // 4. KV count (8 bytes little-endian)
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(metadataPairs.size.toLong()).array())

        // 5. KV pairs
        for ((key, value) in metadataPairs) {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(keyBytes.size.toLong()).array())
            out.write(keyBytes)

            when (value) {
                is String -> {
                    // Type 8: String
                    out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(8).array())
                    val valBytes = value.toByteArray(Charsets.UTF_8)
                    out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(valBytes.size.toLong()).array())
                    out.write(valBytes)
                }
                is Int -> {
                    // Type 4: UInt32
                    out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4).array())
                    out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
                }
                is Long -> {
                    // Type 10: UInt64
                    out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(10).array())
                    out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
                }
                is Boolean -> {
                    // Type 7: Bool
                    out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(7).array())
                    out.write(if (value) 1 else 0)
                }
            }
        }

        return out.toByteArray()
    }

    @Test
    fun `valid GGUF v3 header with chat template is accepted`() = runTest {
        val bytes = buildGgufHeader(version = 3)
        val file = tempFolder.newFile("model.gguf").apply { writeBytes(bytes) }

        val result = LocalModelValidator.validate(file, expectedSizeBytes = bytes.size.toLong())
        assertTrue(result is ModelValidation.Valid)
        val valid = result as ModelValidation.Valid
        assertEquals("llama", valid.metadata.architecture?.architecture)
    }

    @Test
    fun `valid GGUF v2 header is accepted`() = runTest {
        val bytes = buildGgufHeader(version = 2)
        val file = tempFolder.newFile("model_v2.gguf").apply { writeBytes(bytes) }

        val result = LocalModelValidator.validate(file)
        assertTrue(result is ModelValidation.Valid)
    }

    @Test
    fun `legacy GGUF v1 is rejected`() = runTest {
        val bytes = buildGgufHeader(version = 1)
        val file = tempFolder.newFile("model_v1.gguf").apply { writeBytes(bytes) }

        val result = LocalModelValidator.validate(file)
        assertTrue(result is ModelValidation.Rejected)
        val rejected = result as ModelValidation.Rejected
        assertTrue(rejected.reason.contains("v1 is not supported", ignoreCase = true))
    }

    @Test
    fun `split shard GGUF is rejected`() = runTest {
        val bytes = buildGgufHeader(
            metadataPairs = listOf(
                "general.architecture" to "llama",
                "tokenizer.chat_template" to "{{ prompt }}",
                "split.count" to 3,
            )
        )
        val file = tempFolder.newFile("split.gguf").apply { writeBytes(bytes) }

        val result = LocalModelValidator.validate(file)
        assertTrue(result is ModelValidation.Rejected)
        val rejected = result as ModelValidation.Rejected
        assertTrue(rejected.reason.contains("split GGUF", ignoreCase = true))
    }

    @Test
    fun `missing chat template is rejected`() = runTest {
        val bytes = buildGgufHeader(
            metadataPairs = listOf(
                "general.architecture" to "llama",
            )
        )
        val file = tempFolder.newFile("no_template.gguf").apply { writeBytes(bytes) }

        val result = LocalModelValidator.validate(file)
        assertTrue(result is ModelValidation.Rejected)
        val rejected = result as ModelValidation.Rejected
        assertTrue(rejected.reason.contains("chat template", ignoreCase = true))
    }

    @Test
    fun `file size mismatch is rejected`() = runTest {
        val bytes = buildGgufHeader()
        val file = tempFolder.newFile("mismatch.gguf").apply { writeBytes(bytes) }

        val result = LocalModelValidator.validate(file, expectedSizeBytes = bytes.size.toLong() + 100L)
        assertTrue(result is ModelValidation.Rejected)
        val rejected = result as ModelValidation.Rejected
        assertTrue(rejected.reason.contains("size mismatch", ignoreCase = true))
    }

    @Test
    fun `invalid magic is rejected`() = runTest {
        val bytes = buildGgufHeader(magic = byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // PK zip
        val file = tempFolder.newFile("zip.gguf").apply { writeBytes(bytes) }

        val result = LocalModelValidator.validate(file)
        assertTrue(result is ModelValidation.Rejected)
    }
}
