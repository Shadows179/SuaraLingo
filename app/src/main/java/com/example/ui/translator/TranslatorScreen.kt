package com.example.ui.translator

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.R
import com.example.data.model.LanguageCatalog
import com.example.data.model.SupportedLanguage
import com.example.ui.theme.LocalStudioColors
import com.example.ui.updater.UpdateSheetDialog
import com.example.updater.AppUpdateStatus

private enum class PickerTarget {
    SOURCE, TARGET
}

@Composable
fun TranslatorScreen(
    uiState: TranslatorUiState,
    onToggleRecording: () -> Unit,
    onSelectSourceLanguage: (SupportedLanguage) -> Unit,
    onSelectTargetLanguage: (SupportedLanguage) -> Unit,
    onSwapLanguages: () -> Unit,
    onToggleOfflineMode: (Boolean) -> Unit,
    onToggleAutoSpeak: (Boolean) -> Unit,
    onToggleManualInput: () -> Unit,
    onManualTextChanged: (String) -> Unit,
    onSubmitManualTranslation: () -> Unit,
    onClearText: () -> Unit,
    onSpeakSource: () -> Unit,
    onSpeakTarget: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownloadMissingPair: () -> Unit,
    onDismissError: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    onToggleAutoUpdateCheck: (Boolean) -> Unit,
    onSaveRepoAndCheckUpdate: (String) -> Unit,
    onCheckUpdateNow: () -> Unit,
    onDownloadAppUpdate: () -> Unit,
    onCancelAppUpdateDownload: () -> Unit,
    onDismissUpdateBanner: () -> Unit,
    canInstallApk: () -> Boolean,
    onOpenInstallPermissionSettings: (Context) -> Unit,
    onInstallApk: (Context, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val studioColors = LocalStudioColors.current
    var pickerTarget by remember { mutableStateOf<PickerTarget?>(null) }
    var showUpdateSheet by remember { mutableStateOf(false) }
    var swapRotationTarget by remember { mutableFloatStateOf(0f) }
    val swapRotation by animateFloatAsState(
        targetValue = swapRotationTarget,
        animationSpec = tween(durationMillis = 260),
        label = "swap_rotation"
    )

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var showPermissionPromptCard by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            showPermissionPromptCard = false
            onToggleRecording()
        } else {
            showPermissionPromptCard = true
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        hasAudioPermission = granted
        if (granted) {
            showPermissionPromptCard = false
        }
    }

    val copiedMessage = stringResource(R.string.copied_snackbar)

    val handleMicClick: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        hasAudioPermission = granted
        if (granted) {
            showPermissionPromptCard = false
            onToggleRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                    )
                )
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Studio Status Bar
            val hasPendingUpdate = uiState.updateStatus is AppUpdateStatus.UpdateAvailable ||
                uiState.updateStatus is AppUpdateStatus.Downloading ||
                uiState.updateStatus is AppUpdateStatus.ReadyToInstall
            TopStudioHeader(
                isOfflineMode = uiState.isOfflineMode,
                isDeviceOnline = uiState.isDeviceOnline,
                autoSpeak = uiState.autoSpeak,
                hasPendingUpdate = hasPendingUpdate,
                onToggleOfflineMode = onToggleOfflineMode,
                onToggleAutoSpeak = onToggleAutoSpeak,
                onOpenUpdateCenter = { showUpdateSheet = true }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Language Pair Selector Bar
            LanguageSelectorCard(
                sourceLanguage = uiState.sourceLanguage,
                targetLanguage = uiState.targetLanguage,
                downloadedCodes = uiState.downloadedModelCodes,
                swapRotation = swapRotation,
                onSourceClick = { pickerTarget = PickerTarget.SOURCE },
                onTargetClick = { pickerTarget = PickerTarget.TARGET },
                onSwapClick = {
                    swapRotationTarget += 180f
                    onSwapLanguages()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Scrollable Transcript & Translation Stage
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // In-App App Update Banner (when newer release detected from personal repo)
                val showInlineUpdateBanner = !uiState.isUpdateBannerDismissed &&
                    (uiState.updateStatus is AppUpdateStatus.UpdateAvailable ||
                        uiState.updateStatus is AppUpdateStatus.Downloading ||
                        uiState.updateStatus is AppUpdateStatus.ReadyToInstall)
                AnimatedVisibility(
                    visible = showInlineUpdateBanner,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    InlineAppUpdateBanner(
                        status = uiState.updateStatus,
                        onOpenDetails = { showUpdateSheet = true },
                        onDownload = onDownloadAppUpdate,
                        onInstall = { path -> onInstallApk(context, path) },
                        onDismiss = onDismissUpdateBanner
                    )
                }
                // Permission explanation card if user denied audio permission
                AnimatedVisibility(visible = showPermissionPromptCard && !hasAudioPermission) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.permission_mic_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.permission_mic_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                modifier = Modifier.testTag("grant_mic_permission_button")
                            ) {
                                Text(stringResource(R.string.permission_grant_button))
                            }
                        }
                    }
                }

                // Missing offline language pack warning banner
                val missingDownloadInProgress = uiState.missingOfflineCodes.any {
                    uiState.downloadingModelCodes.contains(it)
                }
                AnimatedVisibility(
                    visible = uiState.missingOfflineCodes.isNotEmpty() &&
                        (uiState.isOfflineMode || !uiState.isDeviceOnline),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    val missingNames = uiState.missingOfflineCodes.joinToString(" & ") {
                        LanguageCatalog.findByCode(it).displayName
                    }
                    Surface(
                        color = studioColors.warning.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, studioColors.warning.copy(alpha = 0.45f)),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.WarningAmber,
                                    contentDescription = null,
                                    tint = studioColors.warning,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = stringResource(R.string.offline_missing_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Paket bahasa $missingNames perlu diunduh untuk terjemahan tanpa internet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            if (missingDownloadInProgress) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = stringResource(R.string.downloading_pack_label),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else {
                                FilledTonalButton(
                                    onClick = onDownloadMissingPair,
                                    modifier = Modifier.testTag("download_missing_pair_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CloudDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.download_now_button)
                                    )
                                }
                            }
                        }
                    }
                }

                // Error banner if speech recognizer reports an issue
                AnimatedVisibility(visible = uiState.errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = uiState.errorMessage.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = onDismissError,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.clear_text_cd),
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                // Source Speech / Input Card
                SourceTranscriptCard(
                    language = uiState.sourceLanguage,
                    sourceText = uiState.sourceText,
                    isListening = uiState.isListening,
                    isManualInputMode = uiState.isManualInputMode,
                    onToggleManualInput = onToggleManualInput,
                    onManualTextChanged = onManualTextChanged,
                    onSubmitManual = onSubmitManualTranslation,
                    onSpeakSource = onSpeakSource,
                    onClearText = onClearText
                )

                // Instant Translated Output Card
                TranslatedOutputCard(
                    language = uiState.targetLanguage,
                    translatedText = uiState.translatedText,
                    isTranslating = uiState.isTranslating,
                    isListening = uiState.isListening,
                    isSpeakingTts = uiState.isSpeakingTts,
                    usedOfflineEngine = uiState.lastTranslationUsedOffline,
                    canFavorite = uiState.lastSavedRecordId != null,
                    isFavorite = uiState.lastSavedIsFavorite,
                    onSpeakTarget = onSpeakTarget,
                    onCopyTarget = {
                        runCatching {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Terjemahan", uiState.translatedText))
                        }.onSuccess {
                            onShowSnackbar(copiedMessage)
                        }
                    },
                    onToggleFavorite = onToggleFavorite
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Hero Interactive Voice Recording Dock
            InteractiveMicButton(
                isListening = uiState.isListening,
                isTranslating = uiState.isTranslating,
                rmsLevel = uiState.rmsLevel,
                elapsedSeconds = uiState.recordingElapsedSeconds,
                onClick = handleMicClick,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }

    pickerTarget?.let { target ->
        LanguagePickerSheet(
            title = if (target == PickerTarget.SOURCE) "Pilih Bahasa Sumber" else "Pilih Bahasa Tujuan",
            selectedLanguage = if (target == PickerTarget.SOURCE) uiState.sourceLanguage else uiState.targetLanguage,
            downloadedModelCodes = uiState.downloadedModelCodes,
            onSelectLanguage = { lang ->
                if (target == PickerTarget.SOURCE) {
                    onSelectSourceLanguage(lang)
                } else {
                    onSelectTargetLanguage(lang)
                }
            },
            onDismiss = { pickerTarget = null }
        )
    }

    if (showUpdateSheet) {
        UpdateSheetDialog(
            updateRepoSlug = uiState.updateRepoSlug,
            autoUpdateCheck = uiState.autoUpdateCheck,
            lastUpdateCheckMs = uiState.lastUpdateCheckMs,
            updateStatus = uiState.updateStatus,
            canInstallApk = canInstallApk,
            onToggleAutoUpdate = onToggleAutoUpdateCheck,
            onSaveRepoAndCheck = onSaveRepoAndCheckUpdate,
            onCheckNow = onCheckUpdateNow,
            onDownloadUpdate = onDownloadAppUpdate,
            onCancelDownload = onCancelAppUpdateDownload,
            onOpenInstallPermissionSettings = onOpenInstallPermissionSettings,
            onInstallApk = onInstallApk,
            onDismiss = { showUpdateSheet = false }
        )
    }
}

