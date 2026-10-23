package app.bookorbit.community.reader

import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

enum class ReaderTap {
    PREVIOUS,
    CONTROLS,
    NEXT,
}

fun readerTap(x: Float, width: Int): ReaderTap =
    when {
        width <= 0 || !x.isFinite() -> ReaderTap.CONTROLS
        x < width * 0.25f -> ReaderTap.PREVIOUS
        x > width * 0.75f -> ReaderTap.NEXT
        else -> ReaderTap.CONTROLS
    }

@Composable
fun ReaderSystemBars(window: Window, controlsVisible: Boolean, light: Boolean) {
    SideEffect {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (controlsVisible) show(WindowInsetsCompat.Type.systemBars())
            else hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(window) {
        onDispose {
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
fun rememberTouchExploration(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager =
        androidx.compose.runtime.remember(context) {
            context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
                as android.view.accessibility.AccessibilityManager
        }
    var enabled by
        androidx.compose.runtime.remember(manager) {
            androidx.compose.runtime.mutableStateOf(manager.isTouchExplorationEnabled)
        }
    DisposableEffect(manager) {
        val listener =
            android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener {
                enabled = it
            }
        manager.addTouchExplorationStateChangeListener(listener)
        enabled = manager.isTouchExplorationEnabled
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}
