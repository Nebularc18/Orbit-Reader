package app.bookorbit.community.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class ApiFailure(val status: Int) :
    IOException(
        when (status) {
            401 -> "Your session expired. Sign in again."
            403 -> "Your account does not have permission for this action."
            404 -> "This book or API is unavailable. Check your server version and library access."
            409 ->
                "Listening progress changed on another device. Reopen the book to choose where to resume."
            412 -> "The audiobook files changed. Download or open the book again."
            429 -> "Too many attempts. Wait a moment before trying again."
            else -> "The server could not complete this request ($status)."
        }
    )

fun normalizeServer(value: String): String {
    val url = value.trim().trimEnd('/').toHttpUrl()
    require(
        url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.query == null &&
            url.fragment == null
    ) {
        "Enter a server address without credentials, query, or fragment."
    }
    val path = url.encodedPath.trimEnd('/').removeSuffix("/api/v1")
    return url.newBuilder().encodedPath(path.ifEmpty { "/" }).build().toString().trimEnd('/')
}

class OrbitApi(val sessions: SessionStore) {
    private val refreshLock = Any()
    private val transport =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    val authenticatedClient: OkHttpClient =
        transport
            .newBuilder()
            .addInterceptor { chain ->
                val session = sessions.read() ?: throw ApiFailure(401)
                if (chain.request().tag(String::class.java)?.let { it != session.scope } == true)
                    throw ApiFailure(401)
                require(isApiUrl(session, chain.request().url)) {
                    "Refusing credentials for a different server."
                }
                val request =
                    chain
                        .request()
                        .newBuilder()
                        .header("Authorization", "Bearer ${session.accessToken}")
                        .build()
                val response = chain.proceed(request)
                if (response.code != 401) response
                else {
                    response.close()
                    val refreshed =
                        synchronized(refreshLock) {
                            val current = sessions.read() ?: throw ApiFailure(401)
                            if (current.scope != session.scope) throw ApiFailure(401)
                            if (current.accessToken != session.accessToken) current
                            else {
                                val tokens: Tokens =
                                    decode(
                                        raw(
                                            session.server,
                                            "auth/refresh",
                                            "POST",
                                            buildJsonObject {
                                                    put("refreshToken", current.refreshToken)
                                                }
                                                .toString(),
                                        )
                                    )
                                current
                                    .copy(
                                        accessToken = tokens.accessToken,
                                        refreshToken = tokens.refreshToken,
                                    )
                                    .also(sessions::save)
                            }
                        }
                    chain.proceed(
                        request
                            .newBuilder()
                            .header("Authorization", "Bearer ${refreshed.accessToken}")
                            .build()
                    )
                }
            }
            .build()

    fun login(address: String, username: String, password: String): Session =
        synchronized(refreshLock) {
            val server = normalizeServer(address)
            val credentials: Credentials =
                decode(
                    raw(
                        server,
                        "auth/login",
                        "POST",
                        buildJsonObject {
                                put("username", username.trim())
                                put("password", password)
                                put("clientKind", "native")
                                put("deviceLabel", "Orbit Reader Android")
                            }
                            .toString(),
                    )
                )
            Session(server, credentials.accessToken, credentials.refreshToken, credentials.user)
                .also(sessions::save)
        }

    fun switchConnection(scope: String): Session =
        synchronized(refreshLock) {
            check(sessions.activate(scope)) { "This saved connection is no longer available." }
            sessions.read() ?: throw ApiFailure(401)
        }

    fun logout() =
        synchronized(refreshLock) {
            val session = sessions.read() ?: return@synchronized
            try {
                raw(
                        session.server,
                        "auth/logout",
                        "POST",
                        buildJsonObject { put("refreshToken", session.refreshToken) }.toString(),
                    )
                    .close()
            } finally {
                sessions.save(null)
            }
        }

    fun readingGoal(owner: String): ReadingGoal =
        decode(request("dashboard/widgets/reading-goal", expectedScope = owner))

