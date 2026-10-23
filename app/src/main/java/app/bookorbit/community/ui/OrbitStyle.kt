package app.bookorbit.community.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.AudioManifest
import app.bookorbit.community.data.Session

@Composable
fun OrbitTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val dark =
        when (appearance.theme) {
            "Light" -> false
            "Dark" -> true
            else -> isSystemInDarkTheme()
        }
    val accent =
        when (appearance.finish) {
            "Sage" -> if (dark) Color(0xFFB6CBA8) else Color(0xFF46623B)
            "Ocean" -> if (dark) Color(0xFFA1CED7) else Color(0xFF286575)
            else -> if (dark) Color(0xFFE2C77F) else Color(0xFF806124)
        }
    val colors =
        if (dark)
            darkColorScheme(
                primary = accent,
                onPrimary = Color(0xFF242013),
                primaryContainer = accent.copy(alpha = 0.16f).compositeOver(Color(0xFF191815)),
                onPrimaryContainer = Color(0xFFF4E8C8),
                secondary = accent,
                secondaryContainer = Color(0xFF353128),
                onSecondaryContainer = Color(0xFFECE3D1),
                background = Color(0xFF100F0D),
                onBackground = Color(0xFFF0EDE5),
                surface = Color(0xFF100F0D),
                onSurface = Color(0xFFF0EDE5),
                surfaceVariant = Color(0xFF34322C),
                onSurfaceVariant = Color(0xFFAAA79C),
                outline = Color(0xFF79766A),
                outlineVariant = Color(0xFF39362E),
                surfaceContainerLowest = Color(0xFF0B0A09),
                surfaceContainerLow = Color(0xFF191815),
                surfaceContainer = Color(0xFF211F1A),
                surfaceContainerHigh = Color(0xFF2B2822),
                surfaceContainerHighest = Color(0xFF353129),
            )
        else
            lightColorScheme(
                primary = accent,
                onPrimary = Color.White,
                primaryContainer = accent.copy(alpha = 0.12f).compositeOver(Color(0xFFFAF8F1)),
                onPrimaryContainer = Color(0xFF342B18),
                secondary = accent,
                secondaryContainer = Color(0xFFEBE4D6),
                onSecondaryContainer = Color(0xFF493F2D),
                background = Color(0xFFFAF8F1),
                onBackground = Color(0xFF29261F),
                surface = Color(0xFFFAF8F1),
                onSurface = Color(0xFF29261F),
                surfaceVariant = Color(0xFFE5E0D4),
                onSurfaceVariant = Color(0xFF746E60),
                outline = Color(0xFF8D8577),
                outlineVariant = Color(0xFFDDD6C8),
                surfaceContainerLowest = Color.White,
                surfaceContainerLow = Color(0xFFF4F0E7),
                surfaceContainer = Color(0xFFEFEADF),
                surfaceContainerHigh = Color(0xFFE9E2D5),
                surfaceContainerHighest = Color(0xFFE2DACC),
            )
    val base = Typography()
    SystemBarAppearance(colors.background.luminance() > .5f)
    MaterialTheme(
        colorScheme = colors,
        typography =
            base.copy(
                headlineMedium = base.headlineMedium.copy(fontSize = 26.sp, lineHeight = 32.sp),
                headlineSmall = base.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp),
                titleLarge =
                    base.titleLarge.copy(
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                titleMedium = base.titleMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
                bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
                bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
            ),
        shapes =
            Shapes(
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(24.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
        content = content,
    )
}

@Composable
fun AmbientBackground(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable BoxScope.() -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier.background(background)) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.13f), Color.Transparent),
                    center = Offset(size.width * .85f, 0f),
                    radius = size.width * 1.1f,
                )
            )
            for (i in 0..2) drawCircle(
                accent.copy(alpha = .035f),
                radius = size.width * (.44f + i * .12f),
                center = Offset(size.width * .93f, size.width * .24f),
                style = Stroke(1.dp.toPx()),
            )
        }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}

@Composable
fun BookHero(
    app: OrbitApplication,
    account: Session,
    id: Int,
    version: String,
    title: String,
    authors: String,
    ebook: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(app, account, id, version, Modifier.size(104.dp, 148.dp), ebook)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (ebook) "EBOOK" else "AUDIOBOOK",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.2.sp,
            )
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (authors.isNotBlank())
                Text(
                    authors,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
        }
    }
}

@Composable
fun SegmentPicker(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        labels.forEachIndexed { index, label ->
            Surface(
                onClick = { onSelect(index) },
                shape = RoundedCornerShape(11.dp),
                color =
                    if (index == selected) MaterialTheme.colorScheme.surfaceContainerLowest
                    else Color.Transparent,
                modifier = Modifier.weight(1f).semantics { this.selected = index == selected },
            ) {
                Text(
                    label,
                    Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        if (index == selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun CircleAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .8f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(20.dp)) }
    }
}

@Composable
fun CoverProgress(progress: Double, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        drawLine(
            track,
            Offset(0f, center.y),
            Offset(size.width, center.y),
            3.dp.toPx(),
            StrokeCap.Round,
        )
        drawLine(
            accent,
            Offset(0f, center.y),
            Offset(size.width * (progress / 100).toFloat().coerceIn(0f, 1f), center.y),
            3.dp.toPx(),
            StrokeCap.Round,
        )
    }
}

@Composable
fun MiniPlayer(
    app: OrbitApplication,
    account: Session,
    book: AudioManifest,
    controller: MediaController?,
    onOpen: () -> Unit,
) {
    var playing by remember(controller) { mutableStateOf(controller?.isPlaying == true) }
    DisposableEffect(controller) {
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                }
            }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(app, account, book.book.id, book.revision, Modifier.size(40.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    book.book.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (playing) "Now playing" else "Continue listening",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledIconButton(
                onClick = {
                    controller?.let {
                        if (it.isPlaying) it.pause()
                        else {
                            if (it.playbackState == Player.STATE_ENDED) it.seekTo(0, 0)
                            it.play()
                        }
                    }
                },
                enabled = controller != null,
            ) {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (playing) "Pause audiobook" else "Play audiobook",
                )
            }
        }
    }
}

@Composable
internal fun SystemBarAppearance(light: Boolean) {
    val view = LocalView.current
    SideEffect {
        var context = view.context
        while (
            context is android.content.ContextWrapper && context !is android.app.Activity
        ) context = context.baseContext
        (context as? android.app.Activity)?.let { activity ->
            var parent = view.parent
            var dialogWindow: android.view.Window? = null
            while (parent != null) {
                if (parent is androidx.compose.ui.window.DialogWindowProvider) {
                    dialogWindow = parent.window
                    break
                }
                parent = parent.parent
            }
            androidx.core.view.WindowCompat.getInsetsController(
                    dialogWindow ?: activity.window,
                    view,
                )
                .apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
        }
    }
}
