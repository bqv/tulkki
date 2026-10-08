package uk.xa0.tulkki.ui.pinnedmessage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log

import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptoUtils {

    private const val TAG = "PinnedMsgCrypto"
    private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS_PINNED_MESSAGES = "pinned_messages_encryption_key_v1"
    private const val AES_GCM_NO_PADDING_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128 // Recommended for GCM

    private var keyStoreInstance: KeyStore? = null

    init {
        try {
            keyStoreInstance = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER)
            keyStoreInstance?.load(null)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Failed to initialize Android KeyStore", e)
            // Critical error: The app might not be able to encrypt/decrypt.
        } catch (e: IOException) {
            Log.e(TAG, "Failed to initialize Android KeyStore", e)
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = keyStoreInstance ?: KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).also {
            it.load(null)
            keyStoreInstance = it
        }

        if (keyStore.containsAlias(KEY_ALIAS_PINNED_MESSAGES)) {
            val entry = keyStore.getEntry(KEY_ALIAS_PINNED_MESSAGES, null)
            if (entry is KeyStore.SecretKeyEntry) {
                return entry.secretKey
            } else {
                Log.w(TAG, "Keystore alias found but not a SecretKeyEntry. Recreating.")
                keyStore.deleteEntry(KEY_ALIAS_PINNED_MESSAGES)
            }
        }

        Log.i(TAG, "Generating new secret key for pinned messages.")
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE_PROVIDER)

        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS_PINNED_MESSAGES,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    class EncryptionResult(
        @JvmField val iv: ByteArray,
        @JvmField val ciphertext: ByteArray,
    )

    @JvmStatic
    fun encrypt(plaintextData: ByteArray?): EncryptionResult? {
        if (plaintextData == null || keyStoreInstance == null) {
            Log.e(TAG, "Encryption pre-conditions not met (data or keystore is null).")
            return null
        }
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(AES_GCM_NO_PADDING_TRANSFORMATION)

            // Let the Keystore provider generate the IV.
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val iv = cipher.iv
            if (iv == null) {
                Log.e(TAG, "Cipher failed to generate an IV.")
                return null
            }

            val ciphertext = cipher.doFinal(plaintextData)
            EncryptionResult(iv, ciphertext)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Encryption failed", e)
            null
        } catch (e: IOException) {
            Log.e(TAG, "Encryption failed", e)
            null
        }
    }

    @JvmStatic
    fun decrypt(iv: ByteArray?, ciphertext: ByteArray?): ByteArray? {
        if (iv == null || ciphertext == null || keyStoreInstance == null) {
            Log.e(TAG, "Decryption pre-conditions not met (iv, ciphertext, or keystore is null).")
            return null
        }
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(AES_GCM_NO_PADDING_TRANSFORMATION)

            // For decryption, the IV used during encryption must be provided.
            val gcmParameterSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmParameterSpec)

            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Decryption failed", e)
            null
        } catch (e: IOException) {
            Log.e(TAG, "Decryption failed", e)
            null
        }
    }
}