    fun readingStreak(owner: String): ReadingStreak =
        decode(request("dashboard/widgets/reading-streak", expectedScope = owner))

    fun dashboard(type: String, owner: String): DashboardShelf {
        require(type in setOf("continue-listening", "continue-reading", "up-next-in-series"))
        return decode(request("dashboard/scrollers/$type?limit=10", expectedScope = owner))
    }

    fun nextSeriesAudio(seriesId: Int, bookId: Int, owner: String): Int? {
        require(seriesId > 0 && bookId > 0)
        val value: JsonObject =
            decode(
                request(
                    "series/$seriesId/books/$bookId/next?formatGroup=audio",
                    expectedScope = owner,
                )
            )
        return value["next"]
            ?.takeIf { it != JsonNull }
            ?.jsonObject
            ?.get("bookId")
            ?.jsonPrimitive
            ?.intOrNull
    }

    fun setBookStatus(id: Int, status: String, owner: String) {
        require(id > 0 && status in bookStatuses)
        request(
                "books/$id/status",
                "PATCH",
                buildJsonObject { put("status", status) }.toString(),
                expectedScope = owner,
            )
            .close()
    }

    fun setBookRating(id: Int, rating: Int?, owner: String) {
        require(id > 0 && (rating == null || rating in 1..5))
        request(
                "books/bulk-set-rating",
                "POST",
                buildJsonObject {
                        put("bookIds", buildJsonArray { add(id) })
                        put("rating", rating?.let(::JsonPrimitive) ?: JsonNull)
                    }
                    .toString(),
                expectedScope = owner,
            )
            .close()
    }

    fun bookCollections(owner: String): List<BookCollection> =
        decode(request("collections", expectedScope = owner))

    fun collectionMembership(collectionId: Int, bookId: Int, add: Boolean, owner: String) {
        require(collectionId > 0 && bookId > 0)
        request(
                "collections/$collectionId/books",
                if (add) "POST" else "DELETE",
                buildJsonObject { put("bookIds", buildJsonArray { add(bookId) }) }.toString(),
                expectedScope = owner,
            )
            .close()
    }

    fun libraryGroups(
        kind: LibraryGroupKind,
        page: Int,
        query: String,
        order: GroupOrder,
        owner: String,
    ): LibraryGroupsPage {
        require(page > 0)
        val q = java.net.URLEncoder.encode(query.take(500), "UTF-8")
        if (kind == LibraryGroupKind.SERIES || kind == LibraryGroupKind.AUTHORS)
            return decode(
                request(
                    "${kind.path}?page=${page - 1}&size=30&q=$q&sort=${order.field}&order=${order.direction}",
                    expectedScope = owner,
                )
            )
        val groups: List<LibraryGroup> = decode(request(kind.path, expectedScope = owner))
        val filtered =
            groups.filter {
                it.mediaType != "podcasts" && it.name.contains(query, ignoreCase = true)
            }
        val sorted =
            when (order) {
                GroupOrder.NAME ->
                    filtered.sortedWith(
                        compareBy<LibraryGroup> { it.name.lowercase() }.thenBy { it.id }
                    )
                GroupOrder.NAME_DESC ->
                    filtered.sortedWith(
                        compareByDescending<LibraryGroup> { it.name.lowercase() }.thenBy { it.id }
                    )
                else -> filtered
            }
        return LibraryGroupsPage(sorted.drop((page - 1) * 30).take(30), sorted.size, page - 1, 30)
    }

    fun libraryGroupBooks(kind: LibraryGroupKind, id: Int, page: Int, owner: String): BooksPage {
        require(id > 0 && page > 0)
        val options =
            when (kind) {
                LibraryGroupKind.SERIES -> "&sort=seriesIndex&order=asc"
                LibraryGroupKind.AUTHORS -> "&sort=title&order=asc&collapseSeries=false"
                LibraryGroupKind.COLLECTIONS -> "&collapseSeries=false"
                LibraryGroupKind.SHELVES -> ""
            }
        return decode(
            request(
                "${kind.path}/$id/books?page=${page - 1}&size=30$options",
                expectedScope = owner,
            )
        )
    }

