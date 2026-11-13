package com.omni.aiassistant.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.omni.aiassistant.core.error.AppError
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreAead(
    private val alias: String = "omni_ai_local_aes_v1",
) {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    private fun key(): SecretKey {
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        } catch (t: Throwable) {
            val error = AppError.Encryption("Unable to create local encryption key", t)
            LocalSecurityIncident.report(error)
            throw error
        }
    }

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return "v1:"
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(cipher.iv.size + ciphertext.size)
            cipher.iv.copyInto(packed, 0)
            ciphertext.copyInto(packed, cipher.iv.size)
            "v1:" + Base64.encodeToString(packed, Base64.NO_WRAP)
        } catch (t: Throwable) {
            val error = if (t is AppError.Encryption) t else AppError.Encryption("Local encryption failed", t)
            LocalSecurityIncident.report(error)
            throw error
        }
    }

    fun decrypt(value: String): String {
        if (value == "v1:") return ""
        if (!value.startsWith("v1:")) throw AppError.Encryption("Unsupported encrypted value format")
        return try {
            val packed = Base64.decode(value.removePrefix("v1:"), Base64.NO_WRAP)
            require(packed.size > IV_BYTES + 16) { "Ciphertext is too short" }
            val iv = packed.copyOfRange(0, IV_BYTES)
            val ciphertext = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (t: Throwable) {
            val error = if (t is AppError.Encryption) t else AppError.Encryption("Encrypted local data cannot be decrypted", t)
            LocalSecurityIncident.report(error)
            throw error
        }
    }

    @Synchronized
    fun deleteKey() {
        try {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            LocalSecurityIncident.clear()
        } catch (t: Throwable) {
            val error = AppError.Encryption("Unable to reset the local encryption key", t)
            LocalSecurityIncident.report(error)
            throw error
        }
    }

    companion object {
        private const val IV_BYTES = 12
    }
}
