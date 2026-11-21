package com.example.ui.translator

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.TranslationRecord
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.LanguageCatalog
import com.example.data.model.SupportedLanguage
import com.example.data.repository.OfflineModelMissingException
import com.example.data.repository.TranslationRepository
import android.content.Context
import com.example.updater.AppUpdateManager
import com.example.updater.AppUpdateStatus
import com.example.updater.AppVersionComparator
import com.example.voice.ConnectivityObserver
import com.example.voice.TextToSpeechManager
import com.example.voice.VoiceRecognizerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TranslatorUiState(
    val sourceLanguage: SupportedLanguage = LanguageCatalog.findByCode("id"),
    val targetLanguage: SupportedLanguage = LanguageCatalog.findByCode("en"),
    val sourceText: String = "",
    val translatedText: String = "",
    val isOfflineMode: Boolean = false,
    val isDeviceOnline: Boolean = true,
    val autoSpeak: Boolean = true,
    val isManualInputMode: Boolean = false,
    val isListening: Boolean = false,
    val isSpeechDetected: Boolean = false,
    val rmsLevel: Float = 0f,
    val recordingElapsedSeconds: Int = 0,
    val isTranslating: Boolean = false,
    val isSpeakingTts: Boolean = false,
    val lastTranslationUsedOffline: Boolean = false,
    val lastSavedRecordId: Long? = null,
    val lastSavedIsFavorite: Boolean = false,
    val downloadedModelCodes: Set<String> = emptySet(),
    val downloadingModelCodes: Set<String> = emptySet(),
    val missingOfflineCodes: List<String> = emptyList(),
    val errorMessage: String? = null,
    val isRecognitionAvailable: Boolean = true,
    val isOnDeviceSpeechSupported: Boolean = false,
    val updateRepoSlug: String = "",
    val autoUpdateCheck: Boolean = true,
    val lastUpdateCheckMs: Long = 0L,
    val updateStatus: AppUpdateStatus = AppUpdateStatus.Idle,
    val isUpdateBannerDismissed: Boolean = false
) {
    val isCurrentPairOfflineReady: Boolean
        get() = downloadedModelCodes.contains(sourceLanguage.mlKitCode) &&
            downloadedModelCodes.contains(targetLanguage.mlKitCode)
}

class TranslatorViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val translationRepository = TranslationRepository(database.translationDao())
    private val prefsRepository = UserPreferencesRepository(application)
    private val voiceManager = VoiceRecognizerManager(application, viewModelScope)
    private val ttsManager = TextToSpeechManager(application)
    private val connectivityObserver = ConnectivityObserver(application)
    private val appUpdateManager = AppUpdateManager(application)

    private val _uiState = MutableStateFlow(TranslatorUiState())
    val uiState: StateFlow<TranslatorUiState> = _uiState.asStateFlow()

    val historyList: StateFlow<List<TranslationRecord>> = translationRepository.historyFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteList: StateFlow<List<TranslationRecord>> = translationRepository.favoritesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var liveTranslateJob: Job? = null
    private var updateCheckJob: Job? = null
    private var updateDownloadJob: Job? = null
    private var autoCheckTriggered: Boolean = false
    private var lastTranslatedPartial: String = ""

    init {
        viewModelScope.launch {
            translationRepository.refreshDownloadedModels()
        }

        viewModelScope.launch {
            combine(
                prefsRepository.preferencesFlow,
                translationRepository.downloadedModels,
                translationRepository.downloadingModels,
                connectivityObserver.isOnline,
                ttsManager.isSpeaking
            ) { prefs, downloaded, downloading, isOnline, isSpeaking ->
                val srcLang = LanguageCatalog.findByCode(prefs.sourceLangCode)
                val tgtLang = LanguageCatalog.findByCode(prefs.targetLangCode)
                val missing = if (prefs.isOfflineMode || !isOnline) {
                    buildList {
                        if (!downloaded.contains(srcLang.mlKitCode)) add(srcLang.mlKitCode)
                        if (!downloaded.contains(tgtLang.mlKitCode)) add(tgtLang.mlKitCode)
                    }
                } else {
                    emptyList()
                }
                _uiState.update { current ->
                    current.copy(
                        sourceLanguage = srcLang,
                        targetLanguage = tgtLang,
                        isOfflineMode = prefs.isOfflineMode,
                        autoSpeak = prefs.autoSpeak,
                        downloadedModelCodes = downloaded,
                        downloadingModelCodes = downloading,
                        isDeviceOnline = isOnline,
                        isSpeakingTts = isSpeaking,
                        missingOfflineCodes = missing,
                        updateRepoSlug = prefs.updateRepoSlug,
                        autoUpdateCheck = prefs.autoUpdateCheck,
                        lastUpdateCheckMs = prefs.lastUpdateCheckMs
                    )
                }

                if (!autoCheckTriggered && prefs.autoUpdateCheck && prefs.updateRepoSlug.isNotBlank() && isOnline) {
                    autoCheckTriggered = true
                    checkForUpdateNow(prefs.updateRepoSlug)
                }
            }.collectLatest { }
        }

        viewModelScope.launch {
            appUpdateManager.status.collect { status ->
                _uiState.update { current ->
                    val resetDismiss = status is AppUpdateStatus.UpdateAvailable ||
                        status is AppUpdateStatus.Downloading ||
                        status is AppUpdateStatus.ReadyToInstall
                    current.copy(
                        updateStatus = status,
                        isUpdateBannerDismissed = if (resetDismiss && current.updateStatus::class != status::class) {
                            false
                        } else {
                            current.isUpdateBannerDismissed
                        }
                    )
                }
            }
        }

        viewModelScope.launch {
            voiceManager.state.collect { voiceState ->
                _uiState.update { current ->
                    val updatedSource = if (voiceState.isListening && voiceState.partialText.isNotEmpty()) {
                        voiceState.partialText
                    } else if (voiceState.finalText != null) {
                        voiceState.finalText
                    } else {
                        current.sourceText
                    }
                    current.copy(
                        isListening = voiceState.isListening,
                        isSpeechDetected = voiceState.isSpeechDetected,
                        rmsLevel = voiceState.rmsLevel,
                        recordingElapsedSeconds = voiceState.elapsedSeconds,
                        sourceText = updatedSource,
                        errorMessage = when {
                            voiceState.errorMessage != null -> voiceState.errorMessage
                            voiceState.isListening -> null
                            else -> current.errorMessage
                        },
                        isRecognitionAvailable = voiceState.isRecognitionAvailable,
                        isOnDeviceSpeechSupported = voiceState.isOnDeviceSpeechSupported
                    )
                }

                // Real-time partial translation while user is speaking
                val partial = voiceState.partialText.trim()
                if (voiceState.isListening && partial.isNotEmpty() && partial != lastTranslatedPartial) {
                    lastTranslatedPartial = partial
                    scheduleLiveTranslation(partial, saveToHistory = false, speakAfter = false)
                }

                // Final result committed to history & spoken aloud if autoSpeak enabled
                val final = voiceState.finalText?.trim()
                if (!final.isNullOrEmpty()) {
                    lastTranslatedPartial = ""
                    voiceManager.consumeFinalText()
                    scheduleLiveTranslation(
                        text = final,
                        saveToHistory = true,
                        speakAfter = _uiState.value.autoSpeak,
                        immediate = true
                    )
                }
            }
        }
    }

    fun startRecording() {
        val state = _uiState.value
        ttsManager.stop()
        voiceManager.clearError()
        _uiState.update {
            it.copy(
                errorMessage = null,
                isManualInputMode = false,
                lastSavedRecordId = null,
                lastSavedIsFavorite = false
            )
        }
        lastTranslatedPartial = ""
        val useOfflineSpeech = state.isOfflineMode || !state.isDeviceOnline
        voiceManager.startListening(
            localeTag = state.sourceLanguage.localeTag,
            preferOffline = useOfflineSpeech
        )
    }

    fun stopRecording() {
        voiceManager.stopListening()
    }

    fun toggleRecording() {
        if (_uiState.value.isListening) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    fun stopRecordingIfActive() {
        if (_uiState.value.isListening) {
            voiceManager.cancelListening()
        }
    }

    fun setSourceLanguage(lang: SupportedLanguage) {
        val current = _uiState.value
        val newTarget = if (lang.mlKitCode == current.targetLanguage.mlKitCode) {
            current.sourceLanguage
        } else {
            current.targetLanguage
        }
        _uiState.update {
            it.copy(
                sourceLanguage = lang,
                targetLanguage = newTarget,
                lastSavedRecordId = null,
                lastSavedIsFavorite = false
            )
        }
        viewModelScope.launch {
            prefsRepository.swapLanguages(lang.mlKitCode, newTarget.mlKitCode)
            retranslateCurrentIfPresent(sourceCode = lang.mlKitCode, targetCode = newTarget.mlKitCode)
        }
    }

    fun setTargetLanguage(lang: SupportedLanguage) {
        val current = _uiState.value
        val newSource = if (lang.mlKitCode == current.sourceLanguage.mlKitCode) {
            current.targetLanguage
        } else {
            current.sourceLanguage
        }
        _uiState.update {
            it.copy(
                sourceLanguage = newSource,
                targetLanguage = lang,
                lastSavedRecordId = null,
                lastSavedIsFavorite = false
            )
        }
        viewModelScope.launch {
            prefsRepository.swapLanguages(newSource.mlKitCode, lang.mlKitCode)
            retranslateCurrentIfPresent(sourceCode = newSource.mlKitCode, targetCode = lang.mlKitCode)
        }
    }

    fun swapLanguages() {
        val current = _uiState.value
        val newSource = current.targetLanguage
        val newTarget = current.sourceLanguage
        val previousTranslated = current.translatedText
        val previousSource = current.sourceText

        _uiState.update {
            if (previousTranslated.isNotBlank()) {
                it.copy(
                    sourceLanguage = newSource,
                    targetLanguage = newTarget,
                    sourceText = previousTranslated,
                    translatedText = previousSource,
                    lastSavedRecordId = null,
                    lastSavedIsFavorite = false
                )
            } else {
                it.copy(
                    sourceLanguage = newSource,
                    targetLanguage = newTarget,
                    lastSavedRecordId = null,
                    lastSavedIsFavorite = false
                )
            }
        }
        viewModelScope.launch {
            prefsRepository.swapLanguages(newSource.mlKitCode, newTarget.mlKitCode)
            if (previousTranslated.isBlank() && previousSource.isNotBlank()) {
                retranslateCurrentIfPresent(sourceCode = newSource.mlKitCode, targetCode = newTarget.mlKitCode)
            }
        }
    }

    fun toggleOfflineMode(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepository.setOfflineMode(enabled)
            _uiState.update { it.copy(errorMessage = null) }
        }
    }

    fun toggleAutoSpeak(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepository.setAutoSpeak(enabled)
        }
    }

    fun toggleManualInputMode() {
        val next = !_uiState.value.isManualInputMode
        if (next && _uiState.value.isListening) {
            voiceManager.cancelListening()
        }
        _uiState.update { it.copy(isManualInputMode = next, errorMessage = null) }
    }

    fun onManualTextChanged(newText: String) {
        val bounded = newText.take(TranslationRepository.MAX_TRANSLATE_CHARS)
        _uiState.update {
            it.copy(
                sourceText = bounded,
                lastSavedRecordId = null,
                lastSavedIsFavorite = false,
                errorMessage = null
            )
        }
        if (bounded.isBlank()) {
            liveTranslateJob?.cancel()
            _uiState.update { it.copy(translatedText = "", isTranslating = false) }
        } else {
            scheduleLiveTranslation(bounded, saveToHistory = false, speakAfter = false)
        }
    }

    fun submitManualTranslation() {
        val text = _uiState.value.sourceText.trim().take(TranslationRepository.MAX_TRANSLATE_CHARS)
        if (text.isEmpty()) return
        scheduleLiveTranslation(
            text = text,
            saveToHistory = true,
            speakAfter = _uiState.value.autoSpeak,
            immediate = true
        )
    }

    fun clearTranscripts() {
        liveTranslateJob?.cancel()
        if (_uiState.value.isListening) {
            voiceManager.cancelListening()
        }
        ttsManager.stop()
        lastTranslatedPartial = ""
        _uiState.update {
            it.copy(
                sourceText = "",
                translatedText = "",
                lastSavedRecordId = null,
                lastSavedIsFavorite = false,
                errorMessage = null,
                isTranslating = false
            )
        }
    }

    fun speakSourceText() {
        val state = _uiState.value
        if (state.sourceText.isNotBlank()) {
            ttsManager.speak(state.sourceText, state.sourceLanguage.localeTag)
        }
    }

    fun speakTranslatedText() {
        val state = _uiState.value
        if (state.translatedText.isNotBlank()) {
            if (state.isSpeakingTts) {
                ttsManager.stop()
            } else {
                ttsManager.speak(state.translatedText, state.targetLanguage.localeTag)
            }
        }
    }

    fun speakRecord(text: String, langCode: String) {
        val lang = LanguageCatalog.findByCode(langCode)
        ttsManager.speak(text, lang.localeTag)
    }

    fun toggleCurrentFavorite() {
        val id = _uiState.value.lastSavedRecordId ?: return
        val nextFav = !_uiState.value.lastSavedIsFavorite
        viewModelScope.launch {
            translationRepository.setFavorite(id, nextFav)
            _uiState.update { it.copy(lastSavedIsFavorite = nextFav) }
        }
    }

    fun toggleRecordFavorite(record: TranslationRecord) {
        viewModelScope.launch {
            translationRepository.setFavorite(record.id, !record.isFavorite)
            if (_uiState.value.lastSavedRecordId == record.id) {
                _uiState.update { it.copy(lastSavedIsFavorite = !record.isFavorite) }
            }
        }
    }

    fun deleteHistoryRecord(id: Long) {
        viewModelScope.launch {
            translationRepository.deleteHistoryItem(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            translationRepository.clearAllHistory()
        }
    }

    fun downloadMissingCurrentPair() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = translationRepository.downloadPairModels(
                state.sourceLanguage.mlKitCode,
                state.targetLanguage.mlKitCode
            )
            result.onFailure { err ->
                _uiState.update {
                    it.copy(
                        errorMessage = "Gagal mengunduh paket offline. Pastikan perangkat terhubung ke internet (${err.localizedMessage ?: "error"})."
                    )
                }
            }.onSuccess {
                retranslateCurrentIfPresent()
            }
        }
    }

    fun downloadLanguagePack(langCode: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            translationRepository.downloadLanguageModel(langCode).onFailure { err ->
                _uiState.update {
                    it.copy(
                        errorMessage = "Gagal mengunduh paket bahasa: ${err.localizedMessage ?: "Periksa koneksi internet Anda"}"
                    )
                }
            }
        }
    }

    fun deleteLanguagePack(langCode: String) {
        viewModelScope.launch {
            translationRepository.deleteLanguageModel(langCode)
        }
    }

    fun dismissError() {
        voiceManager.clearError()
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun retranslateCurrentIfPresent(
        sourceCode: String = _uiState.value.sourceLanguage.mlKitCode,
        targetCode: String = _uiState.value.targetLanguage.mlKitCode
    ) {
        val currentText = _uiState.value.sourceText.trim()
        if (currentText.isNotEmpty()) {
            scheduleLiveTranslation(
                text = currentText,
                saveToHistory = false,
                speakAfter = false,
                immediate = true,
                sourceCodeOverride = sourceCode,
                targetCodeOverride = targetCode
            )
        }
    }

    private fun scheduleLiveTranslation(
        text: String,
        saveToHistory: Boolean,
        speakAfter: Boolean,
        immediate: Boolean = false,
        sourceCodeOverride: String? = null,
        targetCodeOverride: String? = null
    ) {
        liveTranslateJob?.cancel()
        liveTranslateJob = viewModelScope.launch {
            if (!immediate) {
                delay(140L)
            }
            val snapshot = _uiState.value
            val srcCode = sourceCodeOverride ?: snapshot.sourceLanguage.mlKitCode
            val tgtCode = targetCodeOverride ?: snapshot.targetLanguage.mlKitCode
            val tgtLocaleTag = LanguageCatalog.findByCode(tgtCode).localeTag
            val useOffline = snapshot.isOfflineMode || !snapshot.isDeviceOnline
            _uiState.update { it.copy(isTranslating = true) }

            val result = translationRepository.translateText(
                text = text,
                sourceCode = srcCode,
                targetCode = tgtCode,
                isOfflineMode = useOffline
            )

            result.onSuccess { outcome ->
                var savedId = _uiState.value.lastSavedRecordId
                if (saveToHistory && outcome.translatedText.isNotBlank()) {
                    savedId = translationRepository.saveHistory(
                        sourceLangCode = srcCode,
                        targetLangCode = tgtCode,
                        sourceText = text,
                        translatedText = outcome.translatedText,
                        isOfflineTranslation = outcome.usedOfflineModel
                    )
                }
                _uiState.update {
                    it.copy(
                        translatedText = outcome.translatedText,
                        isTranslating = false,
                        lastTranslationUsedOffline = outcome.usedOfflineModel,
                        lastSavedRecordId = savedId,
                        missingOfflineCodes = emptyList()
                    )
                }
                if (speakAfter && outcome.translatedText.isNotBlank()) {
                    ttsManager.speak(outcome.translatedText, tgtLocaleTag)
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                if (err is OfflineModelMissingException) {
                    _uiState.update {
                        it.copy(
                            isTranslating = false,
                            missingOfflineCodes = err.missingLanguageCodes,
                            errorMessage = null
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isTranslating = false,
                            errorMessage = "Gagal menerjemahkan: ${err.localizedMessage ?: "Coba lagi"}"
                        )
                    }
                }
            }
        }
    }

    fun setAutoUpdateCheck(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepository.setAutoUpdateCheck(enabled)
        }
    }

    fun saveRepoAndCheckForUpdate(rawRepoInput: String) {
        val trimmed = rawRepoInput.trim()
        viewModelScope.launch {
            if (trimmed.isEmpty()) {
                prefsRepository.setUpdateRepo("")
                appUpdateManager.checkForUpdate("")
                return@launch
            }
            val normalized = AppVersionComparator.normalizeRepoSlug(trimmed)
            if (normalized != null) {
                prefsRepository.setUpdateRepo(normalized)
                checkForUpdateNow(normalized)
            } else {
                appUpdateManager.checkForUpdate(trimmed)
            }
        }
    }

    fun checkForUpdateNow(repoOverride: String? = null) {
        val targetRepo = repoOverride ?: _uiState.value.updateRepoSlug
        updateCheckJob?.cancel()
        updateCheckJob = viewModelScope.launch {
            val result = appUpdateManager.checkForUpdate(targetRepo)
            if (result is AppUpdateStatus.UpToDate || result is AppUpdateStatus.UpdateAvailable) {
                prefsRepository.setLastUpdateCheckMs(System.currentTimeMillis())
                _uiState.update { it.copy(isUpdateBannerDismissed = false) }
            }
        }
    }

    fun downloadAvailableUpdate() {
        val status = _uiState.value.updateStatus
        val release = when (status) {
            is AppUpdateStatus.UpdateAvailable -> status.release
            else -> return
        }
        updateDownloadJob?.cancel()
        updateDownloadJob = viewModelScope.launch {
            _uiState.update { it.copy(isUpdateBannerDismissed = false) }
            appUpdateManager.downloadUpdate(release)
        }
    }

    fun cancelUpdateDownload() {
        updateDownloadJob?.cancel()
        appUpdateManager.cancelActiveDownload()
    }

    fun dismissUpdateBanner() {
        _uiState.update { it.copy(isUpdateBannerDismissed = true) }
    }

    fun canInstallApk(): Boolean = appUpdateManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        runCatching {
            context.startActivity(appUpdateManager.createUnknownSourcesSettingsIntent())
        }.onFailure {
            _uiState.update {
                it.copy(errorMessage = "Tidak dapat membuka pengaturan izin instalasi Android.")
            }
        }
    }

    fun launchApkInstaller(context: Context, apkFilePath: String) {
        if (!appUpdateManager.canRequestPackageInstalls()) {
            openInstallPermissionSettings(context)
            return
        }
        val intent = appUpdateManager.createInstallApkIntent(apkFilePath)
        if (intent == null) {
            _uiState.update {
                it.copy(errorMessage = "File APK pembaruan tidak ditemukan. Silakan unduh ulang.")
            }
            return
        }
        runCatching {
            context.startActivity(intent)
        }.onFailure { err ->
            _uiState.update {
                it.copy(
                    errorMessage = "Gagal membuka pemasang paket Android: ${err.localizedMessage ?: "error"}"
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        updateCheckJob?.cancel()
        updateDownloadJob?.cancel()
        appUpdateManager.cancelActiveDownload()
        voiceManager.release()
        ttsManager.release()
        connectivityObserver.release()
        translationRepository.close()
    }
}
