package uk.xa0.tulkki.ui

import android.content.Context
import android.content.res.TypedArray
import android.preference.DialogPreference
import android.preference.Preference
import android.util.AttributeSet
import android.view.View
import android.widget.TimePicker

import java.text.DateFormat
import java.util.Calendar

class TimePreference(context: Context, attrs: AttributeSet) :
        DialogPreference(context, attrs, 0), Preference.OnPreferenceChangeListener {

    private var picker: TimePicker? = null

    init {
        setOnPreferenceChangeListener(this)
    }

    protected fun setTime(time: Long) {
        persistLong(time)
        notifyDependencyChange(shouldDisableDependents())
        notifyChanged()
        updateSummary(time)
    }

    private fun updateSummary(time: Long) {
        val dateFormat: DateFormat = android.text.format.DateFormat.getTimeFormat(context)
        val date = minutesToCalender(time).time
        setSummary(dateFormat.format(date.time))
    }

    override fun onCreateDialogView(): View {
        val created = TimePicker(context)
        created.setIs24HourView(android.text.format.DateFormat.is24HourFormat(context))
        picker = created
        return created
    }

    override fun onBindDialogView(v: View) {
        super.onBindDialogView(v)
        // The Java dereferenced the picker onCreateDialogView had just set; the same failure, named.
        val timePicker = picker ?: throw NullPointerException("no time picker")
        val time = getPersistedLong(DEFAULT_VALUE)

        timePicker.setCurrentHour(((time % (24 * 60)) / 60).toInt())
        timePicker.setCurrentMinute(((time % (24 * 60)) % 60).toInt())
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        super.onDialogClosed(positiveResult)

        if (positiveResult) {
            val timePicker = picker ?: throw NullPointerException("no time picker")
            setTime((timePicker.currentHour * 60 + timePicker.currentMinute).toLong())
        }
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any? {
        return a.getInteger(index, 0)
    }

    override fun onSetInitialValue(restorePersistedValue: Boolean, defaultValue: Any?) {
        val time: Long =
                if (defaultValue is Long) {
                    if (restorePersistedValue) getPersistedLong(defaultValue) else defaultValue
                } else {
                    if (restorePersistedValue) getPersistedLong(DEFAULT_VALUE) else DEFAULT_VALUE
                }

        setTime(time)
        updateSummary(time)
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        (preference as TimePreference).updateSummary(newValue as Long)
        return true
    }

    companion object {
        const val DEFAULT_VALUE = 0L

        @JvmStatic
        fun minutesToCalender(time: Long): Calendar {
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, ((time % (24 * 60)) / 60).toInt())
            c.set(Calendar.MINUTE, ((time % (24 * 60)) % 60).toInt())
            return c
        }

        @JvmStatic
        fun minutesToTimestamp(time: Long): Long {
            return minutesToCalender(time).timeInMillis
        }
    }
}