    fun activityOverview(owner: String): ActivityOverview =
        decode(request("user-statistics/activity-overview", expectedScope = owner))

    fun activityTimeline(year: Int, week: Int, owner: String): ActivityTimeline {
        require(year in 1970..2100 && week in 1..53)
        return decode(
            request("user-statistics/session-timeline?year=$year&week=$week", expectedScope = owner)
        )
    }

    fun activitySources(owner: String): ActivitySources =
        decode(
            request("user-statistics/reading-source-distribution?days=28", expectedScope = owner)
        )

    fun books(
        page: Int,
        query: String,
        ebooks: Boolean = false,
        owner: String? = null,
        sort: CatalogSort = CatalogSort.TITLE_ASC,
        readFilter: CatalogReadFilter = CatalogReadFilter.ALL,
    ): BooksPage {
        require(page > 0)
        return decode(
            request(
                "books/query",
                "POST",
                buildJsonObject {
                        put(
                            "sort",
                            buildJsonArray {
                                sort.fields.forEach { (field, direction) ->
                                    add(
                                        buildJsonObject {
                                            put("field", field)
                                            put("dir", direction)
                                        }
                                    )
                                }
                            },
                        )
                        put(
                            "pagination",
                            buildJsonObject {
                                put("page", page - 1)
                                put("size", 30)
                            },
                        )
                        put("collapseSeries", false)
                        put("q", query)
                        put(
                            "filter",
                            buildJsonObject {
                                put("type", "group")
                                put("join", "AND")
                                put(
                                    "rules",
                                    buildJsonArray {
                                        if (readFilter != CatalogReadFilter.ALL)
                                            add(
                                                buildJsonObject {
                                                    put("type", "rule")
                                                    put("field", "readStatus")
                                                    put("operator", "includesAny")
                                                    put(
                                                        "value",
                                                        buildJsonArray {
                                                            readFilter.statuses.forEach { add(it) }
                                                        },
                                                    )
                                                }
                                            )
                                        add(
                                            buildJsonObject {
                                                put("type", "rule")
                                                put("field", "format")
                                                put("operator", "includesAny")
                                                put(
                                                    "value",
                                                    buildJsonArray {
                                                        (if (ebooks)
                                                                listOf(
                                                                    "epub",
                                                                    "kepub",
                                                                    "pdf",
                                                                    "cbz",
                                                                    "cbr",
                                                                    "cb7",
                                                                )
                                                            else
                                                                listOf(
                                                                    "m4b",
                                                                    "mp3",
                                                                    "m4a",
                                                                    "opus",
                                                                    "ogg",
                                                                    "flac",
                                                                ))
                                                            .forEach { add(it) }
                                                    },
                                                )
                                            }
                                        )
                                    },
                                )
                            },
                        )
                    }
                    .toString(),
                expectedScope = owner,
            )
        )
    }

    fun ebookProgress(fileId: Int, owner: String): EbookProgress =
        decode(request("books/files/$fileId/progress", expectedScope = owner))

    fun saveEbookProgress(fileId: Int, owner: String, value: EbookProgressWrite) {
        require(value.percentage.isFinite() && value.percentage in 0.0..100.0)
        request("books/files/$fileId/progress", "POST", wireJson.encodeToString(value), owner)
            .close()
    }

    fun annotations(bookId: Int, fileId: Int, owner: String, page: Int): AnnotationPage {
        require(page > 0)
        return decode(
            request(
                "books/$bookId/annotations?bookFileId=$fileId&page=$page&pageSize=25&sortBy=createdAt&sortDir=desc",
                expectedScope = owner,
            )
        )
    }

