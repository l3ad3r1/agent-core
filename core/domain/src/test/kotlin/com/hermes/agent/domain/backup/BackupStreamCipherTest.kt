package com.hermes.agent.domain.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

class BackupStreamCipherTest {

    // Low on purpose: PBKDF2 at the production work factor would make every test take a second.
    private val fast = 1_000

    private fun seal(data: ByteArray, password: String = "pw"): ByteArray {
        val bytes = ByteArrayOutputStream()
        BackupStreamCipher.encrypt(bytes, password, fast).use { it.write(data) }
        return bytes.toByteArray()
    }

    private fun open(sealed: ByteArray, password: String = "pw"): ByteArray =
        BackupStreamCipher.decrypt(ByteArrayInputStream(sealed), password).use { it.readBytes() }

    private fun randomBytes(n: Int) = ByteArray(n).also { Random(7).nextBytes(it) }

    @Test
    fun `small data round trips`() {
        val data = "hello".toByteArray()
        assertArrayEquals(data, open(seal(data)))
    }

    @Test
    fun `empty data round trips`() {
        assertEquals(0, open(seal(ByteArray(0))).size)
    }

    @Test
    fun `data spanning many chunks round trips and lands exactly on a chunk boundary`() {
        for (size in listOf(256 * 1024 - 1, 256 * 1024, 256 * 1024 + 1, 3 * 256 * 1024 + 17)) {
            val data = randomBytes(size)
            assertArrayEquals("size $size", data, open(seal(data)))
        }
    }

    @Test
    fun `plaintext does not appear in the file`() {
        val secret = "sk-live-very-secret-key"
        val sealed = seal(secret.toByteArray())
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains(secret))
    }

    @Test
    fun `the same data encrypts differently each time`() {
        val data = randomBytes(1000)
        assertFalse(seal(data).contentEquals(seal(data)))
    }

    @Test
    fun `a wrong password is reported as one`() {
        val sealed = seal("x".toByteArray())
        val error = runCatching { open(sealed, "nope") }.exceptionOrNull()
        assertTrue(error is BackupCipher.WrongPasswordException)
    }

    @Test
    fun `a file cut at a frame boundary is refused, not read as a smaller backup`() {
        val sealed = seal(randomBytes(3 * 256 * 1024))
        // Drop the whole final frame: what remains is a run of valid, non-final frames.
        val frame = 1 + 4 + 256 * 1024 + 16
        val header = 8 + 4 + 16 + 8
        val cut = sealed.copyOf(header + 2 * frame)
        val error = runCatching { open(cut) }.exceptionOrNull()
        assertTrue(error is BackupStreamCipher.CorruptBackupException)
    }

    @Test
    fun `a file cut mid-frame is refused`() {
        val sealed = seal(randomBytes(1000))
        val error = runCatching { open(sealed.copyOf(sealed.size - 5)) }.exceptionOrNull()
        assertTrue(error is BackupStreamCipher.CorruptBackupException)
    }

    @Test
    fun `a flipped byte in a later frame is reported as damage, not a wrong password`() {
        val sealed = seal(randomBytes(3 * 256 * 1024))
        val tampered = sealed.copyOf()
        tampered[tampered.size - 100] = (tampered[tampered.size - 100].toInt() xor 1).toByte()
        val error = runCatching { open(tampered) }.exceptionOrNull()
        assertTrue(error is BackupStreamCipher.CorruptBackupException)
    }

    @Test
    fun `swapping two frames is refused`() {
        val sealed = seal(randomBytes(3 * 256 * 1024))
        val frame = 1 + 4 + 256 * 1024 + 16
        val header = 8 + 4 + 16 + 8
        val swapped = sealed.copyOf()
        System.arraycopy(sealed, header + frame, swapped, header, frame)
        System.arraycopy(sealed, header, swapped, header + frame, frame)
        assertNotNull(runCatching { open(swapped) }.exceptionOrNull())
    }

    @Test
    fun `a changed iteration count in the header is refused`() {
        val sealed = seal("x".toByteArray())
        val tampered = sealed.copyOf()
        tampered[8 + 3] = (tampered[8 + 3].toInt() xor 1).toByte()
        assertNotNull(runCatching { open(tampered) }.exceptionOrNull())
    }

    @Test
    fun `something that is not a backup is refused`() {
        val error = runCatching { open("just some text, not a backup at all".toByteArray()) }.exceptionOrNull()
        assertTrue(error is BackupStreamCipher.CorruptBackupException)
    }

    @Test
    fun `trailing bytes after the end are refused`() {
        val error = runCatching { open(seal("x".toByteArray()) + byteArrayOf(1, 2, 3)) }.exceptionOrNull()
        assertTrue(error is BackupStreamCipher.CorruptBackupException)
    }
}
