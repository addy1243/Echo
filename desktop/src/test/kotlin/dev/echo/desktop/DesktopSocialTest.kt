package dev.echo.desktop

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.json.JSONArray

class DesktopSocialTest {
    @Test fun productionEndpointStaysDisabledAndOpaqueRelationshipVersionsAreRetainedFailClosed() {
        assertFalse(DesktopSocialClient().enabled)
        val token = "actor-revision:target-revision"
        val people = DesktopSocialClient.users(JSONArray("""[{"userId":"u1","username":"friend","displayName":"Friend","relationshipVersion":"$token"},{"userId":"u2","relationshipVersion":7},{"userId":"u3"},{"userId":"u4","relationshipVersion":"${"x".repeat(257)}"}]"""), "relationshipVersion")
        assertEquals(token, people[0].version)
        assertEquals(null, people[1].version)
        assertEquals(null, people[2].version)
        assertEquals(null, people[3].version)
    }

    @Test fun inviteLinkMustMatchApprovedHostAndTokenComesFromFragment() {
        val client = DesktopSocialClient("https://api.example.test", "https://friends.example.test/open")
        val token = "A".repeat(43)
        assertEquals(token, client.validateInviteLink("https://friends.example.test/open#token=$token"))
        assertThrows(IllegalArgumentException::class.java) {
            client.validateInviteLink("https://attacker.example/open#token=$token")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.validateInviteLink("https://friends.example.test/open?token=$token")
        }
    }

    @Test fun endpointRequiresHttpsAndNoEmbeddedCredentialsOrRedirectTargets() {
        DesktopSocialClient("https://social.example.test")
        listOf("http://social.example.test", "https://user@social.example.test", "https://social.example.test/path?to=elsewhere",
            "https://social.example.test/path#fragment").forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) { DesktopSocialClient(endpoint) }
        }
    }
}
