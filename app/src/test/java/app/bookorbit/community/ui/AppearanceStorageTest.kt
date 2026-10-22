package app.bookorbit.community.ui

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppearanceStorageTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
    }

    @Test
    fun `theme and finish survive reopening without leaking across accounts or servers`() {
        val expected = Appearance("Dark", "Ocean")
        assertTrue(AppearanceStorage(context, "server-a-user-a").save(expected))
        assertEquals(expected, AppearanceStorage(context, "server-a-user-a").read())
        assertEquals(Appearance(), AppearanceStorage(context, "server-a-user-b").read())
        assertEquals(Appearance(), AppearanceStorage(context, "server-b-user-a").read())
    }

    @Test
    fun `unknown settings cannot replace a saved appearance`() {
        val storage = AppearanceStorage(context, "account")
        storage.save(Appearance("Light", "Sage"))
        assertFalse(storage.save(Appearance("Invalid", "Brass")))
        assertFalse(storage.save(Appearance("Dark", "Invalid")))
        assertEquals(Appearance("Light", "Sage"), storage.read())
    }
}
