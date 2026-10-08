/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package uk.xa0.tulkki.crypto

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager

import com.google.common.base.Strings

import uk.xa0.tulkki.xmpp.Config

class OmemoSetting {

    companion object {
        /** The settings key, owned here so the island needs no uk.xa0.tulkki.data name; AppSettings reads it. */
        const val OMEMO = "omemo"

        private var always = false
        private var encryption = OmemoMessage.ENCRYPTION_AXOLOTL

        @JvmStatic
        fun isAlways(): Boolean = always

        @JvmStatic
        fun getEncryption(): Int = encryption

        @JvmStatic
        fun load(context: Context) {
            if (Config.omemoOnly()) {
                always = true
                encryption = OmemoMessage.ENCRYPTION_AXOLOTL
                return
            }
            val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            val value = preferences.getString(OMEMO, context.getString(R.string.omemo_setting_default))
            when (Strings.nullToEmpty(value)) {
                "always" -> {
                    always = true
                    encryption = OmemoMessage.ENCRYPTION_AXOLOTL
                }
                "default_off" -> {
                    always = false
                    encryption = OmemoMessage.ENCRYPTION_NONE
                }
                else -> {
                    always = false
                    encryption = OmemoMessage.ENCRYPTION_AXOLOTL
                }
            }
        }
    }
}
