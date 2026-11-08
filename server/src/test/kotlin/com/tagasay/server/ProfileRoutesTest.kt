package com.tagasay.server

import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.model.Device
import com.tagasay.server.model.UserProfile
import io.ktor.client.request.header
import io.ktor.client.request.post
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

class ProfileRoutesTest {

    @Test
    fun profileUpdateIsPersistedAndReturned() = testApplication {
        val store = InMemoryStore()
        val token = runBlocking {
            val device = Device(
                deviceId = "profile-device",
                username = "profileuser",
                identityKey = "identity",
                signingKey = "signing",
                passwordVerifier = "verifier",
                deviceHash = "hash",
                signedPrekey = "prekey",
                signedPrekeySignature = "signature",
                oneTimePrekeys = emptyList(),
                createdAt = System.currentTimeMillis(),
            )
            val profile = UserProfile(
                username = "profileuser",
                displayName = "Old Name",
                identityKey = "identity",
            )
            store.register(device, profile, null).first
        }
        application { module(store = store, updateStore = UpdateStore("missing", null, null)) }

        val response = client.post("/v1/profile") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"New Name","bio":"hello"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("New Name"))
        assertEquals("New Name", store.user("profileuser")?.displayName)
        assertEquals("hello", store.user("profileuser")?.bio)
    }
}
