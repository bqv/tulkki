package uk.xa0.tulkki.data.model

import org.junit.Assert
import org.junit.Test

/**
 * The one value in the boundary that has no compile-time proof and no other test: this module's
 * `BuildConfig.APPLICATION_ID`.
 *
 * `Contact.phoneAccountHandle()` puts the applicationId into a [android.telecom.PhoneAccountHandle]
 * that Telecom persists, so a wrong value orphans every call account already registered on the
 * device while compiling cleanly and passing every other test. A library module gets no
 * AGP-generated `APPLICATION_ID` (`LIBRARY_PACKAGE_NAME` instead), so after the split the field is
 * declared by hand in the module's generated `build.gradle` from `rootProject.ext.appId`; this test
 * is what makes a wrong one fail here rather than on the phone (the 3.8-r renamed hand-work commit
 * 945ecf86b8, section 8).
 *
 * Both `Contact` constants are `static final String`s initialised from constant expressions, so
 * javac inlines them and the assertions never load `Contact` - which matters, because the class
 * touches the Android framework and this is a plain JVM test.
 */
class ApplicationIdTest {

    @Test
    fun theApplicationIdIsTheAppIdentity() {
        Assert.assertEquals("uk.xa0.tulkki", Contact.APPLICATION_ID)
    }

    @Test
    fun theTelecomConnectionServiceLivesUnderTheApplicationId() {
        Assert.assertTrue(Contact.CONNECTION_SERVICE.startsWith(Contact.APPLICATION_ID + "."))
    }
}
