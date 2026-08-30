package com.hermes.agent.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the handful of settings values that are actually credentials.
 *
 * DataStore keeps its file in app-private storage, which on API 29+ also sits
 * behind file-based encryption — good enough that plaintext there is defensible
 * on a stock device, and not good enough for this app specifically: it ships a
 * privileged shell and an accessibility service, and `:feature:jotter` in Jeeves
 * already holds its GitHub token in an encrypted store. This closes that gap so
 * every credential in the product is protected the same way.
 *
 * Only credential-shaped values go through here; the rest of settings stays
 * readable, because encrypting a theme name buys nothing and makes the store
 * impossible to inspect when something goes wrong.
 */
interface SecretCipher {
    /** Returns [plaintext] in a form safe to persist. Never throws. */
    fun encrypt(plaintext: String): String

    /** Inverse of [encrypt]; returns legacy plaintext unchanged. Never throws. */
    fun decrypt(stored: String): String

    /** Whether [stored] is already ciphertext this cipher produced. */
    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    companion object {
        /**
         * Marks a value as ciphertext, and versions the scheme so a future
         * change can migrate rather than guess. Anything without this prefix is
         * a value written before encryption existed and is read as plaintext.
         */
        const val PREFIX = "enc:v1:"
    }
}

/**
 * [SecretCipher] backed by a hardware-bound AES-256-GCM key in the Android
 * Keystore. The key never leaves the keystore and is not exportable, so the
 * ciphertext is useless if the DataStore file is copied off the device.
 *
 * Every failure path degrades instead of throwing. A keystore key can genuinely
 * disappear underneath a running app — a restore onto new hardware, a factory
 * reset of the secure element — and a crash loop in the settings repository
 * would lock the user out of the app entirely. Losing a stored API key is
 * recoverable by re-entering it; an unopenable app is not.
 */
class KeystoreSecretCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SecretCipher {

    override fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return plaintext
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            // iv || ciphertext, base64'd — GCM's IV is not secret, only unique.
            SecretCipher.PREFIX + android.util.Base64.encodeToString(
                iv + body,
                android.util.Base64.NO_WRAP,
            )
        } catch (t: Throwable) {
            // Storing plaintext is strictly no worse than the behaviour this
            // replaced, and it keeps the setting usable.
            Timber.tag(TAG).e(t, "Could not encrypt a settings secret; storing as-is")
            plaintext
        }
    }

    override fun decrypt(stored: String): String {
        if (!isEncrypted(stored)) return stored
        return try {
            val raw = android.util.Base64.decode(
                stored.removePrefix(SecretCipher.PREFIX),
                android.util.Base64.NO_WRAP,
            )
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES),
            )
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Could not decrypt a settings secret; treating it as unset")
            ""
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately not setUserAuthenticationRequired: background
                // workers reach these credentials with the screen locked.
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "SecretCipher"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_KEY_ALIAS = "hermes.settings.secret.v1"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * Pass-through [SecretCipher] for unit tests and any host without a keystore.
 *
 * Reads still transparently accept real ciphertext-shaped values, so a test can
 * assert the migration path without a device.
 */
class PlaintextSecretCipher : SecretCipher {
    override fun encrypt(plaintext: String): String = plaintext
    override fun decrypt(stored: String): String = stored.removePrefix(SecretCipher.PREFIX)
    override fun isEncrypted(stored: String): Boolean = false
}
