package com.custom.astrion.cards.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.cards.CardRenderer

/**
 * Conditional wrapper — renders its child card(s) only when an entity's state
 * matches. Lets the dashboard show context-specific controls: e.g. an Apple TV
 * app picker only while the AV activity is "Watch Apple TV". Analogous to Home
 * Assistant's conditional card.
 *
 * Show rule (first that applies):
 *   - state_in: [..]  -> show when the entity's state is one of these
 *   - state: "x"      -> show when state == "x"
 *   - state_not: "x"  -> show when state != "x"
 *   - (entity only)   -> show when the entity exists / has any state
 *
 * Config shape:
 *   { "type": "conditional", "options": {
 *       "entity_id": "input_select.lr_av_activity",
 *       "state": "Watch Apple TV",
 *       "card":  { "type": "source_select", "options": { "entity_id": "media_player.living_room_apple_tv" } }
 *       // or "cards": [ { ... }, { ... } ] to reveal several stacked cards
 *   } }
 */
class ConditionalCard : CardRenderer {
    override val type = "conditional"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        if (!matches(config, ctx)) return

        val single = config.options["card"] as? Map<String, Any?>
        val many = config.options["cards"] as? List<Map<String, Any?>>
        val children = when {
            single != null -> listOf(single)
            many != null -> many
            else -> emptyList()
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            children.forEach { child ->
                val t = child["type"] as? String ?: return@forEach
                val opts = (child["options"] as? Map<String, Any?>) ?: emptyMap()
                val renderer = CardRegistry.get(t) ?: return@forEach
                renderer.Render(CardConfig(t, opts), ctx)
            }
        }
    }

    companion object {
        /**
         * Would this conditional show anything?
         *
         * Public because the PAGE needs to ask before it lays out. A card that
         * renders nothing still occupies a LazyColumn slot, and the page spaces
         * its items with Arrangement.spacedBy -- so N hidden cards leave N gaps
         * of dead space. The Main page stacks three conditionals above the first
         * separator, which is why a room with nothing playing showed a large
         * hole between the status bar and "Watch".
         *
         * Kept here, next to Render, so the two can never disagree about what
         * "visible" means.
         */
        fun matches(config: CardConfig, ctx: CardContext): Boolean {
            val entityId = config.string("entity_id") ?: return true
            val cur = ctx.entities[entityId]?.state
            val stateIn = config.stringList("state_in")
            val wantState = config.string("state")
            val wantNot = config.string("state_not")
            return when {
                stateIn.isNotEmpty() -> cur in stateIn
                wantState != null -> cur == wantState
                wantNot != null -> cur != wantNot
                else -> cur != null
            }
        }
    }
}
