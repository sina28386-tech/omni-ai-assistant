package com.omni.aiassistant.core.wake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single hand-off point between the wake-word service and the UI.
 *
 * The service runs without any activity, so it cannot open a chat itself. It
 * publishes the recognised command here, and the shell picks it up and turns
 * it into a real conversation.
 */
object WakeCommandBus {

    data class Command(
        val text: String,
        val id: Long = System.currentTimeMillis(),
    )

    private val mutable = MutableStateFlow<Command?>(null)
    val commands: StateFlow<Command?> = mutable.asStateFlow()

    fun publish(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        mutable.value = Command(clean)
    }

    /** Called by the shell once the command has been handed to a conversation. */
    fun consume() {
        mutable.value = null
    }
}
