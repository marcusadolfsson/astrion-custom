package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer

/**
 * Bubble-Card-style section separator: a small icon + label followed by a
 * divider line filling the rest of the row. Purely visual — groups the bubble
 * selectors into sections like the wall dashboards' `separator` cards.
 *
 * Config: { "type": "separator", "options": { "name": "Display", "icon": "screen" } }
 */
class SeparatorCard : CardRenderer {
    override val type = "separator"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val name = config.string("name") ?: ""
        val icon = CardIcons.forName(config.string("icon"))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFF7FB3C4), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            if (name.isNotBlank()) {
                Text(name, color = Color(0xFF9FC0CB), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.weight(1f).height(1.dp).background(Color(0x33FFFFFF)))
        }
    }
}