    fun createAnnotation(bookId: Int, owner: String, value: AnnotationCreate): BookAnnotation {
        require((value.cfi != null) != (value.pdf != null))
        require(
            value.text.isNotBlank() &&
                value.text.length <= 10000 &&
                value.note.orEmpty().length <= 10000
        )
        return decode(
            request("books/$bookId/annotations", "POST", wireJson.encodeToString(value), owner)
        )
    }

    fun editAnnotation(bookId: Int, id: Int, owner: String, note: String?): BookAnnotation =
        decode(
            request(
                "books/$bookId/annotations/$id",
                "PATCH",
                wireJson.encodeToString(AnnotationEdit(note)),
                owner,
            )
        )

    fun deleteAnnotation(bookId: Int, id: Int, owner: String) {
        request("books/$bookId/annotations/$id", "DELETE", expectedScope = owner).close()
    }

    fun comicPages(fileId: Int, owner: String): Int =
        decode<ComicPages>(request("cbz/files/$fileId/pages", expectedScope = owner)).pageCount

    fun comicPage(fileId: Int, page: Int, owner: String): Response =
        request("cbz/files/$fileId/pages/$page", expectedScope = owner)

    fun ebookDetail(bookId: Int, expectedScope: String): EbookDetail =
        decode(request("books/$bookId", expectedScope = expectedScope))

    fun ebookContent(fileId: Int, expectedScope: String): Response =
        request(
            "books/files/$fileId/serve",
            expectedScope = expectedScope,
            headers = mapOf("Accept-Encoding" to "identity"),
        )

    fun manifest(bookId: Int, expectedScope: String? = null): AudioManifest =
        decode<AudioManifest>(request("audiobooks/$bookId/manifest", expectedScope = expectedScope))
            .validated()

    fun progress(bookId: Int): PlaybackState? = decode(request("audiobooks/$bookId/playback-state"))

    fun saveProgress(
        bookId: Int,
        value: ProgressWrite,
        expectedScope: String? = null,
    ): PlaybackState =
        decode(
            request(
                "audiobooks/$bookId/playback-state",
                "PUT",
                wireJson.encodeToString(value),
                expectedScope,
            )
        )

    fun download(
        asset: AudioAsset,
        expectedScope: String? = null,
        offset: Long = 0,
        validator: String? = null,
    ): Response =
        request(
            "books/files/${asset.fileId}/download",
            expectedScope = expectedScope,
            headers =
                buildMap {
                    put("Accept-Encoding", "identity")
                    if (offset > 0 && validator != null) {
                        put("Range", "bytes=$offset-")
                        put("If-Range", validator)
                    }
                },
        )

    fun url(path: String): String =
        "${sessions.read()?.server ?: throw ApiFailure(401)}/api/v1/$path"

    private fun request(
        path: String,
        method: String = "GET",
        body: String? = null,
        expectedScope: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response =
        checked(
            authenticatedClient
                .newCall(
                    buildRequest(url(path), method, body)
                        .newBuilder()
                        .apply { headers.forEach { (name, value) -> header(name, value) } }
                        .tag(String::class.java, expectedScope ?: sessions.read()?.scope)
                        .build()
                )
                .execute()
        )

    private fun raw(server: String, path: String, method: String, body: String): Response =
        checked(transport.newCall(buildRequest("$server/api/v1/$path", method, body)).execute())

    private fun buildRequest(url: String, method: String, body: String?) =
        Request.Builder()
            .url(url)
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .build()

    private fun checked(response: Response): Response {
        if (!response.isSuccessful) {
            val status = response.code
            response.close()
            throw ApiFailure(status)
        }
        return response
    }

    private inline fun <reified T> decode(response: Response): T =
        response.use { wireJson.decodeFromString(it.body!!.string().ifEmpty { "null" }) }

    private fun isApiUrl(session: Session, url: HttpUrl): Boolean {
        val base = "${session.server}/api/v1/".toHttpUrl()
        return base.scheme == url.scheme &&
            base.host == url.host &&
            base.port == url.port &&
            url.encodedPath.startsWith(base.encodedPath)
    }
}
