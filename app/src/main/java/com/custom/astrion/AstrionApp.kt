package com.custom.astrion

import android.app.Application
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.cards.impl.BubbleLightCard
import com.custom.astrion.cards.impl.BubbleClimateCard
import com.custom.astrion.cards.impl.BubbleSelectCard
import com.custom.astrion.cards.impl.ButtonGridCard
import com.custom.astrion.cards.impl.ClimateCard
import com.custom.astrion.cards.impl.ClockWeatherCard
import com.custom.astrion.cards.impl.ConditionalCard
import com.custom.astrion.cards.impl.CoverCard
import com.custom.astrion.cards.impl.FanCard
import com.custom.astrion.cards.impl.LightCard
import com.custom.astrion.cards.impl.MediaPlayerCard
import com.custom.astrion.cards.impl.MonitorCard
import com.custom.astrion.cards.impl.PictureElementsCard
import com.custom.astrion.cards.impl.PlexCard
import com.custom.astrion.cards.impl.RowCard
import com.custom.astrion.cards.impl.SceneGridCard
import com.custom.astrion.cards.impl.SeparatorCard
import com.custom.astrion.cards.impl.ShadeControlCard
import com.custom.astrion.cards.impl.SourceSelectCard
import com.custom.astrion.cards.impl.SpeakerGroupCard
import com.custom.astrion.cards.impl.SwitchCard
import com.custom.astrion.cards.impl.TvRemoteCard
import com.custom.astrion.cards.impl.VacuumCard

/**
 * App entry point. Register all card types here once at startup.
 *
 * To add a brand-new native card type:
 *   1. Create a class implementing CardRenderer (see cards/impl/ for examples).
 *   2. Add one line below.
 *   3. Reference it in DashboardConfig with its `type` string.
 *
 * That's the whole extension model — no re-patching anyone's APK, no fixed
 * taxonomy of 11 types.
 */
class AstrionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CardRegistry.register(
            LightCard(),
            SceneGridCard(),
            BubbleLightCard(),
            TvRemoteCard(),
            MediaPlayerCard(),
            ClimateCard(),
            CoverCard(),
            FanCard(),
            SwitchCard(),
            ClockWeatherCard(),
            PictureElementsCard(),
            RowCard(),
            MonitorCard(),
            ButtonGridCard(),
            PlexCard(),
            SpeakerGroupCard(),
            SourceSelectCard(),
            VacuumCard(),
            ConditionalCard(),
            BubbleSelectCard(),
            BubbleClimateCard(),
            SeparatorCard(),
            ShadeControlCard(),
            // ← register your own card types here
        )
    }
}
