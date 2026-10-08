package uk.xa0.tulkki.ui.preferences

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast

import androidx.annotation.ArrayRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager

import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.ui.activity.SettingsActivity
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.utils.TimeFrameUtils
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * A settings screen: the <em>host</em>. It reads `SharedPreferences` and writes them, and
 * [PreferenceListView] draws the rows - the Compose replacement for the `PreferenceFragmentCompat`
 * that inflated one of the `res/xml/preferences_*.xml` screens.
 *
 * <p>**Where the old tree's behaviour now lives.** `setPreferencesFromResource` did four things the
 * rows depended on: it applied each row's `android:defaultValue`, it wrote a list's or a text's value
 * on bind, it registered an `OnSharedPreferenceChangeListener` for the fragment's resumed life, and it
 * turned a change into the subclass's `onSharedPreferenceChanged`. Those four are here, once:
 *
 *  * [buildItems] is the screen's own tree, as values; it is re-read on every [render] rather than
 *    built once, so a value written elsewhere is on screen when the screen comes back.
 *  * [applyDefaults] is the bind-time write, done once per view creation and only for a key that is
 *    absent - the same write `ListPreference`/`EditTextPreference`/`ColorPreference` made when their
 *    rows were attached.
 *  * the listener is registered in `onResume` and unregistered in `onPause`, exactly as
 *    [onSharedPreferenceChanged] was reached before.
 *  * a write made by a row goes through [applyValue], which asks [onPreferenceChange] first - the old
 *    `setOnPreferenceChangeListener`'s veto - so a listener that refused a value still refuses it and
 *    the row is redrawn with the value in force.
 *
 * <p>A row's own tap is [onPreferenceClick]; the three value rows ([PreferenceItem.Kind.LIST],
 * [PreferenceItem.Kind.TEXT], [PreferenceItem.Kind.COLOUR]) open a dialog in the screen instead, held
 * as the open row across a re-render so a write behind an open dialog cannot close it.
 *
 * <p>The screens stay fragments on purpose: `TranslationFailuresFragment` is one and is not this
 * lane's, and [uk.xa0.tulkki.ui.TulkkiSettingsFragment] is addressed by class name from
 * `SettingsActivity`, so the container cannot become a Composable.
 */
abstract class PreferenceScreenFragment : Fragment() {

    private var compose: ComposeView? = null

    /** The row whose editor is open, or null. */
    private var dialog: PreferenceItem? = null

    /** The rows in force, kept so a callback can find the row a key names. */
    private var items: List<PreferenceItem> = emptyList()

    /** Whether this view has had its bind-time defaults applied; reset with the view. */
    private var defaultsApplied = false

    private lateinit var appContext: Context

