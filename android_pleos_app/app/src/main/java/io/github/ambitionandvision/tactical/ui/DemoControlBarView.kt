package io.github.ambitionandvision.tactical.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import io.github.ambitionandvision.tactical.R
import io.github.ambitionandvision.tactical.model.FuelKind

/** 데모 제어 막대. GPX 라벨은 setGpxLabel 값을 그대로 표시한다(문구 하드코딩 없음). */
class DemoControlBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    interface Listener {
        fun onPlayPause()
        fun onToggle2x()
        fun onDistanceChanged(distanceM: Float)
        fun onFuelKindChanged(kind: FuelKind)
        fun onLaneDepartureTest()
    }

    var listener: Listener? = null

    private val maxDistM = 500
    private var playing = false
    private var twoX = false
    private var fuelKind = FuelKind.UNKNOWN
    private var updatingFromCode = false

    private val gpxLabel = TextView(context).apply {
        setTextColor(context.getColor(R.color.text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_label))
        setText(R.string.demo_gpx_none)
    }
    private val distLabel = TextView(context).apply {
        setTextColor(context.getColor(R.color.text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_demo))
    }
    private val playBtn = button(R.string.demo_play) { listener?.onPlayPause() }
    private val twoXBtn = button(R.string.demo_2x_off) { listener?.onToggle2x() }
    private val evBtn = button(R.string.demo_ev) { listener?.onFuelKindChanged(FuelKind.EV) }
    private val iceBtn = button(R.string.demo_ice) { listener?.onFuelKindChanged(FuelKind.ICE) }
    // 시연용 버튼이라 화면에는 보이지 않는다(5차). 이탈 경고 시연은 실행 인자로 한다. Listener 계약은 그대로다.
    private val laneBtn = button(R.string.demo_lane_test) { listener?.onLaneDepartureTest() }
    private val replayRow = row(playBtn, twoXBtn)
    private val slider = SeekBar(context).apply {
        max = maxDistM
        contentDescription = context.getString(R.string.cd_distance_slider)
        minimumHeight = resources.getDimensionPixelSize(R.dimen.touch_target_min)
        progress = 0
        val accent = ColorStateList.valueOf(context.getColor(R.color.accent))
        thumbTintList = accent
        progressTintList = accent
        progressBackgroundTintList = ColorStateList.valueOf(context.getColor(R.color.slider_track))
    }

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.panel_bg)
        val pad = resources.getDimensionPixelSize(R.dimen.panel_padding)
        setPadding(pad, pad, pad, pad)

        addView(gpxLabel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(replayRow, rowLp())
        addView(row(evBtn, iceBtn), rowLp())
        addView(row(laneBtn).apply { visibility = GONE }, rowLp())
        addView(distLabel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8f) })
        addView(slider, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.touch_target_min)))

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                val d = (maxDistM - progress).toFloat()
                updateDistLabel(d)
                if (fromUser && !updatingFromCode) listener?.onDistanceChanged(d)
            }
            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })
        setDistance(maxDistM.toFloat())
        refresh()
    }

    private fun button(textRes: Int, onClick: () -> Unit) = Button(context).apply {
        setText(textRes)
        isAllCaps = false
        setTextColor(context.getColorStateList(R.color.button_text))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_demo))
        setBackgroundResource(R.drawable.button_bg)
        stateListAnimator = null // 기본 버튼의 눌림 그림자 애니메이션을 쓰지 않는다
        minHeight = resources.getDimensionPixelSize(R.dimen.touch_target_min)
        minimumHeight = minHeight
        maxLines = 2
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: Button) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (v in views) addView(v, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = context.dp(4f); marginStart = context.dp(4f)
        })
    }

    private fun rowLp() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8f) }

    private fun updateDistLabel(d: Float) {
        distLabel.text = context.getString(R.string.demo_distance, d.toInt())
    }

    private fun refresh() {
        playBtn.setText(if (playing) R.string.demo_pause else R.string.demo_play)
        playBtn.isSelected = playing
        twoXBtn.setText(if (twoX) R.string.demo_2x_on else R.string.demo_2x_off)
        twoXBtn.isSelected = twoX
        evBtn.isSelected = fuelKind == FuelKind.EV || fuelKind == FuelKind.PHEV
        iceBtn.isSelected = fuelKind == FuelKind.ICE || fuelKind == FuelKind.UNKNOWN
    }

    fun setPlaying(playing: Boolean) { this.playing = playing; refresh() }

    fun set2x(enabled: Boolean) { twoX = enabled; refresh() }

    /** 코드에서 거리를 반영한다(리스너 호출 없음). 왼쪽 500m → 오른쪽 0m. */
    fun setDistance(distanceM: Float) {
        updatingFromCode = true
        slider.progress = (maxDistM - distanceM.coerceIn(0f, maxDistM.toFloat())).toInt()
        updatingFromCode = false
        updateDistLabel(distanceM.coerceIn(0f, maxDistM.toFloat()))
    }

    fun setFuelKind(kind: FuelKind) { fuelKind = kind; refresh() }

    fun setGpxLabel(label: String) { gpxLabel.text = label }

    /** 실측위(source=fused) 모드에서는 재생·배속·거리 슬라이더를 숨긴다. EV/ICE 전환은 유지. */
    fun setReplayControlsVisible(visible: Boolean) {
        val v = if (visible) VISIBLE else GONE
        replayRow.visibility = v
        distLabel.visibility = v
        slider.visibility = v
    }
}
