package app.bookorbit.community.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BookDetailsStorageTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        context.filesDir.resolve("book-details").deleteRecursively()
    }

    @Test
    fun `cached book information survives recreation and remains account scoped`() {
        val detail =
            EbookDetail(
                7,
                "Book",
                description = "Synopsis",
                seriesName = "Series",
                seriesIndex = "2.5",
            )
        BookDetailsStorage(context, "server-a-account-a").save(detail)
        assertEquals(detail, BookDetailsStorage(context, "server-a-account-a").read(7))
        assertNull(BookDetailsStorage(context, "server-b-account-a").read(7))
        assertNull(BookDetailsStorage(context, "server-a-account-b").read(7))
        assertNull(BookDetailsStorage(context, "server-a-account-a").read(8))
    }
}
