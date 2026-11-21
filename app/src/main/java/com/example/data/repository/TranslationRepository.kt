package com.example.data.repository

import com.example.data.local.TranslationDao
import com.example.data.local.TranslationRecord
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OfflineModelMissingException(
    val missingLanguageCodes: List<String>
) : IllegalStateException("Paket bahasa offline belum diunduh: ${missingLanguageCodes.joinToString()}")

data class TranslationOutcome(
    val translatedText: String,
    val usedOfflineModel: Boolean
)

class TranslationRepository(
    private val translationDao: TranslationDao
) {
    companion object {
        const val MAX_TRANSLATE_CHARS = 2000
    }

    private val modelManager: RemoteModelManager by lazy { RemoteModelManager.getInstance() }
    private val translatorCache = ConcurrentHashMap<Pair<String, String>, Translator>()
    private val validLanguageCodes: Set<String> by lazy { TranslateLanguage.getAllLanguages().toSet() }

    private val _downloadedModels = MutableStateFlow<Set<String>>(emptySet())
    val downloadedModels: StateFlow<Set<String>> = _downloadedModels.asStateFlow()

    private val _downloadingModels = MutableStateFlow<Set<String>>(emptySet())
    val downloadingModels: StateFlow<Set<String>> = _downloadingModels.asStateFlow()

    val historyFlow: Flow<List<TranslationRecord>> = translationDao.observeAll()
    val favoritesFlow: Flow<List<TranslationRecord>> = translationDao.observeFavorites()

    fun isSupportedLanguageCode(code: String): Boolean {
        return validLanguageCodes.contains(code)
    }

    suspend fun refreshDownloadedModels(): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            val models = modelManager.getDownloadedModels(TranslateRemoteModel::class.java).awaitTask()
            val codes = models.map { it.language }.toSet()
            _downloadedModels.value = codes
            codes
        }.getOrElse { _downloadedModels.value }
    }

    suspend fun isPairOfflineReady(sourceCode: String, targetCode: String): Boolean {
        if (!isSupportedLanguageCode(sourceCode) || !isSupportedLanguageCode(targetCode)) return false
        val current = refreshDownloadedModels()
        return current.contains(sourceCode) && current.contains(targetCode)
    }

    suspend fun downloadLanguageModel(langCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isSupportedLanguageCode(langCode)) {
            return@withContext Result.failure(IllegalArgumentException("Kode bahasa tidak valid: $langCode"))
        }
        _downloadingModels.update { it + langCode }
        try {
            val model = TranslateRemoteModel.Builder(langCode).build()
            val conditions = DownloadConditions.Builder().build()
            modelManager.download(model, conditions).awaitTask()
            refreshDownloadedModels()
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            _downloadingModels.update { it - langCode }
        }
    }

    suspend fun downloadPairModels(sourceCode: String, targetCode: String): Result<Unit> {
        if (!isSupportedLanguageCode(sourceCode) || !isSupportedLanguageCode(targetCode)) {
            return Result.failure(IllegalArgumentException("Kode bahasa tidak valid"))
        }
        val current = refreshDownloadedModels()
        if (!current.contains(sourceCode)) {
            val res = downloadLanguageModel(sourceCode)
            if (res.isFailure) return res
        }
        if (targetCode != sourceCode && !current.contains(targetCode)) {
            val res = downloadLanguageModel(targetCode)
            if (res.isFailure) return res
        }
        return Result.success(Unit)
    }

    suspend fun deleteLanguageModel(langCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isSupportedLanguageCode(langCode)) {
            return@withContext Result.failure(IllegalArgumentException("Kode bahasa tidak valid: $langCode"))
        }
        try {
            val model = TranslateRemoteModel.Builder(langCode).build()
            modelManager.deleteDownloadedModel(model).awaitTask()
            refreshDownloadedModels()
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun translateText(
        text: String,
        sourceCode: String,
        targetCode: String,
        isOfflineMode: Boolean
    ): Result<TranslationOutcome> = withContext(Dispatchers.IO) {
        if (!isSupportedLanguageCode(sourceCode) || !isSupportedLanguageCode(targetCode)) {
            return@withContext Result.failure(IllegalArgumentException("Bahasa tidak didukung"))
        }
        val trimmed = text.trim().take(MAX_TRANSLATE_CHARS)
        if (trimmed.isEmpty()) {
            return@withContext Result.success(TranslationOutcome("", isOfflineMode))
        }
        if (sourceCode == targetCode) {
            return@withContext Result.success(TranslationOutcome(trimmed, isOfflineMode))
        }

        try {
            val downloaded = if (_downloadedModels.value.isEmpty()) {
                refreshDownloadedModels()
            } else {
                _downloadedModels.value
            }

            val missing = buildList {
                if (!downloaded.contains(sourceCode)) add(sourceCode)
                if (!downloaded.contains(targetCode)) add(targetCode)
            }

            if (isOfflineMode && missing.isNotEmpty()) {
                val verified = refreshDownloadedModels()
                val stillMissing = buildList {
                    if (!verified.contains(sourceCode)) add(sourceCode)
                    if (!verified.contains(targetCode)) add(targetCode)
                }
                if (stillMissing.isNotEmpty()) {
                    return@withContext Result.failure(OfflineModelMissingException(stillMissing))
                }
            }

            val translator = getOrCreateTranslator(sourceCode, targetCode)

            if (missing.isNotEmpty() && !isOfflineMode) {
                val conditions = DownloadConditions.Builder().build()
                translator.downloadModelIfNeeded(conditions).awaitTask()
                refreshDownloadedModels()
            }

            val output = translator.translate(trimmed).awaitTask()
            val usedOffline = isOfflineMode || missing.isEmpty()
            Result.success(TranslationOutcome(output, usedOffline))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun getOrCreateTranslator(sourceCode: String, targetCode: String): Translator {
        val pair = sourceCode to targetCode
        return translatorCache.computeIfAbsent(pair) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceCode)
                .setTargetLanguage(targetCode)
                .build()
            Translation.getClient(options)
        }
    }

    suspend fun saveHistory(
        sourceLangCode: String,
        targetLangCode: String,
        sourceText: String,
        translatedText: String,
        isOfflineTranslation: Boolean
    ): Long {
        return translationDao.insert(
            TranslationRecord(
                sourceLangCode = sourceLangCode,
                targetLangCode = targetLangCode,
                sourceText = sourceText.take(MAX_TRANSLATE_CHARS),
                translatedText = translatedText.take(MAX_TRANSLATE_CHARS),
                isOfflineTranslation = isOfflineTranslation
            )
        )
    }

    suspend fun setFavorite(id: Long, isFavorite: Boolean) {
        translationDao.setFavorite(id, isFavorite)
    }

    suspend fun deleteHistoryItem(id: Long) {
        translationDao.deleteById(id)
    }

    suspend fun clearAllHistory() {
        translationDao.clearAll()
    }

    fun close() {
        translatorCache.values.forEach { translator ->
            runCatching { translator.close() }
        }
        translatorCache.clear()
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }
    addOnFailureListener { exception ->
        if (cont.isActive) cont.resumeWithException(exception)
    }
    addOnCanceledListener {
        cont.cancel()
    }
}
