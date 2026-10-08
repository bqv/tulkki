package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: the broadcast receiver this service talks to.
 *
 * <p>The island builds {@code Intent}s *for* the receiver and reads one boolean extra out of the
 * intents it is started with, so the port carries the class and the two string constants rather
 * than the component: the {@code Context} and the action stay the island's.
 */
interface SystemEventPort {

    fun receiverClass(): Class<*>

    fun extraNeedsForegroundService(): String

    fun settingEnabledAccounts(): String
}
