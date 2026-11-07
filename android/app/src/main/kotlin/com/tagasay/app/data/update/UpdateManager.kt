package com.tagasay.app.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.tagasay.app.BuildConfig
import com.tagasay.app.data.local.SettingsStore
import com.tagasay.app.data.model.UpdateInfo
import com.tagasay.app.data.model.UpdateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Проверка и установка обновлений.
 *
 * Сервер отдаёт версию, флаг mandatory и SHA-256 APK. APK скачивается с
 * GitHub Release (или с зеркала relay), проверяется по хешу и по подписи
 * release-сертификата. Обязательное обновление блокирует интерфейс.
 */
class UpdateManager(
    context: Context,
    private val settingsStore: SettingsStore,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .callTimeout(20, TimeUnit.MINUTES)
        .build()

    private val _status = MutableStateFlow(UpdateStatus())
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    @Volatile
    private var info: UpdateInfo? = null

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                check()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    suspend fun check() {
        val serverUrl = runCatching { settingsStore.settings.first().serverUrl }
            .getOrDefault("")
            .trim()
            .trimEnd('/')
        if (serverUrl.isBlank()) return

        runCatching { fetchInfo(serverUrl) }
            .onSuccess { remote ->
                if (!remote.available || remote.versionCode <= BuildConfig.VERSION_CODE) {
                    info = null
                    _status.value = UpdateStatus()
                    if (!remote.mandatory) clearMandatory()
                    return@onSuccess
                }
                val mandatory = remote.mandatory ||
                    BuildConfig.VERSION_CODE < remote.minSupportedVersionCode
                val dismissed = !mandatory && prefs.getInt(KEY_DISMISSED_VERSION, 0) >= remote.versionCode
                info = remote
                if (mandatory) cacheMandatory(remote) else clearMandatory()
                _status.value = UpdateStatus(
                    available = true,
                    mandatory = mandatory,
                    versionName = remote.versionName,
                    versionCode = remote.versionCode,
                    notes = remote.notes,
                    dismissed = dismissed,
                )
            }
            .onFailure {
                Log.w(TAG, "update check failed: ${it.message}")
                // Офлайн: обязательный блок переживает перезапуск.
                loadCachedMandatory()?.let { cached ->
                    if (cached.versionCode > BuildConfig.VERSION_CODE) {
                        info = cached
                        _status.value = UpdateStatus(
                            available = true,
                            mandatory = true,
                            versionName = cached.versionName,
                            versionCode = cached.versionCode,
                            notes = cached.notes,
                        )
                    }
                }
            }
    }

    fun dismissOptional() {
        val current = _status.value
        if (current.mandatory || current.versionCode <= 0) return
        prefs.edit().putInt(KEY_DISMISSED_VERSION, current.versionCode).apply()
        _status.update { it.copy(dismissed = true) }
    }

    suspend fun downloadAndInstall() {
        val target = info ?: return
        _status.update {
            it.copy(downloading = true, progress = 0, error = null, readyToInstall = false)
        }
        val cached = File(appContext.filesDir, "updates/TAGa-Say-${target.versionCode}.apk")
        val cachedValid = withContext(Dispatchers.IO) {
            cached.isFile &&
                target.sha256.length == 64 &&
                sha256(cached).equals(target.sha256, ignoreCase = true) &&
                (BuildConfig.DEBUG || verifySignature(cached))
        }
        if (cachedValid) {
            _status.update {
                it.copy(downloading = false, progress = 100, readyToInstall = true)
            }
            launchInstaller(cached)
            return
        }
        try {
            val file = download(target) { percent ->
                _status.update { it.copy(progress = percent) }
            }
            val expected = target.sha256.trim().lowercase()
            require(expected.length == 64) { "Сервер не вернул SHA-256 обновления" }
            val actual = sha256(file)
            require(actual.equals(expected, ignoreCase = true)) {
                "SHA-256 обновления не совпадает"
            }
            if (!BuildConfig.DEBUG) {
                require(verifySignature(file)) {
                    "APK подписан не release-сертификатом"
                }
            }
            _status.update {
                it.copy(downloading = false, progress = 100, readyToInstall = true)
            }
            launchInstaller(file)
        } catch (e: Exception) {
            Log.w(TAG, "update failed: ${e.message}", e)
            _status.update {
                it.copy(
                    downloading = false,
                    error = e.message ?: "Не удалось скачать обновление",
                )
            }
        }
    }

    private fun fetchInfo(serverUrl: String): UpdateInfo {
        val request = Request.Builder().url("$serverUrl/v1/version").get().build()
        checkClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val text = response.body?.string() ?: error("empty response")
            return json.decodeFromString(UpdateInfo.serializer(), text)
        }
    }

    private suspend fun download(
        target: UpdateInfo,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val urls = listOfNotNull(
            target.downloadUrl?.takeIf { it.startsWith("https://", ignoreCase = true) },
            target.mirrorUrl?.takeIf { it.startsWith("https://", ignoreCase = true) },
        ).distinct()
        require(urls.isNotEmpty()) { "Нет HTTPS-ссылки на обновление" }

        val dir = File(appContext.filesDir, "updates").apply { mkdirs() }
        val file = File(dir, "TAGa-Say-${target.versionCode}.apk")
        var lastError: Exception? = null

        for (url in urls) {
            try {
                val request = Request.Builder().url(url).get().build()
                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("empty body")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            var lastPercent = -1
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                written += read
                                if (total > 0) {
                                    val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        onProgress(percent)
                                    }
                                }
                            }
                        }
                    }
                }
                return@withContext file
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "download from $url failed: ${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("Не удалось скачать APK")
    }

    private fun verifySignature(file: File): Boolean {
        val pm = appContext.packageManager
        val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        } ?: return false

        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            archive.signatures?.firstOrNull()
        } ?: return false

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return hash.equals(BuildConfig.RELEASE_CERT_SHA256, ignoreCase = true)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun launchInstaller(file: File) {
        val pm = appContext.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            _status.update {
                it.copy(
                    error = "Разрешите установку из этого источника и нажмите «Установить» ещё раз",
                )
            }
            return
        }
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    private fun cacheMandatory(value: UpdateInfo) {
        prefs.edit()
            .putString(KEY_MANDATORY, json.encodeToString(UpdateInfo.serializer(), value))
            .putLong(KEY_MANDATORY_AT, System.currentTimeMillis())
            .apply()
    }

    private fun clearMandatory() {
        prefs.edit().remove(KEY_MANDATORY).remove(KEY_MANDATORY_AT).apply()
    }

    private fun loadCachedMandatory(): UpdateInfo? {
        val raw = prefs.getString(KEY_MANDATORY, null) ?: return null
        val at = prefs.getLong(KEY_MANDATORY_AT, 0)
        if (at <= 0 || System.currentTimeMillis() - at > MANDATORY_CACHE_TTL_MS) {
            clearMandatory()
            return null
        }
        return runCatching {
            json.decodeFromString(UpdateInfo.serializer(), raw)
        }.getOrNull()
    }

    private companion object {
        const val TAG = "TAGaUpdate"
        const val PREFS = "taga_update"
        const val KEY_DISMISSED_VERSION = "dismissed_version"
        const val KEY_MANDATORY = "mandatory_info"
        const val KEY_MANDATORY_AT = "mandatory_at"
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val MANDATORY_CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
