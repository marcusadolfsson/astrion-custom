package com.custom.astrion.cards

import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.PickerModal

/**
 * Parses the `items:` list shared by `source_modal` and the D-pad's drawer.
 *
 * A curated entry names its own service, which is what lets ONE list carry both
 * "select this app on that Apple TV" and "deep-link this channel" -- the launcher
 * the TV Wall dashboard has always had, in one modal instead of two.
 *
 * ```yaml
 * items:
 * - name: Netflix
 *   image: /local/astrion/logos/netflix.png
 *   section: Apps          # optional: starts a labelled break above this entry
 *   service: media_player.select_source
 *   entity_id: media_player.living_room_apple_tv
 *   data: {source: Netflix}
 * ```
 */
object CuratedItems {

    /**
     * @param targetFrom entity whose STATE is the media_player to act on. When
     *   set and holding a real id, it overrides each entry's baked-in target --
     *   which is what makes one launcher follow the multi-view pane the keys are
     *   latched to instead of always hitting the primary Apple TV. Resolved at
     *   TAP time, not composition, so a latch change while the modal is open is
     *   still honoured.
     */
    @Suppress("UNCHECKED_CAST")
    fun parse(raw: Any?, ctx: CardContext, targetFrom: String? = null): List<PickerModal.Entry> =
        (raw as? List<*>).orEmpty().mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            val service = m["service"] as? String
            val entityId = m["entity_id"] as? String
            val data = (m["data"] as? Map<String, Any?>).orEmpty()
            PickerModal.Entry(
                name = name,
                image = m["image"] as? String,
                section = m["section"] as? String,
                sectionColumns = (m["section_columns"] as? Number)?.toInt(),
            ) {
                if (service != null) {
                    val override = targetFrom
                        ?.let { ctx.entities[it]?.state }
                        ?.takeIf { it.startsWith("media_player.") }
                    // The target appears in one of two places depending on the
                    // service: as the call's entity_id for select_source, and as
                    // an `apple_tv` field for the Spectrum launcher. Replace
                    // whichever this entry uses and leave the rest alone.
                    val ent = override ?: entityId
                    val d = if (override != null && data.containsKey("apple_tv"))
                        data + ("apple_tv" to override) else data
                    ctx.client.callService(
                        ServiceCall.of(
                            service.substringBefore('.'),
                            service.substringAfter('.'),
                            ent,
                            *d.entries.map { it.key to it.value }.toTypedArray(),
                        )
                    )
                }
            }
        }
}
