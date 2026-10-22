package app.bookorbit.community.data

import org.junit.Assert.*
import org.junit.Test

class CatalogSortTest {
    @Test
    fun `local title ordering spans pages and ties keep stable ids`() {
        val books = (1..65).map { BookCard(it, "Book %03d".format(66 - it)) }
        val asc = sortLocalBooks(books.asSequence(), CatalogSort.TITLE_ASC)
        assertEquals((65 downTo 1).toList(), asc.map { it.id })
        assertEquals(listOf(35, 34, 33), asc.drop(30).take(3).map { it.id })
        assertEquals(
            (1..65).toList(),
            sortLocalBooks(books.asSequence(), CatalogSort.TITLE_DESC).map { it.id },
        )
        assertEquals(
            listOf(1, 2),
            sortLocalBooks(
                    sequenceOf(BookCard(2, "same"), BookCard(1, "Same")),
                    CatalogSort.TITLE_DESC,
                )
                .map { it.id },
        )
    }

    @Test
    fun `author ordering keeps unknown authors last in either direction`() {
        val books =
            listOf(
                BookCard(1, "Unknown"),
                BookCard(2, "B", listOf("Alice")),
                BookCard(3, "A", listOf("Alice")),
                BookCard(4, "Z", listOf("Zoe")),
            )
        assertEquals(
            listOf(3, 2, 4, 1),
            sortLocalBooks(books.asSequence(), CatalogSort.AUTHOR_ASC).map { it.id },
        )
        assertEquals(
            listOf(4, 3, 2, 1),
            sortLocalBooks(books.asSequence(), CatalogSort.AUTHOR_DESC).map { it.id },
        )
    }
}
