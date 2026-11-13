package com.omni.aiassistant.qa

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.domain.repository.ConversationRepository
import com.omni.aiassistant.feature.voice.VoiceViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** No speech service or paid model: the canonical chat owns provider/tool execution. */
@RunWith(AndroidJUnit4::class)
class QaVoiceHandoffTest {
    @Test
    fun transcriptIsHandedOffOnceWithoutAShadowModelConversation() = runBlocking {
        val koin = GlobalContext.get()
        val conversations = koin.get<ConversationRepository>()
        val model = withContext(Dispatchers.Main) {
            VoiceViewModel(conversations, koin.get<SettingsRepository>(), koin.get())
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collector = scope.launch { model.state.collect() }
        var id: String? = null
        try {
            withContext(Dispatchers.Main) {
                model.setRecognizedText("  فایل گزارش را پیدا کن  ")
                model.submit()
                model.submit()
            }
            val handoff = withTimeout(10_000) { model.state.first { it.pendingCommand != null } }.pendingCommand!!
            id = handoff.conversationId
            assertEquals("فایل گزارش را پیدا کن", handoff.text)
            assertNotNull(conversations.get(handoff.conversationId))
            assertTrue(conversations.recentContext(handoff.conversationId, 10).isEmpty())
            withContext(Dispatchers.Main) { model.consumeCommand() }
            withTimeout(5_000) { model.state.first { it.pendingCommand == null && !it.loading } }
            withContext(Dispatchers.Main) { model.submit() }
            val second = withTimeout(5_000) { model.state.first { it.pendingCommand != null } }.pendingCommand!!
            assertEquals("Voice follow-up must keep the selected conversation", handoff.conversationId, second.conversationId)
        } finally {
            collector.cancel()
            scope.cancel()
            id?.let { conversations.deleteConversation(it) }
        }
    }
}
