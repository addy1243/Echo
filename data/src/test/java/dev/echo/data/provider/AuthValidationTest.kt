package dev.echo.data.provider

import dev.echo.data.spotify.validateAuthState
import org.junit.Assert.assertThrows
import org.junit.Test

class AuthValidationTest {
    @Test fun acceptsUnexpiredMatchingState() = validateAuthState("random-state", "random-state", 1_000, 601_000)
    @Test fun rejectsForgedMissingExpiredAndFutureStates() {
        assertThrows(IllegalArgumentException::class.java) { validateAuthState("expected", "forged", 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { validateAuthState("expected", null, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { validateAuthState("expected", "expected", 0, 600_001) }
        assertThrows(IllegalArgumentException::class.java) { validateAuthState("expected", "expected", 100, 99) }
    }
}
