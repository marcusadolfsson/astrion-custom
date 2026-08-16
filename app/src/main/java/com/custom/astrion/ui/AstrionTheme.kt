package com.custom.astrion.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The app's Material theme.
 *
 * This exists because the app had NO MaterialTheme at all. Every card paints its
 * own explicit colours, so that was invisible right up until something used a
 * stock Material component — and then it was very visible: `DropdownMenu` falls
 * back to the framework default, which is a LIGHT scheme, so tapping a selector
 * on this dark dashboard opened a white panel with black text. The menus were not
 * styled differently by choice; they were simply unthemed.
 *
 * Fixing it here rather than per-menu also repairs a subtler bug. Both selector
 * cards render their unselected rows as `Color.Unspecified`, i.e. "use the
 * theme's onSurface" — which resolved to near-black against the light default.
 * With a dark scheme in place those rows become legible without touching either
 * card.
 *
 * Colours are taken from what the cards already use, so the menu reads as part of
 * the same surface family rather than a new one:
 *   0xFF0E2229  page background
 *   0xFF16303A  raised surfaces (the settings/status sheets already use this)
 *   0xFF1E3841  control pills
 *   0xFF2E7D95  accent circles / selected row
 *   0xFFF1F4FA  primary text        0xFF93AFB6  secondary text
 */
private val AstrionColors = darkColorScheme(
    primary = Color(0xFF2E7D95),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF6EA8FE),
    onSecondary = Color(0xFF06202B),
    background = Color(0xFF0E2229),
    onBackground = Color(0xFFE6F0F1),
    // `surface` and `surfaceContainer` both matter: Material3 1.3 draws menus on
    // surfaceContainer via MenuDefaults, while older components use surface.
    // Setting only one leaves the menu on a tonally-derived grey.
    surface = Color(0xFF16303A),
    surfaceContainer = Color(0xFF16303A),
    surfaceContainerHigh = Color(0xFF1B3A45),
    surfaceContainerHighest = Color(0xFF1E3841),
    onSurface = Color(0xFFF1F4FA),
    surfaceVariant = Color(0xFF1E3841),
    onSurfaceVariant = Color(0xFF93AFB6),
    outline = Color(0xFF2A4954),
    outlineVariant = Color(0xFF24424C),
    // Transparent so Material's tonal-elevation overlay does not lighten the
    // menu back toward grey. Without this the container colour set above is
    // tinted by MenuDefaults.TonalElevation and stops matching the pills.
    surfaceTint = Color.Transparent,
    scrim = Color(0xCC000000),
)

/**
 * Corner radii matched to the dashboard's own geometry. Menus use `extraSmall`
 * in Material3, whose 4.dp default looks like a different app next to 30.dp
 * pills — this is most of why the dropdown "felt different".
 */
private val AstrionShapes = Shapes(
    extraSmall = RoundedCornerShape(18.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun AstrionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AstrionColors,
        shapes = AstrionShapes,
        content = content,
    )
}
