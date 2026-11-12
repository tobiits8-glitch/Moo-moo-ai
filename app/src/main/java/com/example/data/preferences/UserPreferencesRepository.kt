package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.MooMooModelTier
import com.example.model.MooMooMood
import com.example.ui.theme.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "moomoo_prefs")

data class UserPreferences(
    val mood: MooMooMood = MooMooMood.CLASSIC,
    val modelTier: MooMooModelTier = MooMooModelTier.SMART,
    val customInstruction: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)

class UserPreferencesRepository(private val context: Context) {
    private object Keys {
        val MOOD_ID = stringPreferencesKey("mood_id")
        val MODEL_TIER_ID = stringPreferencesKey("model_tier_id")
        val CUSTOM_INSTRUCTION = stringPreferencesKey("custom_instruction")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val preferences: Flow<UserPreferences> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            val mood = MooMooMood.fromId(prefs[Keys.MOOD_ID] ?: MooMooMood.CLASSIC.id)
            val modelTier = MooMooModelTier.fromId(prefs[Keys.MODEL_TIER_ID] ?: MooMooModelTier.SMART.id)
            val custom = prefs[Keys.CUSTOM_INSTRUCTION] ?: ""
            val theme = runCatching {
                ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name)
            }.getOrDefault(ThemeMode.SYSTEM)
            UserPreferences(
                mood = mood,
                modelTier = modelTier,
                customInstruction = custom,
                themeMode = theme
            )
        }

    suspend fun setMood(mood: MooMooMood) {
        context.dataStore.edit { it[Keys.MOOD_ID] = mood.id }
    }

    suspend fun setModelTier(tier: MooMooModelTier) {
        context.dataStore.edit { it[Keys.MODEL_TIER_ID] = tier.id }
    }

    suspend fun setCustomInstruction(instruction: String) {
        context.dataStore.edit { it[Keys.CUSTOM_INSTRUCTION] = instruction }
    }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = themeMode.name }
    }
}
