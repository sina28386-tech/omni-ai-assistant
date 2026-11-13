package com.omni.aiassistant.core.security

import com.omni.aiassistant.core.error.AppError
import org.junit.Assert.*
import org.junit.Test

class PortableBackupCipherTest {
    private val cipher = PortableBackupCipher(iterations = 100_000)

    @Test fun roundTrip() {
        val input = "sensitive backup data فارسی".toByteArray()
        val encrypted = cipher.encrypt(input, "correct horse battery".toCharArray())
        assertFalse(input.contentEquals(encrypted))
        val decrypted = cipher.decrypt(encrypted, "correct horse battery".toCharArray())
        assertArrayEquals(input, decrypted)
    }

    @Test fun wrongPasswordFailsClosed() {
        val encrypted = cipher.encrypt("secret".toByteArray(), "correct password".toCharArray())
        assertThrows(AppError.Backup::class.java) {
            cipher.decrypt(encrypted, "incorrect password".toCharArray())
        }
    }

    @Test fun tamperingIsDetected() {
        val encrypted = cipher.encrypt("secret".toByteArray(), "correct password".toCharArray())
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(AppError.Backup::class.java) {
            cipher.decrypt(encrypted, "correct password".toCharArray())
        }
    }

    @Test fun shortPasswordRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            cipher.encrypt(byteArrayOf(1), "short".toCharArray())
        }
    }
}
