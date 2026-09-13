package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubtitleSelectionOverlayAutomaticSyncStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun automaticSyncFollowsLiveAddonSelectionWhileOverlayStaysOpen() {
        val addonSubtitle = Subtitle(
            id = "addon-subtitle",
            url = "https://example.test/subtitle.srt",
            lang = "en",
            addonName = "Test addon",
            addonLogo = null
        )
        val selectedAddonSubtitle = mutableStateOf<Subtitle?>(null)
        val events = mutableListOf<PlayerEvent>()

        composeRule.setContent {
            NuvioTheme {
                SubtitleSelectionOverlay(
                    visible = true,
                    internalTracks = listOf(
                        TrackInfo(
                            index = 0,
                            name = "English",
                            language = "en",
                            isSelected = true
                        )
                    ),
                    selectedInternalIndex = 0,
                    addonSubtitles = listOf(addonSubtitle),
                    selectedAddonSubtitle = selectedAddonSubtitle.value,
                    subtitleStyle = SubtitleStyleSettings(),
                    subtitleDelayMs = 0,
                    installedSubtitleAddonOrder = listOf(addonSubtitle.addonName),
                    isLoadingAddons = false,
                    automaticSyncAvailable = true,
                    automaticSyncRunning = false,
                    automaticSyncMessage = null,
                    automaticSyncReferenceTrackCount = 1,
                    automaticSyncCapturedCueCount = 12,
                    automaticSyncEngineSupported = true,
                    onInternalTrackSelected = {},
                    onAddonSubtitleSelected = { selectedAddonSubtitle.value = it },
                    onDisableSubtitles = {},
                    onEvent = { events += it },
                    onDismiss = {}
                )
            }
        }

        val automaticSyncTitle = context.getString(R.string.subtitle_automatic_sync)
        val selectAddonDetail = context.getString(R.string.subtitle_timing_select_addon_first)
        val automaticSyncDetail = context.getString(R.string.subtitle_automatic_sync_description)

        fun automaticSyncCard() = composeRule.onNode(
            hasClickAction() and hasAnyDescendant(hasText(automaticSyncTitle)),
            useUnmergedTree = true
        )

        fun clickAutomaticSyncCard() {
            automaticSyncCard().performScrollTo()
            automaticSyncCard().performSemanticsAction(SemanticsActions.OnClick)
        }

        val initialDetailNode = composeRule.onNodeWithText(selectAddonDetail)
        initialDetailNode.performScrollTo()
        initialDetailNode.assertIsDisplayed()
        clickAutomaticSyncCard()
        composeRule.runOnIdle {
            assertTrue(events.isEmpty())
            selectedAddonSubtitle.value = addonSubtitle
        }

        val selectedDetailNode = composeRule.onNodeWithText(automaticSyncDetail)
        selectedDetailNode.performScrollTo()
        selectedDetailNode.assertIsDisplayed()
        clickAutomaticSyncCard()
        composeRule.runOnIdle {
            assertEquals(listOf(PlayerEvent.OnAutomaticallySyncSubtitle), events)
            selectedAddonSubtitle.value = null
        }

        val revertedDetailNode = composeRule.onNodeWithText(selectAddonDetail)
        revertedDetailNode.performScrollTo()
        revertedDetailNode.assertIsDisplayed()
        clickAutomaticSyncCard()
        composeRule.runOnIdle {
            assertEquals(listOf(PlayerEvent.OnAutomaticallySyncSubtitle), events)
        }
    }
}
