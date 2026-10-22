package app.bookorbit.community.data

import android.content.Context
import java.text.Collator

enum class CatalogSort(
    val label: String,
    val wireField: String,
    val direction: String,
    val local: Boolean = false,
) {
    TITLE_ASC("Title A-Z", "title", "asc", true),
    TITLE_DESC("Title Z-A", "title", "desc", true),
    AUTHOR_ASC("Author A-Z", "author", "asc", true),
    AUTHOR_DESC("Author Z-A", "author", "desc", true),
    ADDED_NEWEST("Newest added", "addedAt", "desc"),
    ADDED_OLDEST("Oldest added", "addedAt", "asc"),
    SERIES("Series order", "series", "asc"),
    PUBLISHED_NEWEST("Newest published", "publishedDate", "desc"),
    RATING("Highest rated", "rating", "desc");

    val fields: List<Pair<String, String>>
        get() =
            listOf(wireField to direction) +
                (if (this == SERIES) listOf("seriesIndex" to "asc") else emptyList()) +
                (if (wireField != "title") listOf("title" to "asc") else emptyList())
}

class CatalogSortStorage(context: Context, private val scope: String) {
    private val preferences = context.getSharedPreferences("catalog-sorts", 0)

    fun read(shelf: String, offline: Boolean): CatalogSort =
        CatalogSort.entries.firstOrNull {
            it.name == preferences.getString("$scope/$shelf/$offline", null) &&
                (!offline || it.local)
        } ?: CatalogSort.TITLE_ASC

    fun save(shelf: String, offline: Boolean, sort: CatalogSort) {
        require(!offline || sort.local)
        preferences.edit().putString("$scope/$shelf/$offline", sort.name).apply()
    }
}

fun sortLocalBooks(books: Sequence<BookCard>, sort: CatalogSort): List<BookCard> {
    require(sort.local)
    val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
    fun compareText(a: String?, b: String?, reverse: Boolean = false): Int {
        if (a.isNullOrBlank()) return if (b.isNullOrBlank()) 0 else 1
        if (b.isNullOrBlank()) return -1
        val comparison = collator.compare(a, b)
        return if (reverse) -comparison else comparison
    }
    return books
        .sortedWith { a, b ->
            val primary =
                if (sort.wireField == "author")
                    compareText(
                        a.authors.firstOrNull(),
                        b.authors.firstOrNull(),
                        sort.direction == "desc",
                    )
                else compareText(a.title, b.title, sort.direction == "desc")
            val secondary =
                if (primary == 0 && sort.wireField == "author") compareText(a.title, b.title)
                else primary
            if (secondary == 0) a.id.compareTo(b.id) else secondary
        }
        .toList()
}
