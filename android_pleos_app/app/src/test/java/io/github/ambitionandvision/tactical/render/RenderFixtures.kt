package io.github.ambitionandvision.tactical.render

import io.github.ambitionandvision.tactical.engine.ObjGroup
import io.github.ambitionandvision.tactical.engine.ObjMeshLoader
import io.github.ambitionandvision.tactical.model.BuildingFootprint
import io.github.ambitionandvision.tactical.model.CameraMode
import io.github.ambitionandvision.tactical.model.EntryPoint
import io.github.ambitionandvision.tactical.model.FixSource
import io.github.ambitionandvision.tactical.model.FuelKind
import io.github.ambitionandvision.tactical.model.GuidePoint
import io.github.ambitionandvision.tactical.model.HazardPin
import io.github.ambitionandvision.tactical.model.HazardType
import io.github.ambitionandvision.tactical.model.LatLon
import io.github.ambitionandvision.tactical.model.LocalPoint
import io.github.ambitionandvision.tactical.model.RoadStrip
import io.github.ambitionandvision.tactical.model.SnapResult
import io.github.ambitionandvision.tactical.model.StaticNotices
import io.github.ambitionandvision.tactical.model.TacticalFrame
import io.github.ambitionandvision.tactical.model.TacticalPhase
import io.github.ambitionandvision.tactical.model.TriggerConfig
import io.github.ambitionandvision.tactical.model.ZoneData

/** 렌더·브리지 테스트용 합성 데이터 (실데이터 아님, 계약 형태만 맞춘다). */
object RenderFixtures {

    val mainEntry = EntryPoint("MAIN_PARKING", "주차장 통합 입구", setOf(FuelKind.ICE, FuelKind.UNKNOWN), LocalPoint(0f, 0f), "우측 끝 차로")
    val evEntry = EntryPoint("EV_CHARGING_ENTRY", "전기차 충전소 인접 입구", setOf(FuelKind.EV, FuelKind.PHEV), LocalPoint(-118f, -286f), "우측 끝 차로")

    fun zone(): ZoneData = ZoneData(
        schemaVersion = 1, zoneId = "sejong_costco", displayName = "코스트코 세종점",
        origin = LatLon(36.469512, 127.270845), radiusM = 300f,
        entries = listOf(mainEntry, evEntry),
        buildings = listOf(
            BuildingFootprint("TARGET_COSTCO_BUILDING", "TARGET", 20f,
                listOf(LocalPoint(-219f, -299f), LocalPoint(-75f, -299f), LocalPoint(-75f, -140f), LocalPoint(-219f, -140f))),
            BuildingFootprint("B0001", "SURROUND", 10.5f,
                listOf(LocalPoint(20f, 20f), LocalPoint(40f, 20f), LocalPoint(40f, 45f), LocalPoint(20f, 45f))),
        ),
        roads = listOf(RoadStrip("R0001", 15f, 2, listOf(LocalPoint(-300f, 8f), LocalPoint(300f, 8f)))),
        guidePathPoints = listOf(
            GuidePoint("Path_01", -306f, 416f), GuidePoint("Path_02", -159f, 220f), GuidePoint("Path_03", -89f, 173f),
            GuidePoint("Path_04", 40f, 140f), GuidePoint("Path_05", 84f, 120f), GuidePoint("Path_06", 54f, -9f),
            GuidePoint("Path_07", 40f, -18f), GuidePoint("Path_08", 0f, 0f),
        ),
        evGuidePathPoints = listOf(
            GuidePoint("PathEV_01", -306f, 416f), GuidePoint("PathEV_02", -89f, 173f),
            GuidePoint("PathEV_03", -60f, -158f), GuidePoint("PathEV_04", -118f, -286f),
        ),
        hazardPins = listOf(
            HazardPin("Pin_Barrier_01", HazardType.BARRIER, 30f, -15f, "차단기", false),
            HazardPin("Pin_NoTurn_01", HazardType.NO_TURN, 15f, -10f, "회차 불가", false),
            HazardPin("Pin_WaitLane_01", HazardType.WAIT_LANE, 60f, 40f, "대기 차로 \"우측\"", false),
        ),
        staticNotices = StaticNotices(2.3f, "우회전 후 30m 앞 차단기"),
        triggers = TriggerConfig(),
    )

