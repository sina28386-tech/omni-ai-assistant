package com.omni.aiassistant.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseIntegrityTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    @Test fun upsertDoesNotTriggerForeignKeyDeleteSideEffects() = runBlocking {
        val provider = ProviderProfileEntity(
            "p1", "Provider", "OPENAI", "https://api.example.com/v1", "v1:", "model", null, "v1:", 0.7,
            100, 30, false, true, true, "v1:", "/chat/completions", "AUTO", 1, 1,
        )
        db.providerDao().upsert(provider)
        val conversation = ConversationEntity("c1", "v1:", "p1", "v1:", false, false, 1, 1)
        db.conversationDao().upsert(conversation)
        val message = MessageEntity("m1", "c1", "USER", "v1:", "COMPLETE", 1, 1, 1, null, null, null, null)
        db.messageDao().upsert(message)
        val attachment = AttachmentEntity("a1", "m1", "/tmp/a.jpg", "image/jpeg", 1, 1, 1, "00")
        db.attachmentDao().upsert(attachment)

        db.providerDao().upsert(provider.copy(name = "Edited", updatedAt = 2))
        assertEquals("p1", db.conversationDao().getById("c1")?.providerProfileId)

        db.messageDao().upsert(message.copy(updatedAt = 2))
        assertEquals(1, db.attachmentDao().getForMessages(listOf("m1")).size)
    }

    @Test fun deletingConversationCascadesMessagesAndAttachments() = runBlocking {
        val conversation = ConversationEntity("c1", "v1:", null, "v1:", false, false, 1, 1)
        db.conversationDao().upsert(conversation)
        db.messageDao().upsert(MessageEntity("m1", "c1", "USER", "v1:", "COMPLETE", 1, 1, 1, null, null, null, null))
        db.attachmentDao().upsert(AttachmentEntity("a1", "m1", "/tmp/a.jpg", "image/jpeg", 1, 1, 1, "00"))
        db.conversationDao().delete("c1")
        assertNull(db.messageDao().getById("m1"))
        assertTrue(db.attachmentDao().getForMessages(listOf("m1")).isEmpty())
    }
}
