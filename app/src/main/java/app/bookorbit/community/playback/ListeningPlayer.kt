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
