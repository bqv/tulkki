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

package uk.xa0.tulkki.ui.utils

import android.content.Context
import android.os.Build
import android.preference.PreferenceManager
import android.util.Log
import com.google.android.material.color.MaterialColors
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.ui.host.UiHost

object ThemeHelper {

    @JvmStatic
    fun applyCustomColors(context: Context): HashMap<Int, Int> {
        val colors = HashMap<Int, Int>()
        if (Build.VERSION.SDK_INT < 30) return colors
        if (!UiHost.installed().customColorsDesired(context)) return colors

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val colorMatch = sharedPreferences.getBoolean("custom_theme_color_match", false)
        if (sharedPreferences.contains("custom_theme_primary")) {
            val base = sharedPreferences.getInt("custom_theme_primary", 0)
            val roles = MaterialColors.getColorRoles(base, true)
            colors[R.color.md_theme_light_primary] = if (colorMatch) base else roles.accent
            colors[R.color.md_theme_light_onPrimary] = roles.onAccent
            colors[R.color.md_theme_light_primaryContainer] =
                if (colorMatch) base else roles.accentContainer
            colors[R.color.md_theme_light_onPrimaryContainer] = roles.onAccentContainer
        }
        if (sharedPreferences.contains("custom_theme_primary_dark")) {
            val base = sharedPreferences.getInt("custom_theme_primary_dark", 0)
            val roles = MaterialColors.getColorRoles(base, true)
            colors[R.color.md_theme_light_secondary] = if (colorMatch) base else roles.accent
            colors[R.color.md_theme_light_onSecondary] = roles.onAccent
            colors[R.color.md_theme_light_secondaryContainer] =
                if (colorMatch) base else roles.accentContainer
            colors[R.color.md_theme_light_onSecondaryContainer] = roles.onAccentContainer
        }
        if (sharedPreferences.contains("custom_theme_accent")) {
            val base = sharedPreferences.getInt("custom_theme_accent", 0)
            val roles = MaterialColors.getColorRoles(base, true)
            colors[R.color.md_theme_light_tertiary] = if (colorMatch) base else roles.accent
            colors[R.color.md_theme_light_onTertiary] = roles.onAccent
            colors[R.color.md_theme_light_tertiaryContainer] =
                if (colorMatch) base else roles.accentContainer
            colors[R.color.md_theme_light_onTertiaryContainer] = roles.onAccentContainer
        }
        if (sharedPreferences.contains("custom_theme_background_primary")) {
            val backgroundPrimary = sharedPreferences.getInt("custom_theme_background_primary", 0)
            val alpha = (backgroundPrimary shr 24) and 0xFF
            val red = (backgroundPrimary shr 16) and 0xFF
            val green = (backgroundPrimary shr 8) and 0xFF
            val blue = backgroundPrimary and 0xFF
            colors[R.color.md_theme_light_background] = backgroundPrimary
            colors[R.color.md_theme_light_surface] = backgroundPrimary
            //colors.put(R.color.md_theme_light_surface, (int)((alpha << 24) | ((int)(red*.9) << 16) | ((int)(green*.9) << 8) | (int)(blue*.9)));
            colors[R.color.md_theme_light_surfaceVariant] =
                (alpha shl 24) or ((red * .85).toInt() shl 16) or
                    ((green * .85).toInt() shl 8) or (blue * .85).toInt()
        }
        if (sharedPreferences.contains("custom_dark_theme_primary")) {
            val base = sharedPreferences.getInt("custom_dark_theme_primary", 0)
            val roles = MaterialColors.getColorRoles(base, false)
            colors[R.color.md_theme_dark_primary] = if (colorMatch) base else roles.accent
            colors[R.color.md_theme_dark_onPrimary] = roles.onAccent
            colors[R.color.md_theme_dark_primaryContainer] =
                if (colorMatch) base else roles.accentContainer
            colors[R.color.md_theme_dark_onPrimaryContainer] =
                if (colorMatch && MaterialColors.isColorLight(base)) {
                    roles.onAccent
                } else {
                    roles.onAccentContainer
                }
        }
        if (sharedPreferences.contains("custom_dark_theme_primary_dark")) {
            val base = sharedPreferences.getInt("custom_dark_theme_primary_dark", 0)
            val roles = MaterialColors.getColorRoles(base, false)
            colors[R.color.md_theme_dark_secondary] = if (colorMatch) base else roles.accent
            colors[R.color.md_theme_dark_onSecondary] = roles.onAccent
            colors[R.color.md_theme_dark_secondaryContainer] =
                if (colorMatch) base else roles.accentContainer
            colors[R.color.md_theme_dark_onSecondaryContainer] =
                if (colorMatch && MaterialColors.isColorLight(base)) {
                    roles.onAccent
                } else {
                    roles.onAccentContainer
                }
        }
        if (sharedPreferences.contains("custom_dark_theme_accent")) {
            val base = sharedPreferences.getInt("custom_dark_theme_accent", 0)
            val roles = MaterialColors.getColorRoles(base, false)
            colors[R.color.md_theme_dark_tertiary] = if (colorMatch) base else roles.accent
            colors[R.color.md_theme_dark_onTertiary] = roles.onAccent
            colors[R.color.md_theme_dark_tertiaryContainer] =
                if (colorMatch) base else roles.accentContainer
            colors[R.color.md_theme_dark_onTertiaryContainer] =
                if (colorMatch && MaterialColors.isColorLight(base)) {
                    roles.onAccent
                } else {
                    roles.onAccentContainer
                }
        }
        if (sharedPreferences.contains("custom_dark_theme_background_primary")) {
            val backgroundPrimary =
                sharedPreferences.getInt("custom_dark_theme_background_primary", 0)
            val alpha = (backgroundPrimary shr 24) and 0xFF
            val red = (backgroundPrimary shr 16) and 0xFF
            val green = (backgroundPrimary shr 8) and 0xFF
            val blue = backgroundPrimary and 0xFF
            colors[R.color.md_theme_dark_background] = backgroundPrimary
            colors[R.color.md_theme_dark_surface] = backgroundPrimary
            colors[R.color.md_theme_dark_surfaceVariant] =
                (alpha shl 24) or ((40 + red * .84).toInt() shl 16) or
                    ((40 + green * .84).toInt() shl 8) or (40 + blue * .84).toInt()
        }
        if (colors.isEmpty()) return colors

        val loader = ColorResourcesLoaderCreator.create(context, colors)
        try {
            if (loader != null) context.resources.addLoaders(loader)
        } catch (e: IllegalArgumentException) {
            Log.w(Config.LOGTAG, "Custom colour failed: " + e)
        }
        return colors
    }
}
