package com.openclaw.assistant.utils

data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val downloadUrl: String
)

object UpdateChecker {

    /**
     * Disabled in the CTB build. The upstream check pointed at the
     * yuga-hashimoto/openclaw-assistant releases feed and fed an APK install
     * flow whose REQUEST_INSTALL_PACKAGES permission was removed by
     * Spec 001 §C; following it would reinstall the unrestricted upstream
     * surface. This fork is updated from source only.
     */
    suspend fun checkUpdate(currentVersion: String): UpdateInfo? = null
}
