package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarHandoverRegressionTest {

    @Test
    fun `queued sidecar paint is rejected after handover stops the ticker`() {
        assertFalse(
            shouldRenderSidecarCues(
                activeKey = null,
                queuedKey = "addon|https://example.test/subtitle.srt",
                viewTag = "addon|https://example.test/subtitle.srt"
            )
        )
    }

    @Test
    fun `current sidecar paint still requires the matching view generation`() {
        val key = "addon|https://example.test/subtitle.srt"

        assertTrue(shouldRenderSidecarCues(activeKey = key, queuedKey = key, viewTag = key))
        assertFalse(shouldRenderSidecarCues(activeKey = key, queuedKey = key, viewTag = "new-generation"))
    }
}
