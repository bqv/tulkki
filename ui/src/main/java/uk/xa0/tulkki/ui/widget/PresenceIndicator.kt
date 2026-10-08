package uk.xa0.tulkki.ui.widget

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import com.google.android.material.color.MaterialColors
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Presence
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.ui.utils.UIHelper

class PresenceIndicator : View {
    private var paint: Paint = Paint().also {
        it.setColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimaryDark))
        it.style = Paint.Style.STROKE
        it.strokeWidth = 1 * Resources.getSystem().displayMetrics.density
    }

    private var status: Presence.Status? = null

    private var enabled = false

    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    constructor(
        context: Context?,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }

    fun setStatus(contact: Contact?) {
        val status = contact?.shownStatus?.takeIf {
            contact.account?.isOnlineAndConnected() == true
        }
        if (status != this.status) {
            this.status = status
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        enabled = (context as? XmppActivity)
            ?.xmppConnectionService?.getPreferences()
            ?.getBoolean("show_contact_status", context.resources.getBoolean(R.bool.show_contact_status)) ?: false
    }

    override fun onDraw(canvas: Canvas) {
        if (!enabled) {
            return
        }

        super.onDraw(canvas)

        val color: Int? = UIHelper.getColorForStatus(status);

        if (color != null) {
            canvas.drawColor(color)
            canvas.drawCircle(width / 2f, height / 2f, width / 2f, paint)
        }
    }
}