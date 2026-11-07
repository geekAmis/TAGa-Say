package com.tagasay.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class UpdateInfoResponse(
    val versionCode: Int = 0,
    val versionName: String = "",
    val mandatory: Boolean = false,
    val minSupportedVersionCode: Int = 0,
    val sha256: String = "",
    val downloadUrl: String? = null,
    val mirrorUrl: String? = null,
    val notes: String = "",
    val available: Boolean = false,
)

@Serializable
private data class UpdateConfig(
    val versionCode: Int = 0,
    val versionName: String = "",
    val mandatory: Boolean = false,
    val minSupportedVersionCode: Int = 0,
    val sha256: String = "",
    val downloadUrl: String? = null,
    val notes: String = "",
)

/**
 * Политика обновлений. Читает `version.json` из `TAGASAY_UPDATE_DIR`
 * (по умолчанию `data/updates`) и отдаёт приложению версию, флаг
 * обязательности и SHA-256 APK. Если в каталоге лежит `latest.apk`,
 * сервер сам считает его хеш и может отдать файл как зеркало.
 *
 * Ключи и токены здесь не нужны: APK раздаётся с публичного GitHub Release,
 * а хеш из этого файла — единственный источник истины для клиента.
 */
class UpdateStore(
    private val dir: String = System.getenv("TAGASAY_UPDATE_DIR") ?: "data/updates",
    private val apkPath: String? = System.getenv("TAGASAY_UPDATE_APK"),
    private val publicUrl: String? = System.getenv("TAGASAY_PUBLIC_URL"),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val configFile = File(dir, "version.json")

    @Volatile
    private var hashCache: Pair<String, String>? = null // "size:mtime" -> sha256

    fun info(): UpdateInfoResponse {
        val config = readConfig() ?: return UpdateInfoResponse()
        val apk = apkFile()
        val hash = if (apk != null) sha256(apk) else config.sha256.trim().lowercase()
        val download = config.downloadUrl
            ?.trim()
            ?.takeIf { it.startsWith("https://", ignoreCase = true) }
        val mirror = if (apk != null && !publicUrl.isNullOrBlank()) {
            publicUrl.trimEnd('/') + "/v1/version/apk"
        } else {
            null
        }
        val available = config.versionCode > 0 &&
            hash.length == 64 &&
            (download != null || mirror != null)
        return UpdateInfoResponse(
            versionCode = config.versionCode,
            versionName = config.versionName,
            mandatory = config.mandatory,
            minSupportedVersionCode = config.minSupportedVersionCode,
            sha256 = hash,
            downloadUrl = download,
            mirrorUrl = mirror,
            notes = config.notes,
            available = available,
        )
    }

    fun apkFile(): File? {
        apkPath?.let { path ->
            File(path).takeIf { it.isFile }?.let { return it }
        }
        val config = readConfig() ?: return null
        return listOf(
            File(dir, "latest.apk"),
            File(dir, "TAGa-Say-${config.versionName}.apk"),
        ).firstOrNull { it.isFile }
    }

    fun sha256(file: File): String {
        val stamp = "${file.length()}:${file.lastModified()}"
        hashCache?.let { if (it.first == stamp) return it.second }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        hashCache = stamp to hash
        return hash
    }

    private fun readConfig(): UpdateConfig? {
        if (!configFile.isFile) return null
        return runCatching {
            json.decodeFromString(UpdateConfig.serializer(), configFile.readText())
        }.getOrNull()
    }
}
