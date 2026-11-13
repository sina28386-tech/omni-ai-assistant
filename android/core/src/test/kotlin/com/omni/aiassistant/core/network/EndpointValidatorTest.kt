package com.omni.aiassistant.core.network

import com.omni.aiassistant.core.error.AppError
import org.junit.Assert.*
import org.junit.Test

class EndpointValidatorTest {
    @Test fun httpsIsAccepted() {
        EndpointValidator.validateBaseUrl("https://api.example.com/v1", false, false)
    }

    @Test fun publicHttpIsRejectedEvenForLocalDevelopment() {
        assertThrows(AppError.Endpoint::class.java) {
            EndpointValidator.validateBaseUrl("http://example.com/v1", true, true)
        }
    }

    @Test fun credentialsAndQueryAreRejected() {
        assertThrows(AppError.Endpoint::class.java) {
            EndpointValidator.validateBaseUrl("https://user:pass@example.com/v1", false, false)
        }
        assertThrows(AppError.Endpoint::class.java) {
            EndpointValidator.validateBaseUrl("https://example.com/v1?key=x", false, false)
        }
    }

    @Test fun localCleartextRequiresBothPolicies() {
        EndpointValidator.validateBaseUrl("http://127.0.0.1:8080/v1", true, true)
        assertThrows(AppError.Endpoint::class.java) {
            EndpointValidator.validateBaseUrl("http://127.0.0.1:8080/v1", true, false)
        }
        assertThrows(AppError.Endpoint::class.java) {
            EndpointValidator.validateBaseUrl("http://127.0.0.1:8080/v1", false, true)
        }
    }

    @Test fun privateAddressDetectionIsStrict() {
        assertTrue(EndpointValidator.isLocalOrPrivateHost("10.0.2.2"))
        assertTrue(EndpointValidator.isLocalOrPrivateHost("192.168.1.20"))
        assertTrue(EndpointValidator.isLocalOrPrivateHost("172.16.0.1"))
        assertTrue(EndpointValidator.isLocalOrPrivateHost("localhost"))
        assertFalse(EndpointValidator.isLocalOrPrivateHost("8.8.8.8"))
        assertFalse(EndpointValidator.isLocalOrPrivateHost("172.32.0.1"))
    }
}
