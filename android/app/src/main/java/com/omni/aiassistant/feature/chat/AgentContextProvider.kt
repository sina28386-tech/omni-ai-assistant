package com.omni.aiassistant.feature.chat

import com.omni.aiassistant.core.agent.AgentContext
import com.omni.aiassistant.core.agent.RecalledFact
import com.omni.aiassistant.core.agent.UserVerb
import com.omni.aiassistant.data.memory.UserMemoryRepository
import com.omni.aiassistant.data.style.UserStyleRepository
import com.omni.aiassistant.data.verbs.UserVerbRepository
import com.omni.aiassistant.device.context.EnvironmentContextProvider

/**
 * Collects the behaviour blocks that are attached to every model turn.
 *
 * The class is deliberately free of Compose: it is part of the logic layer,
 * and the UI only ever sees the finished prompt through the ViewModel.
 *
 * It is also the single place where the assistant learns from a user message.
 * `observe` runs before the turn is built, so an instruction such as
 * "from now on answer in English" applies to that very turn.
 */
class AgentContextProvider(
    private val verbs: UserVerbRepository,
    private val memory: UserMemoryRepository,
    private val style: UserStyleRepository,
    private val environment: EnvironmentContextProvider,
) {
    /**
     * Updates the learned profile from the user's own words.
     *
     * Failure is never fatal: a prompt without the style block still works, so
     * a storage error must not block the message.
     */
    suspend fun observe(userMessage: String) {
        runCatching { style.learn(userMessage) }
    }

    /**
     * @param userMessage the message that triggered this turn; it decides
     *        which stored facts are relevant.
     */
    suspend fun build(userMessage: String): String {
        val verbList: List<UserVerb> = runCatching { verbs.all() }.getOrDefault(emptyList())
        val facts: List<RecalledFact> = runCatching { memory.recall(userMessage) }
            .getOrDefault(emptyList())
        val profile = runCatching { style.current() }.getOrNull()
        val snapshot = runCatching { environment.snapshot() }.getOrNull()
        return AgentContext.build(
            verbs = verbList,
            facts = facts,
            style = profile,
            environment = snapshot,
        )
    }
}
