package com.tagasay.server

import com.tagasay.server.data.InMemoryStore
import com.tagasay.server.model.Device
import com.tagasay.server.model.UserProfile
import io.ktor.client.request.get
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

class RoomRoutesTest {

    private suspend fun register(store: InMemoryStore, username: String): String {
        val device = Device(
            deviceId = "$username-device",
            username = username,
            identityKey = "identity",
            signingKey = "signing",
            passwordVerifier = "verifier",
            deviceHash = "hash",
            signedPrekey = "prekey",
            signedPrekeySignature = "signature",
            oneTimePrekeys = emptyList(),
            createdAt = System.currentTimeMillis(),
        )
        return store.register(
            device,
            UserProfile(username, username, "identity"),
            null,
        ).first
    }

    @Test
    fun createGroupAddMemberAndIsolation() = testApplication {
        val store = InMemoryStore()
        val alice = runBlocking { register(store, "alice") }
        val bob = runBlocking { register(store, "bob") }
        val carol = runBlocking { register(store, "carol") }
        application { module(store = store, updateStore = UpdateStore("missing", null, null)) }

        val created = client.post("/v1/rooms") {
            header("Authorization", "Bearer $alice")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Team","username":"team","members":["bob"]}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val body = created.bodyAsText()
        assertTrue(body.contains("\"title\":\"Team\""))
        assertTrue(body.contains("\"bob\""))
        val id = Regex("\"id\":\"(room_[^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()

        val bobSees = client.get("/v1/rooms") {
            header("Authorization", "Bearer $bob")
        }
        assertTrue(bobSees.bodyAsText().contains("Team"))

        val carolSees = client.get("/v1/rooms") {
            header("Authorization", "Bearer $carol")
        }
        assertTrue(!carolSees.bodyAsText().contains("Team"))

        val carolReads = client.get("/v1/rooms/$id") {
            header("Authorization", "Bearer $carol")
        }
        assertEquals(HttpStatusCode.Forbidden, carolReads.status)

        val added = client.post("/v1/rooms/$id/members") {
            header("Authorization", "Bearer $alice")
            contentType(ContentType.Application.Json)
            setBody("""{"username":"carol"}""")
        }
        assertEquals(HttpStatusCode.OK, added.status)
        assertTrue(added.bodyAsText().contains("carol"))
    }

    @Test
    fun unknownUserCannotBeAddedToRoom() = testApplication {
        val store = InMemoryStore()
        val owner = runBlocking { register(store, "owner3") }
        application { module(store = store, updateStore = UpdateStore("missing", null, null)) }

        val created = client.post("/v1/rooms") {
            header("Authorization", "Bearer $owner")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"NoGhosts","username":"noghosts"}""")
        }
        val id = Regex("\"id\":\"(room_[^\"]+)\"").find(created.bodyAsText())?.groupValues?.get(1).orEmpty()
        val add = client.post("/v1/rooms/$id/members") {
            header("Authorization", "Bearer $owner")
            contentType(ContentType.Application.Json)
            setBody("""{"username":"ghost"}""")
        }
        assertEquals(HttpStatusCode.NotFound, add.status)
    }

    @Test
    fun channelCanBeJoinedAndReadBySubscriber() = testApplication {
        val store = InMemoryStore()
        val owner = runBlocking { register(store, "owner") }
        val reader = runBlocking { register(store, "reader") }
        application { module(store = store, updateStore = UpdateStore("missing", null, null)) }

        val created = client.post("/v1/rooms") {
            header("Authorization", "Bearer $owner")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"News","username":"newsroom","channel":true}""")
        }
        val id = Regex("\"id\":\"(room_[^\"]+)\"").find(created.bodyAsText())?.groupValues?.get(1).orEmpty()

        val joined = client.post("/v1/rooms/$id/join") {
            header("Authorization", "Bearer $reader")
        }
        assertEquals(HttpStatusCode.OK, joined.status)

        val read = client.get("/v1/rooms/$id") {
            header("Authorization", "Bearer $reader")
        }
        assertEquals(HttpStatusCode.OK, read.status)
        assertTrue(read.bodyAsText().contains("News"))
    }

    @Test
    fun groupJoinRequiresInvite() = testApplication {
        val store = InMemoryStore()
        val owner = runBlocking { register(store, "owner2") }
        val stranger = runBlocking { register(store, "stranger") }
        application { module(store = store, updateStore = UpdateStore("missing", null, null)) }

        val created = client.post("/v1/rooms") {
            header("Authorization", "Bearer $owner")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Secret","username":"secret"}""")
        }
        val id = Regex("\"id\":\"(room_[^\"]+)\"").find(created.bodyAsText())?.groupValues?.get(1).orEmpty()
        val join = client.post("/v1/rooms/$id/join") {
            header("Authorization", "Bearer $stranger")
        }
        assertEquals(HttpStatusCode.Forbidden, join.status)
    }
}
