package app.bookorbit.community.data

import kotlinx.serialization.Serializable

val bookStatuses =
    listOf(
        "unread",
        "want_to_read",
        "reading",
        "on_hold",
        "rereading",
        "read",
        "skimmed",
        "abandoned",
    )

fun statusLabel(value: String) =
    if (value == "read") "Finished" else value.replace('_', ' ').replaceFirstChar(Char::uppercase)

@Serializable
data class BookCollection(
    val id: Int,
    val name: String,
    val isOwner: Boolean = false,
    val mediaType: String = "books",
)
