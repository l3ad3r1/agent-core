package com.hermes.agent.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the at-rest credential cipher.
 *
 * The keystore-backed implementation needs a device, so these cover the parts
 * that decide correctness on the JVM: the tagging scheme that separates
 * ciphertext from values written before encryption existed, and the
 * pass-through implementation the repository tests run against.
 */
class SecretCipherTest {

    /**
     * Stand-in for the real cipher: reversible, and tagged exactly the way the
     * keystore implementation tags its output, so migration logic can be
     * exercised without an Android Keystore.
     */
    private class ReversingCipher : SecretCipher {
        override fun encrypt(plaintext: String): String =
            if (plaintext.isEmpty()) plaintext else SecretCipher.PREFIX + plaintext.reversed()

        override fun decrypt(stored: String): String =
            if (isEncrypted(stored)) stored.removePrefix(SecretCipher.PREFIX).reversed() else stored
    }

    @Test
    fun `encrypted values are tagged so plaintext is distinguishable`() {
        val cipher = ReversingCipher()
        val stored = cipher.encrypt("sk-secret-key")
        assertTrue(stored.startsWith(SecretCipher.PREFIX))
        assertTrue(cipher.isEncrypted(stored))
    }

    @Test
    fun `a round trip returns the original value`() {
        val cipher = ReversingCipher()
        val secret = "sk-ant-api03-abcdefghijklmnop"
        assertEquals(secret, cipher.decrypt(cipher.encrypt(secret)))
    }

    @Test
    fun `a value written before encryption reads back unchanged`() {
        // The migration path: existing installs hold clear text under these keys
        // and must keep working without the user re-entering anything.
        val cipher = ReversingCipher()
        val legacy = "sk-written-before-this-change"
        assertFalse(cipher.isEncrypted(legacy))
        assertEquals(legacy, cipher.decrypt(legacy))
    }

    @Test
    fun `an empty secret stays empty rather than becoming a tagged blob`() {
        // "unset" has to survive the round trip as "unset", or every screen that
        // checks `apiKey.isBlank()` starts believing a key is configured.
        val cipher = ReversingCipher()
        assertEquals("", cipher.encrypt(""))
        assertEquals("", cipher.decrypt(""))
    }

    @Test
    fun `the plaintext cipher is a faithful identity for tests`() {
        val cipher = PlaintextSecretCipher()
        assertEquals("value", cipher.decrypt(cipher.encrypt("value")))
        assertFalse(cipher.isEncrypted(cipher.encrypt("value")))
    }
}
