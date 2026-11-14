package io.github.ambitionandvision.tactical.engine

import io.github.ambitionandvision.tactical.model.CameraMode
import io.github.ambitionandvision.tactical.model.EntryPoint
import io.github.ambitionandvision.tactical.model.FuelKind
import io.github.ambitionandvision.tactical.model.GuidePoint
import io.github.ambitionandvision.tactical.model.HazardPin
import io.github.ambitionandvision.tactical.model.LocalProjection
import io.github.ambitionandvision.tactical.model.SnapResult
import io.github.ambitionandvision.tactical.model.TacticalFrame
import io.github.ambitionandvision.tactical.model.TacticalPhase
import io.github.ambitionandvision.tactical.model.VehicleFix
import io.github.ambitionandvision.tactical.model.ZoneData
import kotlin.math.hypot

/**
 * Phase 1(대기 카드) → Phase 2(3D 전술) → Phase 3(도착) 상태머신. 모든 거리는 활성 입구까지의 직선 거리다.
 *
 * - P1→P2: 거리 ≤ tacticalDistM, 또는 거리 ≤ tacticalSlowDistM 이고 속도 ≤ tacticalSpeedKmh
 * - P2→P1: 거리 > tacticalExitDistM
 * - P2→P3: 거리 ≤ arrivedDistM, 또는 거리 ≤ arrivedStopDistM 에서 arrivedStopSpeedKmh 미만이 arrivedStopHoldSec 유지
 *   (신호 대기 오판 방지: 입구 30m 밖의 정차는 도착이 아니다)
 * - P3 이후: 호출측이 [acknowledgeArrival] 로 대기 화면에 돌아간다. 차량이 tacticalExitDistM 밖으로 나가기 전에는
 *   P2 에 다시 들어가지 않는다(주차장 안 정차로 P2↔P3 가 반복되는 것을 막는다).
 * - cameraMode: 거리 ≤ followViewDistM 이면 APPROACH, > followViewExitDistM 이면 OVERVIEW, 그 사이는 유지.
 * - armed=false(내비 활성 경로의 목적지가 Zone 밖 = 지나가는 차량)면 P1→P2 를 막는다. 이미 P2 면 유지한다.
 *
 * 정차 유지 시간은 측위 타임스탬프로 잰다(GPX 2배속 재생에서도 트랙 시간 기준). 단일 스레드에서 호출한다.
 */
class TacticalStateMachine(private val zone: ZoneData) {

    private val cfg = zone.triggers
    private val projection = LocalProjection(zone.origin)

    private val mainEntry: EntryPoint =
        zone.entries.firstOrNull { it.id == MAIN_PARKING_ID } ?: zone.entries.first()

    private val routes = HashMap<String, Route>()

    var fuelKind: FuelKind = FuelKind.UNKNOWN
        private set
    var motionRestricted: Boolean = false
        private set
    var phase: TacticalPhase = TacticalPhase.PHASE_1_CRUISING
        private set
    var armed: Boolean = true
        private set

    private var route: Route = routeFor(FuelKind.UNKNOWN)
    private var cameraMode = CameraMode.OVERVIEW
    private var reentryLocked = false
    private var stopHoldStartMs: Long? = null
    private var lastHeadingDeg = 0f

    val activeEntry: EntryPoint get() = route.entry
    val activeSnapper: LocalSplineSnapper get() = route.snapper

    fun setFuelKind(k: FuelKind) {
        if (k == fuelKind) return
        fuelKind = k
        val next = routeFor(k)
        if (next !== route) {
            route = next
            route.snapper.reset()
            stopHoldStartMs = null
        }
    }

    fun setArmed(armed: Boolean) {
        this.armed = armed
    }

    fun setMotionRestricted(r: Boolean) {
        motionRestricted = r
    }

    /** Phase 3 표시를 마친 호출측이 대기 화면으로 돌린다. 400m 밖으로 나갈 때까지 P2 재진입은 잠긴다. */
    fun acknowledgeArrival() {
        if (phase == TacticalPhase.PHASE_3_ARRIVED) {
            phase = TacticalPhase.PHASE_1_CRUISING
            reentryLocked = true
            stopHoldStartMs = null
            route.snapper.reset()
        }
    }

    fun reset() {
        phase = TacticalPhase.PHASE_1_CRUISING
        cameraMode = CameraMode.OVERVIEW
        reentryLocked = false
        stopHoldStartMs = null
        lastHeadingDeg = 0f
        routes.values.forEach { it.snapper.reset() }
    }