    /** 축 정렬 상자(바닥 없이 5면)를 OBJ 문자열로. 파일 Z = -북. */
    fun boxObj(sb: StringBuilder, base: Int, x0: Float, z0: Float, x1: Float, z1: Float, h: Float): Int {
        val xs = floatArrayOf(x0, x1, x1, x0)
        val zs = floatArrayOf(z0, z0, z1, z1)
        for (y in floatArrayOf(0f, h)) for (i in 0 until 4) sb.append("v ${xs[i]} $y ${-zs[i]}\n")
        val b = base + 1
        sb.append("f ${b + 4} ${b + 5} ${b + 6} ${b + 7}\n")
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            sb.append("f ${b + i} ${b + j} ${b + 4 + j} ${b + 4 + i}\n")
        }
        return base + 8
    }

    /**
     * 면 수 [targetFaces] 근처의 Blender 스타일 장면: 본관 1동 + 주변 건물 상자들 + 도로 사각형들.
     */
    fun denseObj(targetFaces: Int): List<ObjGroup> {
        val sb = StringBuilder("# synthetic\n")
        var v = 0
        sb.append("o TARGET_COSTCO_BUILDING\n")
        v = boxObj(sb, v, -219f, -299f, -75f, -140f, 20f)
        sb.append("o ROAD_SURFACE_300M\n")
        val roadQuads = targetFaces / 5
        var made = 0
        for (i in 0 until roadQuads) {
            val x = -300f + (i % 30) * 20f
            val z = -300f + (i / 30) * 20f
            sb.append("v $x 0 ${-z}\nv ${x + 12} 0 ${-z}\nv ${x + 12} 0 ${-(z + 12)}\nv $x 0 ${-(z + 12)}\n")
            sb.append("f ${v + 1} ${v + 2} ${v + 3} ${v + 4}\n")
            v += 4
            made++
        }
        sb.append("o SURROUND_BUILDINGS\n")
        val boxes = (targetFaces - 5 - made) / 5
        for (i in 0 until boxes) {
            val x = -290f + (i % 28) * 21f
            val z = -290f + (i / 28) * 21f
            v = boxObj(sb, v, x, z, x + 12f, z + 12f, 6f + (i % 7) * 3.5f)
        }
        return ObjMeshLoader.load(sb.toString().byteInputStream())
    }

    fun frame(x: Float, z: Float, heading: Float, mode: CameraMode, progress: Float = 100f) = TacticalFrame(
        phase = TacticalPhase.PHASE_2_TACTICAL, rawX = x, rawZ = z, vehicleX = x, vehicleZ = z,
        headingDeg = heading, speedKmh = 24.5f, distanceToEntryM = 87.25f,
        snap = SnapResult(x, z, heading, 1.5f, progress, 80f, true, false),
        activeEntry = mainEntry, cameraMode = mode, nextHazard = null, nextHazardDistM = null,
        fuelKind = FuelKind.ICE, motionRestricted = false, source = FixSource.GPX_REPLAY,
    )

    /** 불투명 바탕 [bg] 위에 [fg] 를 알파 합성한 불투명 색. */
    fun composite(fg: Int, bg: Int): Int {
        val a = (fg ushr 24) / 255f
        fun ch(shift: Int) = (((fg shr shift) and 0xFF) * a + ((bg shr shift) and 0xFF) * (1f - a) + 0.5f).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** WCAG 상대 휘도 기준 명암비 (불투명 색). */
    fun contrastRatio(a: Int, b: Int): Float {
        fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
        fun lum(c: Int) = 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
        val la = lum(a); val lb = lum(b)
        return ((maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)).toFloat()
    }
}
