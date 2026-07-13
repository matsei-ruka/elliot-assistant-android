package com.openclaw.assistant.backend

/** Pure write-only credential update rule shared by onboarding and settings. */
object WriteOnlyCredential {
    fun resolve(entry: String, stored: String?, clear: Boolean = false): String? = when {
        clear -> null
        entry.isNotBlank() -> entry.trim()
        else -> stored
    }
}
