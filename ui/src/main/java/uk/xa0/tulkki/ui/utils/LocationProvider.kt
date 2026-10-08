package uk.xa0.tulkki.ui.utils

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.osmdroid.util.GeoPoint
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.Locale
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.ui.R

object LocationProvider {

    @JvmField
    val FALLBACK = GeoPoint(0.0, 0.0)

    @JvmStatic
    fun getUserCountry(context: Context): String {
        return getUserCountry(context, false)
    }

    @JvmStatic
    fun getUserCountry(context: Context, preferNetwork: Boolean): String {
        try {
            val tm = ContextCompat.getSystemService(context, TelephonyManager::class.java)
            if (tm == null) {
                return getUserCountryFallback()
            }
            Log.d(Config.LOGTAG, "SIM Operator: " + tm.simOperator)
            val simCountry = if ("20801" == tm.simOperator || "26001" == tm.simOperator) "us" else tm.simCountryIso
            // if device is not 3G would be unreliable
            val networkCountry = if (tm.phoneType == TelephonyManager.PHONE_TYPE_CDMA) null else tm.networkCountryIso
            if (preferNetwork && networkCountry != null && networkCountry.length == 2) {
                return networkCountry.uppercase(Locale.US)
            }

            if (simCountry != null && simCountry.length == 2) { // SIM country code is available
                return simCountry.uppercase(Locale.US)
            }

            return getUserCountryFallback()
        } catch (e: Exception) {
            return getUserCountryFallback()
        }
    }

    private fun getUserCountryFallback(): String {
        val locale = Locale.getDefault()
        return locale.country
    }

    @JvmStatic
    fun getGeoPoint(context: Context): GeoPoint {
        return getGeoPoint(context, getUserCountry(context))
    }

    @JvmStatic
    @Synchronized
    fun getGeoPoint(context: Context, country: String): GeoPoint {
        try {
            BufferedReader(
                InputStreamReader(context.resources.openRawResource(R.raw.countries))
            ).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val parts = Regex("\\s+").split(line, 4)
                    if (parts.size == 4) {
                        if (country.equals(parts[0], ignoreCase = true)) {
                            try {
                                return GeoPoint(parts[1].toDouble(), parts[2].toDouble())
                            } catch (e: NumberFormatException) {
                                return FALLBACK
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.d(Config.LOGTAG, "unable to parse country->geo map", e)
        }
        return FALLBACK
    }
}
