package app.bookorbit.community.data

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl

const val OIDC_REDIRECT = "bookorbit://oauth2-callback"
const val OIDC_MAX_AGE_MS = 10 * 60 * 1000L

@Serializable
data class PendingOidc(
    val server: String,
    val state: String,
    val verifier: String,
    val nonce: String,
    val startedAt: Long,
    val previousScope: String?,
) {
    fun code(callback: String, now: Long): String {
        require(now >= startedAt && now - startedAt <= OIDC_MAX_AGE_MS) {
            "Sign-in expired. Try again."
        }
        val uri = URI(callback)
        require(
            uri.scheme == "bookorbit" &&
                uri.rawAuthority == "oauth2-callback" &&
                uri.rawPath.orEmpty().isEmpty() &&
                uri.rawFragment == null
        )
        val query =
            uri.rawQuery
                .orEmpty()
                .split('&')
                .filter { it.isNotEmpty() }
                .map { item ->
                    val pair = item.split('=', limit = 2)
                    URLDecoder.decode(pair[0], "UTF-8") to
                        URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                }
                .groupBy({ it.first }, { it.second })
        require(query["state"]?.singleOrNull() == state) {
            "Sign-in could not be verified. Try again."
        }
        require("error" !in query) { "Provider sign-in was cancelled or refused." }
        return requireNotNull(
            query["code"]?.singleOrNull()?.takeIf { it.isNotBlank() && it.length <= 2048 }
        )
    }
}

fun oidcSecret(): String =
    Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

fun pkceChallenge(verifier: String): String =
    Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )

fun oidcAuthorization(provider: OidcProvider, response: OidcState, pending: PendingOidc): String {
    require(
        provider.enabled &&
            provider.clientId.isNotBlank() &&
            response.state.isNotBlank() &&
            response.state.length <= 512
    )
    val endpoint = response.authorizationEndpoint.toHttpUrl()
    val server = pending.server.toHttpUrl()
    require(endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.fragment == null)
    require(
        endpoint.isHttps ||
            (!server.isHttps && endpoint.host == server.host && endpoint.port == server.port)
    ) {
        "The provider must use HTTPS."
    }
    return endpoint
        .newBuilder()
        .apply {
            mapOf(
                    "response_type" to "code",
                    "client_id" to provider.clientId,
                    "redirect_uri" to OIDC_REDIRECT,
                    "scope" to provider.scopes,
                    "code_challenge" to pkceChallenge(pending.verifier),
                    "code_challenge_method" to "S256",
                    "state" to pending.state,
                    "nonce" to pending.nonce,
                )
                .forEach { (key, value) -> setQueryParameter(key, value) }
        }
        .build()
        .toString()
}
