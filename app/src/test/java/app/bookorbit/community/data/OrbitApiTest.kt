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
    fun `book management sends exact contracts and rejects a switched account`() {
        val owner = store.read()!!.scope
        server.enqueue(MockResponse().setBody("{}"))
        api.setBookStatus(7, "read", owner)
        server.takeRequest().let {
            assertEquals("PATCH", it.method)
            assertEquals("/books/api/v1/books/7/status", it.path)
            assertEquals("""{"status":"read"}""", it.body.readUtf8())
        }
        server.enqueue(MockResponse().setBody("{}"))
        api.setBookRating(7, null, owner)
        assertEquals("""{"bookIds":[7],"rating":null}""", server.takeRequest().body.readUtf8())
        for (add in listOf(true, false)) {
            server.enqueue(MockResponse().setBody("{}"))
            api.collectionMembership(2, 7, add, owner)
            server.takeRequest().let {
                assertEquals(if (add) "POST" else "DELETE", it.method)
                assertEquals("/books/api/v1/collections/2/books", it.path)
                assertEquals("""{"bookIds":[7]}""", it.body.readUtf8())
            }
        }
        server.enqueue(MockResponse().setBody("""{"next":{"bookId":9}}"""))
        assertEquals(9, api.nextSeriesAudio(1, 7, owner))
        assertEquals(
            "/books/api/v1/series/1/books/7/next?formatGroup=audio",
            server.takeRequest().path,
        )
        server.enqueue(MockResponse().setBody("""{"next":null}"""))
        assertNull(api.nextSeriesAudio(1, 9, owner))
        server.takeRequest()
        assertThrows(IllegalArgumentException::class.java) {
            api.setBookStatus(7, "finished", owner)
        }
        assertThrows(IllegalArgumentException::class.java) { api.setBookRating(7, 6, owner) }
        assertThrows(ApiFailure::class.java) { api.setBookStatus(7, "read", "other") }
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `two server logins retain credentials and route only the active account`() {
        val first = store.read()!!
        val secondServer = MockWebServer().apply { start() }
        try {
            secondServer.enqueue(
                MockResponse()
                    .setBody(
                        """{"accessToken":"second-access","refreshToken":"second-refresh","user":{"id":7,"username":"reader"}}"""
                    )
            )
            val second = api.login(secondServer.url("/other").toString(), "reader", "test-only")
            assertEquals(2, store.connections().size)
            assertEquals(first, store.connections().first { it.scope == first.scope })
            assertEquals("/other/api/v1/auth/login", secondServer.takeRequest().path)
            assertThrows(ApiFailure::class.java) { api.books(1, "", owner = first.scope) }
            assertEquals(0, server.requestCount)
            assertEquals(first, api.switchConnection(first.scope))
            server.enqueue(MockResponse().setBody("""{"items":[],"total":0,"page":0,"size":30}"""))
            api.books(1, "", owner = first.scope)
            assertEquals("Bearer old-access", server.takeRequest().getHeader("Authorization"))
            api.switchConnection(second.scope)
            secondServer.enqueue(MockResponse().setResponseCode(401))
            secondServer.enqueue(
                MockResponse()
                    .setBody(
                        """{"accessToken":"rotated-second","refreshToken":"rotated-refresh"}"""
                    )
            )
            secondServer.enqueue(
                MockResponse().setBody("""{"items":[],"total":0,"page":0,"size":30}""")
            )
            api.books(1, "", owner = second.scope)
            assertEquals(
                "Bearer second-access",
                secondServer.takeRequest().getHeader("Authorization"),
            )
            assertEquals("/other/api/v1/auth/refresh", secondServer.takeRequest().path)
            assertEquals(
                "Bearer rotated-second",
                secondServer.takeRequest().getHeader("Authorization"),
            )
            api.switchConnection(first.scope)
            assertEquals(first, store.read())
            assertEquals(
                "rotated-refresh",
                store.connections().single { it.scope == second.scope }.refreshToken,
            )
            server.enqueue(MockResponse().setResponseCode(204))
            api.logout()
            assertEquals(second.scope, store.read()!!.scope)
            assertEquals("/books/api/v1/auth/logout", server.takeRequest().path)
            assertEquals(1, store.connections().size)
        } finally {
            secondServer.shutdown()
        }
    }

    @Test
    fun `library group endpoints use bounded pages encoded queries and account scope`() {
        val owner = store.read()!!.scope
        for (kind in listOf(LibraryGroupKind.SERIES, LibraryGroupKind.AUTHORS)) {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"items":[{"id":7,"name":"Orbit","bookCount":3,"coverBookIds":[2]}],"total":31,"page":1,"size":30}"""
                    )
            )
            assertEquals(
                2,
                api.libraryGroups(kind, 2, "A & B", GroupOrder.MOST_BOOKS, owner)
                    .items
                    .single()
                    .coverId,
            )
            val url = server.takeRequest().requestUrl!!
            assertEquals("/books/api/v1/${kind.path}", url.encodedPath)
            assertEquals("1", url.queryParameter("page"))
            assertEquals("30", url.queryParameter("size"))
            assertEquals("A & B", url.queryParameter("q"))
            assertEquals("bookCount", url.queryParameter("sort"))
            assertEquals("desc", url.queryParameter("order"))
        }
        assertThrows(ApiFailure::class.java) {
            api.libraryGroups(LibraryGroupKind.AUTHORS, 1, "", GroupOrder.NAME, "other")
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `saved lists filter podcast collections before paging and preserve stable name sorting`() {
        val rows =
            (1..35).joinToString(",") { """{"id":$it,"name":"Shelf ${100-it}","bookCount":2}""" }
        for (page in 1..2) {
            server.enqueue(
                MockResponse()
                    .setBody("""[$rows,{"id":100,"name":"Shelf podcast","mediaType":"podcasts"}]""")
            )
            val result =
                api.libraryGroups(
                    LibraryGroupKind.COLLECTIONS,
                    page,
                    "Shelf",
                    GroupOrder.NAME_DESC,
                    store.read()!!.scope,
                )
            assertEquals(35, result.total)
            assertEquals(if (page == 1) 30 else 5, result.items.size)
            assertEquals(if (page == 1) 1 else 31, result.items.first().id)
            assertEquals("/books/api/v1/collections", server.takeRequest().path)
        }
    }

    @Test
    fun `group book routes retain volume order and bounded pages without collapsing series`() {
        for (kind in LibraryGroupKind.entries) {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"items":[{"id":2,"files":[{"id":101,"format":"epub"}],"readStatus":{"status":"read"}}],"total":1,"page":0,"size":30}"""
                    )
            )
            val book = api.libraryGroupBooks(kind, 7, 1, store.read()!!.scope).items.single()
            assertTrue(book.files.single().supported)
            assertEquals("read", book.readStatus!!.status)
            val url = server.takeRequest().requestUrl!!
            assertEquals("/books/api/v1/${kind.path}/7/books", url.encodedPath)
            assertEquals("0", url.queryParameter("page"))
            assertEquals("30", url.queryParameter("size"))
            if (kind == LibraryGroupKind.SERIES)
                assertEquals("seriesIndex", url.queryParameter("sort"))
            if (kind == LibraryGroupKind.AUTHORS || kind == LibraryGroupKind.COLLECTIONS)
                assertEquals("false", url.queryParameter("collapseSeries"))
        }
    }

    @Test
    fun `activity contracts accept empty history and an unset goal without inventing values`() {
        val owner = store.read()!!.scope
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"timezone":"UTC","snapshot":{"today":{},"lastSevenDays":{"totalSeconds":7200,"readingSeconds":1200,"listeningSeconds":6000},"previousSevenDays":{},"currentStreak":2,"longestStreak":8,"completedBooksYtd":4},"dailyActivity":{"days":[]},"goal":{"year":2026,"goalBooks":null,"completedBooks":4,"projectedBooks":22.5}}"""
                )
        )
        val overview = api.activityOverview(owner)
        assertNull(overview.goal.goalBooks)
        assertEquals(22.5, overview.goal.projectedBooks!!, 0.0)
        assertEquals(6000L, overview.snapshot.lastSevenDays.listeningSeconds)
        assertEquals("/books/api/v1/user-statistics/activity-overview", server.takeRequest().path)
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"year":2026,"week":43,"weekStart":"2026-10-19","weekEnd":"2026-10-25","items":[]}"""
                )
        )
        assertTrue(api.activityTimeline(2026, 43, owner).items.isEmpty())
        assertEquals(
            "/books/api/v1/user-statistics/session-timeline?year=2026&week=43",
            server.takeRequest().path,
        )
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"totalSeconds":7200,"slices":[{"bucket":"android","readingSeconds":7200}]}"""
                )
        )
        assertEquals("android", api.activitySources(owner).slices.single().bucket)
        assertEquals(
            "/books/api/v1/user-statistics/reading-source-distribution?days=28",
            server.takeRequest().path,
        )
        assertThrows(ApiFailure::class.java) { api.activityOverview("other") }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `status filters combine with formats search sort and pagination`() {
        for (ebooks in listOf(false, true)) for (filter in CatalogReadFilter.entries) {
            server.enqueue(MockResponse().setBody("""{"items":[],"total":0,"page":1,"size":30}"""))
            api.books(2, "Orbit", ebooks, store.read()!!.scope, CatalogSort.AUTHOR_DESC, filter)
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val rules =
                body.getValue("filter").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
            assertEquals(
                "AND",
                body.getValue("filter").jsonObject.getValue("join").jsonPrimitive.content,
            )
            assertEquals("Orbit", body.getValue("q").jsonPrimitive.content)
            assertEquals(
                1,
                body.getValue("pagination").jsonObject.getValue("page").jsonPrimitive.int,
            )
            assertEquals(
                "author",
                body
                    .getValue("sort")
                    .jsonArray
                    .first()
                    .jsonObject
                    .getValue("field")
                    .jsonPrimitive
                    .content,
            )
            val format = rules.single { it.getValue("field").jsonPrimitive.content == "format" }
            assertTrue(
                format.getValue("value").jsonArray.any {
                    it.jsonPrimitive.content == if (ebooks) "epub" else "m4b"
                }
            )
            val status = rules.filter { it.getValue("field").jsonPrimitive.content == "readStatus" }
            if (filter == CatalogReadFilter.ALL) assertTrue(status.isEmpty())
            else {
                assertEquals(
                    "includesAny",
                    status.single().getValue("operator").jsonPrimitive.content,
                )
                assertEquals(
                    filter.statuses,
                    status.single().getValue("value").jsonArray.map { it.jsonPrimitive.content },
                )
            }
        }
        assertEquals(listOf("unread"), CatalogReadFilter.UNREAD.statuses)
        assertEquals(listOf("read"), CatalogReadFilter.FINISHED.statuses)
        assertEquals(listOf("reading", "rereading"), CatalogReadFilter.IN_PROGRESS.statuses)
    }

    @Test
    fun `catalog sorts use supported wire fields and keep search and ordering on later pages`() {
        for (sort in CatalogSort.entries) for (page in 1..2) {
            server.enqueue(
                MockResponse().setBody("""{"items":[],"total":100,"page":0,"size":30}""")
            )
            api.books(page, "Writer", ebooks = true, owner = store.read()!!.scope, sort = sort)
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val fields =
                body.getValue("sort").jsonArray.map {
                    it.jsonObject.let { row ->
                        row.getValue("field").jsonPrimitive.content to
                            row.getValue("dir").jsonPrimitive.content
                    }
                }
            assertEquals(sort.fields, fields)
            assertEquals(
                page - 1,
                body.getValue("pagination").jsonObject.getValue("page").jsonPrimitive.int,
            )
            assertEquals("Writer", body.getValue("q").jsonPrimitive.content)
            assertEquals(setOf("sort", "pagination", "collapseSeries", "q", "filter"), body.keys)
        }
        assertEquals(
            listOf("series" to "asc", "seriesIndex" to "asc", "title" to "asc"),
            CatalogSort.SERIES.fields,
        )
    }

    @Test
    fun `dashboard activity parses server goal and streak without inventing a missing goal`() {
        val owner = store.read()!!.scope
        server.enqueue(
            MockResponse().setBody("""{"goalBooks":null,"completedBooks":4,"year":2026}""")
        )
        val goal = api.readingGoal(owner)
        assertNull(goal.goalBooks)
        assertEquals(4, goal.completedBooks)
        assertEquals("/books/api/v1/dashboard/widgets/reading-goal", server.takeRequest().path)
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"currentStreak":3,"longestStreak":9,"lastSevenDays":[false,false,false,false,true,true,true]}"""
                )
        )
        val streak = api.readingStreak(owner)
        assertEquals(3, streak.currentStreak)
        assertEquals(9, streak.longestStreak)
        assertEquals(3, streak.lastSevenDays.count { it })
        assertEquals("/books/api/v1/dashboard/widgets/reading-streak", server.takeRequest().path)
        assertEquals(0, server.requestCount - 2)
        try {
            api.readingGoal("other-account")
            fail("Expected scope rejection")
        } catch (_: ApiFailure) {}
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `book information parses web metadata while catalog calls enforce account scope`() {
        val owner = store.read()!!.scope
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"id":8,"title":"Book","description":"<p>Synopsis</p>","subtitle":"Subtitle","authors":[{"id":1,"name":"Writer"}],"seriesName":"A Series","seriesIndex":"2.5","publisher":"Publisher","publishedYear":2026,"language":"en","pageCount":200,"rating":4.5,"genres":["Fiction"],"tags":["Favorite"],"collections":[{"id":2,"name":"Shelf"}],"readStatus":{"status":"reading"},"files":[]}"""
                )
        )
        val book = api.ebookDetail(8, owner)
        assertEquals("2.5", book.seriesIndex)
        assertEquals(200, book.pageCount)
        assertEquals("reading", book.readStatus?.status)
        assertEquals("Shelf", book.collections.single().name)
        assertEquals("/books/api/v1/books/8", server.takeRequest().path)
        try {
            api.books(1, "", owner = "another-account")
            fail("Expected scope rejection")
        } catch (error: ApiFailure) {
            assertEquals(401, error.status)
        }
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
            listOf("epub", "kepub", "pdf", "cbz", "cbr", "cb7", "fb2", "mobi"),
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

    @Test
    fun `magic login captures cookie rotation without leaking it into resource requests`() {
        server.enqueue(
            MockResponse()
                .addHeader(
                    "Set-Cookie",
                    "refresh_token=${"a".repeat(64)}; HttpOnly; Path=/books/api/v1/auth",
                )
                .setBody("""{"accessToken":"magic-access","user":{"id":7,"username":"reader"}}""")
        )
        val session =
            api.loginMagic(store.read()!!.server, "${store.read()!!.server}/magic?token=one-time")
        assertTrue(session.cookieRefresh)
        assertEquals("a".repeat(64), session.refreshToken)
        server.takeRequest().let {
            assertEquals("/books/api/v1/auth/magic-links/login", it.path)
            assertEquals("""{"token":"one-time"}""", it.body.readUtf8())
            assertNull(it.getHeader("Authorization"))
            assertNull(it.getHeader("Cookie"))
        }
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse()
                .addHeader(
                    "Set-Cookie",
                    "refresh_token=${"b".repeat(64)}; HttpOnly; Path=/books/api/v1/auth",
                )
                .setBody("""{"accessToken":"rotated"}""")
        )
        server.enqueue(
            MockResponse().setBody("""{"id":7,"title":"Book","authors":[],"files":[]}""")
        )
        api.ebookDetail(7, session.scope)
        assertNull(server.takeRequest().getHeader("Cookie"))
        server.takeRequest().let {
            assertEquals("/books/api/v1/auth/refresh", it.path)
            assertEquals("{}", it.body.readUtf8())
            assertEquals("refresh_token=${"a".repeat(64)}", it.getHeader("Cookie"))
            assertNotNull(it.getHeader("Origin"))
            assertNull(it.getHeader("Authorization"))
        }
        server.takeRequest().let {
            assertEquals("Bearer rotated", it.getHeader("Authorization"))
            assertNull(it.getHeader("Cookie"))
        }
        assertEquals("b".repeat(64), store.read()!!.refreshToken)
        server.enqueue(MockResponse().setBody("{}"))
        api.logout()
        server.takeRequest().let {
            assertEquals("/books/api/v1/auth/logout", it.path)
            assertEquals("refresh_token=${"b".repeat(64)}", it.getHeader("Cookie"))
            assertEquals("{}", it.body.readUtf8())
        }
    }

    @Test
    fun `magic links reject foreign origins duplicated tokens and unusable cookies`() {
        val base = store.read()!!.server
        for (input in
            listOf(
                "https://elsewhere.test/magic?token=secret",
                "$base/magic?token=a&token=b",
                "$base/magic?token=a#fragment",
                "$base/magic?token=a&extra=b",
                "bad token",
            )) assertThrows(Exception::class.java) { magicLinkToken(base, input) }
        assertEquals("raw-token", magicLinkToken(base, "raw-token"))
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "refresh_token=${"a".repeat(64)}; Path=/wrong")
                .setBody("""{"accessToken":"bad","user":{"id":7,"username":"reader"}}""")
        )
        assertThrows(java.io.IOException::class.java) { api.loginMagic(base, "raw-token") }
        assertEquals("old-access", store.read()!!.accessToken)
    }

    @Test
    fun `resumable uploads send idempotency target offset checksum and bounded chunks`() {
        val owner = store.read()!!.scope
        val id = "01234567-89ab-4cde-8fab-0123456789ab"
        val reply =
            """{"id":"$id","filename":"book.epub","sizeBytes":4,"receivedBytes":4,"chunkSizeBytes":65536,"status":"receiving"}"""
        server.enqueue(MockResponse().setBody(reply))
        api.createUpload(
            UploadCreate("book.epub", 4, id, UploadTarget(libraryId = 1, folderId = 2)),
            owner,
        )
        server.takeRequest().let {
            val body = wireJson.parseToJsonElement(it.body.readUtf8()).jsonObject
            assertEquals(setOf("filename", "sizeBytes", "idempotencyKey", "target"), body.keys)
            assertEquals(JsonPrimitive("library"), body.getValue("target").jsonObject["kind"])
        }
        server.enqueue(MockResponse().setBody(reply))
        api.uploadChunk(id, 0, byteArrayOf(1, 2, 3, 4), owner)
        server.takeRequest().let {
            assertEquals("0", it.getHeader("Upload-Offset"))
            assertEquals(
                "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
                it.getHeader("Upload-Checksum"),
            )
            assertTrue(it.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
            assertTrue(it.body.readUtf8().contains("name=\"file\""))
        }
        server.enqueue(MockResponse().setBody(reply))
        api.finishUpload(id, owner)
        assertEquals("{}", server.takeRequest().body.readUtf8())
        assertThrows(IllegalArgumentException::class.java) {
            api.uploadChunk(id, 0, ByteArray(4194305), owner)
        }
        assertThrows(ApiFailure::class.java) { api.uploadSession(id, "other-account") }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `metadata saves send only changed DTO fields`() {
        val source =
            wireJson
                .parseToJsonElement("""{"title":"Old","providerIds":{"google":"id"}}""")
                .jsonObject
        server.enqueue(MockResponse().setBody("{}"))
        api.saveBookMetadata(
            7,
            store.read()!!.scope,
            source,
            metadataFields,
            mapOf("title" to "New"),
        )
        server.takeRequest().let {
            assertEquals("PATCH", it.method)
            assertEquals("/books/api/v1/books/7/metadata", it.path)
            assertEquals("""{"title":"New"}""", it.body.readUtf8())
        }
    }
}

private class MemorySessions(value: Session?) : SessionStore {
    private var registry =
        if (value == null) SessionConnections() else SessionConnections().save(value)

    override fun read() = registry.active

    override fun save(session: Session?) {
        registry = registry.save(session)
    }

    override fun connections() = registry.saved

    override fun activate(scope: String): Boolean {
        if (registry.saved.none { it.scope == scope }) return false
        registry = registry.activate(scope)
        return true
    }
}
