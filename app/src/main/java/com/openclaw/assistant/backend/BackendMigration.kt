package com.openclaw.assistant.backend

import android.content.Context
import com.openclaw.assistant.SecurePrefs
import com.openclaw.assistant.data.SettingsRepository

/**
 * One-shot migration that inspects legacy single-backend settings (OpenClaw
 * Gateway manual config from [SecurePrefs] and the OpenClaw HTTP url from
 * [SettingsRepository]) and synthesises [AgentBackendConfig] entries.
 *
 * Idempotent: if [BackendRepository] already has at least one matching
 * backend, the migration leaves it alone. Existing users keep working without
 * being forced through setup; the primary is chosen deterministically:
 *
 *  1. OpenClaw Gateway if a host/port pair is present (typical full setup)
 *  2. otherwise OpenClaw HTTP if a baseUrl is present
 */
object BackendMigration {
    fun runIfNeeded(context: Context, repo: BackendRepository = BackendRepository.getInstance(context)): MigrationResult {
        if (repo.backends.value.isNotEmpty()) {
            // A previous version may already have populated BackendRepository
            // while leaving the legacy duplicate behind. The repository is now
            // authoritative even when no new migration is needed.
            runCatching { SettingsRepository(context) }.getOrNull()?.let(::clearLegacyHttp)
            return MigrationResult.AlreadyMigrated
        }

        val securePrefs = runCatching { SecurePrefs(context) }.getOrNull()
        val settings = runCatching { SettingsRepository(context) }.getOrNull()

        val created = mutableListOf<AgentBackendConfig>()

        val host = securePrefs?.manualHost?.value?.trim().orEmpty()
        val port = securePrefs?.manualPort?.value ?: 0
        val tls = securePrefs?.manualTls?.value ?: false
        val token = securePrefs?.gatewayToken?.value?.trim().orEmpty()
        if (host.isNotEmpty() && port in 1..65535) {
            created += AgentBackendConfig(
                displayName = "OpenClaw Gateway",
                type = BackendType.OPENCLAW_GATEWAY,
                host = host,
                port = port,
                useTls = tls,
                apiKeyOrToken = token.takeIf { it.isNotEmpty() },
                isPrimary = true,
            )
        }

        val httpUrl = settings?.httpUrl?.trim().orEmpty()
        if (httpUrl.isNotEmpty()) {
            created += AgentBackendConfig(
                displayName = "OpenClaw HTTP",
                type = BackendType.OPENCLAW_HTTP,
                baseUrl = httpUrl,
                apiKeyOrToken = settings?.authToken?.takeIf { it.isNotBlank() },
                isPrimary = created.isEmpty(),
            )
        }

        if (created.isEmpty()) return MigrationResult.NothingToMigrate
        created.forEach(repo::upsert)
        // BackendRepository is authoritative after migration. Remove the
        // duplicated legacy HTTP credential so future UI/code cannot drift or
        // accidentally copy CTB secrets between stores.
        if (created.any { it.type == BackendType.OPENCLAW_HTTP }) {
            settings?.let(::clearLegacyHttp)
        }
        return MigrationResult.Migrated(created.size)
    }

    private fun clearLegacyHttp(settings: SettingsRepository) {
        settings.authToken = ""
        settings.httpUrl = ""
    }

    sealed class MigrationResult {
        object AlreadyMigrated : MigrationResult()
        object NothingToMigrate : MigrationResult()
        data class Migrated(val count: Int) : MigrationResult()
    }
}
