package com.omni.aiassistant.device.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeRecognitionSessionTest {
    @Test
    fun aSuccessfulRecognitionCanBeFollowedByAnotherSession() {
        val session = WakeRecognitionSession()
        assertTrue(session.tryStartListening())
        assertFalse(session.tryStartListening())
        session.recognitionFinished()
        assertTrue(session.tryStartListening())
    }

    @Test
    fun aFailedStartReleasesTheListeningSlotForRetry() {
        val session = WakeRecognitionSession()
        assertTrue(session.tryStartListening())
        session.recognitionFinished()
        assertTrue(session.tryStartListening())
    }

    @Test
    fun partialCommandIsNeverDispatchedBeforeTheFinalRecipientAndBody() {
        val session = WakeRecognitionSession()
        assertEquals(WakeRecognitionEvent.Activated, session.accept("Arman", "Arman", false))
        assertNull(session.accept("send", "Arman", false))
        assertNull(session.accept("send Sara", "Arman", false))
        assertEquals(
            WakeRecognitionEvent.Command("send Sara the updated address"),
            session.accept("send Sara the updated address", "Arman", true),
        )
        assertNull(session.accept("unrelated speech", "Arman", true))
    }

    @Test
    fun wakeOnlyFinalKeepsTheNextUtteranceArmedWithoutDispatchingTheWakeWord() {
        val session = WakeRecognitionSession()
        assertEquals(WakeRecognitionEvent.Activated, session.accept("آرمان", "آرمان", false))
        assertNull(session.accept("ارمان", "آرمان", true))
        assertEquals(
            WakeRecognitionEvent.Command("موسیقی پخش کن"),
            session.accept("موسیقی پخش کن", "آرمان", true),
        )
    }

    @Test
    fun wakeAndCommandInOneFinalUtteranceAreNotLost() {
        val session = WakeRecognitionSession()
        assertEquals(
            WakeRecognitionEvent.Command("Arman check the battery"),
            session.accept("Arman check the battery", "Arman", true),
        )
    }

    @Test
    fun stoppingDiscardsAnArmedCommandAndReleasesTheMicrophoneState() {
        val session = WakeRecognitionSession()
        session.tryStartListening()
        session.accept("Arman", "Arman", false)
        session.reset()
        assertNull(session.accept("send a message", "Arman", true))
        assertTrue(session.tryStartListening())
    }
}
