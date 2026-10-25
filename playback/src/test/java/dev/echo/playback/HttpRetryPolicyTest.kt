package dev.echo.playback

import org.junit.Assert.*
import org.junit.Test

class HttpRetryPolicyTest {
    @Test fun sourceExtractionErrorsStayTerminalWhenWrappedByThePlayer() {
        assertTrue(Exception(SourceResolutionException("YouTube rate limited this request")).isSourceResolutionFailure())
        assertFalse(Exception("A temporary connection failure").isSourceResolutionFailure())
    }
    @Test fun rateLimitsAuthenticationAndMissingFilesRequireUserAction() {
        for (status in listOf(401, 403, 404, 408, 429)) {
            assertFalse("HTTP $status must not be retried automatically", isTransientHttpStatus(status))
        }
    }

    @Test fun onlyKnownServerErrorsAllowBoundedAutomaticRetry() {
        assertFalse(isTransientHttpStatus(null))
        assertFalse(isTransientHttpStatus(499))
        assertTrue(isTransientHttpStatus(500))
        assertTrue(isTransientHttpStatus(503))
        assertTrue(isTransientHttpStatus(599))
        assertFalse(isTransientHttpStatus(600))
    }
}
