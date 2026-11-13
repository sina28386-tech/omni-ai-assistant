package com.omni.aiassistant.data.memory

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.omni.aiassistant.core.agent.RecalledFact
import com.omni.aiassistant.core.agent.UserFact
import com.omni.aiassistant.core.security.KeystoreAead
import com.omni.aiassistant.core.util.Ids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.memoryDataStore by preferencesDataStore(name = "omni_memory")

@Serializable
private data class StoredFacts(val items: List<UserFact> = emptyList())

/**
 * Durable knowledge Arman has about its user.
 *
 * Every fact is something the user stated explicitly. The model may store and
 * recall them, and the user can read or delete them at any time, so the
 * whole list is encrypted with the app key and exposed verbatim in the
 * settings screen.
 */
class UserMemoryRepository(
    private val context: Context,
    private val aead: KeystoreAead,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
    private val key = stringPreferencesKey("facts")
    private var cache: List<UserFact>? = null

    val facts: Flow<List<UserFact>> = context.memoryDataStore.data.map { prefs -> decode(prefs) }

    suspend fun all(): List<UserFact> {
        cache?.let { return it }
        val decoded = decode(context.memoryDataStore.data.first())
        cache = decoded
        return decoded
    }

    suspend fun remember(key: String, value: String, source: String = ""): UserFact {
        val cleanKey = key.trim().lowercase().replace(Regex("[^a-z0-9_\\- ]"), "_").take(60)
        val cleanValue = value.trim().take(500)
        require(cleanKey.isNotEmpty()) { "A memory needs a key" }
        require(cleanValue.isNotEmpty()) { "A memory needs a value" }
        val now = System.currentTimeMillis()
        val current = all().toMutableList()
        val index = current.indexOfFirst { it.key.equals(cleanKey, ignoreCase = true) }
        val fact = UserFact(
            id = current.getOrNull(index)?.id ?: Ids.newId(),
            key = cleanKey,
            value = cleanValue,
            source = source.take(200),
            createdAt = current.getOrNull(index)?.createdAt ?: now,
            updatedAt = now,
        )
        if (index >= 0) current[index] = fact else current += fact
        write(current.takeLast(MAX_FACTS))
        return fact
    }

    suspend fun forget(id: String): Boolean {
        val current = all().toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) write(current)
        return removed
    }

    suspend fun forgetByKey(key: String): Int {
        val current = all().toMutableList()
        val before = current.size
        current.removeAll { it.key.equals(key.trim(), ignoreCase = true) }
        if (current.size != before) write(current)
        return before - current.size
    }

    suspend fun clear() = write(emptyList())

    /**
     * The facts that are relevant to the current message.
     *
     * A fact matches when its key or its value shares a meaningful token with
     * the message. Without a match nothing is injected, so the prompt does
     * not grow with every stored fact.
     */
    suspend fun recall(message: String, limit: Int = 8): List<RecalledFact> {
        val tokens = tokenize(message)
        if (tokens.isEmpty()) return emptyList()
        val scored = all().mapNotNull { fact ->
            val factTokens = tokenize(fact.key) + tokenize(fact.value)
            val hits = factTokens.count { token -> token.length >= 3 && tokens.contains(token) }
            if (hits == 0) null else fact to hits
        }
        return scored
            .sortedByDescending { it.second }
            .take(limit)
            .map { (fact, _) -> RecalledFact(fact.key, fact.value) }
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim() }
            .filter { it.length >= 3 }
            .toSet()

    private suspend fun write(items: List<UserFact>) {
        val payload = StoredFacts(items)
        val encrypted = aead.encrypt(json.encodeToString(payload))
        context.memoryDataStore.edit { prefs -> prefs[key] = encrypted }
        cache = items
    }

    private fun decode(prefs: Preferences): List<UserFact> {
        val raw = prefs[key] ?: return emptyList()
        val plain = runCatching { aead.decrypt(raw) }.getOrNull() ?: return emptyList()
        val stored = runCatching { json.decodeFromString<StoredFacts>(plain) }.getOrNull() ?: return emptyList()
        return stored.items
    }

    private companion object {
        const val MAX_FACTS = 300
    }
}
