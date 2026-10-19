package app.bookorbit.community.data

import org.junit.Assert.*
import org.junit.Test

class ManifestTest {
    private fun asset(sequence: Int) =
        AudioAsset(
            "aud_00000000-0000-4000-8000-${sequence.toString().padStart(12, '0')}",
            sequence + 1,
            sequence,
            "mp3",
            1000,
            100,
            "etag",
        )

    @Test
    fun `multi-file manifests are ordered by sequence`() {
        val manifest =
            AudioManifest(
                    "bookorbit.audiobook-manifest",
                    2,
                    "a".repeat(64),
                    AudioBook(1, "Test"),
                    listOf(asset(2), asset(0), asset(1)),
                    emptyList(),
                    3000,
                )
                .validated()
        assertEquals(listOf(0, 1, 2), manifest.assets.map { it.sequence })
    }

    @Test
    fun `future manifest versions fail explicitly`() {
        try {
            AudioManifest(
                    "bookorbit.audiobook-manifest",
                    99,
                    "revision",
                    AudioBook(1, "Test"),
                    listOf(asset(0)),
                    emptyList(),
                    1000,
                )
                .validated()
            fail("Expected version rejection")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `duplicate assets are rejected`() {
        try {
            AudioManifest(
                    "bookorbit.audiobook-manifest",
                    2,
                    "revision",
                    AudioBook(1, "Test"),
                    listOf(asset(0), asset(0)),
                    emptyList(),
                    2000,
                )
                .validated()
            fail("Expected duplicate rejection")
        } catch (_: IllegalArgumentException) {}
    }
}
