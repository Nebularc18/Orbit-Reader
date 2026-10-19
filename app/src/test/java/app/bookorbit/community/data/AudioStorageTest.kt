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
class AudioStorageTest {
    private val asset =
        AudioAsset("aud_00000000-0000-4000-8000-000000000001", 1, 0, "mp3", 1000, 8, "etag")
    private val manifest =
        AudioManifest(
            "bookorbit.audiobook-manifest",
            2,
            "a".repeat(64),
            AudioBook(1, "Sample"),
            listOf(asset),
            emptyList(),
            1000,
        )
    private lateinit var storage: AudioStorage

    @Before
    fun setup() {
        RuntimeEnvironment.getApplication().filesDir.resolve("audio").deleteRecursively()
        storage = AudioStorage(RuntimeEnvironment.getApplication(), "account-a")
    }

    @Test
    fun `offline library survives storage recreation and excludes incomplete assets`() {
        storage.saveManifest(manifest)
        storage.asset(1, asset).writeBytes(ByteArray(3))
        assertFalse(storage.downloaded(manifest))
        assertTrue(storage.downloadedBooks(1).isEmpty())
        storage.asset(1, asset).writeBytes(ByteArray(8))
        val reopened = AudioStorage(RuntimeEnvironment.getApplication(), "account-a")
        assertTrue(reopened.downloaded(manifest))
        assertEquals("Sample", reopened.downloadedBooks(1).single().book.title)
        assertTrue(reopened.downloadedBooks(2).isEmpty())
    }

    @Test
    fun `another account cannot browse downloaded files or listening positions`() {
        storage.saveManifest(manifest)
        storage.asset(1, asset).writeBytes(ByteArray(8))
        storage.capturePosition(1, asset.assetId, 900, "2026-10-18T12:00:00Z", manifest.revision)
        val other = AudioStorage(RuntimeEnvironment.getApplication(), "account-b")
        assertTrue(other.downloadedBooks(1).isEmpty())
        assertNull(other.position(1))
        assertFalse(other.downloaded(manifest))
    }

    @Test
    fun `acknowledging an older sync preserves newer unsynced listening progress`() {
        storage.capturePosition(1, asset.assetId, 300, "2026-10-18T12:00:00Z", manifest.revision)
        val sent = storage.position(1)!!
        storage.capturePosition(1, asset.assetId, 900, "2026-10-18T12:00:01Z", manifest.revision)
        storage.acknowledge(
            1,
            sent,
            PlaybackState(asset.assetId, 300, sent.capturedAt, 1, manifest.revision),
        )
        val current = storage.position(1)!!
        assertEquals(900, current.positionMs)
        assertEquals(1, current.revision)
        assertTrue(current.dirty)
        storage.acknowledge(
            1,
            current,
            PlaybackState(asset.assetId, 900, current.capturedAt, 2, manifest.revision),
        )
        assertFalse(storage.position(1)!!.dirty)
    }

    @Test
    fun `removing downloads preserves durable listening progress`() {
        storage.saveManifest(manifest)
        storage.asset(1, asset).writeBytes(ByteArray(8))
        storage.capturePosition(1, asset.assetId, 900, "2026-10-18T12:00:00Z", manifest.revision)
        storage.deleteBook(1)
        assertFalse(storage.downloaded(manifest))
        assertEquals(900, storage.position(1)!!.positionMs)
        assertNull(storage.manifest(1))
    }
}
