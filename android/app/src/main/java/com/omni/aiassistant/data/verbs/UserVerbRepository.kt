package com.omni.aiassistant.data.verbs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.omni.aiassistant.core.agent.VerbAction
import com.omni.aiassistant.core.agent.UserVerb
import com.omni.aiassistant.core.agent.normalizeWakePhrase
import com.omni.aiassistant.core.security.KeystoreAead
import com.omni.aiassistant.core.util.Ids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.verbDataStore by preferencesDataStore(name = "omni_verbs")

@Serializable
private data class StoredVerb(
    val id: String,
    val phrase: String,
    val action: String,
    val target: String? = null,
    val createdAt: Long = 0L,
)

@Serializable
private data class StoredVerbs(val items: List<StoredVerb> = emptyList())

/**
 * The user's custom verb dictionary.
 *
 * The whole list is encrypted with the app key, because a verb may encode
 * private information ("the word for my bank's app"). Reads are cheap and
 * cached in memory; writes are transactional.
 */
class UserVerbRepository(
    private val context: Context,
    private val aead: KeystoreAead,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }
    private val key = stringPreferencesKey("verbs")
    private var cache: List<UserVerb>? = null

    val verbs: Flow<List<UserVerb>> = context.verbDataStore.data.map { prefs -> decode(prefs) }

    suspend fun all(): List<UserVerb> {
        cache?.let { return it }
        val decoded = decode(context.verbDataStore.data.first())
        cache = decoded
        return decoded
    }

    /**
     * Adds or replaces a verb.
     *
     * A phrase that normalises to the same text replaces the previous entry,
     * so the user can redefine "تیک" without ending up with two of them.
     */
    suspend fun upsert(phrase: String, action: VerbAction, target: String? = null): UserVerb {
        val clean = phrase.trim().take(80)
        require(clean.isNotEmpty()) { "A verb needs a phrase" }
        val normalized = normalizeWakePhrase(clean)
        require(normalized.isNotEmpty()) { "A verb needs at least one letter" }
        val now = System.currentTimeMillis()
        val current = all().toMutableList()
        val index = current.indexOfFirst { normalizeWakePhrase(it.phrase) == normalized }
        val verb = UserVerb(
            id = current.getOrNull(index)?.id ?: Ids.newId(),
            phrase = clean,
            action = action,
            target = target?.trim()?.take(200)?.takeIf { it.isNotEmpty() },
            createdAt = current.getOrNull(index)?.createdAt ?: now,
        )
        if (index >= 0) current[index] = verb else current += verb
        write(current)
        return verb
    }

    suspend fun remove(id: String): Boolean {
        val current = all().toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) write(current)
        return removed
    }

    suspend fun clear() = write(emptyList())

    private suspend fun write(items: List<UserVerb>) {
        val payload = StoredVerbs(
            items = items.map {
                StoredVerb(
                    id = it.id,
                    phrase = it.phrase,
                    action = it.action.name,
                    target = it.target,
                    createdAt = it.createdAt,
                )
            },
        )
        val encrypted = aead.encrypt(json.encodeToString(payload))
        context.verbDataStore.edit { prefs -> prefs[key] = encrypted }
        cache = items
    }

    private fun decode(prefs: Preferences): List<UserVerb> {
        val raw = prefs[key] ?: return emptyList()
        val plain = runCatching { aead.decrypt(raw) }.getOrNull() ?: return emptyList()
        val stored = runCatching { json.decodeFromString<StoredVerbs>(plain) }.getOrNull() ?: return emptyList()
        return stored.items.mapNotNull { item ->
            val action = runCatching { VerbAction.valueOf(item.action) }.getOrNull() ?: return@mapNotNull null
            UserVerb(
                id = item.id,
                phrase = item.phrase,
                action = action,
                target = item.target,
                createdAt = item.createdAt,
            )
        }
    }
}
