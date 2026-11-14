package io.github.ambitionandvision.tactical

import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import io.github.ambitionandvision.tactical.bridge.UnityBridgeManager
import io.github.ambitionandvision.tactical.engine.ObjMeshLoader
import io.github.ambitionandvision.tactical.engine.TacticalStateMachine
import io.github.ambitionandvision.tactical.model.FuelKind
import io.github.ambitionandvision.tactical.model.LatLon
import io.github.ambitionandvision.tactical.model.LocalProjection
import io.github.ambitionandvision.tactical.model.LocationSource
import io.github.ambitionandvision.tactical.model.TacticalFrame
import io.github.ambitionandvision.tactical.model.TacticalPhase
import io.github.ambitionandvision.tactical.model.VehicleFix
import io.github.ambitionandvision.tactical.model.ZoneData
import io.github.ambitionandvision.tactical.model.ZoneJsonParser
import io.github.ambitionandvision.tactical.sdk.FuelKindDetector
import io.github.ambitionandvision.tactical.sdk.FusedLocationSource
import io.github.ambitionandvision.tactical.sdk.GpxReplaySource
import io.github.ambitionandvision.tactical.sdk.GpxTrack
import io.github.ambitionandvision.tactical.sdk.LaneDepartureInjector
import io.github.ambitionandvision.tactical.sdk.LaneTestSchedule
import io.github.ambitionandvision.tactical.sdk.LocationSubscriptionController
import io.github.ambitionandvision.tactical.sdk.MainLooperScheduler
import io.github.ambitionandvision.tactical.sdk.ManualDistanceSource
import io.github.ambitionandvision.tactical.sdk.NaviDestinationMonitor
import io.github.ambitionandvision.tactical.sdk.NaviDestinationStatus
import io.github.ambitionandvision.tactical.sdk.NaviZoneGate
import io.github.ambitionandvision.tactical.sdk.PleosFusedClient
import io.github.ambitionandvision.tactical.sdk.UxRestrictionMonitor
import io.github.ambitionandvision.tactical.sdk.ZoneGeofence
import io.github.ambitionandvision.tactical.ui.ArrivedOverlayView
import io.github.ambitionandvision.tactical.ui.DemoControlBarView
import io.github.ambitionandvision.tactical.ui.SummaryCardView
import io.github.ambitionandvision.tactical.ui.Tactical3DFallbackView
import io.github.ambitionandvision.tactical.ui.TacticalHudView
import java.util.Locale
import kotlin.math.hypot

/**
 * assets 로드 → 상태머신 ↔ 뷰 연결 → 위치 소스 전환.
 *
 * 위치 소스 모드
 * - DEMO (기본, BuildConfig.SHOW_DEMO_CONTROLS): GPX 재생(자동 재생) 또는 거리 슬라이더. 항상 armed.
 * - FUSED (`source=fused` extra, 또는 데모 컨트롤이 꺼진 빌드): Pleos FusedLocation 실측위.
 *   Zone 밖에서는 ProximityAlert(ENTER/EXIT) + getLastKnownLocation 만 쓰고, Zone 안에서만 연속 측위를 구독한다
 *   ([LocationSubscriptionController]). NaviHelper 활성 경로의 목적지가 Zone 밖이면 P2 진입을 막는다([NaviZoneGate]).
 * - `fuel=ICE|EV|PHEV` extra 는 시작 시 연료 판별 결과를 덮어쓴다(녹화 자동화용).
 * - `laneTestAtSec=N`(정수) extra 는 GPX 재생을 시작하고 N 초 뒤 차선 이탈 테스트를 한 번 실행한다(시연용).
 *   재생이 일시정지·중단되면 예약은 취소된다.
 *
 * Phase 1 에서는 3D 표면을 숨긴다(INVISIBLE). Phase 2 에서 보이고, Phase 3 은 3D 위에 도착 오버레이를 띄운다.
 * onPause 에서 렌더·위치 갱신을 멈추고 onResume 에서 복원한다. configChanges 로 layout / layout-land 를 다시 붙인다.
 */
class MainActivity : Activity(), DemoControlBarView.Listener {

    private enum class SourceMode { DEMO, FUSED }

