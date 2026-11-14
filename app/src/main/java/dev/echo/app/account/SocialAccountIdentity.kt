package dev.echo.app.account

internal data class SocialAccountIdentity(val email: String, val subject: String, val generation: Long) {
    fun matches(email: String?, subject: String?, generation: Long): Boolean =
        this.generation == generation && this.subject == subject && this.email.equals(email, ignoreCase = true)
}

internal fun requireSocialRequestAllowed(
    identity: SocialAccountIdentity,
    email: String?,
    subject: String?,
    generation: Long,
    offlineOnly: Boolean,
) {
    check(!offlineOnly) { "Turn off Offline mode to use social" }
    check(identity.matches(email, subject, generation)) { "Google account changed; social request stopped" }
}
