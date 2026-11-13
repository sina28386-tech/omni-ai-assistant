package com.omni.aiassistant.v2

import com.omni.aiassistant.core.agent.AgentContext
import com.omni.aiassistant.core.agent.RecalledFact
import com.omni.aiassistant.core.agent.UserVerb
import com.omni.aiassistant.core.agent.VerbAction
import com.omni.aiassistant.core.agent.containsWakePhrase
import com.omni.aiassistant.core.agent.normalizeWakePhrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behaviour blocks of the assistant: wake-word matching, the user's own
 * verbs and the routing rules that separate "inside the app" from "on the
 * internet".
 */
class AgentBehaviourTest {

    /* ---------------------------------------------------------------- */
    /* Wake phrase                                                        */
    /* ---------------------------------------------------------------- */

    @Test
    fun normalizesArabicAndPersianLetterVariants() {
        // The alef with maddah and the plain alef are folded onto each other,
        // so a user who says "ارمان" still wakes "آرمان" up.
        assertEquals(normalizeWakePhrase("آرمان"), normalizeWakePhrase("ارمان"))
        assertEquals(normalizeWakePhrase("آرمان"), normalizeWakePhrase(" آرمان "))
        assertEquals("hello", normalizeWakePhrase("  Hello  "))
        // Whitespace is collapsed, not dropped, so word boundaries survive.
        assertEquals("hey arman", normalizeWakePhrase("hey   arman"))
    }

    @Test
    fun wakePhraseMatchesOnlyOnWordBoundaries() {
        assertTrue(containsWakePhrase("آرمان", "آرمان"))
        assertTrue(containsWakePhrase("آرمان، آماده‌ام", "آرمان"))
        assertTrue(containsWakePhrase("hey arman are you there", "arman"))
        assertTrue(containsWakePhrase("Arman!", "arman"))
        // A longer word that merely contains the phrase must not trigger.
        assertFalse(containsWakePhrase("آرمانی", "آرمان"))
        assertFalse(containsWakePhrase("notaraman", "arman"))
        assertFalse(containsWakePhrase("", "arman"))
        assertFalse(containsWakePhrase("arman", ""))
    }

    /* ---------------------------------------------------------------- */
    /* Custom verbs                                                       */
    /* ---------------------------------------------------------------- */

    @Test
    fun everyVerbActionProducesAnInstruction() {
        val actions = VerbAction.entries
        assertTrue(actions.isNotEmpty())
        actions.forEach { action ->
            val verb = UserVerb(id = "v", phrase = "تیک", action = action, target = "notepad")
            val instruction = verb.toInstruction()
            assertTrue(instruction.contains("\"تیک\""))
            assertTrue(instruction.length > 20)
        }
    }

    @Test
    fun verbInstructionNamesTheMatchingTool() {
        val scroll = UserVerb("v", "تیک", VerbAction.SCROLL_DOWN).toInstruction()
        assertTrue(scroll.contains("ui_scroll"))
        assertTrue(scroll.contains("down"))
        val shot = UserVerb("v", "عکس", VerbAction.SCREENSHOT).toInstruction()
        assertTrue(shot.contains("screenshot"))
    }

    /* ---------------------------------------------------------------- */
    /* Context blocks                                                     */
    /* ---------------------------------------------------------------- */

    @Test
    fun contextAlwaysCarriesTheRoutingRules() {
        val block = AgentContext.build()
        assertTrue(block.contains("INSIDE AN APP"))
        assertTrue(block.contains("THE OPEN INTERNET"))
        assertTrue(block.contains("web_fetch"))
        assertTrue(block.contains("screen_read"))
        // A negative instruction removes a route, it never inverts it.
        assertTrue(block.contains("never means"))
    }

    @Test
    fun contextOmitsEmptyBlocks() {
        val block = AgentContext.build(verbs = emptyList(), facts = emptyList())
        assertFalse(block.contains("custom words"))
        assertFalse(block.contains("already know about this user"))
    }

    @Test
    fun contextIncludesVerbsAndFactsWhenPresent() {
        val block = AgentContext.build(
            verbs = listOf(UserVerb("v", "تیک", VerbAction.SCROLL_DOWN)),
            facts = listOf(RecalledFact("favorite_music", "حسین علیزاده")),
        )
        assertTrue(block.contains("تیک"))
        assertTrue(block.contains("favorite_music"))
        assertTrue(block.contains("حسین علیزاده"))
    }

    @Test
    fun contextIsDeterministic() {
        val verbs = listOf(UserVerb("v", "تیک", VerbAction.SCROLL_DOWN))
        val facts = listOf(RecalledFact("k", "v"))
        assertEquals(AgentContext.build(verbs, facts), AgentContext.build(verbs, facts))
    }
}
