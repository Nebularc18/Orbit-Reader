package app.bookorbit.community

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class OidcCallbackViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    private val mutable = MutableStateFlow<String?>(null)
    val result = mutable.asStateFlow()
    private var started = false

    fun complete(callback: String) {
        if (started) return
        started = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val (pending, code) =
                        app.oidcStore.consume(callback, System.currentTimeMillis())
                    app.api.completeOidc(pending, code)
                }
                mutable.value = "success"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.value =
                    "Sign-in failed or expired. Return to the app and start provider sign-in again. If it keeps failing, ask your server administrator to check native sign-in settings."
            }
        }
    }
}

class OidcCallbackActivity : ComponentActivity() {
    private val model: OidcCallbackViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.complete(intent.dataString.orEmpty())
        intent.data = null
        setContent {
            val result by model.result.collectAsStateWithLifecycle()
            LaunchedEffect(result) { if (result == "success") returnToApp(true) }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Provider sign-in", style = MaterialTheme.typography.headlineSmall)
                        if (result == null) {
                            CircularProgressIndicator()
                            Text("Completing sign-in…")
                        } else if (result != "success") {
                            Text(result.orEmpty())
                            Button(onClick = { returnToApp(false) }) {
                                Text("Return to Orbit Reader")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun returnToApp(success: Boolean) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (success) action = "app.bookorbit.community.OIDC_COMPLETE"
            }
        )
        finish()
    }
}
