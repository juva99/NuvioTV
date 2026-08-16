package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubIssueAuthorizationRepositoryTest {
    @Test
    fun `builds a complete GitHub device verification URL`() {
        assertEquals(
            "https://github.com/login/device?user_code=ABCD-EFGH",
            buildVerificationUriComplete(
                verificationUri = "https://github.com/login/device",
                userCode = "ABCD-EFGH"
            )
        )
    }
}
