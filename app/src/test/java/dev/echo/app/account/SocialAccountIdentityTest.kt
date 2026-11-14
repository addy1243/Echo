package dev.echo.app.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialAccountIdentityTest {
    private val captured = SocialAccountIdentity("one@example.test", "subject-one", 4)

    @Test fun pendingActionRequiresSameEmailSubjectAndGeneration() {
        assertTrue(captured.matches("ONE@example.test", "subject-one", 4))
        assertFalse(captured.matches("two@example.test", "subject-one", 4))
        assertFalse(captured.matches("one@example.test", "subject-two", 4))
        assertFalse(captured.matches("one@example.test", "subject-one", 5))
    }

    @Test fun socialSessionBoundaryRejectsOfflineAndChangedAccount() {
        try {
            requireSocialRequestAllowed(captured, "one@example.test", "subject-one", 4, offlineOnly = true)
            throw AssertionError("Offline social request was allowed")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Offline"))
        }
        try {
            requireSocialRequestAllowed(captured, "one@example.test", "subject-one", 5, offlineOnly = false)
            throw AssertionError("Stale account generation was allowed")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("account changed"))
        }
    }
}
