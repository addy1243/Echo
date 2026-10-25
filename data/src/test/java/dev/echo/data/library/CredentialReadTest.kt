package dev.echo.data.library

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialReadTest {
    @Test fun `unreadable credential returns empty value and exposes an error`() {
        var error: String? = null
        assertEquals("", readCredential({ throw IllegalStateException("Keystore unavailable") }) { error = it })
        assertNotNull(error)
        assertEquals("key", readCredential({ "key" }) { error = it })
        assertNull(error)
    }

    @Test fun `cancellation propagates unchanged`() {
        val cancellation = CancellationException("Cancelled")
        val thrown = assertThrows(CancellationException::class.java) {
            readCredential({ throw cancellation }) { throw AssertionError("Cancellation is not a credential error") }
        }
        assertSame(cancellation, thrown)
    }
}
