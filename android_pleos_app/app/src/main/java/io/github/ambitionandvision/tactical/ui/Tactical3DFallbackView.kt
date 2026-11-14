package io.github.ambitionandvision.tactical.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.View
import io.github.ambitionandvision.tactical.engine.ObjGroup
import io.github.ambitionandvision.tactical.model.CameraMode
import io.github.ambitionandvision.tactical.model.TacticalFrame
import io.github.ambitionandvision.tactical.model.TacticalSurface
import io.github.ambitionandvision.tactical.model.ZoneData
import io.github.ambitionandvision.tactical.render.CameraBasis
import io.github.ambitionandvision.tactical.render.CameraRig
import io.github.ambitionandvision.tactical.render.Palette
import io.github.ambitionandvision.tactical.render.RenderState
import io.github.ambitionandvision.tactical.render.TacticalRenderer
import io.github.ambitionandvision.tactical.render.TacticalScene
import io.github.ambitionandvision.tactical.render.VehicleSmoother
import java.util.Locale

/**
 * Unity 가 없을 때 쓰는 네이티브 3D 전술 뷰 (Canvas 전용, GL 미사용).
 * 색은 시스템 주간/야간에 따라 [Palette.DAY] 또는 [Palette.NIGHT] 를 쓴다.
 *
 * 3D 수학·정렬·클리핑은 [TacticalRenderer] (순수 Kotlin) 가 하고, 이 뷰는 결과 다각형을 Canvas 에 칠한다.
 * - 고정 지면 순서(레이더 그리드 → 도로 → 리본 → 화살표), 건물은 건물 단위 원거리→근거리 정렬 후 면 정렬.
 * - OBJ 그룹 `RAMP_*` (선택)는 네온 반투명 경사 쐐기 + 정적 오르막 쉐브론으로, 본관 위에 비쳐 보이게 그린다. 없으면 기존과 같다.
 * - 근평면 Sutherland-Hodgman 클리핑으로 카메라가 건물 사이에 있어도 깨지지 않는다.
 * - onDraw·Choreographer 콜백은 할당하지 않는다. 장면 재구성(setMeshes/bindZone)만 할당한다.
 * - motionRestricted 면 화살표 흐름을 멈춘다. setActive(false) 면 Choreographer 콜백을 해제한다.
 *
 * 성능 측정: 5초마다 logcat `Tactical3D` 태그로 fps·onDraw 평균/최대 시간·출력 다각형 수를 남긴다.
 * 모든 메서드는 메인 스레드에서 호출된다(TEAM_WORKSPACE 5.6).
 */
class Tactical3DFallbackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), TacticalSurface {

    private var zone: ZoneData? = null
    private var groups: List<ObjGroup>? = null
    private var renderer: TacticalRenderer? = null

    private val basis = CameraBasis()
    private val rig = CameraRig()
    private val smoother = VehicleSmoother()
    private val state = RenderState()

    private var active = false
    private var approachMode = false
    private var evActive = false
    private var loopPosted = false
    private var lastDrawNanos = 0L
    private val startNanos = System.nanoTime()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        // 합성 굵게 대신 Pleos 기본 글꼴(Asta Sans)의 Bold(600) 를 쓴다.
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    /** 현재 팔레트. 시스템 야간 모드면 [Palette.NIGHT], 아니면 [Palette.DAY]. 설정이 바뀔 때만 바뀐다. */
    private var palette: Palette = Palette.DAY
    private val path = Path()
    /** 이번 프레임에 그린 라벨 사각형 l,t,r,b (장면 재구성 때만 크기가 바뀐다). */
    private var labelRects = FloatArray(0)

    // 성능 통계 (5초 창)
    private var statFrames = 0
    private var statDrawNanos = 0L
    private var statMaxNanos = 0L
    private var statWindowStart = 0L
    var lastFps = 0f; private set
    var lastAvgDrawMs = 0f; private set

    private val frameCallback = Choreographer.FrameCallback {
        loopPosted = false
        if (shouldLoop()) {
            invalidate()
            postLoop()
        }
    }

    init {
        val d = resources.displayMetrics.density
        strokePaint.strokeWidth = 1.5f * d
        gridPaint.strokeWidth = 1f * d
        ringPaint.strokeWidth = 1.5f * d
        labelPaint.textSize = 15f * d
        labelBorderPaint.strokeWidth = 1f * d
        applyPalette(paletteFor(resources.configuration))
        rebuildScene()
    }

