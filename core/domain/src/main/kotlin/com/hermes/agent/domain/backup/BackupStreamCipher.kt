package com.hermes.agent.domain.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based encryption for a backup too large to hold in memory.
 *
 * [BackupCipher] seals one string, which is right for a JSON file but not for a whole-app snapshot
 * that contains the database: that would mean the plaintext, its base64 and the ciphertext all
 * living in the heap at once. This encrypts a stream in fixed-size chunks instead, so memory use is
 * one chunk however large the backup is.
 *
 * Layout: a header (magic, PBKDF2 iterations, salt, nonce prefix), then frames of
 * `[last flag: 1][length: 4][AES-GCM ciphertext + tag]`.
 *
 * - The nonce of frame *n* is the header's random prefix followed by *n*, so no nonce repeats under
 *   the one key and reordering frames fails to decrypt.
 * - The header and the frame's own last-flag are authenticated data, so a changed iteration count
 *   or a flag flipped to hide a truncation is rejected rather than trusted.
 * - The final frame is marked, so a file cut short at a frame boundary is an error and not a
 *   smaller-but-valid backup.
 */
object BackupStreamCipher {

    private val MAGIC = "HRMSFB01".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val NONCE_PREFIX_BYTES = 8
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val CHUNK = 256 * 1024
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KDF = "PBKDF2WithHmacSHA256"

    /** A file that is not one of ours, or was cut off, altered, or damaged. */
    class CorruptBackupException(message: String) : Exception(message)

    /** Wraps [out] so everything written to it is encrypted. Closing it finishes the file. */
    fun encrypt(out: OutputStream, password: String, iterations: Int = BackupCipher.ITERATIONS): OutputStream {
        require(password.isNotEmpty()) { "A password is required." }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val prefix = ByteArray(NONCE_PREFIX_BYTES).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(MAGIC.size + 4 + SALT_BYTES + NONCE_PREFIX_BYTES)
            .put(MAGIC).putInt(iterations).put(salt).put(prefix).array()
        out.write(header)
        return EncryptingStream(out, deriveKey(password, salt, iterations), prefix, header)
    }

    /** Reads an encrypted stream. Throws [BackupCipher.WrongPasswordException] for a wrong password. */
    fun decrypt(input: InputStream, password: String): InputStream {
        val data = DataInputStream(input)
        val header = ByteArray(MAGIC.size + 4 + SALT_BYTES + NONCE_PREFIX_BYTES)
        try {
            data.readFully(header)
        } catch (e: EOFException) {
            throw CorruptBackupException("This is not a Hermes full-backup file.")
        }
        val buf = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.size).also { buf.get(it) }
        if (!magic.contentEquals(MAGIC)) throw CorruptBackupException("This is not a Hermes full-backup file.")
        val iterations = buf.int
        if (iterations !in 1..10_000_000) throw CorruptBackupException("This backup's header is damaged.")
        val salt = ByteArray(SALT_BYTES).also { buf.get(it) }
        val prefix = ByteArray(NONCE_PREFIX_BYTES).also { buf.get(it) }
        return DecryptingStream(data, deriveKey(password, salt, iterations), prefix, header)
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeySpec(SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded, "AES")
    }

    private fun nonce(prefix: ByteArray, counter: Int): ByteArray =
        ByteBuffer.allocate(NONCE_PREFIX_BYTES + 4).put(prefix).putInt(counter).array()

    private fun aad(header: ByteArray, last: Boolean): ByteArray =
        header + byteArrayOf(if (last) 1 else 0)

    private class EncryptingStream(
        private val sink: OutputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray,
        private val header: ByteArray,
    ) : FilterOutputStream(sink) {
        private val buffer = ByteArrayOutputStream(CHUNK)
        private var counter = 0
        private var closed = false

        override fun write(b: Int) {
            buffer.write(b)
            if (buffer.size() >= CHUNK) flushFull()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var pos = off
            var left = len
            while (left > 0) {
                val n = minOf(left, CHUNK - buffer.size())
                buffer.write(b, pos, n)
                pos += n
                left -= n
                if (buffer.size() >= CHUNK) flushFull()
            }
        }

        private fun flushFull() {
            emit(last = false)
        }

        private fun emit(last: Boolean) {
            val plain = buffer.toByteArray()
            buffer.reset()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, counter++)))
                updateAAD(aad(header, last))
            }
            val body = cipher.doFinal(plain)
            sink.write(if (last) 1 else 0)
            sink.write(ByteBuffer.allocate(4).putInt(body.size).array())
            sink.write(body)
        }

        override fun flush() {
            sink.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            emit(last = true)
            sink.flush()
            sink.close()
        }
    }

    private class DecryptingStream(
        private val source: DataInputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray,
        private val header: ByteArray,
    ) : InputStream() {
        private var current = ByteArray(0)
        private var pos = 0
        private var counter = 0
        private var finished = false

        override fun read(): Int {
            if (!ensure()) return -1
            return current[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!ensure()) return -1
            val n = minOf(len, current.size - pos)
            System.arraycopy(current, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() {
            source.close()
        }

        /** Makes at least one byte available, or returns false at the end of the file. */
        private fun ensure(): Boolean {
            while (pos >= current.size) {
                if (finished) return false
                nextFrame()
            }
            return true
        }

        private fun nextFrame() {
            val flag = try {
                source.readUnsignedByte()
            } catch (e: EOFException) {
                throw CorruptBackupException("This backup is incomplete: the file ends early.")
            }
            if (flag != 0 && flag != 1) throw CorruptBackupException("This backup is damaged.")
            val length = try {
                source.readInt()
            } catch (e: EOFException) {
                throw CorruptBackupException("This backup is incomplete: the file ends early.")
            }
            if (length < TAG_BYTES || length > CHUNK + TAG_BYTES) throw CorruptBackupException("This backup is damaged.")
            val body = ByteArray(length)
            try {
                source.readFully(body)
            } catch (e: EOFException) {
                throw CorruptBackupException("This backup is incomplete: the file ends early.")
            }
            val last = flag == 1
            current = try {
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, counter++)))
                    updateAAD(aad(header, last))
                }.doFinal(body)
            } catch (e: Exception) {
                // GCM cannot tell a wrong password from a tampered frame. Frame 0 failing is
                // almost always the password; later frames failing means the file was changed.
                if (counter == 1) throw BackupCipher.WrongPasswordException()
                throw CorruptBackupException("This backup is damaged: part of it does not verify.")
            }
            pos = 0
            if (last) {
                finished = true
                if (source.read() != -1) throw CorruptBackupException("This backup has extra data after its end.")
            }
        }
    }
}
