package com.omni.aiassistant.domain.bots

import com.omni.aiassistant.domain.model.Conversation
import com.omni.aiassistant.domain.model.ProviderProfile
import com.omni.aiassistant.domain.model.ProviderType
import com.omni.aiassistant.domain.model.TokenParameter
import org.junit.Assert.*
import org.junit.Test

class BotProfilesTest {
    @Test fun createsIndependentNamedCoworkerWithoutChangingBaseProvider() {
        val base = provider()
        val bot = BotProfiles.create(base, "bot-a", "  Research partner  ", "  Compare sources.  ", 200)
        assertEquals("bot-a", bot.id)
        assertEquals("Research partner", bot.name)
        assertEquals("Compare sources.", bot.systemPrompt)
        assertEquals(base.apiKey, bot.apiKey)
        assertEquals(base.modelId, bot.modelId)
        assertEquals(base.supportsTools, bot.supportsTools)
        assertEquals(200L, bot.createdAt)
        assertEquals("Original instructions", base.systemPrompt)
        assertEquals("provider-a", base.id)
    }

    @Test fun refusesDisabledUnconfiguredOrBotBaseProviders() {
        for (base in listOf(provider().copy(enabled = false), provider().copy(modelId = ""),
            provider().copy(id = "bot-existing"))) {
            val error = assertThrows(BotValidationException::class.java) {
                BotProfiles.create(base, "bot-new", "Research", "Check sources", 200)
            }
            assertEquals(BotValidationIssue.PROVIDER_UNAVAILABLE, error.issue)
        }
    }

    @Test fun validatesNamesAndInstructionsWithoutSilentlyTruncating() {
        val cases = listOf(
            Triple("  ", "Research", BotValidationIssue.NAME_REQUIRED),
            Triple("a".repeat(61), "Research", BotValidationIssue.NAME_TOO_LONG),
            Triple("Research", "  ", BotValidationIssue.INSTRUCTIONS_REQUIRED),
            Triple("Research", "a".repeat(12001), BotValidationIssue.INSTRUCTIONS_TOO_LONG),
        )
        for ((name, instructions, issue) in cases) {
            assertEquals(issue, BotProfiles.validate(name, instructions))
        }
        assertNull(BotProfiles.validate("پژوهشگر", "منابع را مقایسه کن"))
    }

    @Test fun editingPreservesIdentityCredentialsAndOriginalCreationTime() {
        val bot = BotProfiles.create(provider(), "bot-a", "Old", "Old role", 100)
        val changed = BotProfiles.edit(bot, "  New  ", "New role", 300)
        assertEquals(bot.id, changed.id)
        assertEquals(100L, changed.createdAt)
        assertEquals(300L, changed.updatedAt)
        assertEquals(bot.apiKey, changed.apiKey)
        assertEquals("New", changed.name)
        assertEquals("New role", changed.systemPrompt)
        assertEquals("Old role", bot.systemPrompt)
    }

    @Test fun editingCannotOverwriteAnOrdinaryProvider() {
        assertThrows(BotValidationException::class.java) {
            BotProfiles.edit(provider(), "New", "Role", 200)
        }
        assertThrows(BotValidationException::class.java) {
            BotProfiles.create(provider(), "provider-a", "New", "Role", 200)
        }
    }

    @Test fun resumesOnlyLatestUnarchivedConversationOwnedByThatBot() {
        val rows = listOf(conversation("old", "bot-a", 100),
            conversation("recent", "bot-a", 300),
            conversation("other", "bot-b", 500),
            conversation("archived", "bot-a", 900).copy(archived = true))
        assertEquals("recent", BotProfiles.latestConversation(rows, "bot-a")?.id)
        assertNull(BotProfiles.latestConversation(rows, "bot-missing"))
    }

    private fun conversation(id: String, providerId: String, time: Long) = Conversation(
        id, "", providerId, "", false, false, 0, time,
    )

    private fun provider() = ProviderProfile(
        id = "provider-a", name = "Model", type = ProviderType.OPENAI,
        baseUrl = "https://api.example.com/v1", apiKey = "test-key", modelId = "model-a",
        organizationId = null, systemPrompt = "Original instructions", temperature = 0.7,
        maxOutputTokens = 1024, timeoutSeconds = 60, supportsVision = false,
        supportsStreaming = true, enabled = true, customHeaders = emptyMap(),
        endpointPath = "/chat/completions", tokenParameter = TokenParameter.AUTO,
        createdAt = 10, updatedAt = 20, supportsTools = true,
    )
}
