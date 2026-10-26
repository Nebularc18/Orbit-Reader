package app.bookorbit.community.playback

import app.bookorbit.community.data.ListeningSessionWrite
import java.time.Instant
import java.util.UUID

data class ListeningCheckpoint(
    val owner: String,
    val fileId: Int,
    val value: ListeningSessionWrite,
)

/** Called on the player thread. Elapsed time counts listening, regardless of playback speed. */
class ListeningSessionTracker {
    private var owner: String? = null
    private var fileId: Int? = null
    private var startedAt = Instant.EPOCH
    private var lastElapsedMs = 0L
    private var activeMs = 0L
    private var sessionId = ""

    fun sample(
        nextOwner: String?,
        nextFileId: Int?,
        playing: Boolean,
        now: Instant,
        elapsedMs: Long,
    ): ListeningCheckpoint? {
        val previousOwner = owner
        val previousFile = fileId
        var checkpoint: ListeningCheckpoint? = null
        if (previousOwner != null && previousFile != null) {
            activeMs += (elapsedMs - lastElapsedMs).coerceAtLeast(0)
            // A wall-clock correction must not create invalid timestamps or inflated durations.
            val seconds = minOf(activeMs / 1000, java.time.Duration.between(startedAt, now).seconds)
            if (seconds >= 10) {
                checkpoint =
                    ListeningCheckpoint(
                        previousOwner,
                        previousFile,
                        ListeningSessionWrite(
                            sessionId,
                            startedAt.toString(),
                            now.toString(),
                            seconds.toInt(),
                            sessionType = "listen",
                            source = "android",
                        ),
                    )
            }
        }
        val continues = playing && nextOwner != null && nextFileId != null
        if (
            !continues ||
                nextOwner != owner ||
                nextFileId != fileId ||
                activeMs >= 300_000 ||
                now < startedAt
        ) {
            owner = null
            fileId = null
            activeMs = 0
        }
        if (continues && owner == null) {
            owner = nextOwner
            fileId = nextFileId
            startedAt = now
            sessionId = UUID.randomUUID().toString()
        }
        lastElapsedMs = elapsedMs
        return checkpoint
    }
}
