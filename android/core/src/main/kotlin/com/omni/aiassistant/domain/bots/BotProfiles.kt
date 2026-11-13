package com.omni.aiassistant.domain.bots

import com.omni.aiassistant.domain.model.Conversation
import com.omni.aiassistant.domain.model.ProviderProfile

enum class BotValidationIssue {
    NAME_REQUIRED,
    NAME_TOO_LONG,
    INSTRUCTIONS_REQUIRED,
    INSTRUCTIONS_TOO_LONG,
    PROVIDER_UNAVAILABLE,
    INVALID_BOT_ID,
}

class BotValidationException(val issue: BotValidationIssue) : IllegalArgumentException(issue.name)

/** Bot identity and validation reuse the encrypted provider/conversation stores. */
object BotProfiles {
    const val MAX_NAME_LENGTH = 60
    const val MAX_INSTRUCTIONS_LENGTH = 12_000
    private const val ID_PREFIX = "bot-"

    fun isBotId(id: String): Boolean = id.startsWith(ID_PREFIX) && id.length > ID_PREFIX.length

    fun canUseAsBase(profile: ProviderProfile): Boolean =
        profile.enabled && !isBotId(profile.id) && profile.modelId.isNotBlank()

    fun validate(name: String, instructions: String): BotValidationIssue? = when {
        name.isBlank() -> BotValidationIssue.NAME_REQUIRED
        name.trim().length > MAX_NAME_LENGTH -> BotValidationIssue.NAME_TOO_LONG
        instructions.isBlank() -> BotValidationIssue.INSTRUCTIONS_REQUIRED
        instructions.trim().length > MAX_INSTRUCTIONS_LENGTH -> BotValidationIssue.INSTRUCTIONS_TOO_LONG
        else -> null
    }

    fun create(
        base: ProviderProfile,
        id: String,
        name: String,
        instructions: String,
        now: Long,
    ): ProviderProfile {
        requireBotId(id)
        if (!canUseAsBase(base)) throw BotValidationException(BotValidationIssue.PROVIDER_UNAVAILABLE)
        validate(name, instructions)?.let { throw BotValidationException(it) }
        return base.copy(
            id = id,
            name = name.trim(),
            systemPrompt = instructions.trim(),
            createdAt = now,
            updatedAt = now,
        )
    }

    fun edit(
        bot: ProviderProfile,
        name: String,
        instructions: String,
        now: Long,
    ): ProviderProfile {
        requireBotId(bot.id)
        validate(name, instructions)?.let { throw BotValidationException(it) }
        return bot.copy(name = name.trim(), systemPrompt = instructions.trim(), updatedAt = now)
    }

    fun latestConversation(conversations: List<Conversation>, botId: String): Conversation? {
        if (!isBotId(botId)) return null
        return conversations.asSequence()
            .filter { it.providerProfileId == botId && !it.archived }
            .maxWithOrNull(compareBy<Conversation> { it.updatedAt }.thenBy { it.createdAt }.thenBy { it.id })
    }

    private fun requireBotId(id: String) {
        if (!isBotId(id)) throw BotValidationException(BotValidationIssue.INVALID_BOT_ID)
    }
}
