package com.custom.astrion.config

/**
 * Works out which HA entities the current layout actually references, so the
 * client can subscribe to just those instead of the whole instance.
 *
 * Why: this HA has ~1,650 entities. `get_states` + `subscribe_events` meant the
 * remote parsed a 0.7 MB seed on every connect and then ~2.6 state_changed
 * events/s forever (chattiest: power/voltage sensors this app never shows) — all
 * on a 1 GB MT6580 with a ~6 MB Java heap. The layout only references ~30.
 *
 * Rather than enumerate every option key that can hold an entity (entity_id,
 * active_entity, target_entity, monitor rows, per-button service targets, the
 * open/stop/close and reverse/forward action maps, conditional children…), this
 * walks the whole options tree and takes any string shaped like an entity id.
 * Over-collecting is harmless — a few extra subscriptions — whereas MISSING one
 * would leave a card permanently blank, so the bias is deliberate.
 */
object EntityRefs {

    // HA entity ids are lowercase `domain.object_id`. Service names like
    // "script.lr_av_off" match too; they're real entities, so subscribing is fine.
    private val ENTITY_ID = Regex("^[a-z][a-z0-9_]*\\.[a-z0-9_]+$")

    fun collect(config: AppConfig): Set<String> {
        val out = sortedSetOf<String>()
        config.pages.forEach { page ->
            page.cards.forEach { card -> walk(card.options, out) }
        }
        // The overlay reads these outside any card, so collect them explicitly —
        // otherwise the filtered subscription would never deliver their updates.
        config.overlay?.let { o ->
            listOfNotNull(o.volumeEntity, o.muteEntity).forEach {
                if (ENTITY_ID.matches(it)) out.add(it)
            }
            // The gate entity too, or the filtered subscription never delivers it
            // and the condition evaluates against a null state forever.
            o.condition?.let { walk(it.options, out) }
        }
        // Page-scoped hotkeys too, not just the global list: a binding that only
        // exists on one page still names an entity, and missing it leaves that
        // card permanently blank (see the over-collect bias noted above).
        val allHotkeys = config.hotkeys + config.longHotkeys +
            config.pages.flatMap { it.hotkeys + it.longHotkeys }
        allHotkeys.forEach { hk ->
            hk.entityId?.let { if (ENTITY_ID.matches(it)) out.add(it) }
            walk(hk.data, out)
        }
        // The page-control entity is written AND read by the app; without it here
        // the app would never see HA's page changes.
        config.pageEntity?.let { if (ENTITY_ID.matches(it)) out.add(it) }
        // Status view cards are off the pager but still subscribe like any others.
        config.status?.cards?.forEach { walk(it.options, out) }
        return out
    }

    private fun walk(value: Any?, out: MutableSet<String>) {
        when (value) {
            is String -> if (ENTITY_ID.matches(value)) out.add(value)
            is Map<*, *> -> value.values.forEach { walk(it, out) }
            is List<*> -> value.forEach { walk(it, out) }
            else -> {}
        }
    }
}
