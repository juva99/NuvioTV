package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateRepositoryTest {
    private val prefix = "v0.8.4-beta-subtitle-sync."

    @Test
    fun `selects highest numbered matching prerelease`() {
        val selected = selectLatestPrerelease(
            listOf(release("$prefix.ignored"), release("${prefix}6"), release("${prefix}12")),
            prefix
        )

        assertEquals("${prefix}12", selected?.tagName)
    }

    @Test
    fun `selects the newest subtitle sync release across app version prefixes`() {
        val selected = selectLatestPrerelease(
            listOf(
                release("v0.7.17-beta-subtitle-sync.12"),
                release("v0.8.4-beta-subtitle-sync.9"),
                release("v0.8.4-beta-subtitle-sync.13"),
                release("v0.8.3-beta-subtitle-sync.99")
            ),
            prefix
        )

        assertEquals("v0.8.4-beta-subtitle-sync.13", selected?.tagName)
    }

    @Test
    fun `ignores drafts stable releases and other prerelease channels`() {
        assertNull(
            selectLatestPrerelease(
                listOf(
                    release("${prefix}7", draft = true),
                    release("${prefix}8", prerelease = false),
                    release("v0.8.4-beta-other.9")
                ),
                prefix
            )
        )
    }

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = true
    ) = GitHubReleaseDto(tagName = tag, draft = draft, prerelease = prerelease)
}
