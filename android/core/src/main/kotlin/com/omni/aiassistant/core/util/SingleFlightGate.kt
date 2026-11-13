package com.omni.aiassistant.core.util

import java.util.concurrent.atomic.AtomicBoolean

class SingleFlightGate {
    private val busy = AtomicBoolean(false)
    fun tryAcquire(): Boolean = busy.compareAndSet(false, true)
    fun release() { busy.set(false) }
    fun isBusy(): Boolean = busy.get()
}
