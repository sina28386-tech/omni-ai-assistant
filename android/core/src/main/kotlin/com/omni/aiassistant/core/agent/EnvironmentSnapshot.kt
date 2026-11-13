package com.omni.aiassistant.core.agent

/**
 * What Arman can see of the device at this moment.
 *
 * The snapshot is deliberately a plain value object: the Android side collects
 * it, the prompt builder turns it into text, and both are testable without a
 * device. App labels are user-controlled data and must never be treated as
 * instructions by the model.
 */
data class EnvironmentSnapshot(
    val deviceLabel: String = "",
    val androidVersion: String = "",
    /** BCP-47 language tag of the device, e.g. "fa-IR". */
    val languageTag: String = "",
    /** Launchable app labels, most useful first, already capped by the collector. */
    val apps: List<String> = emptyList(),
    /** Total number of launchable apps, so "and N more" can be honest. */
    val appCount: Int = 0,
    /** Capability names that are currently usable. */
    val granted: List<String> = emptyList(),
    /** Capability names that are blocked, with the human-readable reason. */
    val blocked: Map<String, String> = emptyMap(),
    val pcBridgeReady: Boolean = false,
) {
    val isEmpty: Boolean
        get() = deviceLabel.isBlank() && apps.isEmpty() && granted.isEmpty() && blocked.isEmpty()
}
