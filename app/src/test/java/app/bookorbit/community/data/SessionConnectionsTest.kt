package app.bookorbit.community.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SessionConnectionsTest {
    private fun session(server: String, token: String = "access") =
        Session(server, token, "refresh-$token", Account(7, username = "reader"))

    @Test
    fun `existing single sign-in migrates without changing scope or tokens`() {
        val old = session("https://one.example/books")
        val registry = decodeSessionConnections(wireJson.encodeToString(old))
        assertEquals(old, registry.active)
        assertEquals(listOf(old), registry.saved)
        val second = session("https://two.example/books")
        val persisted = decodeSessionConnections(wireJson.encodeToString(registry.save(second)))
        assertEquals(second, persisted.active)
        assertEquals(old, persisted.activate(old.scope).active)
    }

    @Test
    fun `same account ID on two servers stays isolated and rotation updates only that connection`() {
        val a = session("https://one.example")
        val b = session("https://two.example")
        val registry =
            SessionConnections()
                .save(a)
                .save(b)
                .activate(a.scope)
                .save(a.copy(accessToken = "rotated", refreshToken = "rotated-refresh"))
        assertEquals(2, registry.saved.size)
        assertEquals("rotated", registry.active!!.accessToken)
        assertEquals(b, registry.activate(b.scope).active)
        assertEquals(
            "rotated-refresh",
            decodeSessionConnections(wireJson.encodeToString(registry)).active!!.refreshToken,
        )
    }

    @Test
    fun `disconnect removes only the active connection and picks the remaining server`() {
        val a = session("https://one.example")
        val b = session("https://two.example")
        val remaining = SessionConnections().save(a).save(b).save(null)
        assertEquals(a, remaining.active)
        assertEquals(listOf(a), remaining.saved)
        assertTrue(remaining.save(null).saved.isEmpty())
        assertNull(remaining.save(null).active)
        assertThrows(IllegalArgumentException::class.java) { remaining.activate(b.scope) }
    }
}