    private lateinit var zone: ZoneData
    private lateinit var stateMachine: TacticalStateMachine
    private lateinit var projection: LocalProjection
    private lateinit var fallbackView: Tactical3DFallbackView
    private lateinit var surface: UnityBridgeManager

    private lateinit var surfaceContainer: FrameLayout
    private lateinit var summaryScroll: View
    private lateinit var summaryCard: SummaryCardView
    private lateinit var hud: TacticalHudView
    private lateinit var arrivedOverlay: ArrivedOverlayView
    private lateinit var controlBar: DemoControlBarView

    private val handler = Handler(Looper.getMainLooper())
    private val scheduler = MainLooperScheduler()
    private val sliderScheduler = MainLooperScheduler()
    private val subscriptionScheduler = MainLooperScheduler()

    private val tracks = HashMap<String, GpxTrack>()
    private var replay: GpxReplaySource? = null
    private var slider: ManualDistanceSource? = null
    private var activeDemoSource: LocationSource? = null
    private lateinit var laneInjector: LaneDepartureInjector
    private var laneTest: LaneTestSchedule? = null

    private lateinit var fusedClient: PleosFusedClient
    private lateinit var fusedSource: FusedLocationSource
    private lateinit var geofence: ZoneGeofence
    private lateinit var subscription: LocationSubscriptionController
    private lateinit var naviGate: NaviZoneGate
    private var fuelDetector: FuelKindDetector? = null
    private var naviMonitor: NaviDestinationMonitor? = null
    private var uxMonitor: UxRestrictionMonitor? = null

    private var sourceMode = SourceMode.DEMO
    private var fuelOverridden = false
    private var resumeReplayOnResume = false
    private var naviAvailable = true
    private var naviStatus: NaviDestinationStatus? = null
    private var lastFrame: TacticalFrame? = null
    private var lastPhase: TacticalPhase? = null
    private var loaded = false

