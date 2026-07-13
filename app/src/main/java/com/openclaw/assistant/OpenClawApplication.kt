package com.openclaw.assistant

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.openclaw.assistant.backend.BackendMigration
import com.openclaw.assistant.backend.AgentDiagnostics
import com.openclaw.assistant.bridge.BridgeActivityLog
import com.openclaw.assistant.bridge.WakeLockManager
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.node.NodeRuntime
import java.security.Security

class OpenClawApplication : Application() {

    @Volatile private var _nodeRuntime: NodeRuntime? = null

    /**
     * Returns the runtime, initializing it if needed.
     * Call this from Activities/ViewModels to ensure the runtime exists.
     */
    fun ensureRuntime(): NodeRuntime {
        _nodeRuntime?.let { return it }
        return synchronized(this) {
            _nodeRuntime ?: NodeRuntime(this).also { _nodeRuntime = it }
        }
    }

    /**
     * Returns the runtime if already initialized, or null if it has not been created yet.
     * Use this in places that must NOT force initialization (e.g. foreground service).
     */
    fun peekRuntime(): NodeRuntime? = _nodeRuntime

    /**
     * Retained for backwards compatibility with existing call sites.
     * Equivalent to [ensureRuntime].
     */
    val nodeRuntime: NodeRuntime get() = ensureRuntime()

    override fun onCreate() {
        super.onCreate()
        applySavedAppLocale()
        // Register Bouncy Castle as highest-priority provider for Ed25519 support
        try {
            val bcProvider = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                .getDeclaredConstructor().newInstance() as java.security.Provider
            Security.removeProvider("BC")
            Security.insertProviderAt(bcProvider, 1)
        } catch (e: Throwable) {
            Log.e("OpenClawApp", "Failed to register Bouncy Castle provider", e)
        }

        // One-shot migration of legacy OpenClaw single-backend settings into the
        // multi-backend repository. Safe to call on every cold start: it no-ops
        // once any backend is registered.
        try {
            BackendMigration.runIfNeeded(this)
        } catch (e: Throwable) {
            Log.w("OpenClawApp", "Backend migration skipped: ${e.message}")
        }
        AgentDiagnostics.initialize(this)
        BridgeActivityLog.initialize(this)
        WakeLockManager.initialize(this)
    }

    private fun applySavedAppLocale() {
        val tag = SettingsRepository.getInstance(this).appLanguage.trim()
        val locales = if (tag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
