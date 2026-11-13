package com.omni.aiassistant.core.agent

import kotlinx.serialization.Serializable

/**
 * One durable fact Arman learned about the user.
 *
 * Facts are only stored when the user said them explicitly. The model never
 * infers a fact on its own.
 */
@Serializable
data class UserFact(
    val id: String,
    val key: String,
    val value: String,
    val source: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * A single memory the model may recall.
 *
 * The key is a short machine-ish slug ("favorite_music"), the value is the
 * human content ("حسین علیزاده"). Arman receives the facts whose key or
 * value overlaps with the current message, so the prompt stays small.
 */
data class RecalledFact(val key: String, val value: String)
