package dev.pesnik.roro.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "roro_settings")

data class HermesSettings(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

/** Per-device Hermes connection settings, persisted with DataStore. */
class SettingsRepository(private val context: Context) {

    companion object {
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_MODEL = stringPreferencesKey("model")

        const val DEFAULT_BASE_URL = "https://office.pesnik.dev/hermes/api/v1"
        const val DEFAULT_MODEL = "hermes-agent"
    }

    val settings: Flow<HermesSettings> = context.dataStore.data.map { prefs ->
        HermesSettings(
            baseUrl = prefs[KEY_BASE_URL] ?: DEFAULT_BASE_URL,
            apiKey = prefs[KEY_API_KEY] ?: "",
            model = prefs[KEY_MODEL] ?: DEFAULT_MODEL,
        )
    }

    suspend fun update(baseUrl: String, apiKey: String, model: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = baseUrl
            prefs[KEY_API_KEY] = apiKey
            prefs[KEY_MODEL] = model
        }
    }
}
