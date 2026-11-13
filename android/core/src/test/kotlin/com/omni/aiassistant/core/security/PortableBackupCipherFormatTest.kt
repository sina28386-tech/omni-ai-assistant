package com.omni.aiassistant.core.security

import com.omni.aiassistant.core.error.AppError
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** Header-format and fail-closed edge coverage for [PortableBackupCipher]. */
class PortableBackupCipherFormatTest {
    private val cipher = PortableBackupCipher(iterations = 100_000)
    private val password = "correct horse battery".toCharArray()

    private data class Header(
        val magic: String,
        val version: Int,
        val iterations: Int,
        val salt: ByteArray,
        val nonce: ByteArray,
        val cipherLength: Int,
        val trailingBytes: Int,
    )

    private fun header(bytes: ByteArray): Header {
        val buffer = ByteBuffer.wrap(bytes)
        val magic = ByteArray(8).also { buffer.get(it) }
        val version = buffer.get().toInt() and 0xff
        val iterations = buffer.getInt()
        val saltLength = buffer.get().toInt() and 0xff
        val salt = ByteArray(saltLength).also { buffer.get(it) }
        val nonceLength = buffer.get().toInt() and 0xff
        val nonce = ByteArray(nonceLength).also { buffer.get(it) }
        val cipherLength = buffer.getInt()
        buffer.position(buffer.position() + cipherLength)
        return Header(
            magic = String(magic, Charsets.US_ASCII),
            version = version,
            iterations = iterations,
            salt = salt,
            nonce = nonce,
            cipherLength = cipherLength,
            trailingBytes = buffer.remaining(),
        )
    }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    @Test fun headerUsesDocumentedFormatSpecifiers() {
        val plaintext = "hello backup".toByteArray()
        val encrypted = cipher.encrypt(plaintext, password)
        val h = header(encrypted)

        assertEquals("OMNIAIBK", h.magic)
        assertEquals(1, h.version)
        assertEquals(100_000, h.iterations)
        assertEquals(32, h.salt.size)
        assertEquals(12, h.nonce.size)
        assertEquals(plaintext.size + 16, h.cipherLength)
        assertEquals(0, h.trailingBytes)
        assertEquals(8 + 1 + 4 + 1 + 32 + 1 + 12 + 4 + plaintext.size + 16, encrypted.size)
    }

    @Test fun headerTamperingFailsClosed() {
        val valid = cipher.encrypt("payload".toByteArray(), password)

        fun rejected(mutated: ByteArray) {
            assertThrows(AppError.Backup::class.java) {
                cipher.decrypt(mutated, "correct horse battery".toCharArray())
            }
        }

        rejected(valid.copyOf().also { it[0] = (it[0] + 1).toByte() })
        rejected(valid.copyOf().also { it[8] = 2 })
        rejected(valid.copyOf().also { putInt(it, 9, 99_999) })
        rejected(valid.copyOf().also { putInt(it, 9, 2_000_001) })
        rejected(valid.copyOf().also { it[13] = 15 })
        rejected(valid.copyOf().also { it[13] = 65 })
        rejected(valid.copyOf().also { it[46] = 11 })
        rejected(valid.copyOf().also { putInt(it, 59, 0) })
        rejected(valid.copyOf(20))
        rejected(valid + byteArrayOf(0))
    }

    @Test fun passwordArraysAreClearedAfterUse() {
        val encryptPassword = "correct horse battery".toCharArray()
        val encrypted = cipher.encrypt(byteArrayOf(1, 2, 3), encryptPassword)
        assertTrue(encryptPassword.all { it == '\u0000' })

        val decryptPassword = "correct horse battery".toCharArray()
        val decrypted = cipher.decrypt(encrypted, decryptPassword)
        assertArrayEquals(byteArrayOf(1, 2, 3), decrypted)
        assertTrue(decryptPassword.all { it == '\u0000' })
    }

    @Test fun shortPasswordIsRejectedForEncryptAndDecrypt() {
        assertThrows(IllegalArgumentException::class.java) {
            cipher.encrypt(byteArrayOf(1), "short".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            cipher.decrypt(byteArrayOf(1, 2, 3), "short".toCharArray())
        }
    }
}
