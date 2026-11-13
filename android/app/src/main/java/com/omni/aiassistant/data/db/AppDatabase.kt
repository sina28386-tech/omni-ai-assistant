package com.omni.aiassistant.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProviderProfileEntity::class, ConversationEntity::class, MessageEntity::class, AttachmentEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        /**
         * v2 adds agent tool calling:
         * - messages carry the calls requested by the assistant and the result
         *   payload of each `role=tool` message;
         * - providers gain a `supportsTools` switch because several
         *   OpenAI-compatible models reject the `tools` field outright.
         *
         * Defaults are deliberately conservative (empty calls, tools enabled)
         * so an upgrade never leaves an unusable transcript behind.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN encryptedToolCallsJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN toolCallId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN toolName TEXT")
                db.execSQL("ALTER TABLE provider_profiles ADD COLUMN supportsTools INTEGER NOT NULL DEFAULT 1")
            }
        }
    }
}
