package com.omni.aiassistant.core.util

import org.junit.Assert.*
import org.junit.Test

class SingleFlightGateTest {
    @Test fun rejectsDuplicateUntilReleased() {
        val gate = SingleFlightGate()
        assertTrue(gate.tryAcquire())
        assertTrue(gate.isBusy())
        assertFalse(gate.tryAcquire())
        gate.release()
        assertFalse(gate.isBusy())
        assertTrue(gate.tryAcquire())
    }
}
