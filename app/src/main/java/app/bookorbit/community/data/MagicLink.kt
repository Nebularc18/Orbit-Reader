package app.bookorbit.community.data

import okhttp3.HttpUrl.Companion.toHttpUrl

fun magicLinkToken(server: String, input: String): String {
    val value = input.trim()
    val token =
        if ("://" in value) {
            val base = normalizeServer(server).toHttpUrl()
            val url = value.toHttpUrl()
            require(
                url.scheme == base.scheme &&
                    url.host == base.host &&
                    url.port == base.port &&
                    url.username.isEmpty() &&
                    url.password.isEmpty()
            ) {
                "Use a magic link from this server."
            }
            require(
                url.encodedPath == base.encodedPath.trimEnd('/') + "/magic" &&
                    url.fragment == null &&
                    url.queryParameterNames == setOf("token")
            )
            url.queryParameterValues("token").singleOrNull().orEmpty()
        } else value
    require(
        token.isNotBlank() &&
            token.length <= 512 &&
            token.none { it.isWhitespace() || it.code < 32 }
    ) {
        "Enter a valid magic link or token."
    }
    return token
}