@Composable
private fun TopStudioHeader(
    isOfflineMode: Boolean,
    isDeviceOnline: Boolean,
    autoSpeak: Boolean,
    hasPendingUpdate: Boolean,
    onToggleOfflineMode: (Boolean) -> Unit,
    onToggleAutoSpeak: (Boolean) -> Unit,
    onOpenUpdateCenter: () -> Unit
) {
    val studioColors = LocalStudioColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = if (isDeviceOnline) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                    contentDescription = null,
                    tint = if (isDeviceOnline) studioColors.offlineReady else studioColors.warning,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = when {
                        isOfflineMode -> "Mode Offline Aktif"
                        !isDeviceOnline -> stringResource(R.string.status_network_offline)
                        else -> "Siap Suara Real-Time"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // In-App Update Center button
            Surface(
                color = if (hasPendingUpdate) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = CircleShape,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .testTag("open_update_center_button")
                    .clickable(onClick = onOpenUpdateCenter)
            ) {
                BadgedBox(
                    badge = {
                        if (hasPendingUpdate) {
                            Badge(containerColor = MaterialTheme.colorScheme.primary)
                        }
                    },
                    modifier = Modifier.padding(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = stringResource(R.string.update_center_cd),
                        tint = if (hasPendingUpdate) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Auto-speak quick toggle
            Surface(
                color = if (autoSpeak) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = CircleShape,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .testTag("auto_speak_toggle")
                    .clickable { onToggleAutoSpeak(!autoSpeak) }
            ) {
                Icon(
                    imageVector = if (autoSpeak) {
                        Icons.AutoMirrored.Rounded.VolumeUp
                    } else {
                        Icons.AutoMirrored.Rounded.VolumeOff
                    },
                    contentDescription = stringResource(R.string.auto_speak_label),
                    tint = if (autoSpeak) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(10.dp).size(20.dp)
                )
            }

            // Interactive Offline Mode Toggle Pill
            Surface(
                color = if (isOfflineMode) {
                    studioColors.offlineReady.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isOfflineMode) {
                        studioColors.offlineReady
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                    }
                ),
                shape = RoundedCornerShape(50)
            ) {
                Row(
                    modifier = Modifier
                        .padding(start = 10.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (isOfflineMode) Icons.Rounded.CloudOff else Icons.Rounded.CloudDone,
                        contentDescription = null,
                        tint = if (isOfflineMode) {
                            studioColors.offlineReady
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Offline",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isOfflineMode) {
                            studioColors.offlineReady
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Switch(
                        checked = isOfflineMode,
                        onCheckedChange = onToggleOfflineMode,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.surface,
                            checkedTrackColor = studioColors.offlineReady
                        ),
                        modifier = Modifier.testTag("offline_mode_switch")
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineAppUpdateBanner(
    status: AppUpdateStatus,
    onOpenDetails: () -> Unit,
    onDownload: () -> Unit,
    onInstall: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val studioColors = LocalStudioColors.current
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetails)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.NewReleases,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    when (status) {
                        is AppUpdateStatus.UpdateAvailable -> {
                            Text(
                                text = "Update ${status.release.tagName} tersedia",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Ketuk untuk melihat catatan rilis atau unduh langsung.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        is AppUpdateStatus.Downloading -> {
                            val pct = status.progress?.let { "${(it * 100).toInt()}%" } ?: "…"
                            Text(
                                text = "Mengunduh ${status.release.tagName} ($pct)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        is AppUpdateStatus.ReadyToInstall -> {
                            Text(
                                text = "${status.release.tagName} siap dipasang",
                                style = MaterialTheme.typography.titleSmall,
                                color = studioColors.offlineReady
                            )
                        }
                        else -> Unit
                    }
                }
                when (status) {
                    is AppUpdateStatus.UpdateAvailable -> {
                        FilledTonalButton(onClick = onDownload) {
                            Text("Unduh")
                        }
                    }
                    is AppUpdateStatus.ReadyToInstall -> {
                        Button(onClick = { onInstall(status.apkFilePath) }) {
                            Text("Pasang")
                        }
                    }
                    else -> Unit
                }
                if (status !is AppUpdateStatus.Downloading) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.action_cancel),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            if (status is AppUpdateStatus.Downloading) {
                Spacer(modifier = Modifier.height(8.dp))
                if (status.progress != null) {
                    LinearProgressIndicator(
                        progress = { status.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageSelectorCard(
    sourceLanguage: SupportedLanguage,
    targetLanguage: SupportedLanguage,
    downloadedCodes: Set<String>,
    swapRotation: Float,
    onSourceClick: () -> Unit,
    onTargetClick: () -> Unit,
    onSwapClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LanguageChipButton(
                language = sourceLanguage,
                isOfflineReady = downloadedCodes.contains(sourceLanguage.mlKitCode),
                onClick = onSourceClick,
                modifier = Modifier
                    .weight(1f)
                    .testTag("source_language_button")
            )

            IconButton(
                onClick = onSwapClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("swap_languages_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.SwapHoriz,
                    contentDescription = stringResource(R.string.swap_languages_cd),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(26.dp)
                        .rotate(swapRotation)
                )
            }

            LanguageChipButton(
                language = targetLanguage,
                isOfflineReady = downloadedCodes.contains(targetLanguage.mlKitCode),
                onClick = onTargetClick,
                modifier = Modifier
                    .weight(1f)
                    .testTag("target_language_button")
            )
        }
    }
}

@Composable
private fun LanguageChipButton(
    language: SupportedLanguage,
    isOfflineReady: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studioColors = LocalStudioColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = language.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = language.shortCode,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "•",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    if (isOfflineReady) R.string.status_offline_ready else R.string.status_needs_download
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (isOfflineReady) {
                    studioColors.offlineReady
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SourceTranscriptCard(
    language: SupportedLanguage,
    sourceText: String,
    isListening: Boolean,
    isManualInputMode: Boolean,
    onToggleManualInput: () -> Unit,
    onManualTextChanged: (String) -> Unit,
    onSubmitManual: () -> Unit,
    onSpeakSource: () -> Unit,
    onClearText: () -> Unit
) {
    val studioColors = LocalStudioColors.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = if (isListening) 1.5.dp else 1.dp,
            color = if (isListening) {
                studioColors.recordingActive.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            }
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = language.displayName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    if (isListening) {
                        Surface(
                            color = studioColors.recordingActive.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = "MENDENGARKAN",
                                style = MaterialTheme.typography.labelSmall,
                                color = studioColors.recordingActive,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onToggleManualInput,
                        modifier = Modifier.testTag("toggle_manual_input_button")
                    ) {
                        Icon(
                            imageVector = if (isManualInputMode) Icons.Rounded.Mic else Icons.Rounded.Keyboard,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isManualInputMode) "Mode Suara" else stringResource(R.string.manual_input_toggle),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    if (sourceText.isNotEmpty()) {
                        IconButton(
                            onClick = onSpeakSource,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                                contentDescription = stringResource(R.string.speak_source_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = onClearText,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("clear_source_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.clear_text_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (isManualInputMode) {
                OutlinedTextField(
                    value = sourceText,
                    onValueChange = onManualTextChanged,
                    placeholder = { Text(stringResource(R.string.manual_input_hint)) },
                    minLines = 2,
                    maxLines = 4,
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_source_input")
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onSubmitManual,
                        enabled = sourceText.isNotBlank(),
                        modifier = Modifier.testTag("submit_manual_translate_button")
                    ) {
                        Text(stringResource(R.string.translate_now_button))
                    }
                }
            } else {
                Text(
                    text = sourceText.ifBlank { stringResource(R.string.source_placeholder) },
                    style = MaterialTheme.typography.titleLarge,
                    color = if (sourceText.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag("source_transcript_text")
                )
            }
        }
    }
}

@Composable
private fun TranslatedOutputCard(
    language: SupportedLanguage,
    translatedText: String,
    isTranslating: Boolean,
    isListening: Boolean,
    isSpeakingTts: Boolean,
    usedOfflineEngine: Boolean,
    canFavorite: Boolean,
    isFavorite: Boolean,
    onSpeakTarget: () -> Unit,
    onCopyTarget: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val studioColors = LocalStudioColors.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = language.displayName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    if (translatedText.isNotBlank()) {
                        Surface(
                            color = if (usedOfflineEngine) {
                                studioColors.offlineReady.copy(alpha = 0.16f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = if (usedOfflineEngine) "OFFLINE ENGINE" else "INSTAN",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (usedOfflineEngine) {
                                    studioColors.offlineReady
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                if (translatedText.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onSpeakTarget,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("speak_translation_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                                contentDescription = stringResource(R.string.speak_target_cd),
                                tint = if (isSpeakingTts) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        IconButton(
                            onClick = onCopyTarget,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("copy_translation_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = stringResource(R.string.copy_translation_cd),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (canFavorite) {
                            IconButton(
                                onClick = onToggleFavorite,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("favorite_translation_button")
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                                    contentDescription = stringResource(R.string.toggle_favorite_cd),
                                    tint = if (isFavorite) {
                                        studioColors.warning
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (isTranslating) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                )
            } else {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            Text(
                text = when {
                    translatedText.isNotBlank() -> translatedText
                    isListening -> "Menerjemahkan ucapan secara real-time…"
                    else -> stringResource(R.string.target_placeholder)
                },
                style = MaterialTheme.typography.headlineSmall,
                color = if (translatedText.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("translated_output_text")
            )
        }
    }
}
