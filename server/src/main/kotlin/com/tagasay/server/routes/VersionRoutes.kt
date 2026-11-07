package com.tagasay.server.routes

import com.tagasay.server.RateLimits
import com.tagasay.server.UpdateStore
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Публичная политика обновлений. Не требует авторизации: приложение должно
 * проверить версию ещё до онбординга. Целостность APK проверяется SHA-256,
 * подлинность — подписью release-сертификата на клиенте.
 */
fun Route.versionRoutes(updateStore: UpdateStore) {
    get("/v1/version") {
        if (!RateLimits.allow(call, "version", limit = 120)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate limited"))
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
        call.respond(updateStore.info())
    }

    get("/v1/version/apk") {
        if (!RateLimits.allow(call, "version-apk", limit = 10)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate limited"))
            return@get
        }
        val file = updateStore.apkFile()
        if (file == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no apk"))
            return@get
        }
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"${file.name}\"",
        )
        call.respondFile(file)
    }
}
