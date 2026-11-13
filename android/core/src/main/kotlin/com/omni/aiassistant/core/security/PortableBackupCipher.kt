package com.omni.aiassistant.core.security

import com.omni.aiassistant.core.error.AppError
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class PortableBackupCipher(
    private val iterations: Int = 310_000,
) {
    private val random = SecureRandom()

    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 10) { "Backup password must contain at least 10 characters" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = derive(password, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(MAGIC)
            val ciphertext = cipher.doFinal(plaintext)
            ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.write(MAGIC)
                    out.writeByte(VERSION)
                    out.writeInt(iterations)
                    out.writeByte(salt.size)
                    out.write(salt)
                    out.writeByte(nonce.size)
                    out.write(nonce)
                    out.writeInt(ciphertext.size)
                    out.write(ciphertext)
                }
                buffer.toByteArray()
            }
        } catch (t: Throwable) {
            throw AppError.Backup("Unable to encrypt backup", t)
        } finally {
            password.fill('\u0000')
        }
    }

    fun decrypt(fileBytes: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 10) { "Backup password must contain at least 10 characters" }
        try {
            val input = DataInputStream(ByteArrayInputStream(fileBytes))
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            if (!magic.contentEquals(MAGIC)) throw AppError.Backup("Not an Omni AI backup")
            val version = input.readUnsignedByte()
            if (version != VERSION) throw AppError.Backup("Unsupported backup version: $version")
            val fileIterations = input.readInt()
            if (fileIterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw AppError.Backup("Invalid backup key-derivation parameters")
            val saltLength = input.readUnsignedByte()
            if (saltLength !in 16..64) throw AppError.Backup("Invalid backup salt")
            val salt = ByteArray(saltLength).also(input::readFully)
            val nonceLength = input.readUnsignedByte()
            if (nonceLength != NONCE_BYTES) throw AppError.Backup("Invalid backup nonce")
            val nonce = ByteArray(nonceLength).also(input::readFully)
            val cipherLength = input.readInt()
            // AES-GCM always appends a 16 byte tag, so 16 bytes is the smallest
            // legal payload (an empty document) and anything shorter is corrupt.
            if (cipherLength < 16 || cipherLength > MAX_ENCRYPTED_BYTES || cipherLength > input.available()) {
                throw AppError.Backup("Invalid backup payload length")
            }
            val ciphertext = ByteArray(cipherLength).also(input::readFully)
            if (input.available() != 0) throw AppError.Backup("Unexpected trailing backup data")
            val key = derive(password, salt, fileIterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(MAGIC)
            return cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw AppError.Backup("Wrong password or corrupted backup", e)
        } catch (e: AppError.Backup) {
            throw e
        } catch (t: Throwable) {
            throw AppError.Backup("Unable to decrypt backup", t)
        } finally {
            password.fill('\u0000')
        }
    }

    private fun derive(password: CharArray, salt: ByteArray, rounds: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, rounds, 256)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val encoded = factory.generateSecret(spec).encoded
            try {
                SecretKeySpec(encoded, "AES")
            } finally {
                encoded.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        private val MAGIC = "OMNIAIBK".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        private const val SALT_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val MIN_ITERATIONS = 100_000
        private const val MAX_ITERATIONS = 2_000_000
        private const val MAX_ENCRYPTED_BYTES = 64 * 1024 * 1024
    }
}
