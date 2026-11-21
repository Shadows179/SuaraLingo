package com.example.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val releaseTitle: String,
    val changelog: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val expectedSha256: String?,
    val publishedAt: String
)

sealed interface AppUpdateStatus {
    data object Idle : AppUpdateStatus
    data object RepoNotConfigured : AppUpdateStatus
    data object Checking : AppUpdateStatus
    data class UpToDate(
        val currentVersion: String,
        val latestTag: String
    ) : AppUpdateStatus
    data class UpdateAvailable(
        val currentVersion: String,
        val release: ReleaseInfo
    ) : AppUpdateStatus
    data class Downloading(
        val release: ReleaseInfo,
        val progress: Float?,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : AppUpdateStatus
    data class ReadyToInstall(
        val release: ReleaseInfo,
        val apkFilePath: String,
        val sha256Verified: Boolean
    ) : AppUpdateStatus
    data class Error(val message: String) : AppUpdateStatus
}

class AppUpdateManager(
    context: Context,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val httpClient: OkHttpClient = createDefaultClient()
) {
    companion object {
        private const val MAX_JSON_BYTES = 2L * 1024L * 1024L // 2 MB cap for release JSON
        private const val MAX_APK_BYTES = 250L * 1024L * 1024L // 250 MB cap for APK download
        private const val UPDATES_SUBDIR = "updates"
        private const val APK_FILENAME = "suaralingo_update.apk"
        private const val PART_FILENAME = "suaralingo_update.apk.part"

        fun createDefaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .addNetworkInterceptor { chain ->
                    val url = chain.request().url
                    if (!AppVersionComparator.isTrustedGitHubUrl(url)) {
                        throw SecurityException("Domain unduhan tidak diizinkan: ${url.host}")
                    }
                    chain.proceed(chain.request())
                }
                .build()
        }
    }

    private val appContext = context.applicationContext
    private val _status = MutableStateFlow<AppUpdateStatus>(AppUpdateStatus.Idle)
    val status: StateFlow<AppUpdateStatus> = _status.asStateFlow()

    @Volatile
    private var activeCall: okhttp3.Call? = null

    suspend fun checkForUpdate(repoInput: String): AppUpdateStatus = withContext(Dispatchers.IO) {
        val repoSlug = AppVersionComparator.normalizeRepoSlug(repoInput)
        if (repoSlug == null) {
            val next = if (repoInput.isBlank()) {
                AppUpdateStatus.RepoNotConfigured
            } else {
                AppUpdateStatus.Error("Format repositori tidak valid. Gunakan owner/repo (contoh: nama/SuaraLingo).")
            }
            _status.value = next
            return@withContext next
        }

        _status.value = AppUpdateStatus.Checking

        try {
            val apiUrl = "https://api.github.com/repos/$repoSlug/releases/latest"
            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "SuaraLingo-Updater/$currentVersionName")
                .build()

            val call = httpClient.newCall(request)
            activeCall = call
            val bodyString = call.execute().use { response ->
                if (response.code == 404) {
                    val notFound = AppUpdateStatus.Error(
                        "Rilis atau repositori \"$repoSlug\" belum ditemukan di GitHub."
                    )
                    _status.value = notFound
                    return@withContext notFound
                }
                if (response.code == 403 || response.code == 429) {
                    val rateLimited = AppUpdateStatus.Error(
                        "Batas akses API GitHub tercapai sementara. Coba lagi nanti."
                    )
                    _status.value = rateLimited
                    return@withContext rateLimited
                }
                if (!response.isSuccessful) {
                    val err = AppUpdateStatus.Error("Gagal memeriksa pembaruan (HTTP ${response.code}).")
                    _status.value = err
                    return@withContext err
                }
                val body = response.body ?: throw IOException("Respons kosong dari GitHub")
                val contentLength = body.contentLength()
                if (contentLength > MAX_JSON_BYTES) {
                    throw IOException("Ukuran respons rilis terlalu besar.")
                }
                val raw = body.string()
                if (raw.length > MAX_JSON_BYTES) {
                    throw IOException("Ukuran respons rilis melebihi batas.")
                }
                raw
            }

            val json = JSONObject(bodyString)
            if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) {
                val upToDate = AppUpdateStatus.UpToDate(currentVersionName, currentVersionName)
                _status.value = upToDate
                return@withContext upToDate
            }

