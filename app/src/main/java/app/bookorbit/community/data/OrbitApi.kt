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

    fun books(page: Int, query: String): BooksPage {
        require(page > 0)
        return decode(
            request(
                "books/query",
                "POST",
                buildJsonObject {
                        put(
                            "sort",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("field", "title")
                                        put("dir", "asc")
                                    }
                                )
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
                                        add(
                                            buildJsonObject {
                                                put("type", "rule")
                                                put("field", "format")
                                                put("operator", "includesAny")
                                                put(
                                                    "value",
                                                    buildJsonArray {
                                                        listOf(
                                                                "m4b",
                                                                "mp3",
                                                                "m4a",
                                                                "opus",
                                                                "ogg",
                                                                "flac",
                                                            )
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
            )
        )
    }

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
