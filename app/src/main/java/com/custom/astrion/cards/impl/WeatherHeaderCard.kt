package com.custom.astrion.cards.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer

/**
 * Weather, shaped to sit in the dock index's header band.
 *
 * The band is reserved whether or not anything is playing, so that the buttons
 * below never move (see HEADER_BAND in Dashboard.kt). That leaves a strip of
 * empty background in a quiet room, and this is what goes in it -- a readout
 * that is worth something at a glance and asks for nothing.
 *
 * Deliberately NOT a control. Nothing here is tappable: this is the one card
 * that appears without being asked for, and a surprise target in the space
 * where a now-playing row usually sits is how you end up pressing it by
 * accident when the music stops.
 *
 * ```yaml
 * - type: conditional
 *   options:
 *     entity_id: binary_sensor.lr_dock_media_visible
 *     state: 'off'
 *     dock_header: true
 *     card:
 *       type: weather_header
 *       options: { entity_id: weather.forecast_home }
 * ```
 */
class WeatherHeaderCard : CardRenderer {
    override val type = "weather_header"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId] ?: return
        val condition = e.state?.trim().orEmpty()
        val temp = e.attrDouble("temperature")
        val unit = e.attrString("temperature_unit") ?: "°"
        val humidity = e.attrDouble("humidity")
        val wind = e.attrDouble("wind_speed")
        val windUnit = e.attrString("wind_speed_unit") ?: ""

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Centred in the gap between the status bar and the first row
                // of buttons -- NOT on the media header's inset.
                //
                // The media row cannot be centred there: it has a timeline
                // pinned under it, so its content sits high by necessity. This
                // has nothing below it, so it gets the middle of the space
                // instead of inheriting a position that only makes sense with a
                // bar beneath it.
                //
                // The arithmetic, at this screen's 1.375 density: the clock
                // bottoms out at y=53 and the first tile row starts at y=193, so
                // the middle is 123. The content is 54dp (74px) tall, which puts
                // its top at 86px -- 62dp. That also clears the room indicator,
                // which hangs 17px lower than the clock and would otherwise be
                // clipped by the temperature.
                .padding(start = 18.dp, end = 18.dp, top = 62.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                iconFor(condition),
                contentDescription = null,
                tint = Color(0xFF9FD4E3),
                modifier = Modifier.size(54.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    temp?.let { "${fmt(it)}$unit" } ?: "—",
                    color = Color(0xFFF1F4FA),
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Light,
                )
                Text(
                    label(condition),
                    color = Color(0xFF93AFB6),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.weight(1f))
            // Right-aligned, quiet, and only what actually resolves. A weather
            // integration that does not publish wind should leave a gap rather
            // than a row of dashes pretending to be a reading.
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                humidity?.let {
                    Reading(Icons.Filled.WaterDrop, "${fmt(it)}%")
                }
                wind?.let {
                    Reading(Icons.Filled.Air, "${fmt(it)} $windUnit".trim())
                }
            }
        }
    }

    @Composable
    private fun Reading(icon: ImageVector, text: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color(0xFF6E8A93),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(text, color = Color(0xFF93AFB6), fontSize = 15.sp)
        }
    }

    /** Trim a trailing ".0" so 83.0 reads 83, and round the rest to one place. */
    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format("%.1f", v)

    /**
     * Home Assistant's weather states, which are a fixed vocabulary -- so this
     * is a complete mapping rather than a best effort, and the `else` is only
     * reached by `exceptional` and anything a future HA adds.
     */
    private fun iconFor(condition: String): ImageVector = when (condition) {
        "sunny" -> Icons.Filled.WbSunny
        "clear-night" -> Icons.Filled.NightsStay
        "partlycloudy" -> Icons.Filled.WbCloudy
        "cloudy" -> Icons.Filled.Cloud
        "fog" -> Icons.Filled.CloudQueue
        "rainy", "snowy-rainy" -> Icons.Filled.Grain
        "pouring" -> Icons.Filled.Umbrella
        "lightning", "lightning-rainy" -> Icons.Filled.Thunderstorm
        "snowy", "hail" -> Icons.Filled.AcUnit
        "windy", "windy-variant" -> Icons.Filled.Air
        else -> Icons.Filled.Cloud
    }

    /** "partlycloudy" is not a word. Title-case the rest. */
    private fun label(condition: String): String = when (condition) {
        "partlycloudy" -> "Partly cloudy"
        "clear-night" -> "Clear"
        "snowy-rainy" -> "Sleet"
        "lightning-rainy" -> "Thunderstorms"
        "pouring" -> "Heavy rain"
        "exceptional" -> "Severe"
        "" -> "—"
        else -> condition.replace('-', ' ').replaceFirstChar { it.uppercase() }
    }
}