    fun update(fix: VehicleFix): TacticalFrame {
        val p = projection.toLocal(fix.lat, fix.lon)
        val entry = route.entry
        val dist = hypot(p.x - entry.position.x, p.z - entry.position.z)

        if (dist > cfg.tacticalExitDistM) reentryLocked = false

        when (phase) {
            TacticalPhase.PHASE_1_CRUISING -> {
                val enter = dist <= cfg.tacticalDistM ||
                    (dist <= cfg.tacticalSlowDistM && fix.speedKmh <= cfg.tacticalSpeedKmh)
                if (enter && !reentryLocked && armed) {
                    phase = TacticalPhase.PHASE_2_TACTICAL
                    route.snapper.reset()
                    stopHoldStartMs = null
                }
            }
            TacticalPhase.PHASE_2_TACTICAL -> {
                if (dist > cfg.tacticalExitDistM) {
                    phase = TacticalPhase.PHASE_1_CRUISING
                    stopHoldStartMs = null
                } else if (dist <= cfg.arrivedDistM || stopHeld(dist, fix)) {
                    phase = TacticalPhase.PHASE_3_ARRIVED
                    reentryLocked = true
                    stopHoldStartMs = null
                }
            }
            TacticalPhase.PHASE_3_ARRIVED -> {
                if (dist > cfg.tacticalExitDistM) phase = TacticalPhase.PHASE_1_CRUISING
            }
        }

        cameraMode = when {
            dist <= cfg.followViewDistM -> CameraMode.APPROACH
            dist > cfg.followViewExitDistM -> CameraMode.OVERVIEW
            else -> cameraMode
        }

        val snap: SnapResult? =
            if (phase == TacticalPhase.PHASE_1_CRUISING) null else route.snapper.snap(p.x, p.z)

        val heading = when {
            snap != null && snap.attached -> snap.headingDeg
            fix.headingDeg != null -> fix.headingDeg
            else -> lastHeadingDeg
        }
        lastHeadingDeg = heading

        var nextHazard: HazardPin? = null
        var nextHazardDist: Float? = null
        if (snap != null) {
            route.pins.firstOrNull { it.second > snap.progressM }?.let { (pin, at) ->
                nextHazard = pin
                nextHazardDist = at - snap.progressM
            }
        }

        val attached = snap?.attached == true
        return TacticalFrame(
            phase = phase,
            rawX = p.x,
            rawZ = p.z,
            vehicleX = if (attached) snap!!.snappedX else p.x,
            vehicleZ = if (attached) snap!!.snappedZ else p.z,
            headingDeg = heading,
            speedKmh = fix.speedKmh,
            distanceToEntryM = dist,
            pathRemainingM = snap?.remainingM,
            snap = snap,
            activeEntry = entry,
            cameraMode = cameraMode,
            nextHazard = nextHazard,
            nextHazardDistM = nextHazardDist,
            fuelKind = fuelKind,
            motionRestricted = motionRestricted,
            source = fix.source,
        )
    }

    private fun stopHeld(dist: Float, fix: VehicleFix): Boolean {
        if (dist > cfg.arrivedStopDistM || fix.speedKmh >= cfg.arrivedStopSpeedKmh) {
            stopHoldStartMs = null
            return false
        }
        val start = stopHoldStartMs ?: fix.timestampMs.also { stopHoldStartMs = it }
        return fix.timestampMs - start >= (cfg.arrivedStopHoldSec * 1000f).toLong()
    }

    /** UNKNOWN 은 MAIN_PARKING. 연료 종류를 받는 입구가 없으면 MAIN_PARKING 으로 돌아간다. */
    private fun entryFor(k: FuelKind): EntryPoint =
        if (k == FuelKind.UNKNOWN) mainEntry else zone.entries.firstOrNull { k in it.fuelKinds } ?: mainEntry

    private fun routeFor(k: FuelKind): Route {
        val entry = entryFor(k)
        return routes.getOrPut(entry.id) {
            val points: List<GuidePoint> =
                if (entry.id == EV_ENTRY_ID && zone.evGuidePathPoints.isNotEmpty()) zone.evGuidePathPoints
                else zone.guidePathPoints
            val snapper = LocalSplineSnapper(points, cfg)
            // 이 유도선 근처의 핀만 진행거리 순으로. 다른 입구 경로의 핀이 섞이지 않게 한다.
            val pins = zone.hazardPins
                .map { it to snapper.project(it.x, it.z) }
                .filter { (_, s) -> s.lateralOffsetM <= cfg.offRouteWarnDistM + PIN_ROUTE_MARGIN_M }
                .map { (pin, s) -> pin to s.progressM }
                .sortedBy { it.second }
            Route(entry, snapper, pins)
        }
    }

    private class Route(val entry: EntryPoint, val snapper: LocalSplineSnapper, val pins: List<Pair<HazardPin, Float>>)

    companion object {
        const val MAIN_PARKING_ID = "MAIN_PARKING"
        const val EV_ENTRY_ID = "EV_CHARGING_ENTRY"

        /** 핀이 유도선에서 offRouteWarnDistM + 이 값(m) 안에 있어야 그 경로의 위험요소로 본다. 저작 기준값. */
        const val PIN_ROUTE_MARGIN_M = 10f
    }
}
