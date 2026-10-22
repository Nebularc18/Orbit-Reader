package app.bookorbit.community.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OrbitTabBar(selectedTab: Int, onSelect: (Int) -> Unit) {
    val icons =
        listOf(
            Icons.Rounded.Home,
            Icons.AutoMirrored.Rounded.LibraryBooks,
            Icons.Rounded.Headphones,
            Icons.Rounded.AutoStories,
            Icons.Rounded.DownloadDone,
        )
    val labels = listOf("Home", "Library", "Listen", "Read", "Downloads")
    val tabIds = listOf(0, 4, 1, 2, 3)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(5.dp)) {
            labels.forEachIndexed { index, label ->
                Surface(
                    onClick = { onSelect(tabIds[index]) },
                    modifier =
                        Modifier.weight(1f).semantics {
                            selected = selectedTab == tabIds[index]
                            role = Role.Tab
                        },
                    shape = CircleShape,
                    color =
                        if (selectedTab == tabIds[index])
                            MaterialTheme.colorScheme.primary.copy(alpha = .17f)
                        else Color.Transparent,
                ) {
                    Column(
                        Modifier.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        val tint =
                            if (selectedTab == tabIds[index]) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        Icon(icons[index], null, Modifier.size(22.dp), tint = tint)
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSheet(
    appearance: Appearance,
    model: AppearanceViewModel,
    onClose: () -> Unit,
    onSignOut: () -> Unit,
    onServers: () -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SystemBarAppearance(MaterialTheme.colorScheme.surface.luminance() > .5f)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Make yourself at home", style = MaterialTheme.typography.titleLarge)
            Text(
                "Appearance",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SegmentPicker(
                listOf("System", "Light", "Dark"),
                listOf("System", "Light", "Dark").indexOf(appearance.theme),
                { model.theme(listOf("System", "Light", "Dark")[it]) },
            )
            Text(
                "Finish",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("Brass", "Sage", "Ocean").forEach { finish ->
                    val accent =
                        when (finish) {
                            "Sage" -> Color(0xFFB6CBA8)
                            "Ocean" -> Color(0xFFA1CED7)
                            else -> Color(0xFFE2C77F)
                        }
                    Surface(
                        onClick = { model.finish(finish) },
                        modifier =
                            Modifier.weight(1f).semantics {
                                selected = appearance.finish == finish
                            },
                        shape = RoundedCornerShape(18.dp),
                        color = Color(0xFF1C1A16),
                        border =
                            BorderStroke(
                                if (appearance.finish == finish) 2.dp else 1.dp,
                                if (appearance.finish == finish) accent else Color(0xFF454137),
                            ),
                    ) {
                        Column(
                            Modifier.padding(vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "Aa",
                                fontFamily = FontFamily.Serif,
                                fontSize = 25.sp,
                                color = accent,
                            )
                            Text(
                                finish,
                                style = MaterialTheme.typography.labelMedium,
                                color = accent,
                            )
                            if (appearance.finish == finish)
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    "Selected",
                                    Modifier.size(16.dp),
                                    tint = accent,
                                )
                            else Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(onClick = onServers) {
                Icon(Icons.Rounded.Dns, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Servers")
            }
            TextButton(onClick = onSignOut) {
                Icon(Icons.Rounded.AccountCircle, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sign out")
            }
        }
    }
}