    private val sharedPreferenceChangeListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key != null && isAdded) {
                    onSharedPreferenceChanged(key)
                    render()
                }
            }

    /** The same storage the old `PreferenceFragmentCompat` was bound to. */
    protected val preferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(appContext)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = requireContext().applicationContext
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?): View {
        val view = ComposeView(requireContext())
        // Dispose with the fragment's view, not with the window: the settings hierarchy detaches and
        // re-attaches this screen, and the default strategy would drop the composition with it.
        compose = view
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        render()
    }

    override fun onStart() {
        super.onStart()
        render()
    }

    override fun onResume() {
        super.onResume()
        preferences.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
        val xmppActivity = activity as? XmppActivity
        if (xmppActivity?.xmppConnectionService != null) {
            onBackendConnected()
        }
        render()
    }

    override fun onPause() {
        preferences.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
        super.onPause()
    }

    override fun onDestroyView() {
        compose = null
        dialog = null
        items = emptyList()
        defaultsApplied = false
        super.onDestroyView()
    }

    // ---------------------------------------------------------------------------------------------
    // What a screen implements
    // ---------------------------------------------------------------------------------------------

    /**
     * The screen's rows, in the order it draws them - the `PreferenceScreen` the XML resource
     * described, as values. Read fresh on every render, so a value in force is this screen's reading
     * and not a copy.
     */
    protected abstract fun buildItems(): List<PreferenceItem>

    /** A row that is not a value: a door, a picker or a plain action. */
    protected open fun onPreferenceClick(item: PreferenceItem) {}

    /**
     * A value a row is about to write. Returns false to refuse it, exactly as a
     * `setOnPreferenceChangeListener` that returned false did; the screen is redrawn with the value
     * still in force.
     */
    protected open fun onPreferenceChange(item: PreferenceItem, newValue: Any?): Boolean = true

    /** The old `OnSharedPreferenceChangeListener` callback: one key, one side effect. */
    protected open fun onSharedPreferenceChanged(key: String) {
        Log.d(Config.LOGTAG, "onSharedPreferenceChanged($key)")
    }

    /** A screen calls this too; `SettingsActivity` calls it when the backend connects. */
    open fun onBackendConnected() {}

    // ---------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------

    /** Redraw the screen from a fresh reading of [buildItems]. */
    protected fun render() {
        val view = compose ?: return
        if (!isAdded) {
            return
        }
        val list = buildItems()
        items = list
        if (!defaultsApplied) {
            applyDefaults(list)
            defaultsApplied = true
        }
        view.setTulkkiContent(darkTheme = isDark()) {
            PreferenceListView(
                items = list,
                dialog = dialog,
                onToggle = { key, value -> applyValue(key, value) },
                onClick = { item -> open(item) },
                onCopy = { item -> copy(item) },
                onValue = { key, value ->
                    applyValue(key, value)
                    dismissDialog()
                },
                onColour = { key, value ->
                    applyValue(key, value)
                    dismissDialog()
                },
                onDismiss = { dismissDialog() },
            )
        }
    }

    /** A tap that is not a value's: a dialog for the three value rows, the screen's own for the rest. */
    private fun open(item: PreferenceItem) {
        when (item.kind) {
            PreferenceItem.Kind.LIST,
            PreferenceItem.Kind.TEXT,
            PreferenceItem.Kind.COLOUR -> {
                dialog = item
                render()
            }
            else -> onPreferenceClick(item)
        }
    }

    private fun dismissDialog() {
        dialog = null
        render()
    }

    /**
     * The one long press the old rows had: `android:copyingEnabled` put the row's summary on the
     * clipboard and said so, and it is the about row's own way of telling the owner what build they
     * are on - so it survives, with the library's own confirmation sentence.
     */
    private fun copy(item: PreferenceItem) {
        val summary = item.summary ?: return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(item.title ?: "", summary.toString()))
        Toast.makeText(
                requireContext(),
                requireContext().getString(R.string.preference_copied, summary.toString()),
                Toast.LENGTH_SHORT,
        )
                .show()
    }

    /**
     * Open another settings screen the way the XML's `app:fragment` did: replace this fragment in its
     * own container and put the transaction on the back stack. The container is this fragment's own
     * parent, not a named id, so a screen still navigates correctly while the Activity's shell is the
     * one thing that changes under it.
     */
    protected fun openScreen(screen: Fragment) {
        val containerId =
                (view?.parent as? ViewGroup)?.id ?: R.id.settings_fragment_container
        parentFragmentManager
                .beginTransaction()
                .replace(containerId, screen)
                .addToBackStack(null)
                .commit()
    }

    private fun isDark(): Boolean {
        val xmppActivity = activity as? XmppActivity
        if (xmppActivity != null) {
            return xmppActivity.isDark()
        }
        val nightModeFlags =
                requireContext().resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    // ---------------------------------------------------------------------------------------------
    // Reading and writing
    // ---------------------------------------------------------------------------------------------

    /**
     * Write one value for one key and let the registered listener do the rest: the side effect and the
     * redraw are the same path a change made anywhere else takes, so there is no second place a key's
     * meaning could be spelled.
     */
    private fun applyValue(key: String, value: Any?) {
        val item = items.firstOrNull { it.key == key } ?: return
        if (!onPreferenceChange(item, value)) {
            render()
            return
        }
        when (value) {
            is Boolean -> preferences.edit().putBoolean(key, value).apply()
            is Int -> preferences.edit().putInt(key, value).apply()
            is String -> preferences.edit().putString(key, value).apply()
            else -> preferences.edit().remove(key).apply()
        }
    }

    /** Write a value the screen decides itself, outside the rows - a reset, or a preference's own write. */
    protected fun putBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    protected fun putString(key: String, value: String?) {
        preferences.edit().putString(key, value).apply()
    }

    protected fun putInt(key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
    }

    /**
     * The bind-time default: a value an absent key gets from its row, written once per view creation
     * (never a write for a key that is present) and without a listener's side effect - the old tree
     * applied its defaults before the fragment's listener was registered.
     *
     * <p>A [PreferenceItem.Kind.SWITCH] is deliberately not here: a `SwitchPreferenceCompat` read its
     * default but only ever persisted a change, so writing `false` where the key was absent would be a
     * write the old screen never made.
     */
    private fun applyDefaults(list: List<PreferenceItem>) {
        val editor = preferences.edit()
        var changed = false
        for (item in list) {
            val key = item.key ?: continue
            if (preferences.contains(key)) {
                continue
            }
            when (item.kind) {
                PreferenceItem.Kind.LIST,
                PreferenceItem.Kind.TEXT -> {
                    val value = item.defaultValue ?: continue
                    editor.putString(key, value)
                    changed = true
                }
                PreferenceItem.Kind.COLOUR -> {
                    if (item.defaultColour == 0) {
                        continue
                    }
                    editor.putInt(key, item.defaultColour)
                    changed = true
                }
                else -> Unit
            }
        }
        if (changed) {
            editor.apply()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The helpers every screen used
    // ---------------------------------------------------------------------------------------------

    /**
     * A `SwitchPreferenceCompat`: its key, its two strings, its icon and the value it reads when the
     * key is absent. The default is read and never written - a switch only ever persisted a change.
     */
    protected fun switchItem(
            key: String,
            @StringRes title: Int,
            @StringRes summary: Int?,
            @DrawableRes icon: Int?,
            default: Boolean,
            enabled: Boolean = true
    ): PreferenceItem =
            PreferenceItem(
                    key = key,
                    title = getString(title),
                    summary = summary?.let { getString(it) },
                    icon = icon,
                    kind = PreferenceItem.Kind.SWITCH,
                    checked = preferences.getBoolean(key, default),
                    enabled = enabled,
            )

    /** The selected entry's own text, or null when no entry matches - `ListPreference.getEntry()`. */
    protected fun listSummary(
            labels: List<CharSequence>,
            values: List<String>,
            value: String?
    ): CharSequence? = labels.getOrNull(values.indexOf(value))

    /** One list row from two string-array resources, the way `android:entries`/`entryValues` did. */
    protected fun listFromArrays(
            @ArrayRes entriesRes: Int,
            @ArrayRes valuesRes: Int
    ): Pair<List<CharSequence>, List<String>> =
            resources.getStringArray(entriesRes).toList() to
                    resources.getStringArray(valuesRes).toList()

    /**
     * The old `setValues`: a list whose entries come from an int-array resource, each value named by
     * the screen's own function.
     */
    protected fun listFromIntArray(
            @ArrayRes resId: Int,
            valueToName: (Int) -> String
    ): Pair<List<CharSequence>, List<String>> {
        val choices = resources.getIntArray(resId)
        val labels = ArrayList<CharSequence>(choices.size)
        val values = ArrayList<String>(choices.size)
        for (choice in choices) {
            labels.add(valueToName(choice))
            values.add(choice.toString())
        }
        return labels to values
    }

    /** A resolved string resource, for a row's own field. */
    protected fun text(@StringRes resId: Int): CharSequence = getString(resId)

    protected fun reconnectAccounts() {
        val service = requireService()
        for (account in AccountRegistry.get().getAccounts()) {
            if (account.isEnabled()) {
                service.reconnectAccountInBackground(account)
            }
        }
    }

    protected fun requireXmppActivity(): XmppActivity {
        val activity = requireActivity()
        if (activity is XmppActivity) {
            return activity
        }
        throw IllegalStateException()
    }

    protected fun requireSettingsActivity(): SettingsActivity {
        val activity = requireActivity()
        if (activity is SettingsActivity) {
            return activity
        }
        throw IllegalStateException(
                String.format(
                        "%s is not %s",
                        activity.javaClass.name, SettingsActivity::class.java.name))
    }

    protected fun requireService(): XmppConnectionService {
        val xmppActivity = requireXmppActivity()
        val service = xmppActivity.xmppConnectionService
        if (service != null) {
            return service
        }
        throw IllegalStateException()
    }

    protected fun runOnUiThread(runnable: Runnable) {
        requireActivity().runOnUiThread(runnable)
    }

    companion object {
        @JvmStatic
        protected fun timeframeValueToName(context: Context, value: Int): String {
            return if (value == 0) {
                context.getString(R.string.never)
            } else {
                TimeFrameUtils.resolve(context, 1000L * value)
            }
        }
    }
}
