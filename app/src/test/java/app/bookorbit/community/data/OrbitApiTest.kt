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
    fun `dashboard requests bounded shelves using the current account scope`() {
        val owner = store.read()!!.scope
        listOf("continue-listening", "continue-reading", "up-next-in-series").forEach { type ->
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"books":[{"id":2,"title":"Orbit","files":[{"id":101,"format":"epub"}],"seriesName":"Quiet Orbit"}],"total":99}"""
                    )
            )
            val shelf = api.dashboard(type, owner)
            assertEquals("Quiet Orbit", shelf.books.single().seriesName)
            assertTrue(shelf.books.single().files.single().supported)
            assertEquals(
                "/books/api/v1/dashboard/scrollers/$type?limit=10",
                server.takeRequest().path,
            )
        }
        try {
            api.dashboard("continue-reading", "another-account")
            fail("Expected account scope rejection")
        } catch (error: ApiFailure) {
            assertEquals(401, error.status)
        }
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
        server.enqueue(MockResponse().setBody("""{"items":[],"total":13000,"page":3,"size":30}"""))
        val response = api.books(4, "Le Guin")
        assertEquals(13000, response.total)
        val request = server.takeRequest()
        assertEquals("Bearer old-access", request.getHeader("Authorization"))
        val body = wireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(30, body["pagination"]!!.jsonObject["size"]!!.jsonPrimitive.int)
        assertEquals(3, body["pagination"]!!.jsonObject["page"]!!.jsonPrimitive.int)
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
    fun `first displayed library page requests server page zero`() {
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"items":[{"id":1,"title":"First book"}],"total":1,"page":0,"size":30}"""
                )
        )
        assertEquals("First book", api.books(1, "").items.single().title)
        val body = wireJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(0, body["pagination"]!!.jsonObject["page"]!!.jsonPrimitive.int)
    }

    @Test
    fun `resumed download keeps identity encoding and conditional range headers`() {
        val asset =
            AudioAsset(
                "aud_00000000-0000-4000-8000-000000000001",
                1,
                0,
                "mp3",
                etag = "manifest-version",
            )
        server.enqueue(MockResponse().setResponseCode(206).setBody("remaining audio"))
        api.download(asset, store.read()!!.scope, 8192, "\"http-version\"").close()
        val request = server.takeRequest()
        assertEquals("/books/api/v1/books/files/1/download", request.path)
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        assertEquals("bytes=8192-", request.getHeader("Range"))
        assertEquals("\"http-version\"", request.getHeader("If-Range"))
        assertEquals("Bearer old-access", request.getHeader("Authorization"))
    }

    @Test
    fun `download queued for another account fails before making a request`() {
        val asset =
            AudioAsset(
                "aud_00000000-0000-4000-8000-000000000001",
                1,
                0,
                "mp3",
                etag = "manifest-version",
            )
        try {
            api.download(asset, "different-account").close()
            fail("Expected account scope rejection")
        } catch (error: ApiFailure) {
            assertEquals(401, error.status)
        }
        assertEquals(0, server.requestCount)
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

    @Test
    fun `ebook library uses bounded server search with EPUB formats`() {
        server.enqueue(MockResponse().setBody("""{"items":[],"total":0,"page":0,"size":30}"""))
        api.books(1, "Orbit", ebooks = true)
        val request = server.takeRequest()
        val body = wireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        val rule = body["filter"]!!.jsonObject["rules"]!!.jsonArray.single().jsonObject
        assertEquals(
            listOf("epub", "kepub", "pdf", "cbz", "cbr", "cb7"),
            rule["value"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(0, body["pagination"]!!.jsonObject["page"]!!.jsonPrimitive.int)
        assertEquals(30, body["pagination"]!!.jsonObject["size"]!!.jsonPrimitive.int)
        assertEquals("Orbit", body["q"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ebook detail parses server file models and content requests enforce account scope`() {
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"id":2,"title":"Orbit","authors":[{"id":4,"name":"Writer"}],"files":[{"id":101,"format":"epub","sizeBytes":123,"fileHash":"hash","absolutePath":"ignored"}]}"""
                )
        )
        val owner = store.read()!!.scope
        val detail = api.ebookDetail(2, owner)
        assertEquals("Writer", detail.authors.single().name)
        assertEquals("hash", detail.files.single().revision)
        assertEquals("/books/api/v1/books/2", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("epub"))
        api.ebookContent(101, owner).close()
        val request = server.takeRequest()
        assertEquals("/books/api/v1/books/files/101/serve", request.path)
        assertEquals("Bearer old-access", request.getHeader("Authorization"))
        assertThrows(ApiFailure::class.java) { api.ebookContent(101, "other-account") }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `ebook progress publishes only accepted text fields and scoped requests`() {
        val owner = store.read()!!.scope
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"percentage":25,"cfi":null,"pageNumber":2,"textUpdatedAt":"2026-10-20T12:00:00Z"}"""
                )
        )
        val progress = api.ebookProgress(101, owner)
        assertEquals(25.0, progress.percentage, 0.0)
        assertEquals("/books/api/v1/books/files/101/progress", server.takeRequest().path)
        server.enqueue(MockResponse().setResponseCode(204))
        api.saveEbookProgress(101, owner, EbookProgressWrite(50.0, 3))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val data = wireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("percentage", "pageNumber"), data.keys)
        assertEquals(3, data["pageNumber"]!!.jsonPrimitive.int)
        assertThrows(ApiFailure::class.java) { api.ebookProgress(101, "other") }
        assertThrows(IllegalArgumentException::class.java) {
            api.saveEbookProgress(101, owner, EbookProgressWrite(Double.NaN))
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `comic conversion reads protected page endpoints without token URLs`() {
        val owner = store.read()!!.scope
        server.enqueue(MockResponse().setBody("""{"pageCount":3}"""))
        assertEquals(3, api.comicPages(104, owner))
        assertEquals("/books/api/v1/cbz/files/104/pages", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("image"))
        api.comicPage(104, 2, owner).close()
        val request = server.takeRequest()
        assertEquals("/books/api/v1/cbz/files/104/pages/2", request.path)
        assertEquals("Bearer old-access", request.getHeader("Authorization"))
    }

    @Test
    fun `annotation requests use bounded pages accepted fields and account scope`() {
        val owner = store.read()!!.scope
        val row =
            """{"id":7,"bookId":2,"jumpFileId":101,"cfi":"epubcfi(/6/2!/4/2/1:0)","text":"Quote","note":null}"""
        server.enqueue(MockResponse().setBody("""{"items":[$row],"total":1,"page":1}"""))
        assertEquals(7, api.annotations(2, 101, owner, 1).items.single().id)
        assertEquals(
            "/books/api/v1/books/2/annotations?bookFileId=101&page=1&pageSize=25&sortBy=createdAt&sortDir=desc",
            server.takeRequest().path,
        )
        server.enqueue(MockResponse().setBody(row))
        api.createAnnotation(
            2,
            owner,
            AnnotationCreate(101, "Quote", cfi = "epubcfi(/6/2!/4/2/1:0)"),
        )
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals(
            setOf("bookFileId", "text", "cfi"),
            wireJson.parseToJsonElement(create.body.readUtf8()).jsonObject.keys,
        )
        server.enqueue(MockResponse().setBody(row))
        api.editAnnotation(2, 7, owner, null)
        val edit = server.takeRequest()
        assertEquals("PATCH", edit.method)
        assertEquals("""{"note":null}""", edit.body.readUtf8())
        server.enqueue(MockResponse().setResponseCode(204))
        api.deleteAnnotation(2, 7, owner)
        assertEquals("DELETE", server.takeRequest().method)
        assertThrows(ApiFailure::class.java) { api.annotations(2, 101, "different", 1) }
        assertThrows(IllegalArgumentException::class.java) {
            api.createAnnotation(2, owner, AnnotationCreate(101, "Missing location"))
        }
        assertEquals(4, server.requestCount)
    }
}

private class MemorySessions(private var value: Session?) : SessionStore {
    override fun read() = value

    override fun save(session: Session?) {
        value = session
    }
}
