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
        private const val KEY_API_KEY_PREFIX = "pref_api_key_"
        private const val KEY_BASE_URL_PREFIX = "pref_base_url_"
        private const val KEY_MODEL_PREFIX = "pref_model_"
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

    data class ProviderProfile(
        val apiKey: String,
        val baseUrl: String,
        val modelName: String
    )

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
                editor.apply()
                oldPrefs.edit().clear().apply()
            }
        } catch (e: Exception) {
            // Ignore migration failure if old prefs inaccessible
        }
    }

    fun saveProviderProfile(provider: ProviderType, apiKey: String, baseUrl: String, modelName: String) {
        prefs.edit().apply {
            putString("${KEY_API_KEY_PREFIX}${provider.name}", apiKey)
            putString("${KEY_BASE_URL_PREFIX}${provider.name}", baseUrl)
            putString("${KEY_MODEL_PREFIX}${provider.name}", modelName)
            apply()
        }
    }

    fun loadProviderProfile(provider: ProviderType): ProviderProfile {
        val apiKey = prefs.getString("${KEY_API_KEY_PREFIX}${provider.name}", "") ?: ""
        val baseUrl = prefs.getString("${KEY_BASE_URL_PREFIX}${provider.name}", provider.defaultBaseUrl) ?: provider.defaultBaseUrl
        var model = prefs.getString("${KEY_MODEL_PREFIX}${provider.name}", provider.defaultModel) ?: provider.defaultModel
        // Migrate deprecated defaults
        if (provider == ProviderType.GEMINI && model == "gemini-2.0-flash") {
            model = provider.defaultModel
        } else if (provider == ProviderType.CLAUDE && model == "claude-3-7-sonnet-20250219") {
            model = provider.defaultModel
        }
        return ProviderProfile(apiKey = apiKey, baseUrl = baseUrl, modelName = model)
    }

    fun saveModelConfig(config: ModelConfig) {
        saveProviderProfile(config.provider, config.apiKey, config.baseUrl, config.modelName)
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
        val profile = loadProviderProfile(provider)
        var effectiveBaseUrl = profile.baseUrl
        var effectiveModelName = profile.modelName
        val legacyProvider = prefs.getString(KEY_PROVIDER, "")
        if (legacyProvider == provider.name) {
            val legacyBaseUrl = prefs.getString(KEY_BASE_URL, "") ?: ""
            if (legacyBaseUrl.isNotEmpty() && !prefs.contains("${KEY_BASE_URL_PREFIX}${provider.name}")) {
                effectiveBaseUrl = legacyBaseUrl
            }
            val legacyModel = prefs.getString(KEY_MODEL, "") ?: ""
            if (legacyModel.isNotEmpty() && !prefs.contains("${KEY_MODEL_PREFIX}${provider.name}")) {
                effectiveModelName = legacyModel
            }
        }
        val effectiveApiKey = profile.apiKey.ifEmpty {
            val legacyKey = prefs.getString(KEY_API_KEY, "") ?: ""
            if (legacyKey.isNotEmpty() && legacyProvider == provider.name) {
                saveProviderProfile(provider, legacyKey, effectiveBaseUrl, effectiveModelName)
                legacyKey
            } else ""
        }

        val fallbackProviderName = prefs.getString(KEY_FALLBACK_PROVIDER, "") ?: ""
        val fallbackProvider = if (fallbackProviderName.isNotEmpty()) {
            try { ProviderType.valueOf(fallbackProviderName) } catch (e: Exception) { null }
        } else null
        val fallbackModelName = prefs.getString(KEY_FALLBACK_MODEL, "") ?: ""
        val fallbackApiKey = prefs.getString(KEY_FALLBACK_API_KEY, "") ?: ""
        val fallbackBaseUrl = prefs.getString(KEY_FALLBACK_BASE_URL, "") ?: ""

        return ModelConfig(
            provider = provider,
            modelName = effectiveModelName,
            apiKey = effectiveApiKey,
            baseUrl = effectiveBaseUrl,
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
