package io.github.ambitionandvision.tactical.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ambitionandvision.tactical.R
import io.github.ambitionandvision.tactical.model.EntryPoint

/** Phase 3 도착 오버레이. 정적 표시(페이드 등 애니메이션 없음). */
class ArrivedOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    private val entryName = context.valueText(R.dimen.text_title, R.color.text_primary)
    private val message = TextView(context).apply {
        setText(R.string.arrived_message)
        setTextColor(context.getColor(R.color.arrived_green))
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_hero))
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)
        gravity = Gravity.CENTER
    }

    init {
        setBackgroundColor(context.getColor(R.color.overlay_scrim))
        isClickable = true // 뒤쪽 뷰로 터치가 새지 않게 막는다
        val pad = resources.getDimensionPixelSize(R.dimen.panel_padding) * 2
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundResource(R.drawable.panel_bg)
        }
        entryName.gravity = Gravity.CENTER
        col.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(entryName, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(16f) })
        addView(col, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            val m = context.dp(32f); setMargins(m, m, m, m)
        })
    }

    fun show(entry: EntryPoint) {
        message.setText(if (entry.id == EV_ENTRY_ID) R.string.arrived_message_ev else R.string.arrived_message)
        entryName.text = entry.label
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
    }

    private companion object {
        const val EV_ENTRY_ID = "EV_CHARGING_ENTRY"
    }
}
