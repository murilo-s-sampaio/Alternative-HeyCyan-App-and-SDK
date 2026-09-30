package com.fersaiyan.cyanbridge.shared.platform

/**
 * Platform hooks behind the shared Settings screen. The iOS host installs them;
 * Android keeps SettingsActivity and leaves them unset.
 */
interface SharedSettingsPlatform {
    fun openAppLanguageSettings()
    fun exportLocalData()
    fun importLocalData()
    fun importChatGptData()
    fun importClaudeData()
    fun clearLocalData()
    fun sendDebugLogs()
    fun stopMeetingCapture()

    /** True when a vault passphrase is stored. */
    fun vaultHasPassphrase(): Boolean

    /** Prompts for a new passphrase; returns true when one was saved. */
    suspend fun setVaultPassphrase(): Boolean
    suspend fun clearVaultPassphrase(): Boolean

    /** Prompts for the passphrase when one is set; returns true when unlocked. */
    suspend fun unlockVault(): Boolean
}

object SharedSettingsHooks {
    var platform: SharedSettingsPlatform? = null
}
