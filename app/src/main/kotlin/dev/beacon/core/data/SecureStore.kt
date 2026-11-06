package dev.beacon.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keystore-backed secret storage . AES-256/GCM; one hardware-backed
 * key per secret alias; ciphertext held in app-private storage with the random IV
 * prepended. Deliberately hand-rolled instead of androidx.security-crypto, which was
 * deprecated upstream (ADR-0003). Secrets are API keys / JWTs — never logged, and
 * cleared from volatile state (and Keystore) on sign-out.
 */
@Singleton
class SecureStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val keystore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    private val random = SecureRandom()

    fun store(alias: String, plaintext: String) {
        val key = loadOrCreateKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val sealed = cipher.doFinal(plaintext.encodeToByteArray())

        File(dir(), "$alias.bin").writeBytes(iv + sealed)
    }

    fun read(alias: String): String? {
        val file = File(dir(), "$alias.bin")
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.size <= IV_LENGTH) return null

        val key = loadOrCreateKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, bytes, 0, IV_LENGTH))
        return try {
            cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH).decodeToString()
        } catch (_: Exception) {
            // Key invalidated (e.g. OS credential reset) — treat as signed-out.
            null
        }
    }

    fun deleteAll() {
        dir().takeIf { it.exists() }?.deleteRecursively()
        aliases().forEach { alias ->
            try {
                keystore.deleteEntry(alias)
            } catch (_: Exception) {
            }
        }
    }

    /** Removes one secret (ciphertext file + its Keystore key). */
    fun delete(alias: String) {
        File(dir(), "$alias.bin").delete()
        try {
            keystore.deleteEntry(alias)
        } catch (_: Exception) {
        }
    }

    private fun aliases(): List<String> = listOf(API_KEY_ALIAS, AUTH_TOKEN_ALIAS, HEADERS_ALIAS)

    private fun dir(): File = File(context.filesDir, "beacon/secrets").apply { mkdirs() }

    private fun loadOrCreateKey(alias: String): SecretKey =
        (keystore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: newKey(alias)

    private fun newKey(alias: String): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val TAG_LENGTH_BITS = 128
        private const val IV_LENGTH = 12

        const val API_KEY_ALIAS = "api_key"
        const val AUTH_TOKEN_ALIAS = "auth_token"

        /** The custom-headers JSON blob (M4 headers editor; values are gateway credentials). */
        const val HEADERS_ALIAS = "custom_headers"
    }
}