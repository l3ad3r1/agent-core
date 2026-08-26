package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalModelInstallerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `rejects an incomplete staged model without replacing the installed model`() {
        val staging = temporaryFolder.newFile("staging.gguf").apply { writeBytes(byteArrayOf(1, 2)) }
        val destination = temporaryFolder.newFolder("models")
        val installed = destination.resolve("model.gguf").apply { writeBytes(byteArrayOf(9, 9, 9)) }

        val moved = LocalModelInstaller.moveIntoPlace(
            staging = staging,
            destDir = destination,
            fileName = installed.name,
            expectedSize = 3,
        )

        assertFalse(moved)
        assertArrayEquals(byteArrayOf(9, 9, 9), installed.readBytes())
    }

    @Test
    fun `promotes a complete staged model and removes its temporary file`() {
        val contents = byteArrayOf(1, 2, 3)
        val staging = temporaryFolder.newFile("staging.gguf").apply { writeBytes(contents) }
        val destination = temporaryFolder.newFolder("models")

        val moved = LocalModelInstaller.moveIntoPlace(
            staging = staging,
            destDir = destination,
            fileName = "model.gguf",
            expectedSize = contents.size.toLong(),
        )

        assertTrue(moved)
        assertFalse(staging.exists())
        assertArrayEquals(contents, destination.resolve("model.gguf").readBytes())
        assertFalse(destination.resolve(".model.gguf.incoming").exists())
    }

    @Test
    fun `replaces an installed model only after complete staging`() {
        val replacement = byteArrayOf(4, 5, 6)
        val staging = temporaryFolder.newFile("staging.gguf").apply { writeBytes(replacement) }
        val destination = temporaryFolder.newFolder("models")
        destination.resolve("model.gguf").writeBytes(byteArrayOf(1, 2, 3))

        val moved = LocalModelInstaller.moveIntoPlace(
            staging = staging,
            destDir = destination,
            fileName = "model.gguf",
            expectedSize = replacement.size.toLong(),
        )

        assertTrue(moved)
        assertArrayEquals(replacement, destination.resolve("model.gguf").readBytes())
        assertFalse(destination.resolve(".model.gguf.previous").exists())
    }

    @Test
    fun `creates verified sidecar and validates correctly`() {
        val contents = byteArrayOf(1, 2, 3, 4)
        val staging = temporaryFolder.newFile("staging.gguf").apply { writeBytes(contents) }
        val destination = temporaryFolder.newFolder("models")
        val sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

        val moved = LocalModelInstaller.moveIntoPlace(
            staging = staging,
            destDir = destination,
            fileName = "model.gguf",
            expectedSize = contents.size.toLong(),
            expectedSha256 = sha256,
        )

        assertTrue(moved)
        val installed = destination.resolve("model.gguf")
        assertTrue(LocalModelInstaller.sidecarFile(installed).exists())

        // Valid sidecar check
        assertTrue(LocalModelInstaller.isSidecarValid(installed, sha256, contents.size.toLong()))

        // Wrong digest fails check
        assertFalse(LocalModelInstaller.isSidecarValid(installed, "wrong-digest", contents.size.toLong()))

        // Wrong size fails check
        assertFalse(LocalModelInstaller.isSidecarValid(installed, sha256, 999L))
    }
}
