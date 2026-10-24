package app.bookorbit.community.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bookorbit.community.reader.ReadAlongController

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadAlongDialog(controller: ReadAlongController, onClose: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Read Along") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Embedded narration for this chapter. Text follows the book's timing marks. Leaving the reader pauses narration."
                )
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Passage ${if (state.count == 0) 0 else state.index + 1} of ${state.count}")
                Text(state.quote.take(1000))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { controller.move(-1) },
                        enabled = state.ready && state.index > 0,
                    ) {
                        Text("Previous")
                    }
                    Button(
                        onClick = { if (state.playing) controller.pause() else controller.play() },
                        enabled = state.ready,
                    ) {
                        Text(if (state.playing) "Pause" else "Play narration")
                    }
                    TextButton(
                        onClick = { controller.move(1) },
                        enabled = state.ready && state.index < state.count - 1,
                    ) {
                        Text("Next")
                    }
                }
                Text("Speed: ${state.speed}×")
                Slider(
                    modifier =
                        Modifier.semantics {
                            contentDescription = "Narration speed"
                            stateDescription = "${state.speed} times"
                        },
                    value = state.speed,
                    onValueChange = controller::speed,
                    valueRange = .5f..3f,
                    steps = 9,
                    enabled = state.ready,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
