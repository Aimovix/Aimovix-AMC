package com.agent.mobile.data.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.agent.mobile.data.model.ExecutionMode
import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType

class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "amc_encrypted_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        throw IllegalStateException("Secure storage is unavailable. Unlock the device and try again. Credentials were not saved.", e)
    }

    companion object {
        private const val KEY_PROVIDER = "key_provider"
        private const val KEY_MODEL = "key_model"
        private const val KEY_API_KEY = "key_api_key"
        private const val KEY_BASE_URL = "key_base_url"
        private const val KEY_FALLBACK_PROVIDER = "key_fallback_provider"
        private const val KEY_FALLBACK_MODEL = "key_fallback_model"
        private const val KEY_FALLBACK_API_KEY = "key_fallback_api_key"
        private const val KEY_FALLBACK_BASE_URL = "key_fallback_base_url"
        private const val KEY_EXEC_MODE = "key_exec_mode"
        private const val KEY_AUTH_TOKEN = "key_auth_token"
        private const val KEY_WHITELIST = "key_whitelist"
        private const val KEY_BLACKLIST = "key_blacklist"
        private const val KEY_STRICT_MODE = "key_strict_mode"
        private const val KEY_ACTIVE_SESSION_ID = "key_active_session_id"
    }

    init {
        migrateOldPrefs(context)
    }

    private fun migrateOldPrefs(context: Context) {
        try {
            val oldPrefs = context.getSharedPreferences("amc_secure_prefs", Context.MODE_PRIVATE)
            if (oldPrefs.all.isNotEmpty() && prefs !== oldPrefs) {
                val editor = prefs.edit()
                oldPrefs.all.forEach { (key, value) ->
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                    }
                }
                check(editor.commit()) { "Could not migrate credentials to encrypted storage." }
                oldPrefs.edit().clear().commit()
            }
        } catch (e: Exception) {
            Log.w("PreferenceManager", "Migration from legacy preferences failed: ${e.message}")
        }
    }

    fun saveModelConfig(config: ModelConfig) {
        prefs.edit().apply {
            putString(KEY_PROVIDER, config.provider.name)
            putString(KEY_MODEL, config.modelName)
            putString(KEY_API_KEY, config.apiKey)
            putString(KEY_BASE_URL, config.baseUrl)
            putString(KEY_FALLBACK_PROVIDER, config.fallbackProvider?.name ?: "")
            putString(KEY_FALLBACK_MODEL, config.fallbackModelName)
            putString(KEY_FALLBACK_API_KEY, config.fallbackApiKey)
            putString(KEY_FALLBACK_BASE_URL, config.fallbackBaseUrl)
            apply()
        }
    }

    fun loadModelConfig(): ModelConfig {
        val providerName = prefs.getString(KEY_PROVIDER, ProviderType.GEMINI.name) ?: ProviderType.GEMINI.name
        val provider = try {
            ProviderType.valueOf(providerName)
        } catch (e: Exception) {
            ProviderType.GEMINI
        }
        val modelName = prefs.getString(KEY_MODEL, provider.defaultModel) ?: provider.defaultModel
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val baseUrl = prefs.getString(KEY_BASE_URL, provider.defaultBaseUrl) ?: provider.defaultBaseUrl

        val fallbackProviderName = prefs.getString(KEY_FALLBACK_PROVIDER, "") ?: ""
        val fallbackProvider = if (fallbackProviderName.isNotEmpty()) {
            try { ProviderType.valueOf(fallbackProviderName) } catch (e: Exception) { null }
        } else null
        val fallbackModelName = prefs.getString(KEY_FALLBACK_MODEL, "") ?: ""
        val fallbackApiKey = prefs.getString(KEY_FALLBACK_API_KEY, "") ?: ""
        val fallbackBaseUrl = prefs.getString(KEY_FALLBACK_BASE_URL, "") ?: ""

        return ModelConfig(
            provider = provider,
            modelName = modelName,
            apiKey = apiKey,
            baseUrl = baseUrl,
            fallbackProvider = fallbackProvider,
            fallbackModelName = fallbackModelName,
            fallbackApiKey = fallbackApiKey,
            fallbackBaseUrl = fallbackBaseUrl
        )
    }

    fun saveExecutionMode(mode: ExecutionMode) {
        prefs.edit().putString(KEY_EXEC_MODE, mode.name).apply()
    }

    fun loadExecutionMode(): ExecutionMode {
        val modeName = prefs.getString(KEY_EXEC_MODE, ExecutionMode.STEP_BY_STEP.name)
        return try {
            ExecutionMode.valueOf(modeName ?: ExecutionMode.AUTOPILOT.name)
        } catch (e: Exception) {
            ExecutionMode.STEP_BY_STEP
        }
    }

    fun saveAuthToken(token: String) {
        prefs.edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    fun loadAuthToken(): String {
        return prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
    }

    fun saveWhitelist(patterns: List<String>) {
        prefs.edit().putString(KEY_WHITELIST, patterns.joinToString("\n")).apply()
    }

    fun loadWhitelist(): List<String> {
        val raw = prefs.getString(KEY_WHITELIST, "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun saveBlacklist(patterns: List<String>) {
        prefs.edit().putString(KEY_BLACKLIST, patterns.joinToString("\n")).apply()
    }

    fun loadBlacklist(): List<String> {
        val raw = prefs.getString(KEY_BLACKLIST, "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun saveStrictMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STRICT_MODE, enabled).apply()
    }

    fun loadStrictMode(): Boolean {
        return prefs.getBoolean(KEY_STRICT_MODE, false)
    }

    fun saveActiveSessionId(sessionId: String) {
        prefs.edit().putString(KEY_ACTIVE_SESSION_ID, sessionId).apply()
    }

    fun loadActiveSessionId(): String? {
        return prefs.getString(KEY_ACTIVE_SESSION_ID, null)
    }
}
