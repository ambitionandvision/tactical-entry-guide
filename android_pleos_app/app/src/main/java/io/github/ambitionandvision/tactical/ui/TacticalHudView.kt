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
import io.github.ambitionandvision.tactical.model.TacticalFrame

/**
 * Phase 2 HUD. 3D 를 가리지 않도록 컴팩트한 패널이다. 추천 차선, 입구까지 거리, 차선 이탈 경고만 보여준다.
 * 위험요소(높이 제한·회차불가·대기차로)는 3D 지도 위의 핀·라벨과 요약 카드가 담당한다.
 * 경고는 정적 표시이며 깜빡임·애니메이션이 없다(주의분산 가이드, motionRestricted 와 무관하게 동일).
 */
class TacticalHudView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    private val lane = context.valueText(colorRes = R.color.lane_value)
    private val entryDist = context.valueText(R.dimen.text_hero, R.color.entry_dist_value)
    private val warning = TextView(context).apply {
        setText(R.string.hud_off_route)
        setBackgroundResource(R.drawable.warning_bg)
        setTextColor(context.getColor(R.color.warning_text))
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_title))
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)
        gravity = Gravity.CENTER
        minHeight = resources.getDimensionPixelSize(R.dimen.touch_target_min)
        visibility = View.GONE
    }

    init {
        setBackgroundResource(R.drawable.panel_bg)
        val pad = resources.getDimensionPixelSize(R.dimen.panel_padding)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        col.addView(warning, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = context.dp(10f) })

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val laneCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        laneCol.addView(context.label(R.string.hud_lane))
        laneCol.addView(lane)
        bottom.addView(laneCol, LinearLayout.LayoutParams(0, wrap, 1f))
        val distCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        distCol.addView(context.label(R.string.hud_entry_dist))
        distCol.addView(entryDist)
        bottom.addView(distCol, LinearLayout.LayoutParams(wrap, wrap))
        col.addView(bottom, LinearLayout.LayoutParams(match, wrap))
        addView(col, LayoutParams(match, wrap))
    }

    fun onFrame(frame: TacticalFrame) {
        lane.text = frame.activeEntry.recommendedLane
        // 유도선 투영이 있으면(이탈 경고 중 포함) 경로상 남은 거리, 없으면 직선거리.
        val toEntry = frame.pathRemainingM ?: frame.distanceToEntryM
        entryDist.text = context.getString(R.string.distance_m, toEntry.toInt().coerceAtLeast(0))
        val off = frame.snap?.offRouteWarning == true
        warning.visibility = if (off) View.VISIBLE else View.GONE
    }
}