            val tagName = json.optString("tag_name", "").trim()
            if (tagName.isEmpty()) {
                val err = AppUpdateStatus.Error("Informasi versi rilis tidak ditemukan di repositori.")
                _status.value = err
                return@withContext err
            }

            if (!AppVersionComparator.isRemoteVersionNewer(currentVersionName, tagName)) {
                val upToDate = AppUpdateStatus.UpToDate(currentVersionName, tagName)
                _status.value = upToDate
                return@withContext upToDate
            }

            val assets = json.optJSONArray("assets") ?: JSONArray()
            val apkAsset = selectApkAsset(assets)
            if (apkAsset == null) {
                val err = AppUpdateStatus.Error(
                    "Versi baru ($tagName) ditemukan, tetapi belum ada file .apk di aset rilis GitHub."
                )
                _status.value = err
                return@withContext err
            }

            val release = ReleaseInfo(
                tagName = tagName,
                releaseTitle = json.optString("name", tagName).ifBlank { tagName },
                changelog = json.optString("body", "").trim(),
                apkDownloadUrl = apkAsset.downloadUrl,
                apkFileName = apkAsset.name,
                apkSizeBytes = apkAsset.size,
                expectedSha256 = apkAsset.sha256,
                publishedAt = json.optString("published_at", "")
            )
            val available = AppUpdateStatus.UpdateAvailable(currentVersionName, release)
            _status.value = available
            available
        } catch (ce: CancellationException) {
            throw ce
        } catch (se: SecurityException) {
            val err = AppUpdateStatus.Error(se.message ?: "URL rilis tidak valid.")
            _status.value = err
            err
        } catch (t: Throwable) {
            val err = AppUpdateStatus.Error(
                "Tidak dapat memeriksa pembaruan: ${t.localizedMessage ?: "Periksa koneksi internet"}"
            )
            _status.value = err
            err
        } finally {
            activeCall = null
        }
    }

    suspend fun downloadUpdate(release: ReleaseInfo): AppUpdateStatus = withContext(Dispatchers.IO) {
        val parsedUrl = release.apkDownloadUrl.toHttpUrlOrNull()
        if (parsedUrl == null || !AppVersionComparator.isTrustedGitHubUrl(parsedUrl)) {
            val err = AppUpdateStatus.Error("URL unduhan APK ditolak karena tidak berasal dari GitHub HTTPS.")
            _status.value = err
            return@withContext err
        }

        val updatesDir = File(appContext.cacheDir, UPDATES_SUBDIR).apply { mkdirs() }
        val canonicalDir = updatesDir.canonicalPath + File.separator
        val partFile = File(updatesDir, PART_FILENAME)
        val finalFile = File(updatesDir, APK_FILENAME)
        if (!partFile.canonicalPath.startsWith(canonicalDir) ||
            !finalFile.canonicalPath.startsWith(canonicalDir)
        ) {
            val err = AppUpdateStatus.Error("Lokasi penyimpanan internal tidak valid.")
            _status.value = err
            return@withContext err
        }

        partFile.delete()
        finalFile.delete()

        _status.value = AppUpdateStatus.Downloading(
            release = release,
            progress = 0f,
            downloadedBytes = 0L,
            totalBytes = release.apkSizeBytes
        )

        try {
            val request = Request.Builder()
                .url(parsedUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "SuaraLingo-Updater/$currentVersionName")
                .build()

            val call = httpClient.newCall(request)
            activeCall = call

            var computedSha256 = ""
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Gagal mengunduh APK (HTTP ${response.code})")
                }
                val body = response.body ?: throw IOException("File APK kosong")
                val declaredLength = body.contentLength()
                val totalBytes = when {
                    declaredLength > 0L -> declaredLength
                    release.apkSizeBytes > 0L -> release.apkSizeBytes
                    else -> -1L
                }
                if (totalBytes > MAX_APK_BYTES) {
                    throw IOException("Ukuran APK melebihi batas aman 250 MB.")
                }

                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(16 * 1024)
                var downloaded = 0L
                var lastUiUpdateMs = 0L

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            downloaded += read
                            if (downloaded > MAX_APK_BYTES) {
                                throw IOException("Ukuran file melebihi batas aman 250 MB.")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)

                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdateMs >= 80L || (totalBytes > 0L && downloaded == totalBytes)) {
                                lastUiUpdateMs = now
                                val progress = if (totalBytes > 0L) {
                                    (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    null
                                }
                                _status.value = AppUpdateStatus.Downloading(
                                    release = release,
                                    progress = progress,
                                    downloadedBytes = downloaded,
                                    totalBytes = totalBytes
                                )
                            }
                        }
                        output.flush()
                    }
                }
                computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            }

            val expectedSha = release.expectedSha256?.lowercase()
            if (expectedSha != null && computedSha256 != expectedSha) {
                partFile.delete()
                val err = AppUpdateStatus.Error("Verifikasi SHA-256 APK gagal. File rusak atau tidak cocok.")
                _status.value = err
                return@withContext err
            }

            if (!isValidTargetApk(partFile)) {
                partFile.delete()
                val err = AppUpdateStatus.Error(
                    "File yang diunduh bukan paket pembaruan valid untuk ${appContext.packageName}."
                )
                _status.value = err
                return@withContext err
            }

            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }

            val ready = AppUpdateStatus.ReadyToInstall(
                release = release,
                apkFilePath = finalFile.absolutePath,
                sha256Verified = expectedSha != null
            )
            _status.value = ready
            ready
        } catch (ce: CancellationException) {
            partFile.delete()
            _status.value = AppUpdateStatus.UpdateAvailable(currentVersionName, release)
            throw ce
        } catch (se: SecurityException) {
            partFile.delete()
            val err = AppUpdateStatus.Error(se.message ?: "Sumber unduhan ditolak.")
            _status.value = err
            err
        } catch (t: Throwable) {
            partFile.delete()
            val err = AppUpdateStatus.Error(
                "Unduhan gagal: ${t.localizedMessage ?: "Periksa koneksi internet Anda"}"
            )
            _status.value = err
            err
        } finally {
            activeCall = null
        }
    }

    fun cancelActiveDownload() {
        activeCall?.cancel()
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun createUnknownSourcesSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createInstallApkIntent(apkFilePath: String): Intent? {
        val updatesDir = File(appContext.cacheDir, UPDATES_SUBDIR)
        val apkFile = File(apkFilePath)
        if (!apkFile.exists()) return null
        if (!apkFile.canonicalPath.startsWith(updatesDir.canonicalPath + File.separator)) {
            return null
        }
        val contentUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun isValidTargetApk(file: File): Boolean {
        val pm = appContext.packageManager
        val info: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, 0)
        }
        return info != null && info.packageName == appContext.packageName
    }

    private data class ParsedAsset(
        val name: String,
        val downloadUrl: String,
        val size: Long,
        val sha256: String?
    )

    private fun selectApkAsset(assets: JSONArray): ParsedAsset? {
        val candidates = mutableListOf<ParsedAsset>()
        for (i in 0 until assets.length()) {
            val obj = assets.optJSONObject(i) ?: continue
            val name = obj.optString("name", "").trim()
            val url = obj.optString("browser_download_url", "").trim()
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val parsed = url.toHttpUrlOrNull() ?: continue
            if (!AppVersionComparator.isTrustedGitHubUrl(parsed)) continue

            val rawDigest = obj.optString("digest", "").trim()
            val sha256 = if (rawDigest.startsWith("sha256:", ignoreCase = true)) {
                rawDigest.substringAfter(':').trim().takeIf { it.length == 64 }
            } else {
                null
            }

            candidates += ParsedAsset(
                name = name,
                downloadUrl = url,
                size = obj.optLong("size", 0L),
                sha256 = sha256
            )
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull {
            val lower = it.name.lowercase()
            lower.contains("universal") || lower.contains("arm64") || lower.contains("release")
        } ?: candidates.first()
    }
}
