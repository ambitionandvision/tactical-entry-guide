package io.github.ambitionandvision.tactical.model

/**
 * 모듈 공용 계약(Contract). 시그니처를 바꾸면 docs/SPEC.md 5장도 함께 고친다.
 *
 * 로컬 좌표계: x = 동(m), z = 북(m), y = 위(m), 원점 = zone.origin.
 * JSON 키 이름은 아래 프로퍼티 이름과 동일하다 (gis_data/sejong_costco_zone.json).
 */

enum class TacticalPhase { PHASE_1_CRUISING, PHASE_2_TACTICAL, PHASE_3_ARRIVED }

/** EvBattery / Fuel capability 조합으로 판별한다. CarInfo 에는 연료 타입 API 가 없다. */
enum class FuelKind { EV, PHEV, ICE, UNKNOWN }

enum class HazardType { BARRIER, NO_TURN, WAIT_LANE }

enum class FixSource { PLEOS_FUSED, GPX_REPLAY, MANUAL_SLIDER }

enum class CameraMode { OVERVIEW, APPROACH }

data class LatLon(val lat: Double, val lon: Double)

data class LocalPoint(val x: Float, val z: Float)

data class GuidePoint(val id: String, val x: Float, val z: Float)

data class HazardPin(
    val id: String,
    val type: HazardType,
    val x: Float,
    val z: Float,
    val label: String,
    /** 현장 실측으로 확인된 위치인가. 저작(가정) 배치는 false. */
    val fieldVerified: Boolean,
)

data class EntryPoint(
    /** "MAIN_PARKING" 또는 "EV_CHARGING_ENTRY" */
    val id: String,
    val label: String,
    val fuelKinds: Set<FuelKind>,
    val position: LocalPoint,
    val recommendedLane: String,
)

data class BuildingFootprint(
    val id: String,
    /** "TARGET" 또는 "SURROUND" */
    val role: String,
    val heightM: Float,
    val footprint: List<LocalPoint>,
)

data class RoadStrip(
    val id: String,
    val widthM: Float,
    val lanes: Int,
    val centerline: List<LocalPoint>,
)

data class StaticNotices(
    /** 시설물 정적 안내 배지 전용. 차량 전고와 비교·필터링하는 로직은 금지. */
    val heightLimitM: Float,
    val barrierNote: String,
)

data class TriggerConfig(
    val tacticalDistM: Float = 300f,
    val tacticalSlowDistM: Float = 350f,
    val tacticalSpeedKmh: Float = 30f,
    val tacticalExitDistM: Float = 400f,
    val followViewDistM: Float = 100f,
    val followViewExitDistM: Float = 115f,
    val arrivedDistM: Float = 15f,
    val arrivedStopDistM: Float = 30f,
    val arrivedStopSpeedKmh: Float = 3f,
    val arrivedStopHoldSec: Float = 3f,
    val snapDistM: Float = 15f,
    val offRouteWarnDistM: Float = 20f,
)

data class ZoneData(
    val schemaVersion: Int,
    val zoneId: String,
    val displayName: String,
    val origin: LatLon,
    val radiusM: Float,
    val entries: List<EntryPoint>,
    val buildings: List<BuildingFootprint>,
    val roads: List<RoadStrip>,
    /** MAIN_PARKING 유도선 제어점 Path_01..Path_08 */
    val guidePathPoints: List<GuidePoint>,
    /** EV_CHARGING_ENTRY 유도선 제어점 PathEV_01.. */
    val evGuidePathPoints: List<GuidePoint>,
    val hazardPins: List<HazardPin>,
    val staticNotices: StaticNotices,
    val triggers: TriggerConfig,
)

/** 위치 소스가 내보내는 측위 1건. speedKmh 는 연속 측위에서 추정한 값이다 (LocationData 에 속도 필드 없음). */
data class VehicleFix(
    val lat: Double,
    val lon: Double,
    val headingDeg: Float?,
    val speedKmh: Float,
    val timestampMs: Long,
    val source: FixSource,
)

data class SnapResult(
    val snappedX: Float,
    val snappedZ: Float,
    val headingDeg: Float,
    /** 유도선까지의 수평 거리(m). 항상 0 이상. */
    val lateralOffsetM: Float,
    val progressM: Float,
    val remainingM: Float,
    /** lateralOffsetM <= snapDistM */
    val attached: Boolean,
    /** lateralOffsetM >= offRouteWarnDistM */
    val offRouteWarning: Boolean,
)

/** 상태머신이 매 측위마다 만드는 단일 진실 원천. 모든 뷰는 이 값만 읽는다. */
data class TacticalFrame(
    val phase: TacticalPhase,
    val rawX: Float,
    val rawZ: Float,
    /** 화면에 그릴 차량 위치. attached 면 스냅 좌표, 아니면 원시 좌표. */
    val vehicleX: Float,
    val vehicleZ: Float,
    val headingDeg: Float,
    val speedKmh: Float,
    /** 활성 입구까지의 직선 거리(m). 모든 거리 트리거의 기준. */
    val distanceToEntryM: Float,
    val snap: SnapResult?,
    val activeEntry: EntryPoint,
    val cameraMode: CameraMode,
    val nextHazard: HazardPin?,
    val nextHazardDistM: Float?,
    val fuelKind: FuelKind,
    /** CarUxRestrictions 가 주행 제한 상태인가. true 면 자동 애니메이션을 멈춘다. */
    val motionRestricted: Boolean,
    val source: FixSource,
    /**
     * 유도선을 따라 활성 입구까지 남은 거리(m). 유도선 투영([snap])이 있으면 흡착 여부와 관계없이 값이 있고,
     * 투영을 계산하지 않는 Phase 1 에서는 null 이다. [nextHazardDistM] 과 같은 진행거리 기준이다.
     * 화면 표시 전용이다. Phase 전이·카메라·도착 판정은 [distanceToEntryM] 을 쓴다.
     */
    val pathRemainingM: Float? = null,
)

/** 3D 전술 표면. Tactical3DFallbackView(네이티브)와 Unity 브리지가 같은 계약을 구현한다. */
interface TacticalSurface {
    fun bindZone(zone: ZoneData)

    /** Phase 2 에서만 true. false 면 렌더 루프 또는 Unity 를 재운다. */
    fun setActive(active: Boolean)

    fun onFrame(frame: TacticalFrame)
}

/** 위치 소스. Pleos FusedLocation, GPX 리플레이, 거리 슬라이더가 구현한다. */
interface LocationSource {
    fun start(listener: (VehicleFix) -> Unit)

    fun stop()
}
