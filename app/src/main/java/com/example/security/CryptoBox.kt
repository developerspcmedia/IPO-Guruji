package com.example.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM at-rest encryption utility backed by AndroidKeyStore.
 *
 * Uses key alias [KEY_ALIAS] with `setUserAuthenticationRequired(false)` so background
 * JobServices (such as AllotmentMonitorJobService) can decrypt stored PAN numbers without
 * requiring an interactive biometric unlock.
 *
 * Output wire format: Base64(NO_WRAP) of `[12-byte random IV || ciphertext || 16-byte GCM tag]`.
 */
object CryptoBox {
    internal const val KEY_ALIAS = "ipo_guruji_pan_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BITS = 256
    internal const val IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8

    private val secureRandom = SecureRandom()
    private val keyLock = Any()

    @Volatile
    private var robolectricTestKey: SecretKey? = null

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val useAndroidKeyStore = isAndroidKeyStoreAvailable()
        val key = getOrCreateSecretKey(useAndroidKeyStore)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv: ByteArray = if (useAndroidKeyStore) {
            // AndroidKeyStore with randomized encryption generates a fresh random 12-byte GCM IV.
            cipher.init(Cipher.ENCRYPT_MODE, key)
            checkNotNull(cipher.iv) { "Missing GCM IV from AndroidKeyStore" }.also { generatedIv ->
                check(generatedIv.size == IV_LENGTH_BYTES) {
                    "Unexpected GCM IV length: ${generatedIv.size}"
                }
            }
        } else {
            ByteArray(IV_LENGTH_BYTES).also { generatedIv ->
                secureRandom.nextBytes(generatedIv)
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, generatedIv))
            }
        }

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(IV_LENGTH_BYTES + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, IV_LENGTH_BYTES)
        System.arraycopy(ciphertext, 0, combined, IV_LENGTH_BYTES, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(ciphertextBase64: String): String {
        if (ciphertextBase64.isEmpty()) return ""
        val combined = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        require(combined.size >= IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES) {
            "Invalid encrypted payload"
        }
        val iv = combined.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = combined.copyOfRange(IV_LENGTH_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val key = getOrCreateSecretKey(isAndroidKeyStoreAvailable())
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val plaintextBytes = cipher.doFinal(ciphertext)
        return String(plaintextBytes, Charsets.UTF_8)
    }

    fun isEncrypted(value: String): Boolean {
        if (value.isEmpty()) return false
        return runCatching {
            decrypt(value)
            true
        }.getOrDefault(false)
    }

    private fun isAndroidKeyStoreAvailable(): Boolean =
        Security.getProvider(ANDROID_KEYSTORE) != null

    private fun isRobolectricRuntime(): Boolean =
        "robolectric".equals(Build.FINGERPRINT, ignoreCase = true)

    private fun getOrCreateSecretKey(useAndroidKeyStore: Boolean): SecretKey {
        if (useAndroidKeyStore) {
            return synchronized(keyLock) {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val existingEntry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                if (existingEntry != null) {
                    existingEntry.secretKey
                } else {
                    val keyGenerator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        ANDROID_KEYSTORE,
                    )
                    val spec = KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(KEY_SIZE_BITS)
                        .setUserAuthenticationRequired(false)
                        .build()
                    keyGenerator.init(spec)
                    keyGenerator.generateKey()
                }
            }
        }

        check(isRobolectricRuntime()) {
            "AndroidKeyStore provider is unavailable on this runtime."
        }
        return robolectricTestKey ?: synchronized(keyLock) {
            robolectricTestKey ?: KeyGenerator.getInstance("AES").apply {
                init(KEY_SIZE_BITS, secureRandom)
            }.generateKey().also { robolectricTestKey = it }
        }
    }
}
