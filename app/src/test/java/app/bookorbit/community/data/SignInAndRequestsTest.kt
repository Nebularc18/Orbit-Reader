package app.bookorbit.community.data

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SignInAndRequestsTest {
    private val pending =
        PendingOidc("https://books.example/root", "state-value", "verifier", "nonce", 1000, null)

    @Test
    fun `PKCE uses the RFC S256 vector and random URL safe secrets`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
        val a = oidcSecret()
        val b = oidcSecret()
        assertEquals(43, a.length)
        assertTrue(a.matches(Regex("[a-zA-Z0-9_-]+")))
        assertNotEquals(a, b)
    }

    @Test
    fun `callbacks require exact redirect state age and a single code`() {
        assertEquals(
            "test-code",
            pending.code("bookorbit://oauth2-callback?state=state-value&code=test-code", 1001),
        )
        val bad =
            listOf(
                "evil://oauth2-callback?state=state-value&code=x",
                "bookorbit://evil?state=state-value&code=x",
                "bookorbit://oauth2-callback/path?state=state-value&code=x",
                "bookorbit://oauth2-callback?state=wrong&code=x",
                "bookorbit://oauth2-callback?state=state-value&code=a&code=b",
                "bookorbit://oauth2-callback?state=state-value&state=state-value&code=x",
                "bookorbit://oauth2-callback?state=state-value&code=x#fragment",
                "bookorbit://oauth2-callback?state=state-value&error=denied",
                "bookorbit://oauth2-callback?state=state-value",
            )
        bad.forEach { callback ->
            assertThrows(IllegalArgumentException::class.java) { pending.code(callback, 1001) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            pending.code("bookorbit://oauth2-callback?state=state-value&code=x", 999)
        }
        assertThrows(IllegalArgumentException::class.java) {
            pending.code(
                "bookorbit://oauth2-callback?state=state-value&code=x",
                1001 + OIDC_MAX_AGE_MS,
            )
        }
    }

    @Test
    fun `authorization URL encodes PKCE and disallows insecure remote providers`() {
        val provider = OidcProvider("sso", "SSO", true, "client+id", "openid profile")
        val url =
            oidcAuthorization(
                    provider,
                    OidcState(
                        pending.state,
                        "https://identity.example/authorize?response_type=token",
                    ),
                    pending,
                )
                .toHttpUrl()
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("client+id", url.queryParameter("client_id"))
        assertEquals(OIDC_REDIRECT, url.queryParameter("redirect_uri"))
        assertEquals(pkceChallenge(pending.verifier), url.queryParameter("code_challenge"))
        assertNull(url.queryParameter("code_verifier"))
        assertThrows(IllegalArgumentException::class.java) {
            oidcAuthorization(
                provider,
                OidcState(pending.state, "http://identity.example/authorize"),
                pending,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            oidcAuthorization(
                provider,
                OidcState(pending.state, "https://user:pass@identity.example/authorize"),
                pending,
            )
        }
    }

    @Test
    fun `native OIDC exchange uses public endpoints and exact native DTO and preserves server path`() {
        MockWebServer().use { server ->
            val sessions = FeatureSessions(null)
            val api = OrbitApi(sessions)
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"passwordLoginEnabled":false,"oidcProviders":[{"slug":"sso","displayName":"SSO","enabled":true,"clientId":"id","scopes":"openid"}]}"""
                    )
            )
            assertFalse(
                api.loginOptions(server.url("/root/api/v1").toString()).passwordLoginEnabled
            )
            server.takeRequest().let {
                assertEquals("/root/api/v1/auth/login-options", it.path)
                assertNull(it.getHeader("Authorization"))
                assertEquals("GET", it.method)
            }
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"state":"state-value","authorizationEndpoint":"https://id.example/auth"}"""
                    )
            )
            api.oidcState(server.url("/root").toString(), "sso")
            server.takeRequest().let {
                assertEquals("/root/api/v1/auth/oidc/sso/state", it.path)
                assertEquals("{}", it.body.readUtf8())
            }
            server.enqueue(
                MockResponse()
                    .setBody(
                        """{"mode":"login","accessToken":"native-access","refreshToken":"rotated","user":{"id":8,"username":"sso-user"}}"""
                    )
            )
            val saved =
                api.completeOidc(
                    pending.copy(server = server.url("/root").toString().trimEnd('/')),
                    "code",
                )
            val request = server.takeRequest()
            val body = wireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(
                setOf(
                    "code",
                    "codeVerifier",
                    "redirectUri",
                    "nonce",
                    "state",
                    "clientKind",
                    "deviceLabel",
                ),
                body.keys,
            )
            assertEquals("native", body.getValue("clientKind").jsonPrimitive.content)
            assertEquals(OIDC_REDIRECT, body.getValue("redirectUri").jsonPrimitive.content)
            assertNull(request.getHeader("Authorization"))
            assertEquals(saved, sessions.read())
            sessions.current = saved.copy(user = Account(9, username = "other"))
            assertThrows(IllegalStateException::class.java) { api.completeOidc(pending, "code") }
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `requests use bounded filters exact write contracts and active account checks`() {
        MockWebServer().use { server ->
            val sessions =
                FeatureSessions(
                    Session(
                        server.url("/root").toString().trimEnd('/'),
                        "access",
                        "refresh",
                        Account(7, username = "reader"),
                    )
                )
            val api = OrbitApi(sessions)
            val owner = sessions.read()!!.scope
            val row =
                """{"id":4,"userId":7,"title":"A book","authors":["Author"],"mediaKind":"ebook","status":"pending","note":null,"decisionNote":null,"statusReason":null}"""
            server.enqueue(MockResponse().setBody("""{"items":[$row],"total":65}"""))
            assertEquals(65, api.bookRequests(2, "pending", "ebook", owner).total)
            server.takeRequest().let {
                assertEquals(
                    "/root/api/v1/book-requests?page=2&limit=30&status=pending&mediaKind=ebook",
                    it.path,
                )
                assertEquals("Bearer access", it.getHeader("Authorization"))
            }
            server.enqueue(MockResponse().setBody("""{"request":$row,"subscribed":true}"""))
            assertTrue(
                api.submitBookRequest(
                        NewBookRequest(" A book ", listOf(" Author "), "ebook", "Please"),
                        owner,
                    )
                    .subscribed
            )
            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/root/api/v1/book-requests", create.path)
            val body = wireJson.parseToJsonElement(create.body.readUtf8()).jsonObject
            assertEquals(setOf("title", "authors", "mediaKind", "note"), body.keys)
            assertEquals("A book", body.getValue("title").jsonPrimitive.content)
            server.enqueue(MockResponse().setBody(row.replace("pending", "cancelled")))
            assertEquals("cancelled", api.cancelBookRequest(4, owner).status)
            server.takeRequest().let {
                assertEquals("/root/api/v1/book-requests/4/cancel", it.path)
                assertEquals("{}", it.body.readUtf8())
            }
            sessions.current = sessions.read()!!.copy(user = Account(8, username = "other"))
            assertThrows(ApiFailure::class.java) { api.bookRequests(1, null, null, owner) }
            assertThrows(ApiFailure::class.java) {
                api.submitBookRequest(NewBookRequest("Book", emptyList(), "audiobook"), owner)
            }
            assertThrows(ApiFailure::class.java) { api.cancelBookRequest(4, owner) }
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `request validation and cancellation follow personal permissions and lifecycle`() {
        val account = Account(7, username = "reader")
        requestStatuses.forEach { status ->
            val row = BookRequest(1, 7, "Book", mediaKind = "ebook", status = status)
            assertEquals(
                status !in listOf("rejected", "cancelled", "available"),
                row.canCancel(account),
            )
            assertFalse(row.copy(userId = 8).canCancel(account))
        }
        listOf(
                NewBookRequest(" ", emptyList(), "ebook"),
                NewBookRequest("Book", emptyList(), "comic"),
                NewBookRequest("Book", listOf("a".repeat(256)), "ebook"),
                NewBookRequest("Book", emptyList(), "ebook", "a".repeat(2001)),
            )
            .forEach { assertThrows(IllegalArgumentException::class.java) { it.validated() } }
    }
}

private class FeatureSessions(var current: Session?) : SessionStore {
    override fun read() = current

    override fun save(session: Session?) {
        current = session
    }
}
