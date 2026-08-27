package com.hermes.agent.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCipherTest {

    private val secret = """{"notes":[{"title":"private"}],"key":"sk-live-abc123"}"""

    @Test
    fun `a password round-trips the payload`() {
        val sealed = BackupCipher.encrypt("hunter2", secret)
        assertEquals(secret, BackupCipher.decrypt("hunter2", sealed))
    }

    @Test
    fun `the ciphertext reveals nothing of the plaintext`() {
        val sealed = BackupCipher.encrypt("hunter2", secret)
        assertFalse("the file must not leak its contents", sealed.contains("sk-live-abc123"))
        assertFalse(sealed.contains("private"))
    }

    @Test
    fun `a wrong password is reported as such rather than yielding rubbish`() {
        val sealed = BackupCipher.encrypt("hunter2", secret)
        // GCM authenticates, so a wrong key fails the tag check instead of
        // decrypting to plausible-looking nonsense the caller might then import.
        assertThrows(BackupCipher.WrongPasswordException::class.java) {
            BackupCipher.decrypt("wrong", sealed)
        }
    }

    @Test
    fun `a tampered file is rejected, not silently accepted`() {
        val sealed = BackupCipher.encrypt("hunter2", secret)
        val flipped = sealed.toCharArray().also { it[it.size / 2] = if (it[it.size / 2] == 'A') 'B' else 'A' }
            .concatToString()
        assertThrows(BackupCipher.WrongPasswordException::class.java) {
            BackupCipher.decrypt("hunter2", flipped)
        }
    }

    @Test
    fun `the same input encrypts differently every time`() {
        val a = BackupCipher.encrypt("hunter2", secret)
        val b = BackupCipher.encrypt("hunter2", secret)
        assertTrue("a fixed salt or IV would leak that two files are identical", a != b)
    }

    @Test
    fun `an encrypted file announces itself so a restore knows to ask`() {
        val envelope = EncryptedBackup.json.encodeToString(
            EncryptedBackup.serializer(),
            EncryptedBackup(payload = BackupCipher.encrypt("hunter2", secret)),
        )
        assertTrue(EncryptedBackup.looksEncrypted(envelope))
        assertFalse(EncryptedBackup.looksEncrypted("""{"schemaVersion":1,"notes":[]}"""))
    }
}
