package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.LanguageCatalog
import com.example.data.repository.TranslationRepository
import com.example.updater.AppUpdateManager
import com.example.updater.AppUpdateStatus
import com.example.updater.AppVersionComparator
import com.example.updater.ReleaseInfo
import com.example.voice.VoiceRecognizerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SuaraLingo", appName)
    }

    @Test
    fun `language catalog includes Indonesian and English`() {
        val id = LanguageCatalog.findByCode("id")
        val en = LanguageCatalog.findByCode("en")
        assertEquals("id-ID", id.localeTag)
        assertEquals("en-US", en.localeTag)
        assertTrue(LanguageCatalog.allLanguages.size >= 15)
    }

    @Test
    fun `repository rejects invalid language codes`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = TranslationRepository(db.translationDao())
            assertFalse(repo.isSupportedLanguageCode("xx-invalid"))
            val result = repo.translateText(
                text = "Halo",
                sourceCode = "../invalid",
                targetCode = "en",
                isOfflineMode = true
            )
            assertTrue(result.isFailure)
        } finally {
            db.close()
        }
    }

    @Test
    fun `voice recognizer enforces audio permission before listening`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val manager = VoiceRecognizerManager(context, scope)
            manager.startListening("id-ID", preferOffline = false)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(manager.state.value.isListening)
            assertEquals("Izin mikrofon belum diberikan.", manager.state.value.errorMessage)
            manager.release()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `translation repository does not swallow coroutine cancellation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = TranslationRepository(db.translationDao())
            val job = launch {
                repo.translateText(
                    text = "Halo dunia",
                    sourceCode = "id",
                    targetCode = "id",
                    isOfflineMode = true
                )
            }
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
        } finally {
            db.close()
        }
    }

    @Test
    fun `version comparator normalizes github slugs and rejects untrusted hosts`() {
        assertEquals("user/SuaraLingo", AppVersionComparator.normalizeRepoSlug("user/SuaraLingo"))
        assertEquals(
            "user/SuaraLingo",
            AppVersionComparator.normalizeRepoSlug("https://github.com/user/SuaraLingo.git")
        )
        assertNull(AppVersionComparator.normalizeRepoSlug("../etc/passwd"))
        assertNull(AppVersionComparator.normalizeRepoSlug("https://evil.com/user/SuaraLingo"))
        assertNull(AppVersionComparator.normalizeRepoSlug("http://github.com/user/SuaraLingo"))

        assertTrue(AppVersionComparator.isTrustedGitHubUrl("https://api.github.com/repos/a/b".toHttpUrl()))
        assertTrue(
            AppVersionComparator.isTrustedGitHubUrl(
                "https://objects.githubusercontent.com/github-production-release-asset/app.apk".toHttpUrl()
            )
        )
        assertFalse(AppVersionComparator.isTrustedGitHubUrl("http://github.com/a/b".toHttpUrl()))
        assertFalse(AppVersionComparator.isTrustedGitHubUrl("https://127.0.0.1/app.apk".toHttpUrl()))
    }

    @Test
    fun `semantic version comparator avoids false update on trailing zero`() {
        assertFalse(AppVersionComparator.isRemoteVersionNewer("1.0", "v1.0.0"))
        assertTrue(AppVersionComparator.isRemoteVersionNewer("1.0", "v1.0.1"))
        assertTrue(AppVersionComparator.isRemoteVersionNewer("1.2.3", "v1.10.0"))
        assertFalse(AppVersionComparator.isRemoteVersionNewer("2.0.0", "v1.9.9"))
        assertFalse(AppVersionComparator.isRemoteVersionNewer("1.2.0", "v1.2.0-beta1"))
    }

    @Test
    fun `app update manager parses release and rejects mismatched sha256`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("api.github.com")) {
                    val json = """
                        {
                          "tag_name": "v1.2.0",
                          "name": "SuaraLingo 1.2.0",
                          "body": "Perbaikan suara offline",
                          "draft": false,
                          "prerelease": false,
                          "assets": [
                            {
                              "name": "suaralingo-release.apk",
                              "browser_download_url": "https://github.com/user/SuaraLingo/releases/download/v1.2.0/suaralingo-release.apk",
                              "size": 12,
                              "digest": "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                            }
                          ]
                        }
                    """.trimIndent()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(json.toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("not-matching-apk".toByteArray().toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                }
            }
            .build()

        val manager = AppUpdateManager(
            context = context,
            currentVersionName = "1.0",
            httpClient = fakeClient
        )
        val checkStatus = manager.checkForUpdate("user/SuaraLingo")
        assertTrue(checkStatus is AppUpdateStatus.UpdateAvailable)
        val release: ReleaseInfo = (checkStatus as AppUpdateStatus.UpdateAvailable).release
        assertEquals("v1.2.0", release.tagName)

        val dlStatus = manager.downloadUpdate(release)
        assertTrue(dlStatus is AppUpdateStatus.Error)
        assertTrue((dlStatus as AppUpdateStatus.Error).message.contains("SHA-256"))
    }
}
