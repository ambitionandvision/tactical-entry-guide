package io.github.ambitionandvision.tactical.model

import kotlin.math.cos

/**
 * TEAM_WORKSPACE 5.1 의 단일 좌표 수식. Python·C# 구현과 같은 식이어야 GPS 측위와 지도가 일치한다.
 *
 *   x = (lon - lon0) * 111320 * cos(lat0)
 *   z = (lat - lat0) * 110574
 */
class LocalProjection(val origin: LatLon) {
    private val metersPerDegLon = M_PER_DEG_LON_EQUATOR * cos(Math.toRadians(origin.lat))

    fun toLocal(lat: Double, lon: Double): LocalPoint =
        LocalPoint(
            ((lon - origin.lon) * metersPerDegLon).toFloat(),
            ((lat - origin.lat) * M_PER_DEG_LAT).toFloat(),
        )

    fun toLatLon(x: Double, z: Double): LatLon =
        LatLon(origin.lat + z / M_PER_DEG_LAT, origin.lon + x / metersPerDegLon)

    companion object {
        const val M_PER_DEG_LON_EQUATOR = 111320.0
        const val M_PER_DEG_LAT = 110574.0
    }
}
