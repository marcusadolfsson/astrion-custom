package com.custom.astrion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.concurrent.atomic.AtomicInteger

/**
 * How many menus or dialogs are open right now.
 *
 * Exists so the idle clock can hold off while someone is mid-decision. Without
 * it the screensaver simply counts out its timer and raises itself behind an
 * open dropdown -- the menu still works, but the dashboard behind it has been
 * replaced by a black clock, which looks broken.
 *
 * A counter rather than a boolean because more than one surface can be up at
 * once (a card's dialog over a menu), and the last one to close is the one that
 * should release the hold.
 *
 * Deliberately a plain global, not state: nothing RECOMPOSES on this, it is only
 * ever read from the activity's idle timer. Making it observable would invite
 * exactly the kind of dashboard-wide recomposition the entity plumbing works to
 * avoid.
 */
object OpenOverlays {
    private val count = AtomicInteger(0)

    val any: Boolean get() = count.get() > 0

    /**
     * Register an overlay for as long as [open] is true and this composable is
     * in the tree. Tied to composition rather than to a callback so it cannot
     * leak a count: whatever removes the menu -- a pick, a back press, the card
     * scrolling out of the layout -- disposes this too.
     */
    @Composable
    fun Track(open: Boolean) {
        DisposableEffect(open) {
            if (open) count.incrementAndGet()
            onDispose { if (open) count.decrementAndGet() }
        }
    }
}
