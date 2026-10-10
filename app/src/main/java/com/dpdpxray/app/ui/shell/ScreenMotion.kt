package com.dpdpxray.app.ui.shell

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.dpdpxray.app.ui.theme.Motion

/**
 * Screen transitions for the NavHost, in the Material 3 feel: going deeper fades in while sliding a little from the
 * right; going back reverses it; switching tabs is a quiet fade-through. Nothing moves when animations are off.
 */
object ScreenMotion {
    /** Both ends are bottom-bar tabs: a fade-through instead of a slide, since tabs are peers, not a hierarchy. */
    fun isTabSwitch(from: String?, to: String?): Boolean = AppTabs.tabOf(from) != null && AppTabs.tabOf(to) != null

    fun enter(reduce: Boolean, tab: Boolean): EnterTransition = when {
        reduce -> EnterTransition.None
        tab -> fadeIn(tween(Motion.MEDIUM, delayMillis = 60, easing = Motion.enter)) + scaleIn(tween(Motion.MEDIUM, delayMillis = 60, easing = Motion.enter), initialScale = 0.98f)
        else -> fadeIn(tween(Motion.MEDIUM, easing = Motion.enter)) + slideInHorizontally(tween(Motion.LONG, easing = Motion.enter)) { it / 12 }
    }

    fun exit(reduce: Boolean, tab: Boolean): ExitTransition = when {
        reduce -> ExitTransition.None
        tab -> fadeOut(tween(90, easing = Motion.exit))
        else -> fadeOut(tween(Motion.SHORT, easing = Motion.exit)) + slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.exit)) { -it / 24 }
    }

    fun popEnter(reduce: Boolean, tab: Boolean): EnterTransition = when {
        reduce -> EnterTransition.None
        tab -> enter(false, true)
        else -> fadeIn(tween(Motion.MEDIUM, easing = Motion.enter)) + slideInHorizontally(tween(Motion.LONG, easing = Motion.enter)) { -it / 24 }
    }

    fun popExit(reduce: Boolean, tab: Boolean): ExitTransition = when {
        reduce -> ExitTransition.None
        tab -> exit(false, true)
        else -> fadeOut(tween(Motion.SHORT, easing = Motion.exit)) + slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.exit)) { it / 12 }
    }
}
