package com.hermes.agent.domain.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based encryption for backup files.
 *
 * A backup leaves the device, so it cannot be protected by the install's
 * keystore key the way settings at rest are — the key has to be something the
 * user can carry to the other device. AES-GCM gives confidentiality and
 * tamper-detection together, and the PBKDF2 work factor is what stands between
 * a stolen file and an offline guessing attack on a human-chosen password.
 *
 * Shared by both apps so a file written by one restores into the other; two
 * implementations of an envelope format is two chances to get it subtly wrong.
 */
object BackupCipher {

    const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KDF = "PBKDF2WithHmacSHA256"

    /** Thrown when a file will not open, so callers can say why in plain words. */
    class WrongPasswordException : Exception(
        "That password did not open this backup. Check it and try again.",
    )

    fun encrypt(password: String, plaintext: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // Salt and IV are public inputs and travel with the ciphertext.
        return Base64.getEncoder().encodeToString(salt + iv + body)
    }

    /**
     * Reverses [encrypt]. A wrong password fails GCM's tag check rather than
     * yielding plausible-looking rubbish, so this can distinguish "wrong
     * password" from "corrupt file" and is reported as the former.
     */
    fun decrypt(password: String, encoded: String): String = try {
        val raw = Base64.getDecoder().decode(encoded)
        val salt = raw.copyOfRange(0, SALT_BYTES)
        val iv = raw.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
        val body = raw.copyOfRange(SALT_BYTES + IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        String(cipher.doFinal(body), Charsets.UTF_8)
    } catch (e: Exception) {
        throw WrongPasswordException()
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return SecretKeySpec(SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded, "AES")
    }
}

/**
 * The on-disk shape of an encrypted backup.
 *
 * The marker fields stay in the clear so a reader can tell at a glance that a
 * file is encrypted, and which parameters produced it, without first knowing
 * the password — otherwise the scheme could never be changed safely.
 */
@Serializable
data class EncryptedBackup(
    val encrypted: Boolean = true,
    val kdf: String = "PBKDF2WithHmacSHA256",
    val iterations: Int = BackupCipher.ITERATIONS,
    val payload: String,
) {
    companion object {
        val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

        /** Cheap sniff so a restore knows whether to ask for a password. */
        fun looksEncrypted(text: String): Boolean =
            runCatching {
                Json { ignoreUnknownKeys = true }
                    .decodeFromString(serializer(), text).encrypted
            }.getOrDefault(false)
    }
}

/** The credentials a backup can carry, in the clear inside an encrypted file. */
@Serializable
data class CredentialsBackup(
    val cloudApiKey: String = "",
    val auxApiKey: String = "",
    val apiServerKey: String = "",
    val sshPassword: String = "",
    /** Provider id → API key, so a rename or reorder cannot misassign them. */
    val providerKeys: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = listOf(cloudApiKey, auxApiKey, apiServerKey, sshPassword)
            .all { it.isBlank() } && providerKeys.isEmpty()
}
