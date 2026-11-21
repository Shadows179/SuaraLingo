package com.example.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class VoiceSessionState(
    val isListening: Boolean = false,
    val isReadyForSpeech: Boolean = false,
    val isSpeechDetected: Boolean = false,
    val rmsLevel: Float = 0f,
    val partialText: String = "",
    val finalText: String? = null,
    val elapsedSeconds: Int = 0,
    val errorMessage: String? = null,
    val isRecognitionAvailable: Boolean = true,
    val isOnDeviceSpeechSupported: Boolean = false
)

class VoiceRecognizerManager(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var timerJob: Job? = null
    private var activeSessionId: Long = 0L
    private var stopRequestedForActiveSession: Boolean = false
    @Volatile
    private var isReleased: Boolean = false

    private val _state = MutableStateFlow(
        VoiceSessionState(
            isRecognitionAvailable = SpeechRecognizer.isRecognitionAvailable(appContext),
            isOnDeviceSpeechSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            } else {
                false
            }
        )
    )
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    fun startListening(localeTag: String, preferOffline: Boolean) {
        if (isReleased) return
        mainHandler.post {
            if (isReleased) return@post

            val hasPermission = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                _state.update {
                    it.copy(
                        isListening = false,
                        errorMessage = "Izin mikrofon belum diberikan."
                    )
                }
                return@post
            }

            val standardAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)
            val onDeviceAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            } else {
                false
            }

            if (!standardAvailable && !(preferOffline && onDeviceAvailable)) {
                _state.update {
                    it.copy(
                        isListening = false,
                        isRecognitionAvailable = false,
                        errorMessage = "Layanan pengenalan suara tidak tersedia di perangkat ini. Anda tetap dapat mengetik teks secara manual."
                    )
                }
                return@post
            }

            val sessionId = ++activeSessionId
            stopRequestedForActiveSession = false
            stopTimer()
            destroyRecognizerInternal()

            val recognizer = if (preferOffline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && onDeviceAvailable) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            } else {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }

            recognizer.setRecognitionListener(createListener(sessionId))
            speechRecognizer = recognizer

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                if (preferOffline) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }

            _state.update {
                it.copy(
                    isListening = true,
                    isReadyForSpeech = false,
                    isSpeechDetected = false,
                    rmsLevel = 0.08f,
                    partialText = "",
                    finalText = null,
                    elapsedSeconds = 0,
                    errorMessage = null,
                    isRecognitionAvailable = true,
                    isOnDeviceSpeechSupported = onDeviceAvailable
                )
            }

            startTimer(sessionId)
            recognizer.startListening(intent)
        }
    }

    fun stopListening() {
        if (isReleased) return
        mainHandler.post {
            if (isReleased) return@post
            stopTimer()
            val snapshot = _state.value
            if (!snapshot.isReadyForSpeech && snapshot.partialText.isBlank()) {
                // Recognizer hasn't become ready yet; cancel cleanly to avoid ERROR_CLIENT
                activeSessionId++
                stopRequestedForActiveSession = false
                speechRecognizer?.cancel()
                destroyRecognizerInternal()
                _state.update {
                    it.copy(
                        isListening = false,
                        isReadyForSpeech = false,
                        isSpeechDetected = false,
                        rmsLevel = 0f
                    )
                }
            } else {
                stopRequestedForActiveSession = true
                speechRecognizer?.stopListening()
                _state.update {
                    it.copy(
                        isListening = false,
                        isReadyForSpeech = false,
                        rmsLevel = 0f
                    )
                }
            }
        }
    }

    fun cancelListening() {
        mainHandler.post {
            activeSessionId++
            stopTimer()
            speechRecognizer?.cancel()
            destroyRecognizerInternal()
            _state.update {
                it.copy(
                    isListening = false,
                    isReadyForSpeech = false,
                    isSpeechDetected = false,
                    rmsLevel = 0f,
                    elapsedSeconds = 0
                )
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun consumeFinalText() {
        _state.update { it.copy(finalText = null) }
    }

    private fun startTimer(sessionId: Long) {
        timerJob?.cancel()
        timerJob = scope.launch(Dispatchers.Main) {
            var seconds = 0
            while (isActive && !isReleased && activeSessionId == sessionId && _state.value.isListening) {
                delay(1000L)
                if (!isActive || isReleased || activeSessionId != sessionId || !_state.value.isListening) break
                seconds++
                _state.update { it.copy(elapsedSeconds = seconds) }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun createListener(sessionId: Long): RecognitionListener = object : RecognitionListener {
        private var sessionTerminated = false

        private fun isStale(): Boolean = isReleased || sessionId != activeSessionId || sessionTerminated

        override fun onReadyForSpeech(params: Bundle?) {
            if (isStale()) return
            _state.update { it.copy(isReadyForSpeech = true, errorMessage = null) }
        }

        override fun onBeginningOfSpeech() {
            if (isStale()) return
            _state.update { it.copy(isSpeechDetected = true) }
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (isStale()) return
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1f)
            _state.update { current ->
                if (current.isListening) current.copy(rmsLevel = normalized) else current
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (isStale()) return
            stopTimer()
            _state.update {
                it.copy(
                    isListening = false,
                    isSpeechDetected = false,
                    rmsLevel = 0f
                )
            }
        }

        override fun onError(error: Int) {
            if (isStale()) return
            sessionTerminated = true
            stopTimer()

            // If user tapped STOP and the recognizer aborted with ERROR_CLIENT after capturing partial text,
            // finalize that partial text instead of losing the spoken sentence.
            if (error == SpeechRecognizer.ERROR_CLIENT && stopRequestedForActiveSession) {
                val fallbackPartial = _state.value.partialText.trim()
                _state.update {
                    it.copy(
                        isListening = false,
                        isReadyForSpeech = false,
                        isSpeechDetected = false,
                        rmsLevel = 0f,
                        finalText = fallbackPartial.ifEmpty { null },
                        errorMessage = null
                    )
                }
                destroyRecognizerInternal()
                return
            }

            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Gagal mengakses audio mikrofon."
                SpeechRecognizer.ERROR_CLIENT -> null
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Izin mikrofon belum diberikan."
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "Koneksi suara bermasalah. Aktifkan Mode Offline atau ketik teks secara manual."
                SpeechRecognizer.ERROR_NO_MATCH ->
                    "Suara tidak terdengar jelas. Silakan ketuk mikrofon dan coba lagi."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    "Pengenal suara sedang sibuk, tunggu sebentar."
                SpeechRecognizer.ERROR_SERVER ->
                    "Layanan suara sedang tidak tersedia. Coba Mode Offline atau input manual."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "Tidak ada ucapan terdeteksi. Ketuk mikrofon untuk mulai berbicara."
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    "Paket pengenalan suara offline untuk bahasa ini belum terpasang di sistem perangkat."
                else -> "Terjadi kendala pengenalan suara (kode $error)."
            }
            _state.update {
                it.copy(
                    isListening = false,
                    isReadyForSpeech = false,
                    isSpeechDetected = false,
                    rmsLevel = 0f,
                    errorMessage = message
                )
            }
            destroyRecognizerInternal()
        }

        override fun onResults(results: Bundle?) {
            if (isStale()) return
            sessionTerminated = true
            stopTimer()
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = matches?.firstOrNull()?.trim().orEmpty()
            val fallbackPartial = _state.value.partialText.trim()
            val resolved = best.ifEmpty { fallbackPartial }

            _state.update {
                it.copy(
                    isListening = false,
                    isReadyForSpeech = false,
                    isSpeechDetected = false,
                    rmsLevel = 0f,
                    partialText = resolved,
                    finalText = resolved.ifEmpty { null }
                )
            }
            destroyRecognizerInternal()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (isStale()) return
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull()?.trim().orEmpty()
            if (partial.isNotEmpty()) {
                _state.update { it.copy(partialText = partial) }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun destroyRecognizerInternal() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    fun release() {
        isReleased = true
        activeSessionId++
        mainHandler.post {
            stopTimer()
            destroyRecognizerInternal()
        }
    }
}
