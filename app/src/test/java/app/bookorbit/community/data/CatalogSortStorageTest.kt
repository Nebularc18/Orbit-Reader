package app.bookorbit.community.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CatalogSortStorageTest {
    @Test
    fun `reading filters persist per shelf and account`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("catalog-read-filters", 0).edit().clear().commit()
        CatalogReadFilterStorage(context, "a").save("audio", CatalogReadFilter.FINISHED)
        CatalogReadFilterStorage(context, "a").save("ebooks", CatalogReadFilter.UNREAD)
        assertEquals(
            CatalogReadFilter.FINISHED,
            CatalogReadFilterStorage(context, "a").read("audio"),
        )
        assertEquals(
            CatalogReadFilter.UNREAD,
            CatalogReadFilterStorage(context, "a").read("ebooks"),
        )
        assertEquals(CatalogReadFilter.ALL, CatalogReadFilterStorage(context, "b").read("audio"))
        CatalogReadFilterStorage(context, "a").save("audio", CatalogReadFilter.ALL)
        assertEquals(CatalogReadFilter.ALL, CatalogReadFilterStorage(context, "a").read("audio"))
    }

    @Test
    fun `sorts survive recreation and stay separate across shelves accounts and servers`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("catalog-sorts", 0).edit().clear().commit()
        CatalogSortStorage(context, "server-a-user-a").save("audio", false, CatalogSort.SERIES)
        val reopened = CatalogSortStorage(context, "server-a-user-a")
        assertEquals(CatalogSort.SERIES, reopened.read("audio", false))
        assertEquals(CatalogSort.TITLE_ASC, reopened.read("audio", true))
        assertEquals(CatalogSort.TITLE_ASC, reopened.read("ebooks", false))
        assertEquals(
            CatalogSort.TITLE_ASC,
            CatalogSortStorage(context, "server-a-user-b").read("audio", false),
        )
        assertEquals(
            CatalogSort.TITLE_ASC,
            CatalogSortStorage(context, "server-b-user-a").read("audio", false),
        )
        reopened.save("audio", true, CatalogSort.AUTHOR_DESC)
        assertEquals(
            CatalogSort.AUTHOR_DESC,
            CatalogSortStorage(context, "server-a-user-a").read("audio", true),
        )
    }
}
