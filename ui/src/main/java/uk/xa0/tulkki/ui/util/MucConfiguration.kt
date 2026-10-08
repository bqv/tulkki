package uk.xa0.tulkki.ui.util

import android.content.Context
import android.os.Bundle
import androidx.annotation.StringRes
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.ui.R

class MucConfiguration private constructor(
    @JvmField @StringRes val title: Int,
    @JvmField val names: Array<String>,
    @JvmField val values: BooleanArray,
    @JvmField val options: Array<Option>,
) {

    fun toBundle(values: BooleanArray): Bundle {
        val bundle = Bundle()
        for (i in values.indices) {
            val option = options[i]
            bundle.putString(option.name, option.values[if (values[i]) 0 else 1])
        }
        return bundle
    }

    class Option {
        @JvmField
        val name: String

        @JvmField
        val values: Array<String>

        private constructor(name: String) {
            this.name = name
            this.values = arrayOf("1", "0")
        }

        private constructor(name: String, on: String, off: String) {
            this.name = name
            this.values = arrayOf(on, off)
        }

        companion object {
            fun of(name: String) = Option(name)

            fun of(name: String, on: String, off: String) = Option(name, on, off)
        }
    }

    companion object {
        @JvmStatic
        fun get(context: Context, advanced: Boolean, mucOptions: MucOptions): MucConfiguration {
            if (mucOptions.isPrivateAndNonAnonymous()) {
                val names = arrayOf(
                    context.getString(R.string.allow_participants_to_edit_subject),
                    context.getString(R.string.allow_participants_to_invite_others),
                )
                val values = booleanArrayOf(
                    mucOptions.participantsCanChangeSubject(),
                    mucOptions.allowInvites(),
                )
                val options = arrayOf(
                    Option.of("muc#roomconfig_changesubject"),
                    Option.of("muc#roomconfig_allowinvites"),
                )
                return MucConfiguration(R.string.conference_options, names, values, options)
            } else {
                val names: Array<String>
                val values: BooleanArray
                val options: Array<Option>
                if (advanced) {
                    names = arrayOf(
                        context.getString(R.string.non_anonymous),
                        context.getString(R.string.allow_participants_to_edit_subject),
                        context.getString(R.string.moderated),
                    )
                    values = booleanArrayOf(
                        mucOptions.nonanonymous(),
                        mucOptions.participantsCanChangeSubject(),
                        mucOptions.moderated(),
                    )
                    options = arrayOf(
                        Option.of("muc#roomconfig_whois", "anyone", "moderators"),
                        Option.of("muc#roomconfig_changesubject"),
                        Option.of("muc#roomconfig_moderatedroom"),
                    )
                } else {
                    names = arrayOf(
                        context.getString(R.string.non_anonymous),
                        context.getString(R.string.allow_participants_to_edit_subject),
                    )
                    values = booleanArrayOf(
                        mucOptions.nonanonymous(),
                        mucOptions.participantsCanChangeSubject(),
                    )
                    options = arrayOf(
                        Option.of("muc#roomconfig_whois", "anyone", "moderators"),
                        Option.of("muc#roomconfig_changesubject"),
                    )
                }
                return MucConfiguration(R.string.channel_options, names, values, options)
            }
        }

        @JvmStatic
        fun describe(context: Context, mucOptions: MucOptions): String {
            val builder = StringBuilder()
            if (mucOptions.isPrivateAndNonAnonymous()) {
                if (mucOptions.participantsCanChangeSubject()) {
                    builder.append(context.getString(R.string.anyone_can_edit_subject))
                } else {
                    builder.append(context.getString(R.string.owners_can_edit_subject))
                }
                builder.append(' ')
                if (mucOptions.allowInvites()) {
                    builder.append(context.getString(R.string.anyone_can_invite_others))
                } else {
                    builder.append(context.getString(R.string.owners_can_invite_others))
                }
            } else {
                if (mucOptions.nonanonymous()) {
                    builder.append(context.getString(R.string.jabber_ids_are_visible_to_anyone))
                } else {
                    builder.append(context.getString(R.string.jabber_ids_are_visible_to_admins))
                }
                builder.append(' ')
                if (mucOptions.participantsCanChangeSubject()) {
                    builder.append(context.getString(R.string.anyone_can_edit_subject))
                } else {
                    builder.append(context.getString(R.string.admins_can_edit_subject))
                }
            }
            return builder.toString()
        }
    }
}
