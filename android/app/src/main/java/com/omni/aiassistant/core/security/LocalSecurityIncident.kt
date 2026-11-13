package com.omni.aiassistant.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local signal for failures that make Keystore-protected persisted data unreadable.
 * It contains no decrypted payload, key material, or credential value.
 */
object LocalSecurityIncident {
    private val mutableError = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = mutableError.asStateFlow()

    fun report(error: Throwable) {
        mutableError.value = error
    }

    fun clear() {
        mutableError.value = null
    }
}
