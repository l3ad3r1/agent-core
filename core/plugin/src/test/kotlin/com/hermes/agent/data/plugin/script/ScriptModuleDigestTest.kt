package com.hermes.agent.data.plugin.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptModuleDigestTest {

    /** Known-answer vector, so a broken hash implementation cannot self-agree. */
    @Test
    fun `sha256Hex matches the published digest of the empty string`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ScriptModuleDigest.sha256Hex(""),
        )
    }

    @Test
    fun `sha256Hex hashes the UTF-8 bytes`() {
        // "abc" -> the standard SHA-256 test vector.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ScriptModuleDigest.sha256Hex("abc"),
        )
    }

    @Test
    fun `a matching digest passes`() {
        val body = """{"id":"weather","main":"hermes.registerTool('weather', f);"}"""
        val result = ScriptModuleDigest.check(ScriptModuleDigest.sha256Hex(body), body)
        assertEquals(ScriptModuleDigest.Result.Match, result)
    }

    @Test
    fun `a changed manifest is caught even by one character`() {
        val approved = """{"id":"weather","main":"return 'sunny';"}"""
        val swapped = """{"id":"weather","main":"return 'sunnv';"}"""
        val result = ScriptModuleDigest.check(ScriptModuleDigest.sha256Hex(approved), swapped)
        assertTrue("expected a mismatch, got $result", result is ScriptModuleDigest.Result.Mismatch)
    }

    @Test
    fun `an empty pin reports unpinned rather than passing silently`() {
        assertEquals(ScriptModuleDigest.Result.Unpinned, ScriptModuleDigest.check("", "anything"))
        assertEquals(ScriptModuleDigest.Result.Unpinned, ScriptModuleDigest.check("   ", "anything"))
    }

    @Test
    fun `a pin is compared case-insensitively and ignoring surrounding space`() {
        val body = "payload"
        val upper = ScriptModuleDigest.sha256Hex(body).uppercase()
        assertEquals(ScriptModuleDigest.Result.Match, ScriptModuleDigest.check("  $upper  ", body))
    }

    @Test
    fun `the refusal names the module and both digests`() {
        val result = ScriptModuleDigest.check(ScriptModuleDigest.sha256Hex("original"), "tampered")
        val mismatch = result as ScriptModuleDigest.Result.Mismatch
        val message = with(ScriptModuleDigest) { mismatch.message("weather") }
        assertTrue(message.contains("weather"))
        assertTrue(message.contains(mismatch.expected))
        assertTrue(message.contains(mismatch.actual))
        assertTrue(message.contains("refused"))
    }
}
