package com.tagasay.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionRoutesTest {

    @Test
    fun versionEndpointReturnsHashAndServesApk() = testApplication {
        val dir = createTempDirectory("taga-update").toFile()
        val apk = File(dir, "latest.apk").apply {
            writeBytes(ByteArray(4096) { (it % 251).toByte() })
        }
        File(dir, "version.json").writeText(
            """
            {
              "versionCode": 5,
              "versionName": "1.0.3",
              "mandatory": true,
              "minSupportedVersionCode": 4,
              "sha256": "",
              "downloadUrl": "https://github.com/geekAmis/TAGa-Say/releases/download/v1.0.3/TAGa-Say-1.0.3.apk",
              "notes": "test"
            }
            """.trimIndent(),
        )
        val updateStore = UpdateStore(dir.path, apk.path, "https://relay.test")
        application { module(updateStore = updateStore) }

        val info = client.get("/v1/version")
        assertEquals(HttpStatusCode.OK, info.status)
        val body = info.bodyAsText()
        assertTrue(body.contains("\"versionCode\":5"))
        assertTrue(body.contains("\"mandatory\":true"))
        assertTrue(body.contains("\"mirrorUrl\":\"https://relay.test/v1/version/apk\""))
        assertTrue(Regex("\"sha256\":\"[0-9a-f]{64}\"").containsMatchIn(body))

        val apkResponse = client.get("/v1/version/apk")
        assertEquals(HttpStatusCode.OK, apkResponse.status)
        assertContentEquals(apk.readBytes(), apkResponse.bodyAsBytes())

        dir.deleteRecursively()
    }

    @Test
    fun emptyUpdateDirectoryReturnsUnavailable() = testApplication {
        val dir = createTempDirectory("taga-update-empty").toFile()
        application { module(updateStore = UpdateStore(dir.path, null, "https://relay.test")) }
        val info = client.get("/v1/version")
        assertEquals(HttpStatusCode.OK, info.status)
        assertTrue(info.bodyAsText().contains("\"available\":false"))
        dir.deleteRecursively()
    }
}
