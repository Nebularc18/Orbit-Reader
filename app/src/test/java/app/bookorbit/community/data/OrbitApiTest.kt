package app.bookorbit.community.data

import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OrbitApiTest {
    private lateinit var server: MockWebServer
    private lateinit var store: MemorySessions
    private lateinit var api: OrbitApi

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        store =
            MemorySessions(
                Session(
                    server.url("/books").toString().trimEnd('/'),
                    "old-access",
                    "refresh-token",
                    Account(7, username = "reader"),
                )
            )
        api = OrbitApi(store)
    }

    @After
    fun cleanup() {
        server.shutdown()
    }

    @Test
    fun `native sign in sends only accepted DTO fields and keeps base path`() {
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"accessToken":"a","refreshToken":"r","user":{"id":7,"username":"reader"},"accessTokenExpiresAt":"ignored"}"""
                )
        )
        api.login(server.url("/books/api/v1/").toString(), " reader ", "password")
        val request = server.takeRequest()
        assertEquals("/books/api/v1/auth/login", request.path)
        assertEquals("POST", request.method)
        val body = wireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("username", "password", "clientKind", "deviceLabel"), body.keys)
        assertEquals("native", body["clientKind"]!!.jsonPrimitive.content)
        assertEquals("reader", body["username"]!!.jsonPrimitive.content)
        assertEquals("r", store.read()!!.refreshToken)
    }

    @Test
    fun `library queries remain bounded and use server search and audio filter`() {
        server.enqueue(MockResponse().setBody("""{"items":[],"total":13000,"page":4,"size":30}"""))
        val response = api.books(4, "Le Guin")
        assertEquals(13000, response.total)
        val request = server.takeRequest()
        assertEquals("Bearer old-access", request.getHeader("Authorization"))
        val body = wireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(30, body["pagination"]!!.jsonObject["size"]!!.jsonPrimitive.int)
        assertEquals(4, body["pagination"]!!.jsonObject["page"]!!.jsonPrimitive.int)
        assertEquals("Le Guin", body["q"]!!.jsonPrimitive.content)
        assertEquals(
            "includesAny",
            body["filter"]!!
                .jsonObject["rules"]!!
                .jsonArray[0]
                .jsonObject["operator"]!!
                .jsonPrimitive
                .content,
        )
        assertEquals("asc", body["sort"]!!.jsonArray[0].jsonObject["dir"]!!.jsonPrimitive.content)
    }

    @Test
    fun `401 rotates native credentials and retries exactly once`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse().setBody("""{"accessToken":"new-access","refreshToken":"new-refresh"}""")
        )
        server.enqueue(MockResponse().setBody("null"))
        assertNull(api.progress(5))
        assertEquals("Bearer old-access", server.takeRequest().getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/books/api/v1/auth/refresh", refresh.path)
        assertEquals(
            "refresh-token",
            wireJson
                .parseToJsonElement(refresh.body.readUtf8())
                .jsonObject["refreshToken"]!!
                .jsonPrimitive
                .content,
        )
        assertEquals("Bearer new-access", server.takeRequest().getHeader("Authorization"))
        assertEquals("new-refresh", store.read()!!.refreshToken)
    }

    @Test
    fun `conflict never retries a progress write with a newer revision`() {
        server.enqueue(MockResponse().setResponseCode(409))
        val position =
            LocalPosition(
                "aud_11111111-1111-4111-8111-111111111111",
                42000,
                "2026-10-18T12:00:00Z",
                3,
                "a".repeat(64),
            )
        try {
            api.saveProgress(5, position.write())
            fail("Expected conflict")
        } catch (error: ApiFailure) {
            assertEquals(409, error.status)
        }
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        val fields = wireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("PUT", request.method)
        assertEquals(
            setOf(
                "assetId",
                "positionMs",
                "capturedAt",
                "operationId",
                "baseRevision",
                "manifestRevision",
            ),
            fields.keys,
        )
        assertEquals(3, fields["baseRevision"]!!.jsonPrimitive.int)
        assertEquals(42000, fields["positionMs"]!!.jsonPrimitive.int)
    }

    @Test
    fun `redirects are not followed with account credentials`() {
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", "https://other.example/steal")
        )
        try {
            api.progress(5)
            fail("Expected redirect rejection")
        } catch (error: ApiFailure) {
            assertEquals(302, error.status)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `authenticated transport refuses another origin before sending a token`() {
        try {
            api.authenticatedClient
                .newCall(Request.Builder().url("https://other.example/api/v1/books/1").build())
                .execute()
                .close()
            fail("Expected origin rejection")
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `logout clears local credentials even if server is unavailable`() {
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            api.logout()
        } catch (_: ApiFailure) {}
        assertNull(store.read())
    }

    @Test
    fun `scope isolates users and servers`() {
        val first = store.read()!!
        assertNotEquals(first.scope, first.copy(user = first.user.copy(id = 8)).scope)
        assertNotEquals(first.scope, first.copy(server = "https://elsewhere.example").scope)
        assertEquals(first.scope, first.copy(accessToken = "rotated").scope)
    }

    @Test
    fun `server URLs reject embedded credentials`() {
        try {
            normalizeServer("https://reader:password@books.example.com")
            fail("Expected rejection")
        } catch (_: IllegalArgumentException) {}
        assertEquals(
            "https://books.example.com/prefix",
            normalizeServer("https://books.example.com/prefix/api/v1/"),
        )
    }
}

private class MemorySessions(private var value: Session?) : SessionStore {
    override fun read() = value

    override fun save(session: Session?) {
        value = session
    }
}
