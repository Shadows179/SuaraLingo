package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "suaralingo_prefs")

data class TranslatorPreferences(
    val sourceLangCode: String = TranslateLanguage.INDONESIAN,
    val targetLangCode: String = TranslateLanguage.ENGLISH,
    val isOfflineMode: Boolean = false,
    val autoSpeak: Boolean = true,
    val updateRepoSlug: String = "",
    val autoUpdateCheck: Boolean = true,
    val lastUpdateCheckMs: Long = 0L
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val SOURCE_LANG = stringPreferencesKey("source_lang")
        val TARGET_LANG = stringPreferencesKey("target_lang")
        val OFFLINE_MODE = booleanPreferencesKey("offline_mode")
        val AUTO_SPEAK = booleanPreferencesKey("auto_speak")
        val UPDATE_REPO = stringPreferencesKey("update_repo")
        val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val LAST_UPDATE_CHECK_MS = longPreferencesKey("last_update_check_ms")
    }

    val preferencesFlow: Flow<TranslatorPreferences> = context.dataStore.data.map { prefs ->
        TranslatorPreferences(
            sourceLangCode = prefs[Keys.SOURCE_LANG] ?: TranslateLanguage.INDONESIAN,
            targetLangCode = prefs[Keys.TARGET_LANG] ?: TranslateLanguage.ENGLISH,
            isOfflineMode = prefs[Keys.OFFLINE_MODE] ?: false,
            autoSpeak = prefs[Keys.AUTO_SPEAK] ?: true,
            updateRepoSlug = prefs[Keys.UPDATE_REPO] ?: "",
            autoUpdateCheck = prefs[Keys.AUTO_UPDATE_CHECK] ?: true,
            lastUpdateCheckMs = prefs[Keys.LAST_UPDATE_CHECK_MS] ?: 0L
        )
    }

    suspend fun setSourceLang(code: String) {
        context.dataStore.edit { it[Keys.SOURCE_LANG] = code }
    }

    suspend fun setTargetLang(code: String) {
        context.dataStore.edit { it[Keys.TARGET_LANG] = code }
    }

    suspend fun swapLanguages(newSource: String, newTarget: String) {
        context.dataStore.edit {
            it[Keys.SOURCE_LANG] = newSource
            it[Keys.TARGET_LANG] = newTarget
        }
    }

    suspend fun setOfflineMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.OFFLINE_MODE] = enabled }
    }

    suspend fun setAutoSpeak(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_SPEAK] = enabled }
    }

    suspend fun setUpdateRepo(repoSlug: String) {
        context.dataStore.edit { it[Keys.UPDATE_REPO] = repoSlug }
    }

    suspend fun setAutoUpdateCheck(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_UPDATE_CHECK] = enabled }
    }

    suspend fun setLastUpdateCheckMs(timestampMs: Long) {
        context.dataStore.edit { it[Keys.LAST_UPDATE_CHECK_MS] = timestampMs }
    }
}
