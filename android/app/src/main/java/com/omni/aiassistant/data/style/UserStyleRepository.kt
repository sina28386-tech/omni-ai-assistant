package com.omni.aiassistant.data.style

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.omni.aiassistant.core.agent.StyleLearner
import com.omni.aiassistant.core.agent.UserStyle
import com.omni.aiassistant.core.security.KeystoreAead
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.styleDataStore by preferencesDataStore(name = "omni_style")

/**
 * The learned communication profile, stored encrypted next to the facts.
 *
 * The repository is the only writer: the learner proposes updates, the
 * repository merges them, and the prompt reads the merged value. Nothing here
 * is inferred beyond what the user literally said, and [clear] wipes it.
 */
class UserStyleRepository(
    private val context: Context,
    private val aead: KeystoreAead,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
    private val key = stringPreferencesKey("profile")
    private val mutex = Mutex()
    private var cache: UserStyle? = null

    val profile: Flow<UserStyle> = context.styleDataStore.data.map(::decode)

    suspend fun current(): UserStyle {
        cache?.let { return it }
        val decoded = decode(context.styleDataStore.data.first())
        cache = decoded
        return decoded
    }

    /**
     * Applies the explicit signals found in one user message.
     *
     * Returns the merged profile so the caller can build the prompt from the
     * exact value that was just persisted.
     */
    suspend fun learn(text: String): UserStyle {
        val updates = StyleLearner.observe(text)
        if (updates.isEmpty()) return current()
        return apply(updates)
    }

    suspend fun apply(updates: List<StyleLearner.Update>): UserStyle = mutex.withLock {
        val now = System.currentTimeMillis()
        var next = current()
        // Later updates in the same message win, which matches the order of
        // the sentence: "کوتاه جواب بده، نه، بیشتر توضیح بده".
        updates.forEach { next = next.with(it, now) }
        write(next)
        next
    }

    suspend fun clear(): UserStyle = mutex.withLock {
        val empty = UserStyle()
        write(empty)
        empty
    }

    private suspend fun write(style: UserStyle) {
        val encrypted = aead.encrypt(json.encodeToString(style))
        context.styleDataStore.edit { prefs -> prefs[key] = encrypted }
        cache = style
    }

    private fun decode(prefs: Preferences): UserStyle {
        val raw = prefs[key] ?: return UserStyle()
        val plain = runCatching { aead.decrypt(raw) }.getOrNull() ?: return UserStyle()
        return runCatching { json.decodeFromString<UserStyle>(plain) }.getOrDefault(UserStyle())
    }
}
