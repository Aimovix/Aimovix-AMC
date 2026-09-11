package com.agent.mobile.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.agent.mobile.data.model.ExecutionMode
import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType

class PreferenceManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("amc_secure_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PROVIDER = "key_provider"
        private const val KEY_MODEL = "key_model"
        private const val KEY_API_KEY = "key_api_key"
        private const val KEY_BASE_URL = "key_base_url"
        private const val KEY_EXEC_MODE = "key_exec_mode"
        private const val KEY_AUTH_TOKEN = "key_auth_token"
    }

    fun saveModelConfig(config: ModelConfig) {
        prefs.edit().apply {
            putString(KEY_PROVIDER, config.provider.name)
            putString(KEY_MODEL, config.modelName)
            putString(KEY_API_KEY, config.apiKey)
            putString(KEY_BASE_URL, config.baseUrl)
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

        return ModelConfig(
            provider = provider,
            modelName = modelName,
            apiKey = apiKey,
            baseUrl = baseUrl
        )
    }

    fun saveExecutionMode(mode: ExecutionMode) {
        prefs.edit().putString(KEY_EXEC_MODE, mode.name).apply()
    }

    fun loadExecutionMode(): ExecutionMode {
        val modeName = prefs.getString(KEY_EXEC_MODE, ExecutionMode.AUTOPILOT.name)
        return try {
            ExecutionMode.valueOf(modeName ?: ExecutionMode.AUTOPILOT.name)
        } catch (e: Exception) {
            ExecutionMode.AUTOPILOT
        }
    }

    fun saveAuthToken(token: String) {
        prefs.edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    fun loadAuthToken(): String {
        return prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
    }
}
