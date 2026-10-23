package app.bookorbit.community.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

fun useNavigationRail(widthDp: Int) = widthDp >= 600

fun catalogCellWidth(widthDp: Int, fontScale: Float): Int =
    ((if (useNavigationRail(widthDp)) 156 else 100) * fontScale.coerceIn(1f, 2f)).toInt()

@Composable
fun adaptiveBookColumns(): GridCells {
    val config = LocalConfiguration.current
    return GridCells.Adaptive(catalogCellWidth(config.screenWidthDp, config.fontScale).dp)
}

@Composable
fun AdaptiveAppScaffold(
    selectedTab: Int,
    onTab: (Int) -> Unit,
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val config = LocalConfiguration.current
    val rail = useNavigationRail(config.screenWidthDp)
    val largeText = config.fontScale >= 1.3f
    Row(Modifier.fillMaxSize()) {
        if (rail) {
            NavigationRail(
                Modifier.safeDrawingPadding()
                    .width(if (largeText) 240.dp else 112.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                containerColor = Color.Transparent,
            ) {
                val labels = listOf("Home", "Library", "Listen", "Read", "Downloads")
                val ids = listOf(0, 4, 1, 2, 3)
                val icons =
                    listOf(
                        Icons.Rounded.Home,
                        Icons.AutoMirrored.Rounded.LibraryBooks,
                        Icons.Rounded.Headphones,
                        Icons.Rounded.AutoStories,
                        Icons.Rounded.DownloadDone,
                    )
                labels.forEachIndexed { index, label ->
                    if (largeText)
                        NavigationDrawerItem(
                            selected = selectedTab == ids[index],
                            onClick = { onTab(ids[index]) },
                            icon = { Icon(icons[index], null) },
                            label = {
                                Text(
                                    label,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    else
                        NavigationRailItem(
                            selected = selectedTab == ids[index],
                            onClick = { onTab(ids[index]) },
                            icon = { Icon(icons[index], null) },
                            label = {
                                Text(
                                    label,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                }
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            containerColor = Color.Transparent,
            topBar = topBar,
            bottomBar = bottomBar,
        ) { padding ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 1200.dp).fillMaxSize()) { content(padding) }
            }
        }
    }
}
