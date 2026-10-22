package app.bookorbit.community.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.bookorbit.community.data.Session

fun serverLabel(session: Session): String =
    android.net.Uri.parse(session.server).let {
        (it.host ?: session.server) +
            (if (it.port >= 0) ":${it.port}" else "") +
            it.path.orEmpty().trimEnd('/')
    }

@Composable
fun ServerConnections(
    connections: List<Session>,
    active: String?,
    busy: Boolean,
    error: String?,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Your servers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Choose a server to browse. Playback stops when you switch. Your books and progress stay with their server.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LazyColumn(
                    Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(connections, key = { it.scope }) { connection ->
                        Surface(
                            onClick = { onSwitch(connection.scope) },
                            enabled = !busy && connection.scope != active,
                            color =
                                if (connection.scope == active)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (connection.scope == active) Icons.Rounded.CheckCircle
                                    else Icons.Rounded.Dns,
                                    if (connection.scope == active) "Active server" else null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        serverLabel(connection),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        connection.user.username.ifBlank { connection.user.name },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAdd, enabled = !busy) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add server")
            }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Done") } },
    )
}
