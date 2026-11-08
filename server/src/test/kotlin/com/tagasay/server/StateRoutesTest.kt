package com.tagasay.server

import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.model.Device
import com.tagasay.server.model.UserProfile
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StateRoutesTest {

    private suspend fun register(store: InMemoryStore): String {
        val device = Device(
            deviceId = "device-1",
            username = "stateuser",
            identityKey = "identity",
            signingKey = "signing",
            passwordVerifier = "verifier",
            deviceHash = "hash",
            signedPrekey = "prekey",
            signedPrekeySignature = "prekey-signature",
            oneTimePrekeys = emptyList(),
            createdAt = System.currentTimeMillis(),
        )
        val profile = UserProfile(
            username = "stateuser",
            displayName = "State User",
            identityKey = "identity",
        )
        val (access, _) = store.register(device, profile, null)
        return access
    }

    @Test
    fun stateRoundTripAndVersionIncrements() = testApplication {
        val store = InMemoryStore()
        val token = runBlocking { register(store) }
        application { module(store = store, updateStore = UpdateStore("missing", null, null)) }

        val empty = client.get("/v1/state") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, empty.status)
        assertTrue(empty.bodyAsText().contains("\"version\":0"))

        val first = client.put("/v1/state") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"ciphertext":"YWJj","updatedAt":123}""")
        }
        assertEquals(HttpStatusCode.OK, first.status)
        assertTrue(first.bodyAsText().contains("\"version\":1"))

        val second = client.put("/v1/state") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"ciphertext":"ZGVm","updatedAt":456}""")
        }
        assertTrue(second.bodyAsText().contains("\"version\":2"))

        val loaded = client.get("/v1/state") {
            header("Authorization", "Bearer $token")
        }
        val body = loaded.bodyAsText()
        assertTrue(body.contains("\"ciphertext\":\"ZGVm\""))
        assertTrue(body.contains("\"updatedAt\":456"))
    }

    @Test
    fun stateRequiresAuth() = testApplication {
        val store = InMemoryStore()
        application { module(store = store, updateStore = UpdateStore("missing", null, null)) }
        val response = client.get("/v1/state")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