    private val arrivalReturn = Runnable {
        stateMachine.acknowledgeArrival()
        arrivedOverlay.hide()
        lastPhase = null
        lastFrame?.let { render(it.copy(phase = stateMachine.phase)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loaded = try {
            zone = assets.open(ZONE_ASSET).bufferedReader(Charsets.UTF_8).use { ZoneJsonParser.parse(it.readText()) }
            for (name in listOf(MAIN_GPX_ASSET, EV_GPX_ASSET)) {
                runCatching { assets.open(name).use { GpxTrack.parse(it) } }
                    .onSuccess { tracks[name] = it }
                    .onFailure { Log.w(TAG, "GPX 로드 실패 $name: ${it.message}") }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "zone 데이터 로드 실패", e)
            false
        }
        if (!loaded) {
            Toast.makeText(this, "전술 데이터(assets)를 불러오지 못했습니다", Toast.LENGTH_LONG).show()
            return
        }

        sourceMode = when {
            intent.getStringExtra(EXTRA_SOURCE).equals(SOURCE_FUSED, ignoreCase = true) -> SourceMode.FUSED
            BuildConfig.SHOW_DEMO_CONTROLS -> SourceMode.DEMO
            else -> SourceMode.FUSED
        }
        Log.i(TAG, "위치 소스 모드: $sourceMode")

        projection = LocalProjection(zone.origin)
        stateMachine = TacticalStateMachine(zone)
        laneInjector = LaneDepartureInjector(projection)
        naviGate = NaviZoneGate(zone)

        fallbackView = Tactical3DFallbackView(this)
        runCatching { assets.open(OBJ_ASSET).use { ObjMeshLoader.load(it) } }
            .onSuccess { fallbackView.setMeshes(it) }
            .onFailure { Log.w(TAG, "OBJ 로드 실패: ${it.message}") }
        surface = UnityBridgeManager(this, fallbackView)
        surface.bindZone(zone)
        Log.i(TAG, "3D 표면: ${if (surface.isUnityAvailable) "Unity(UaaL)" else "네이티브 Canvas 폴백"}")

        bindViews()
        setupFused()

        naviMonitor = NaviDestinationMonitor(this, naviGate).also { m ->
            m.onStatus = { s ->
                naviAvailable = true
                naviStatus = s
                updateArming()
                updateNaviLine()
            }
            m.onUnavailable = {
                Log.i(TAG, "NaviHelper 사용 불가 → armed 유지")
                naviAvailable = false
                updateArming()
            }
        }
        uxMonitor = UxRestrictionMonitor(this) { restricted ->
            stateMachine.setMotionRestricted(restricted)
            lastFrame?.let { render(it.copy(motionRestricted = restricted)) }
        }

        val forcedFuel = intent.getStringExtra(EXTRA_FUEL)?.let { v ->
            runCatching { FuelKind.valueOf(v.trim().uppercase(Locale.US)) }.getOrNull()
        }
        if (forcedFuel != null) {
            fuelOverridden = true
            Log.i(TAG, "연료 타입 extra 로 고정: $forcedFuel")
        }
        fuelDetector = FuelKindDetector(this).also { d ->
            d.detect { kind -> if (!fuelOverridden) applyFuelKind(kind) else Log.i(TAG, "판별 결과 $kind 는 extra 로 덮어씀") }
        }
        applyFuelKind(forcedFuel ?: stateMachine.fuelKind, force = true)
        updateArming()

        requestFusedPermissionIfNeeded()
        resumeReplayOnResume = sourceMode == SourceMode.DEMO && intent.getBooleanExtra(EXTRA_AUTOPLAY, true)
        if (sourceMode == SourceMode.DEMO) {
            LaneTestSchedule.delayFromExtra(intent.getIntExtra(EXTRA_LANE_TEST_AT_SEC, -1))?.let { sec ->
                laneTest = LaneTestSchedule(MainLooperScheduler(), sec) {
                    Log.i(TAG, "조작: 차선 이탈 테스트 (실행 인자, ${sec}초)")
                    onLaneDepartureTest()
                }
            }
        }
    }

    private fun setupFused() {
        fusedClient = PleosFusedClient(this)
        fusedSource = FusedLocationSource(fusedClient)
        geofence = ZoneGeofence(fusedClient)
        subscription = LocationSubscriptionController(
            object : LocationSubscriptionController.Actions {
                override fun registerGeofence(center: LatLon, radiusM: Float, generation: Int) =
                    geofence.register(center, radiusM, generation)
                override fun unregisterGeofence() = geofence.unregister()
                override fun requestLastKnown() {
                    if (!fusedSource.requestLastKnown()) Log.i(TAG, "getLastKnownLocation 호출 불가")
                }
                override fun startContinuous() {
                    Log.i(TAG, "위치 소스: Pleos FusedLocation 연속 측위 시작")
                    fusedSource.start(::onFix)
                }
                override fun stopContinuous() {
                    Log.i(TAG, "위치 소스: Pleos FusedLocation 연속 측위 해제(Zone 밖 대기)")
                    fusedSource.stop()
                }
                override fun log(message: String) {
                    Log.i(TAG, "구독 전환: $message")
                }
            },
            subscriptionScheduler,
            enterDistM = zone.triggers.tacticalExitDistM,
        )
        subscription.isPhase1 = { stateMachine.phase == TacticalPhase.PHASE_1_CRUISING }
        geofence.onRegistered = { gen -> subscription.onGeofenceRegistered(gen) }
        geofence.onFailed = { gen, reason -> subscription.onGeofenceFailed(gen, reason) }
        geofence.onTransition = { enter -> if (enter) subscription.onEnter() else subscription.onExit() }
        fusedSource.onLastKnown = { lat, lon ->
            val p = projection.toLocal(lat, lon)
            val e = stateMachine.activeEntry.position
            val d = hypot(p.x - e.x, p.z - e.z)
            Log.i(TAG, "마지막 위치: 입구까지 ${d.toInt()}m")
            subscription.onDistance(d)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!loaded) return
        // layout ↔ layout-land 교체. 상태머신·위치 소스는 그대로 둔다.
        (fallbackView.parent as? ViewGroup)?.removeView(fallbackView)
        setContentView(R.layout.activity_main)
        bindViews()
        lastPhase = null
        lastFrame?.let { render(it) }
    }

    private fun bindViews() {
        surfaceContainer = findViewById(R.id.tacticalSurfaceContainer)
        surfaceContainer.addView(
            fallbackView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        summaryScroll = findViewById(R.id.summaryScroll)
        summaryCard = findViewById(R.id.summaryCard)
        hud = findViewById(R.id.tacticalHud)
        arrivedOverlay = findViewById(R.id.arrivedOverlay)
        controlBar = findViewById(R.id.demoControlBar)

        // 첫 프레임 전에도 Phase 1 화면: 3D 숨김 + 요약 카드
        setSurfaceVisible(lastFrame?.let { it.phase != TacticalPhase.PHASE_1_CRUISING } ?: false)
        summaryScroll.visibility = View.VISIBLE

        controlBar.visibility = if (BuildConfig.SHOW_DEMO_CONTROLS) View.VISIBLE else View.GONE
        controlBar.listener = this
        controlBar.setFuelKind(stateMachine.fuelKind)
        controlBar.setReplayControlsVisible(sourceMode == SourceMode.DEMO)
        controlBar.setPlaying(replay?.playing == true)
        controlBar.set2x(replay?.speed2x == true)
        if (sourceMode == SourceMode.DEMO) {
            replay?.let { controlBar.setGpxLabel(it.track.replayLabel) }
        } else {
            controlBar.setGpxLabel(getString(R.string.source_fused_label))
        }
        bindSummary()
        updateNaviLine()
    }

    private fun bindSummary() {
        summaryCard.bind(
            zone,
            lastFrame?.copy(fuelKind = stateMachine.fuelKind, activeEntry = stateMachine.activeEntry),
            stateMachine.fuelKind,
            stateMachine.activeEntry,
        )
    }

    private fun setSurfaceVisible(visible: Boolean) {
        surfaceContainer.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    override fun onResume() {
        super.onResume()
        if (!loaded) return
        uxMonitor?.start()
        naviMonitor?.start()
        when (sourceMode) {
            SourceMode.FUSED -> startFusedIfPermitted()
            SourceMode.DEMO -> {
                val s = slider
                if (s != null && activeDemoSource === s) {
                    Log.i(TAG, "위치 소스: 거리 슬라이더")
                    s.start(::onFix)
                } else {
                    activeDemoSource = replay
                    if (resumeReplayOnResume) {
                        Log.i(TAG, "위치 소스: ${replay?.track?.replayLabel} 재생 시작 replayIndex=${replay?.positionIndex}")
                        replay?.start(::onFix)
                        if (replay?.playing == true) laneTest?.arm()
                    }
                }
                controlBar.setPlaying(replay?.playing == true)
            }
        }
        lastFrame?.let { surface.setActive(it.phase == TacticalPhase.PHASE_2_TACTICAL) }
    }

    override fun onPause() {
        super.onPause()
        if (!loaded) return
        // 백그라운드: 렌더 루프·위치 갱신·센서 수집 정리
        resumeReplayOnResume = replay?.playing == true
        Log.i(TAG, "onPause: 재생 중=$resumeReplayOnResume replayIndex=${replay?.positionIndex}")
        cancelLaneTest("onPause")
        replay?.stop()
        slider?.stop()
        subscription.stop()
        fusedSource.stop()
        fusedClient.release()
        naviMonitor?.stop()
        uxMonitor?.stop()
        surface.setActive(false)
        controlBar.setPlaying(false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (loaded) fuelDetector?.release()
        super.onDestroy()
    }

    // ---- 위치 처리 ---------------------------------------------------------------------------

    private fun onFix(fix: VehicleFix) {
        val now = System.currentTimeMillis()
        val frame = stateMachine.update(laneInjector.apply(fix, now))
        if (sourceMode == SourceMode.FUSED) subscription.onDistance(frame.distanceToEntryM)
        render(frame)
    }

    private fun render(frame: TacticalFrame) {
        lastFrame = frame
        val phaseChanged = frame.phase != lastPhase
        lastPhase = frame.phase
        if (phaseChanged) {
            Log.i(TAG, "phase=${frame.phase} 입구까지 ${frame.distanceToEntryM.toInt()}m replayIndex=${replay?.positionIndex}")
        }
        when (frame.phase) {
            TacticalPhase.PHASE_1_CRUISING -> {
                if (phaseChanged) {
                    handler.removeCallbacks(arrivalReturn)
                    arrivedOverlay.hide()
                    hud.visibility = View.GONE
                    summaryScroll.visibility = View.VISIBLE
                    surface.setActive(false)
                    setSurfaceVisible(false)
                }
                summaryCard.bind(zone, frame)
            }
            TacticalPhase.PHASE_2_TACTICAL -> {
                if (phaseChanged) {
                    handler.removeCallbacks(arrivalReturn)
                    arrivedOverlay.hide()
                    summaryScroll.visibility = View.GONE
                    hud.visibility = View.VISIBLE
                    setSurfaceVisible(true)
                    surface.setActive(true)
                }
                hud.onFrame(frame)
                surface.onFrame(frame)
            }
            TacticalPhase.PHASE_3_ARRIVED -> {
                if (phaseChanged) {
                    hud.visibility = View.GONE
                    summaryScroll.visibility = View.GONE
                    setSurfaceVisible(true)
                    surface.onFrame(frame)
                    surface.setActive(false)
                    arrivedOverlay.show(frame.activeEntry)
                    handler.removeCallbacks(arrivalReturn)
                    handler.postDelayed(arrivalReturn, ARRIVED_DISPLAY_MS)
                }
            }
        }
        if (sourceMode == SourceMode.DEMO && activeDemoSource === replay) {
            controlBar.setDistance(frame.distanceToEntryM.coerceIn(0f, 500f))
        }
    }

    // ---- 내비 게이트 ---------------------------------------------------------------------------

    /** 데모 소스는 항상 armed. 실측위는 NaviHelper 목적지 기준. */
    private fun updateArming() {
        val armed = sourceMode == SourceMode.DEMO || NaviZoneGate.armed(naviStatus, naviAvailable)
        if (armed != stateMachine.armed) Log.i(TAG, "armed=$armed (navi=$naviStatus available=$naviAvailable)")
        stateMachine.setArmed(armed)
    }

    private fun updateNaviLine() {
        if (!::summaryCard.isInitialized) return
        val s = naviStatus
        summaryCard.setNaviInfo(
            when {
                s == null || !s.hasActiveRoute || !s.nearZone -> null
                s.remainDistanceM != null && s.remainDistanceM > 0 ->
                    getString(R.string.summary_navi_remain, String.format(Locale.US, "%.1f", s.remainDistanceM / 1000f))
                else -> getString(R.string.summary_navi_linked)
            },
        )
    }

    // ---- 연료 타입 · 위치 소스 -----------------------------------------------------------------

    /** 활성 입구를 바꾸고, 지오펜스를 그 입구로 옮기며, 데모 모드면 그 입구의 GPX 로 교체한다. */
    private fun applyFuelKind(kind: FuelKind, force: Boolean = false) {
        val before = stateMachine.activeEntry.id
        stateMachine.setFuelKind(kind)
        controlBar.setFuelKind(kind)
        bindSummary()
        subscription.setEntryCenter(entryLatLon())
        if (stateMachine.activeEntry.id == before && !force && replay != null) return
        Log.i(TAG, "연료=$kind 입구=${stateMachine.activeEntry.id}")
        if (sourceMode != SourceMode.DEMO) return

        val asset = if (stateMachine.activeEntry.id == TacticalStateMachine.EV_ENTRY_ID) EV_GPX_ASSET else MAIN_GPX_ASSET
        val track = tracks[asset] ?: tracks[MAIN_GPX_ASSET] ?: return
        if (replay?.track === track) return

        val wasPlaying = replay?.playing == true
        val keep2x = replay?.speed2x == true
        cancelLaneTest("GPX 교체")
        replay?.stop()
        slider?.stop()
        stateMachine.reset()
        lastPhase = null
        replay = GpxReplaySource(track, scheduler) {
            Log.i(TAG, "GPX 재생 끝 (${track.points.size}점)")
            cancelLaneTest("재생 끝")
            controlBar.setPlaying(false)
        }.also { it.set2x(keep2x) }
        slider = ManualDistanceSource(track, projection, stateMachine.activeEntry.position, sliderScheduler)
        controlBar.setGpxLabel(track.replayLabel)
        activeDemoSource = replay
        if (wasPlaying) replay?.start(::onFix)
        controlBar.setPlaying(replay?.playing == true)
        Log.i(TAG, "위치 소스: ${track.replayLabel} ($asset)")
    }

    private fun entryLatLon(): LatLon {
        val e = stateMachine.activeEntry.position
        return projection.toLatLon(e.x.toDouble(), e.z.toDouble())
    }

    private fun switchToReplay() {
        if (activeDemoSource === replay) return
        slider?.stop()
        stateMachine.reset()
        lastPhase = null
        replay?.rewind()
        activeDemoSource = replay
        Log.i(TAG, "위치 소스: ${replay?.track?.replayLabel}")
    }

    private fun switchToSlider() {
        if (activeDemoSource === slider) return
        cancelLaneTest("거리 슬라이더")
        replay?.stop()
        controlBar.setPlaying(false)
        stateMachine.reset()
        lastPhase = null
        activeDemoSource = slider
        Log.i(TAG, "위치 소스: 거리 슬라이더")
        slider?.start(::onFix)
    }

    private fun requestFusedPermissionIfNeeded() {
        if (checkSelfPermission(FUSED_LOCATION_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(FUSED_LOCATION_PERMISSION), REQ_FUSED)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_FUSED && loaded && sourceMode == SourceMode.FUSED) startFusedIfPermitted()
    }

    private fun startFusedIfPermitted() {
        if (checkSelfPermission(FUSED_LOCATION_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "FUSED_LOCATION 권한 없음 → 권한 허용 후 시작")
            return
        }
        if (subscription.mode != LocationSubscriptionController.Mode.IDLE) return
        Log.i(TAG, "위치 소스: Pleos FusedLocation (지오펜스 대기, 입구 ${stateMachine.activeEntry.id})")
        subscription.start(entryLatLon())
    }

    // ---- DemoControlBarView.Listener ------------------------------------------------------------

    override fun onPlayPause() {
        if (sourceMode != SourceMode.DEMO) return
        switchToReplay()
        val r = replay ?: return
        if (r.playing) {
            cancelLaneTest("일시정지")
            r.pause()
        } else {
            if (r.isFinished) {
                stateMachine.reset()
                lastPhase = null
            }
            r.start(::onFix)
        }
        Log.i(TAG, "조작: 재생/일시정지 → 재생 중=${r.playing} replayIndex=${r.positionIndex}")
        controlBar.setPlaying(r.playing)
    }

    override fun onToggle2x() {
        val r = replay ?: return
        r.set2x(!r.speed2x)
        Log.i(TAG, "조작: 2배속=${r.speed2x} replayIndex=${r.positionIndex}")
        controlBar.set2x(r.speed2x)
    }

    override fun onDistanceChanged(distanceM: Float) {
        if (sourceMode != SourceMode.DEMO) return
        switchToSlider()
        slider?.setDistance(distanceM)
    }

    override fun onFuelKindChanged(kind: FuelKind) {
        fuelOverridden = true
        applyFuelKind(kind)
    }

    override fun onLaneDepartureTest() {
        laneInjector.trigger(System.currentTimeMillis())
    }

    /** 실행 인자로 예약한 차선 이탈 테스트를 취소한다(재생 일시정지·중단·백그라운드). 예약이 없으면 아무것도 하지 않는다. */
    private fun cancelLaneTest(reason: String) {
        if (laneTest?.cancel() == true) Log.i(TAG, "차선 이탈 테스트 예약 취소: $reason")
    }

    companion object {
        private const val TAG = "TacticalMain"
        const val ZONE_ASSET = "sejong_costco_zone.json"
        const val MAIN_GPX_ASSET = "sejong_costco_cn8_drive.gpx"
        const val EV_GPX_ASSET = "sejong_costco_ev_drive.gpx"
        const val OBJ_ASSET = "sejong_costco_custom.obj"
        const val EXTRA_AUTOPLAY = "autoplay"
        /** `source=fused`: 데모 소스 대신 Pleos FusedLocation 실측위 (adb emu geo fix 주입 경로 시연용). */
        const val EXTRA_SOURCE = "source"
        const val SOURCE_FUSED = "fused"
        /** `fuel=ICE|EV|PHEV`: 시작 시 연료 판별 결과 덮어쓰기. */
        const val EXTRA_FUEL = "fuel"
        /** `laneTestAtSec=N`(정수, `--ei`): 재생 시작 N 초 뒤 차선 이탈 테스트 1회. */
        const val EXTRA_LANE_TEST_AT_SEC = "laneTestAtSec"
        private const val FUSED_LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION"
        private const val REQ_FUSED = 1001
        private const val ARRIVED_DISPLAY_MS = 5_000L
    }
}
