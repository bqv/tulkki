package uk.xa0.tulkki.xmpp.models.muc

/**
 * XEP-0045 multi-user chat: an occupant's affiliation. The Java enum moved name for name, order
 * included; `user.Item.getAffiliation` reads it with `valueOf` and falls back to [NONE] on a name it
 * does not know.
 */
enum class Affiliation {
    OWNER,
    ADMIN,
    MEMBER,
    OUTCAST,
    NONE,
}
