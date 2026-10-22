package app.bookorbit.community.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bookorbit.community.reader.ReadAloudController

@Composable
fun ReadAloudDialog(controller: ReadAloudController, onClose: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var voices by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Read aloud") },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "This chapter · ${if(state.count==0) 0 else state.index+1} of ${state.count} sentences",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(state.sentence.take(600), style = MaterialTheme.typography.bodyMedium)
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row {
                    TextButton(onClick = { controller.move(-1) }, enabled = state.index > 0) {
                        Text("Previous")
                    }
                    Button(
                        onClick = { if (state.playing) controller.pause() else controller.play() },
                        enabled = state.ready && state.count > 0 && !state.loading,
                    ) {
                        Text(if (state.playing) "Pause" else "Read")
                    }
                    TextButton(
                        onClick = { controller.move(1) },
                        enabled = state.index < state.count - 1,
                    ) {
                        Text("Next")
                    }
                }
                var draftSpeed by remember(state.speed) { mutableFloatStateOf(state.speed) }
                Text("Speed: ${draftSpeed}×")
                Slider(
                    draftSpeed,
                    { draftSpeed = it },
                    onValueChangeFinished = { controller.speed(draftSpeed) },
                    valueRange = .5f..2f,
                    steps = 5,
                )
                TextButton(onClick = { voices = true }, enabled = state.voices.isNotEmpty()) {
                    Text("Choose installed voice")
                }
                if (!state.ready)
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                            }
                        }
                    ) {
                        Text("Android speech settings")
                    }
                Text(
                    "Read-aloud pauses when you leave the reader. Dismiss these controls to follow the highlighted sentence.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Hide controls") } },
    )
    if (voices)
        AlertDialog(
            onDismissRequest = { voices = false },
            title = { Text("Installed voices") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(state.voices, key = { it.name }) { voice ->
                        TextButton(
                            onClick = {
                                controller.voice(voice.name)
                                voices = false
                            }
                        ) {
                            Text((if (voice.name == state.voice) "✓ " else "") + voice.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { voices = false }) { Text("Done") } },
        )
}
