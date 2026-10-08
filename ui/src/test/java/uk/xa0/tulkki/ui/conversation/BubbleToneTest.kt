package uk.xa0.tulkki.ui.conversation

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.ui.projection.Direction
import uk.xa0.tulkki.ui.projection.EncryptionFailure
import uk.xa0.tulkki.ui.projection.UiEncryption

/**
 * `MessageAdapter`'s own bubble-colour branch, pinned case by case.
 *
 * <p>The tree's rule had three inputs and this rule has three: the direction, whether the owner asked
 * for colour, and whether the body's crypto could be read. The cell that matters most is the
 * warning family, because it is the one the projection can decide - a live session flag became
 * [UiEncryption.Failed] - and a row that lost its ciphertext must not be drawn as an ordinary
 * incoming message.
 */
class BubbleToneTest {

    @Test
    fun anIncomingBodyTakesTheSecondaryFamilyWhenColourWasAskedFor() {
        Assert.assertEquals(
            BubbleTone.SECONDARY,
            BubbleTone.of(Direction.INCOMING, UiEncryption.None, colorful = true),
        )
        Assert.assertEquals(
            "and a decrypted body is an ordinary incoming one",
            BubbleTone.SECONDARY,
            BubbleTone.of(Direction.INCOMING, UiEncryption.Decrypted, colorful = true),
        )
    }

    @Test
    fun anIncomingBodyFallsBackToThePlainSurface() {
        Assert.assertEquals(BubbleTone.SURFACE, BubbleTone.of(Direction.INCOMING, UiEncryption.None, colorful = false))
    }

    @Test
    fun anOwnBodyTakesTertiaryOrThePlainHighSurface() {
        Assert.assertEquals(BubbleTone.TERTIARY, BubbleTone.of(Direction.OUTGOING, UiEncryption.None, colorful = true))
        Assert.assertEquals(
            BubbleTone.SURFACE_HIGH,
            BubbleTone.of(Direction.OUTGOING, UiEncryption.None, colorful = false),
        )
    }

    @Test
    fun aBodyWhoseCryptoFailedTakesTheWarningFamily() {
        Assert.assertEquals(
            BubbleTone.WARNING,
            BubbleTone.of(Direction.INCOMING, UiEncryption.Failed(EncryptionFailure.OMEMO), colorful = true),
        )
        Assert.assertEquals(
            "and the colour does not depend on whether the owner asked for colour",
            BubbleTone.WARNING,
            BubbleTone.of(Direction.INCOMING, UiEncryption.Failed(EncryptionFailure.PGP), colorful = false),
        )
    }

    @Test
    fun aPendingDecryptionIsNotAFailure() {
        Assert.assertEquals(
            "waiting for the decryption service is not a lost body",
            BubbleTone.SECONDARY,
            BubbleTone.of(Direction.INCOMING, UiEncryption.Pending, colorful = true),
        )
    }

    @Test
    fun theWarningFamilyIsTheSameOnBothSides() {
        Assert.assertEquals(
            "the tree asked the session, not the direction",
            BubbleTone.WARNING,
            BubbleTone.of(Direction.OUTGOING, UiEncryption.Failed(EncryptionFailure.OMEMO), colorful = true),
        )
    }
}
