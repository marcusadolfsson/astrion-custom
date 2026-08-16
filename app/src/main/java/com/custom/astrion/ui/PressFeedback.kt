package com.custom.astrion.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * Tap feedback for controls whose RESULT arrives seconds later.
 *
 * Most of this dashboard reads its own state back quickly, so a Material ripple
 * is enough: press a light, the tile lights up. The shades are the opposite
 * case. Bond is RF one-way, the cover state is inferred rather than reported,
 * and the physical travel is 30 s -- so a press produces no visible change for
 * 5-10 seconds. On a 3" screen a 200 ms ripple is not an answer to "did that
 * register", which is why the same button gets pressed three times.
 *
 * So this does two things a ripple does not:
 *  - reacts to the FINGER, not the click: [ButtonPressState.pressed] follows the
 *    interaction source, so the control responds on touch-down rather than on
 *    release.
 *  - LATCHES after the tap for [ACK_MS], holding a visible highlight well past
 *    the moment the finger leaves. That is the part that answers the question,
 *    because the press is over long before anything moves.
 *
 * Deliberately NOT an optimistic state change: it says "the press was taken",
 * not "the shade is open". Claiming the latter would be a lie for the 30 s the
 * shade is still moving, and a worse lie if the RF never landed.
 */
object PressFeedback {
    /** How long the acknowledgement stays visible after the tap. */
    const val ACK_MS = 900L

    /** Scale applied while pressed or acknowledging. Small -- it reads as a press, not a bounce. */
    const val PRESSED_SCALE = 0.90f
}

/** Live press + acknowledgement state for one control. */
class ButtonPressState(
    val interactionSource: MutableInteractionSource,
    val pressed: Boolean,
    val acked: Boolean,
) {
    /** True while the control should look "active" for either reason. */
    val active: Boolean get() = pressed || acked
}

/**
 * Remember press/ack state for a control, and a wrapped onClick that arms the
 * acknowledgement.
 *
 * Usage: hoist with [rememberPressFeedback], spread [ButtonPressState] into the
 * visual (scale, colour), and pass the returned lambda to `clickable`.
 */
@Composable
fun rememberPressFeedback(onClick: () -> Unit): Pair<ButtonPressState, () -> Unit> {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var acked by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }

    // Keyed on a counter, not on `acked`: a second tap during the window has to
    // RESTART the timer, and keying on the boolean would leave the first
    // effect's delay running and cut the second acknowledgement short.
    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        acked = true
        delay(PressFeedback.ACK_MS)
        acked = false
    }

    val state = ButtonPressState(interaction, pressed, acked)
    return state to {
        tick++
        onClick()
    }
}

/** Scale + clickable wiring shared by every control that uses the feedback. */
@Composable
fun Modifier.pressFeedback(state: ButtonPressState, onClick: () -> Unit): Modifier {
    // Animated, not snapped. The press-down is quick so it feels like a button;
    // the release back to full size is slower, which is what makes the
    // acknowledgement read as a deliberate confirmation rather than a flicker.
    val scale by animateFloatAsState(
        targetValue = if (state.active) PressFeedback.PRESSED_SCALE else 1f,
        animationSpec = tween(durationMillis = if (state.active) 70 else 220),
        label = "pressScale",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = state.interactionSource,
            // No ripple: the scale + colour change IS the feedback, and a ripple
            // on top of it reads as two different things happening.
            indication = null,
            onClick = onClick,
        )
}

/** Brighten a control's background while it is pressed or acknowledging. */
fun ackColor(base: Color, state: ButtonPressState): Color =
    if (state.active) Color(
        red = (base.red + (1f - base.red) * 0.45f),
        green = (base.green + (1f - base.green) * 0.45f),
        blue = (base.blue + (1f - base.blue) * 0.45f),
        alpha = base.alpha,
    ) else base
