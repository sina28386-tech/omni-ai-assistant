package com.omni.aiassistant.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omni.aiassistant.core.error.AppError
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreAeadInstrumentedTest {
    @Test fun roundTripAndTamperDetection() {
        val crypto = KeystoreAead("omni_test_${System.nanoTime()}")
        val encrypted = crypto.encrypt("top secret")
        assertNotEquals("top secret", encrypted)
        assertEquals("top secret", crypto.decrypt(encrypted))
        val tampered = encrypted.dropLast(2) + "AA"
        assertThrows(AppError.Encryption::class.java) { crypto.decrypt(tampered) }
    }
}
