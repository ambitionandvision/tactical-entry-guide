package io.github.ambitionandvision.tactical.sdk

import io.github.ambitionandvision.tactical.model.LocalProjection
import io.github.ambitionandvision.tactical.model.ZoneData
import kotlin.math.abs
import kotlin.math.hypot

/** NaviHelper 목적지를 Zone 기준으로 해석한 결과. */
data class NaviDestinationStatus(
    /** 순정 내비에 활성 경로가 있는가. 경로가 없으면 SDK 가 좌표 (0,0)·remainDistance 0 을 준다(에뮬레이터 실측). */
    val hasActiveRoute: Boolean,
    val name: String,
    /** 목적지가 Zone(원점 또는 어느 입구) 에서 [NaviZoneGate.nearZoneM] 이내인가. 활성 경로가 없으면 false. */
    val nearZone: Boolean,
    /** 목적지와 Zone(원점·입구 중 가장 가까운 곳) 의 거리(m). 활성 경로가 없으면 null. */
    val distanceToZoneM: Float?,
    /** 남은 거리(m). navi-data DestinationInfo KDoc: "remainDistance … (in meters)", Int. */
    val remainDistanceM: Int?,
)

/**
 * 지나가는 차량에 3D 가 뜨지 않게 하는 게이트. Android 의존 없음.
 *
 * - 활성 경로가 있고 목적지가 Zone 밖 → armed=false (P1→P2 금지)
 * - 활성 경로 없음, NaviHelper 실패·미지원, 아직 응답 없음 → armed=true (내비 없이도 동작)
 * - 목적지 판정은 활성 입구가 아니라 Zone 전체(원점 + 모든 입구) 기준이라 EV/ICE 전환에 흔들리지 않는다.
 */
class NaviZoneGate(private val zone: ZoneData, val nearZoneM: Float = 500f) {

    private val projection = LocalProjection(zone.origin)

    fun classify(name: String?, lat: Double?, lon: Double?, remainDistance: Int?): NaviDestinationStatus {
        val noRoute = lat == null || lon == null || (abs(lat) < NO_ROUTE_EPS_DEG && abs(lon) < NO_ROUTE_EPS_DEG)
        if (noRoute) return NaviDestinationStatus(false, name.orEmpty(), false, null, null)
        val p = projection.toLocal(lat!!, lon!!)
        val dOrigin = hypot(p.x, p.z)
        val dEntry = zone.entries.minOfOrNull { hypot(p.x - it.position.x, p.z - it.position.z) } ?: dOrigin
        val d = minOf(dOrigin, dEntry)
        return NaviDestinationStatus(true, name.orEmpty(), d <= nearZoneM, d, remainDistance)
    }

    companion object {
        /** 위경도 (0,0) 은 대서양 한가운데다. 이 부근 값은 "경로 없음" 으로 본다. */
        const val NO_ROUTE_EPS_DEG = 1e-6

        /** [status] 가 null 이면(응답 없음·실패·미지원) armed. */
        fun armed(status: NaviDestinationStatus?, naviAvailable: Boolean): Boolean = when {
            !naviAvailable || status == null -> true
            !status.hasActiveRoute -> true
            else -> status.nearZone
        }
    }
}
