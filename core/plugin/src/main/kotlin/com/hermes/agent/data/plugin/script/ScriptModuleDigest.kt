package com.hermes.agent.data.plugin.script

import java.security.MessageDigest

/**
 * Integrity check between a registry entry and the manifest it points at.
 *
 * HTTPS proves who served a document, not that it is still the document the
 * registry vouched for. A script module's JavaScript lives *inside* its
 * manifest, so a manifest that changes under an already-approved URL is a code
 * change the user never reviewed. The registry pins a digest; this is where it
 * is enforced, before anything is parsed, persisted, or executed.
 */
object ScriptModuleDigest {

    /** Lowercase hex SHA-256 of [body] as UTF-8 — the bytes the server sent. */
    fun sha256Hex(body: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    /**
     * The outcome of checking one manifest against its registry entry.
     *
     * [Unpinned] is deliberately distinct from [Match]: a registry that carries
     * no digest is not verified, and callers should be able to say so rather
     * than treat it as a pass.
     */
    sealed interface Result {
        data object Match : Result
        data object Unpinned : Result
        data class Mismatch(val expected: String, val actual: String) : Result
    }

    fun check(expectedSha256: String, body: String): Result {
        val expected = expectedSha256.trim().lowercase()
        if (expected.isEmpty()) return Result.Unpinned
        val actual = sha256Hex(body)
        return if (actual == expected) Result.Match else Result.Mismatch(expected, actual)
    }

    /** Human-readable refusal, for surfacing to the person doing the install. */
    fun Result.Mismatch.message(moduleId: String): String =
        "Module '$moduleId' does not match the digest its registry pinned " +
            "(expected $expected, got $actual). It was refused rather than installed."
}
