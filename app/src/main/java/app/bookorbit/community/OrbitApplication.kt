package app.bookorbit.community

import android.app.Application
import app.bookorbit.community.data.AudioManifest
import app.bookorbit.community.data.EncryptedSessionStore
import app.bookorbit.community.data.OrbitApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore

class OrbitApplication : Application() {
    val sessions by lazy { EncryptedSessionStore(this) }
    val api by lazy { OrbitApi(sessions) }
    val currentManifest = MutableStateFlow<AudioManifest?>(null)
    val playbackMessage = MutableStateFlow<String?>(null)
    val progressConflict = MutableStateFlow<Int?>(null)
    val sleepMinutes = MutableStateFlow(0)
    val downloadSlots = Semaphore(2)
    private val downloadLocks = Array(64) { Mutex() }

    fun downloadLock(scope: String, bookId: Int): Mutex =
        downloadLocks[("$scope-$bookId".hashCode() and Int.MAX_VALUE) % downloadLocks.size]
}
