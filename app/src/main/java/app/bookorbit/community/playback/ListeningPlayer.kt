package app.bookorbit.community.playback

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import app.bookorbit.community.data.AudioManifest
import app.bookorbit.community.data.SkipIntervals
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ListeningPlayer(private val delegate: Player) : ForwardingSimpleBasePlayer(delegate) {
    private var timeline: AudioTimeline? = null

    fun setManifest(book: AudioManifest) {
        timeline = AudioTimeline(book)
    }

    private var intervals = SkipIntervals()
    private var smart = false
    private var rewind = 10
    private var pausedAt: Long? = null

    init {
        delegate.addListener(
            object : Player.Listener {
                override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                    if (!ready && delegate.playbackState != Player.STATE_ENDED)
                        pausedAt = android.os.SystemClock.elapsedRealtime()
                }
            }
        )
    }

    fun smartResume(enabled: Boolean, seconds: Int) {
        smart = enabled
        rewind = seconds
        pausedAt = null
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val elapsed = pausedAt?.let { android.os.SystemClock.elapsedRealtime() - it } ?: 0
        val delta = app.bookorbit.community.data.smartRewindMillis(smart, elapsed, rewind)
        if (
            playWhenReady &&
                delta > 0 &&
                timeline != null &&
                delegate.playbackState != Player.STATE_ENDED
        ) {
            val target =
                timeline!!.skip(
                    delegate.currentMediaItemIndex,
                    delegate.currentPosition,
                    -delta,
                    delegate.duration,
                )
            delegate.seekTo(target.assetIndex, target.positionMs)
        }
        if (playWhenReady) pausedAt = null
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    fun setIntervals(value: SkipIntervals) {
        intervals = value.validated()
        invalidateState()
    }

    override fun getState(): SimpleBasePlayer.State =
        super.getState()
            .buildUpon()
            .setSeekBackIncrementMs(intervals.backSeconds * 1000L)
            .setSeekForwardIncrementMs(intervals.forwardSeconds * 1000L)
            .build()

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        pausedAt = null
        if (seekCommand != Player.COMMAND_SEEK_BACK && seekCommand != Player.COMMAND_SEEK_FORWARD)
            return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        val timeline = timeline ?: return Futures.immediateVoidFuture()
        val delta =
            if (seekCommand == Player.COMMAND_SEEK_BACK) -seekBackIncrement
            else seekForwardIncrement
        val target =
            timeline.skip(
                delegate.currentMediaItemIndex,
                delegate.currentPosition,
                delta,
                delegate.duration,
            )
        delegate.seekTo(target.assetIndex, target.positionMs)
        return Futures.immediateVoidFuture()
    }
}
