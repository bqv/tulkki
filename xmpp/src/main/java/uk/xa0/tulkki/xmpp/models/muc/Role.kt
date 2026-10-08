package uk.xa0.tulkki.xmpp.models.muc

/**
 * XEP-0045 multi-user chat: an occupant's role. The Java enum moved name for name, order included;
 * `user.Item.getRole` reads it with `valueOf` and falls back to [NONE] on a name it does not know.
 */
enum class Role {
    MODERATOR,
    VISITOR,
    PARTICIPANT,
    NONE,
}
