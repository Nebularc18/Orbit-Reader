package app.bookorbit.community.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.bookorbit.community.data.EbookDetail
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

fun bookDescription(value: String): String {
    val document = Jsoup.parse(value.take(100_000))
    document.select("script,style,iframe,object").remove()
    document.select("p,div,br,li,h1,h2,h3").forEach { it.after(TextNode("\n")) }
    return document.body().wholeText().trim().replace(Regex("\\n{3,}"), "\n\n")
}

@Composable
fun BookInformation(book: EbookDetail) {
    val context = LocalContext.current
    val description = remember(book.description) { book.description?.let(::bookDescription) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        book.subtitle
            ?.takeIf { it.isNotBlank() }
            ?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        book.seriesName?.let {
            Text(
                it + (book.seriesIndex?.let { index -> " · Book $index" } ?: ""),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (!description.isNullOrBlank()) {
            Text("About this book", style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
        val fields =
            listOfNotNull(
                    book.readStatus?.status?.let {
                        "Reading status" to it.replace('_', ' ').replaceFirstChar(Char::uppercase)
                    },
                    book.rating?.let { "Your rating" to it.toString() },
                    book.publisher?.let { "Publisher" to it },
                    (book.publishedDate?.takeIf { it.isNotBlank() }
                            ?: book.publishedYear?.toString())
                        ?.let { "Published" to it },
                    book.language?.let { "Language" to it },
                    book.pageCount?.takeIf { it > 0 }?.let { "Pages" to it.toString() },
                    book.isbn13?.let { "ISBN-13" to it },
                    book.isbn10?.let { "ISBN-10" to it },
                    book.libraryName?.let { "Library" to it },
                    book.genres
                        .takeIf { it.isNotEmpty() }
                        ?.let { "Genres" to it.joinToString(", ") },
                    book.tags.takeIf { it.isNotEmpty() }?.let { "Tags" to it.joinToString(", ") },
                    book.collections
                        .takeIf { it.isNotEmpty() }
                        ?.let { "Collections" to it.joinToString(", ") { entry -> entry.name } },
                )
                .filter { it.second.isNotBlank() }
        if (fields.isNotEmpty()) Text("Book details", style = MaterialTheme.typography.titleLarge)
        if (fields.isNotEmpty())
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border =
                    androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
            ) {
                Column {
                    fields.chunked(2).forEachIndexed { index, row ->
                        if (index > 0)
                            HorizontalDivider(
                                Modifier.padding(horizontal = 14.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            row.forEach { (label, value) ->
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(value, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        book.personalNote
            ?.takeIf { it.isNotBlank() }
            ?.let {
                Text("Your note", style = MaterialTheme.typography.titleMedium)
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        if (book.files.isNotEmpty()) {
            Text("Available files", style = MaterialTheme.typography.titleMedium)
            book.files.forEach { file ->
                Text(
                    listOfNotNull(
                            file.filename,
                            file.format?.uppercase(),
                            file.sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
                        )
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
