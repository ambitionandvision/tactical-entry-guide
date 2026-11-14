package io.github.ambitionandvision.tactical.engine

import io.github.ambitionandvision.tactical.model.BuildingFootprint
import io.github.ambitionandvision.tactical.model.EntryPoint
import io.github.ambitionandvision.tactical.model.FixSource
import io.github.ambitionandvision.tactical.model.FuelKind
import io.github.ambitionandvision.tactical.model.GuidePoint
import io.github.ambitionandvision.tactical.model.HazardPin
import io.github.ambitionandvision.tactical.model.HazardType
import io.github.ambitionandvision.tactical.model.LatLon
import io.github.ambitionandvision.tactical.model.LocalPoint
import io.github.ambitionandvision.tactical.model.LocalProjection
import io.github.ambitionandvision.tactical.model.StaticNotices
import io.github.ambitionandvision.tactical.model.TriggerConfig
import io.github.ambitionandvision.tactical.model.VehicleFix
import io.github.ambitionandvision.tactical.model.ZoneData

/** 단위 테스트용 합성 Zone: MAIN_PARKING 유도선은 북쪽 600m 에서 원점(입구)까지 남하하는 직선. */
object TestZones {
    val ORIGIN = LatLon(36.469512, 127.270845)
    val projection = LocalProjection(ORIGIN)

    fun zone(
        guide: List<GuidePoint> = (0..6).map { GuidePoint("Path_0${it + 1}", 0f, 600f - it * 100f) },
        pins: List<HazardPin> = listOf(
            HazardPin("Pin_WaitLane_01", HazardType.WAIT_LANE, 3f, 200f, "대기차로", false),
            HazardPin("Pin_Barrier_01", HazardType.BARRIER, -2f, 50f, "차단기", false),
            HazardPin("Pin_NoTurn_01", HazardType.NO_TURN, 200f, 100f, "다른 경로", false),
        ),
    ) = ZoneData(
        schemaVersion = 1,
        zoneId = "test",
        displayName = "테스트",
        origin = ORIGIN,
        radiusM = 300f,
        entries = listOf(
            EntryPoint("MAIN_PARKING", "주차장 통합 입구", setOf(FuelKind.ICE, FuelKind.UNKNOWN), LocalPoint(0f, 0f), "우측 끝 차로"),
            EntryPoint("EV_CHARGING_ENTRY", "전기차 충전소 인접 입구", setOf(FuelKind.EV, FuelKind.PHEV), LocalPoint(-118f, -286f), "우측 끝 차로"),
        ),
        buildings = listOf(BuildingFootprint("TARGET_COSTCO_BUILDING", "TARGET", 20f, listOf(LocalPoint(0f, 0f)))),
        roads = emptyList(),
        guidePathPoints = guide,
        evGuidePathPoints = listOf(GuidePoint("PathEV_01", -118f, 300f), GuidePoint("PathEV_02", -118f, 0f), GuidePoint("PathEV_03", -118f, -286f)),
        hazardPins = pins,
        staticNotices = StaticNotices(2.3f, "우회전 후 30m 앞 차단기"),
        triggers = TriggerConfig(),
    )

    fun fix(x: Float, z: Float, speedKmh: Float, tMs: Long, heading: Float? = 180f): VehicleFix {
        val ll = projection.toLatLon(x.toDouble(), z.toDouble())
        return VehicleFix(ll.lat, ll.lon, heading, speedKmh, tMs, FixSource.GPX_REPLAY)
    }
}
