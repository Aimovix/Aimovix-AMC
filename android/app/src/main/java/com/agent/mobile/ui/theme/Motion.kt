package com.agent.mobile.ui.theme

import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext

/**
 * Motion rules. Movement explains a change: where something came from, that a touch registered,
 * or that content was swapped. It never delays the user, never loops to hold attention, and is
 * switched off when the system setting "Remove animations" is on.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) {
            false
        }
    }
}

/** Spring for layout changes (sizes, rotation). Snaps when motion is reduced. */
@Composable
fun <T> amcSpring(): FiniteAnimationSpec<T> =
    if (rememberReducedMotion()) snap<T>()
    else spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** Content appearing: a short fade while it opens. */
@Composable
fun amcEnter(): EnterTransition =
    if (rememberReducedMotion()) EnterTransition.None
    else fadeIn(tween(160)) + expandVertically(tween(220, easing = FastOutSlowInEasing))

/** Content leaving: quicker than it arrived. */
@Composable
fun amcExit(): ExitTransition =
    if (rememberReducedMotion()) ExitTransition.None
    else fadeOut(tween(120)) + shrinkVertically(tween(180, easing = FastOutSlowInEasing))

/** Content swapped in place (for example an approval request turning into its result). */
@Composable
fun amcContentTransform(): ContentTransform {
    val reduced = rememberReducedMotion()
    return remember(reduced) {
        if (reduced) {
            EnterTransition.None togetherWith ExitTransition.None
        } else {
            fadeIn(tween(180)) togetherWith fadeOut(tween(120))
        }
    }
}

/** Immediate tactile feedback: the element eases down while it is pressed. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, pressedScale: Float = 0.97f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val reduced = rememberReducedMotion()
    val value by animateFloatAsState(
        targetValue = if (pressed && !reduced) pressedScale else 1f,
        animationSpec = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    return this.scale(value)
}
