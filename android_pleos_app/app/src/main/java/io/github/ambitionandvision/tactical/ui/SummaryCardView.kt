package io.github.ambitionandvision.tactical.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ambitionandvision.tactical.R
import io.github.ambitionandvision.tactical.model.EntryPoint
import io.github.ambitionandvision.tactical.model.FuelKind
import io.github.ambitionandvision.tactical.model.TacticalFrame
import io.github.ambitionandvision.tactical.model.ZoneData
import java.util.Locale

/** Phase 1 요약 카드. 정적 표시만 하며 움직임이 없다. 높이 제한은 시설물 정적 배지이고 차량 데이터와 비교하지 않는다. */
class SummaryCardView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    private val destination = context.valueText(R.dimen.text_xl)
    private val entry = context.valueText(R.dimen.text_title)
    private val fuel = context.valueText()
    private val lane = context.valueText(colorRes = R.color.lane_value)
    private val notice = context.valueText()
    private val noticeSub = context.label(R.string.summary_notice_unverified)
    private val navi = context.valueText(colorRes = R.color.accent).apply { visibility = GONE }
    private val maxCardWidth = resources.getDimensionPixelSize(R.dimen.summary_card_max_width)
    private val badge = TextView(context).apply {
        setBackgroundResource(R.drawable.badge_bg)
        setTextColor(context.getColor(R.color.badge_text))
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)
        val h = context.dp(14f); val v = context.dp(8f)
        setPadding(h, v, h, v)
    }

    init {
        setBackgroundResource(R.drawable.panel_bg)
        val pad = resources.getDimensionPixelSize(R.dimen.panel_padding)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun row(titleRes: Int, v: TextView) {
            col.addView(context.label(titleRes), lp(top = context.dp(10f)))
            col.addView(v, lp())
        }
        col.addView(context.label(R.string.summary_title), lp())
        col.addView(destination, lp())
        col.addView(navi, lp(top = context.dp(6f)))
        row(R.string.summary_entry_label, entry)
        row(R.string.summary_fuel_label, fuel)
        row(R.string.summary_lane_label, lane)
        row(R.string.summary_notice_label, notice)
        col.addView(noticeSub, lp())
        col.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(12f); gravity = Gravity.START
        })
        addView(col, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** 넓은 창(landscape 1682dp 등) 에서도 읽기 좋은 폭으로 제한한다. 부모가 가운데 정렬한다. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && w > maxCardWidth) {
            MeasureSpec.makeMeasureSpec(maxCardWidth, MeasureSpec.EXACTLY)
        } else widthMeasureSpec
        super.onMeasure(spec, heightMeasureSpec)
    }

    /** NaviHelper 목적지 연동 한 줄. null 이면 숨긴다. */
    fun setNaviInfo(text: String?) {
        navi.text = text.orEmpty()
        navi.visibility = if (text == null) GONE else VISIBLE
    }

    private fun lp(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }

    /** [idleFuel]·[idleEntry] 는 첫 측위 전(frame == null) 표시용. 판별된 연료·활성 입구를 그대로 보여준다. */
    fun bind(zone: ZoneData, frame: TacticalFrame?, idleFuel: FuelKind? = null, idleEntry: EntryPoint? = null) {
        val kind = frame?.fuelKind ?: idleFuel ?: FuelKind.UNKNOWN
        val active = frame?.activeEntry
            ?: idleEntry
            ?: zone.entries.firstOrNull { kind in it.fuelKinds }
            ?: zone.entries.firstOrNull()
        destination.text = zone.displayName
        entry.text = active?.label.orEmpty()
        fuel.setText(
            when (kind) {
                FuelKind.EV -> R.string.fuel_ev
                FuelKind.PHEV -> R.string.fuel_phev
                FuelKind.ICE -> R.string.fuel_ice
                FuelKind.UNKNOWN -> R.string.fuel_unknown
            }
        )
        lane.text = active?.recommendedLane.orEmpty()
        notice.text = zone.staticNotices.barrierNote
        badge.text = context.getString(
            R.string.summary_height_badge,
            String.format(Locale.US, "%.1f", zone.staticNotices.heightLimitM)
        )
    }
}
