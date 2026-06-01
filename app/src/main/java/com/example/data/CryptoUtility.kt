package com.example.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptoUtility {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "GatewaySecureStorageKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "secure_gcm:"

    init {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                keyGenerator.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            android.util.Log.e("CryptoUtility", "Failed to initialize secure keystore", e)
        }
    }

    private fun getSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        } catch (e: Exception) {
            android.util.Log.e("CryptoUtility", "Failed to retrieve secret key", e)
            null
        }
    }

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val secretKey = getSecretKey() ?: return plainText
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            
            val ivString = Base64.encodeToString(iv, Base64.NO_WRAP)
            val encryptedString = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
            "$PREFIX$ivString:$encryptedString"
        } catch (e: Exception) {
            android.util.Log.e("CryptoUtility", "Encryption failed", e)
            plainText
        }
    }

    fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        if (!cipherText.startsWith(PREFIX)) return cipherText // Legacy plaintext or empty
        val secretKey = getSecretKey() ?: return cipherText
        val payload = cipherText.substring(PREFIX.length)
        val parts = payload.split(":")
        if (parts.size != 2) return cipherText
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encryptedBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("CryptoUtility", "Decryption failed, falling back to original", e)
            cipherText
        }
    }
}
