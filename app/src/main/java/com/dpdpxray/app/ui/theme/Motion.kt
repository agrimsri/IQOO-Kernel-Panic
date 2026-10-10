package com.dpdpxray.app.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 motion, kept small: three durations (150 / 250 / 350 ms) and the emphasized curves. Nothing bounces.
 * Every animated element asks [LocalReduceMotion] first and stands still when the person has turned animations off.
 */
object Motion {
    const val SHORT = 150
    const val MEDIUM = 250
    const val LONG = 350

    /** Emphasized: most on-screen movement. */
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Emphasized decelerate: things arriving (screens, cards, the score). */
    val enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Emphasized accelerate: things leaving. */
    val exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> enterSpec(ms: Int = MEDIUM, delay: Int = 0): FiniteAnimationSpec<T> = tween(ms, delay, enter)
    fun <T> exitSpec(ms: Int = SHORT): FiniteAnimationSpec<T> = tween(ms, 0, exit)
    fun <T> standard(ms: Int = MEDIUM): FiniteAnimationSpec<T> = tween(ms, 0, emphasized)

    /** [spec], or an instant change when motion is reduced. */
    fun <T> orSnap(reduce: Boolean, spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = if (reduce) snap() else spec
}

/** True when the system "Remove animations" setting (animator duration scale 0) is on. Provided by [XrayTheme]. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Pure rule, so it can be tested: a duration scale of 0 means "no animations". */
fun reduceMotionFor(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

/** Reads the system animator duration scale and follows changes while the app is open. */
@Composable
fun rememberSystemReduceMotion(): Boolean {
    val ctx = LocalContext.current
    val resolver = ctx.contentResolver
    fun read(): Boolean = reduceMotionFor(
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f),
    )
    var reduce by remember { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduce = read() }
        }
        runCatching { resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer) }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
    return reduce
}
