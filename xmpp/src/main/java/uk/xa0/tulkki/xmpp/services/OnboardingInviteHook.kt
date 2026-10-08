package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: the easy-onboarding invite continuation.
 *
 * <p>It carries the three strings the invite is built from rather than the invite itself, so the
 * `:ui` implementer builds {@code uk.xa0.tulkki.app.utils.EasyOnboardingInvite} on its own side of the
 * boundary - the island has no reason to name a {@code Parcelable} it only ever forwards.
 *
 * <p>`landingUrl` and `message` stay nullable: the only caller (`MdsBookmarks`) passes
 * `Data.getValue("landing-url")` and `AbstractParser.errorMessage(...)`, both of which answer null,
 * and the Java's platform signatures tolerated it.
 */
interface OnboardingInviteHook {

    fun inviteRequested(domain: String, uri: String, landingUrl: String?)

    fun inviteRequestFailed(message: String?)
}
