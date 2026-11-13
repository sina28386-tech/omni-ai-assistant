package com.omni.aiassistant.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationOwnershipTest {
    @Test
    fun canceledGenerationCleanupCannotReleaseTheNextGeneration() {
        val ownership = GenerationOwnership()
        val old = ownership.acquire()!!
        ownership.cancel()
        val current = ownership.acquire()!!

        assertFalse(ownership.finish(old))
        assertNull(ownership.acquire())
        assertTrue(ownership.finish(current))
        assertNotNull(ownership.acquire())
    }

    @Test
    fun duplicateCleanupCannotReleaseAnotherGeneration() {
        val ownership = GenerationOwnership()
        val first = ownership.acquire()!!
        assertTrue(ownership.finish(first))
        val second = ownership.acquire()!!
        assertFalse(ownership.finish(first))
        assertNull(ownership.acquire())
        assertTrue(ownership.finish(second))
    }
}
