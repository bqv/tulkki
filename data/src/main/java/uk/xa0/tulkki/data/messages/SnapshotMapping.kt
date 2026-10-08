package uk.xa0.tulkki.data.messages

/**
 * The entity -> read-model mapping (S5-6), and the one place a stored column becomes a snapshot
 * field.
 *
 * <p>Both functions are `internal` because the entity is: `docs/MIGRATION.md`, "Design: the data
 * layer" §2.7 makes the entity invisible outside `:data` by Kotlin's own module rule, so a public
 * function could not name it. What is public is the read model and the repository that hands it
 * out ([ConversationSnapshots], [MessageSnapshots]).
 *
 * <p>The bodies are the only pair that needs a decision, and it is the file's: `translated_body` is
 * the app-language side and `body` is the original, and they travel together into
 * [ConversationBodies]. `MessageSnapshot`'s scalars are copied at the width and the nullability the
 * entity declares, so no stored value is reinterpreted here.
 */
internal fun MessageEntity.asSnapshot(): MessageSnapshot =
    MessageSnapshot(
        id = uuid,
        conversationId = conversationUuid,
        timeSent = timeSent,
        counterpart = counterpart,
        trueCounterpart = trueCounterpart,
        type = type,
        status = status,
        encryption = encryption,
        delivery = delivery,
        read = read,
        deleted = deleted,
        fileDeleted = fileDeleted,
        markable = markable,
        oob = oob,
        carbon = carbon,
        retractId = retractId,
        edited = edited,
        serverMsgId = serverMsgId,
        remoteMsgId = remoteMsgId,
        axolotlFingerprint = axolotlFingerprint,
        // The file carries the occupant twice, `occupantId` and its legacy `occupant_id` spelling,
        // and `Message`'s own reader lets the legacy column win when it is not null
        // (`Message.java:447-449`). The fold is that reading, not a second rule.
        occupantId = occupantIdSnake ?: occupantId,
        relativeFilePath = relativeFilePath,
        fileParams = fileParams,
        oobUri = oobUri,
        errorMsg = errorMsg,
        bodyLanguage = bodyLanguage,
        reactions = reactions,
        readByMarkers = readByMarkers,
        translationState = translationState,
        translationLang = translationLang,
        timeReceived = timeReceived,
        subject = subject,
        expireAt = expireAt,
        ephemeralTimer = ephemeralTimer,
        notificationDismissed = notificationDismissed,
        bodies = ConversationBodies(translated = translatedBody, original = body),
    )

/**
 * The conversation row with its pointer, which the caller has either projected or not.
 *
 * <p>Both pointer fields are null on a read that did not project one (the single-row read, which has
 * no subquery); that is the same answer a conversation with no message gets, and neither reader
 * draws a preview for it. The list read projects them, and [ConversationRow.asSnapshot] is the only
 * caller that passes non-null values.
 */
internal fun ConversationEntity.asSnapshot(
    lastMessageId: String?,
    lastMessageAt: Long?,
): ConversationSnapshot =
    ConversationSnapshot(
        id = uuid,
        accountId = accountUuid,
        name = name,
        jid = contactJid,
        contactUuid = contactUuid,
        mode = mode,
        status = status,
        created = created,
        attributes = attributes,
        detectedLanguage = detectedLanguage,
        languageOverride = languageOverride,
        doubtHold = doubtHold,
        lastMessageId = lastMessageId,
        lastMessageAt = lastMessageAt,
    )

/** The list's joined row, mapped: the embedded entity plus the two pointer columns (S5-6). */
internal fun ConversationRow.asSnapshot(): ConversationSnapshot =
    row.asSnapshot(lastMessageId = lastMessageId, lastMessageAt = lastMessageAt)
