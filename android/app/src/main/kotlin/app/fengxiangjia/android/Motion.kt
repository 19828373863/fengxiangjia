package app.fengxiangjia.android

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/** Native motion vocabulary; edits and task completion never wait for these timings. */
object FxjMotion {
    const val Quick = 120
    const val Standard = 220
    const val Exit = 160
    const val Settle = 280
    val Easing = CubicBezierEasing(.2f, 0f, 0f, 1f)
}

val LocalFxjMotionEnabled = compositionLocalOf { true }

@Composable
fun rememberSystemMotionEnabled(): Boolean {
    val context = LocalContext.current
    fun enabled() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var motionEnabled by remember(context) { mutableStateOf(enabled()) }
    val owner = context as? LifecycleOwner
    var foreground by remember(owner) { mutableStateOf(owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> foreground = owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { motionEnabled = enabled() }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return motionEnabled && foreground
}

/** Outgoing content is visual only; it must not remain a second touch/accessibility target. */
fun Modifier.inactiveMotionContent(inactive: Boolean): Modifier = if (!inactive) this else
    this.clearAndSetSemantics {}.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

@Composable
fun MotionReveal(visible: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val enabled = LocalFxjMotionEnabled.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.inactiveMotionContent(!visible),
        enter = if (enabled) expandVertically(tween(FxjMotion.Standard, easing = FxjMotion.Easing), expandFrom = Alignment.Top) + fadeIn(tween(FxjMotion.Quick)) else EnterTransition.None,
        exit = if (enabled) shrinkVertically(tween(FxjMotion.Exit, easing = FxjMotion.Easing), shrinkTowards = Alignment.Top) + fadeOut(tween(FxjMotion.Quick)) else ExitTransition.None,
        label = "section disclosure",
    ) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}