    /** OBJ 메쉬(ObjMeshLoader 결과)를 장면에 반영한다. */
    fun setMeshes(groups: List<ObjGroup>) {
        this.groups = groups
        rebuildScene()
    }

    override fun bindZone(zone: ZoneData) {
        this.zone = zone
        rebuildScene()
    }

    override fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        if (active) {
            lastDrawNanos = 0L
            statWindowStart = 0L
            postLoop()
        } else {
            removeLoop()
        }
        invalidate()
    }

    override fun onFrame(frame: TacticalFrame) {
        smoother.setTarget(frame.vehicleX, frame.vehicleZ, frame.headingDeg)
        val snap = frame.snap
        state.progressM = snap?.progressM ?: Float.NaN
        state.motionRestricted = frame.motionRestricted
        approachMode = frame.cameraMode == CameraMode.APPROACH
        val ev = frame.activeEntry.id == EV_ENTRY_ID
        if (ev != evActive) {
            evActive = ev
            updateOverview()
        }
        if (!active) invalidate()
    }

    private fun paletteFor(config: Configuration): Palette =
        if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) Palette.NIGHT else Palette.DAY

    private fun applyPalette(p: Palette) {
        palette = p
        gridPaint.color = p.GRID
        ringPaint.color = p.GRID_RING
        labelPaint.color = p.LABEL
        labelBgPaint.color = p.LABEL_BG
        labelBorderPaint.color = p.LABEL_BORDER
    }

    /** 시스템 주간/야간이 바뀌면 팔레트를 바꾸고 장면을 다시 만든다 (메인 스레드, 프레임 밖). */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val p = paletteFor(newConfig)
        if (p !== palette) {
            applyPalette(p)
            rebuildScene()
        }
    }

    private fun rebuildScene() {
        val scene = TacticalScene.build(groups, zone, palette)
        renderer = TacticalRenderer(scene).also { it.fovMinDeg = rig.fovMinDeg }
        labelRects = FloatArray(scene.labels.size * 4)
        updateOverview()
        Log.i(TAG, "scene: faces=${scene.faceCount} solidFaces=${scene.solidFaceCount} objects=${scene.objectCount} " +
            "palette=${if (palette === Palette.NIGHT) "night" else "day"} vertices=${scene.vertexCount} ramps=${scene.rampObjs.size} mainPath=${scene.mainPath?.lengthM} evPath=${scene.evPath?.lengthM}")
        invalidate()
    }

    private fun updateOverview() {
        val sc = renderer?.scene ?: return
        val p = (if (evActive) sc.evPath else sc.mainPath) ?: sc.mainPath ?: sc.evPath
        if (p != null && p.count >= 2) rig.setOverviewFromPath(p) else rig.setOverviewTarget(0f, 0f, TacticalScene.GRID_RADIUS_M, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        val t0 = System.nanoTime()
        canvas.drawColor(palette.BACKGROUND)
        val r = renderer ?: return
        val dt = if (lastDrawNanos == 0L) 0f else (t0 - lastDrawNanos) / 1e9f
        lastDrawNanos = t0
        val nowSec = (t0 - startNanos) / 1e9f

        smoother.step(dt)
        if (height > 0) rig.aspect = width.toFloat() / height
        rig.update(nowSec, approachMode, smoother.x, smoother.z, smoother.headingDeg, smoother.hasValue)
        basis.lookAt(rig.eye[0], rig.eye[1], rig.eye[2], rig.target[0], rig.target[1], rig.target[2])

        state.hasVehicle = smoother.hasValue
        state.vehicleX = smoother.x
        state.vehicleZ = smoother.z
        state.vehicleHeadingDeg = smoother.headingDeg
        state.evActive = evActive
        state.nowSec = nowSec
        val out = r.render(basis, width, height, state)

        if (out.gridFloats > 0) canvas.drawLines(out.gridLines, 0, out.gridFloats, gridPaint)
        if (out.ringFloats > 0) canvas.drawLines(out.ringLines, 0, out.ringFloats, ringPaint)

        // 같은 (채움, 외곽선) 이 연속인 다각형은 한 Path 로 묶는다. 감기 방향이 통일돼 있어 합집합으로 칠해진다.
        var i = 0
        val xy = out.polyXY
        while (i < out.polyCount) {
            val color = out.polyColor[i]
            val stroke = out.polyStroke[i]
            path.rewind()
            var j = i
            while (j < out.polyCount && out.polyColor[j] == color && out.polyStroke[j] == stroke) {
                val off = out.polyStart[j] * 2
                val n = out.polySize[j]
                path.moveTo(xy[off], xy[off + 1])
                for (k in 1 until n) path.lineTo(xy[off + k * 2], xy[off + k * 2 + 1])
                path.close()
                j++
            }
            fillPaint.color = color
            canvas.drawPath(path, fillPaint)
            if (stroke != 0) {
                strokePaint.color = stroke
                canvas.drawPath(path, strokePaint)
            }
            i = j
        }

        // 라벨: 장면 순서(입구 → 핀) 우선, 이미 그린 라벨과 겹치면 생략한다.
        val labels = r.scene.labels
        val pad = labelPaint.textSize * 0.35f
        if (labelRects.size < out.labelCount * 4) labelRects = FloatArray(r.scene.labels.size * 4)
        var placed = 0
        for (k in 0 until out.labelCount) {
            val text = labels[out.labelIndex[k]]
            val x = out.labelX[k]
            val y = out.labelY[k]
            val w = labelPaint.measureText(text) / 2f + pad
            val l = x - w
            val t = y - labelPaint.textSize - pad
            val rr = x + w
            val b = y + pad
            var overlap = false
            for (q in 0 until placed) {
                if (l < labelRects[q * 4 + 2] && rr > labelRects[q * 4] && t < labelRects[q * 4 + 3] && b > labelRects[q * 4 + 1]) {
                    overlap = true
                    break
                }
            }
            if (overlap) continue
            labelRects[placed * 4] = l; labelRects[placed * 4 + 1] = t; labelRects[placed * 4 + 2] = rr; labelRects[placed * 4 + 3] = b
            placed++
            canvas.drawRoundRect(l, t, rr, b, pad, pad, labelBgPaint)
            if (palette.LABEL_BORDER != 0) canvas.drawRoundRect(l, t, rr, b, pad, pad, labelBorderPaint)
            canvas.drawText(text, x, y, labelPaint)
        }

        recordStats(System.nanoTime() - t0, out.polyCount, r)
    }

    private fun recordStats(drawNanos: Long, polys: Int, r: TacticalRenderer) {
        if (!active) return
        val now = System.nanoTime()
        if (statWindowStart == 0L) {
            statWindowStart = now
            statFrames = 0; statDrawNanos = 0L; statMaxNanos = 0L
            return
        }
        statFrames++
        statDrawNanos += drawNanos
        if (drawNanos > statMaxNanos) statMaxNanos = drawNanos
        val window = now - statWindowStart
        if (window >= STAT_WINDOW_NANOS) {
            lastFps = statFrames * 1e9f / window
            lastAvgDrawMs = statDrawNanos / 1e6f / statFrames
            Log.i(TAG, String.format(Locale.US,
                "perf fps=%.1f drawAvg=%.2fms drawMax=%.2fms polys=%d sceneFaces=%d solidFaces=%d culled=%d back=%d clipped=%d %dx%d",
                lastFps, lastAvgDrawMs, statMaxNanos / 1e6f, polys, r.scene.faceCount, r.scene.solidFaceCount,
                r.output.culledFaces, r.output.backFaces, r.output.clippedFaces, width, height))
            statWindowStart = now
            statFrames = 0; statDrawNanos = 0L; statMaxNanos = 0L
        }
    }

    private fun shouldLoop(): Boolean = active && isAttachedToWindow && windowVisibility == VISIBLE

    private fun postLoop() {
        if (loopPosted || !shouldLoop()) return
        loopPosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun removeLoop() {
        if (!loopPosted) return
        loopPosted = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postLoop()
    }

    override fun onDetachedFromWindow() {
        removeLoop()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            lastDrawNanos = 0L
            postLoop()
        } else {
            removeLoop()
        }
    }

    companion object {
        private const val TAG = "Tactical3D"
        private const val EV_ENTRY_ID = "EV_CHARGING_ENTRY"
        private const val STAT_WINDOW_NANOS = 5_000_000_000L
    }
}
