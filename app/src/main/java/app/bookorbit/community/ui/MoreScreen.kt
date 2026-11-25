package app.bookorbit.community.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun MoreScreen(
    modifier: Modifier,
    onActivity: () -> Unit,
    onHistory: () -> Unit,
    onNotebook: () -> Unit,
    onRequests: () -> Unit,
    onUploads: () -> Unit,
    onAppearance: () -> Unit,
    onServers: () -> Unit,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "More",
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        MoreGroup("Reading") {
            MoreItem("Activity", Icons.Rounded.Insights, onActivity)
            MoreItem("History", Icons.Rounded.History, onHistory)
            MoreItem("Notebook", Icons.Rounded.Bookmarks, onNotebook)
        }
        MoreGroup("Library") {
            MoreItem("Book requests", Icons.Rounded.Add, onRequests)
            MoreItem("Upload a book", Icons.Rounded.Upload, onUploads)
        }
        MoreGroup("Settings") {
            MoreItem("Appearance and account", Icons.Rounded.Tune, onAppearance)
            MoreItem("Servers", Icons.Rounded.Dns, onServers)
        }
    }
}

@Composable
private fun MoreGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun MoreItem(title: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainer) {
        ListItem(
            headlineContent = { Text(title) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
            colors =
                ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
    }
}
