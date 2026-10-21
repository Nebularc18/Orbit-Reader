package app.bookorbit.community.reader

import app.bookorbit.community.data.EbookProgress
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReadingFeaturesTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        context.filesDir.resolve("ebooks").deleteRecursively()
    }

    @Test
    fun `notes survive recreation and deletion remains scoped to file and account`() {
        val store = ReadingNotes(context, "server-account", 101)
        val note =
            ReadingNote(
                revision = "v1",
                locator = "location",
                title = "Bookmark",
                quote = "A <script>literal quote</script>",
                note = "Note",
                highlight = true,
            )
        store.save(note)
        val reopened = ReadingNotes(context, "server-account", 101)
        assertEquals(listOf(note), reopened.all())
        reopened.save(note.copy(note = "Edited"))
        assertEquals(1, reopened.all().size)
        assertEquals("Edited", reopened.all().single().note)
        assertTrue(ReadingNotes(context, "other-account", 101).all().isEmpty())
        assertTrue(ReadingNotes(context, "server-account", 102).all().isEmpty())
        reopened.delete(note.id)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `invalid notes preserve the existing note`() {
        val store = ReadingNotes(context, "owner", 1)
        val note = ReadingNote(revision = "v1", locator = "location", title = "Name")
        store.save(note)
        assertThrows(IllegalArgumentException::class.java) { store.save(note.copy(title = " ")) }
        assertThrows(IllegalArgumentException::class.java) {
            store.save(note.copy(note = "x".repeat(10001)))
        }
        assertEquals(listOf(note), store.all())
    }

    @Test
    fun `comic page ordering handles numeric suffixes without integer overflow`() {
        val pages = listOf("page10.png", "page2.png", "page1.png", "page9999999999999999999999.png")
        assertEquals(
            listOf("page1.png", "page2.png", "page10.png", "page9999999999999999999999.png"),
            pages.sortedWith(::naturalPageCompare),
        )
    }

    @Test
    fun `progress changes include timestamps and backwards reading`() {
        val baseline = EbookProgress(50.0, null, 5, "one")
        assertTrue(sameReadingProgress(baseline, baseline.copy()))
        assertFalse(sameReadingProgress(baseline, baseline.copy(textUpdatedAt = "two")))
        assertFalse(sameReadingProgress(baseline, baseline.copy(percentage = 20.0, pageNumber = 2)))
        assertFalse(sameReadingProgress(baseline, baseline.copy(cfi = "another-location")))
    }
}
