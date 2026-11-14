package io.github.ambitionandvision.tactical.render

import io.github.ambitionandvision.tactical.engine.ObjGroup
import io.github.ambitionandvision.tactical.engine.ObjMeshLoader
import io.github.ambitionandvision.tactical.model.ZoneJsonParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 개발용 미리보기. `app/build/ramp_preview/` 폴더가 있을 때만 돈다 (평소에는 건너뜀).
 * 렌더러 출력(화면 다각형 목록)을 텍스트로 떨군다. 별도 도구가 뷰와 같은 규칙으로 칠해 눈으로 확인한다. 라벨은 기준점과 글자만 남긴다.
 * 자산에 RAMP_ 그룹이 없으면 `ramp_preview/synthetic_ramp.obj` (있을 때)를 덧붙인다.
 */
class RampPreviewRenderTest {

    /** 한 프레임을 텍스트로: "G|R x0 y0 x1 y1" (격자·링 선), "P color stroke x y x y ..." (그리는 순서). */
    private fun paint(out: RenderOutput, w: Int, h: Int, file: File, scene: TacticalScene) {
        val pal = scene.palette
        val sb = StringBuilder("S $w $h ${pal.BACKGROUND} ${pal.GRID} ${pal.GRID_RING} ${pal.LABEL} ${pal.LABEL_BG} ${pal.LABEL_BORDER}\n")
        for (i in 0 until out.labelCount) sb.append("L ${out.labelX[i]} ${out.labelY[i]} ${scene.labels[out.labelIndex[i]]}\n")
        for (i in 0 until out.gridFloats / 4) sb.append("G ${out.gridLines[i * 4]} ${out.gridLines[i * 4 + 1]} ${out.gridLines[i * 4 + 2]} ${out.gridLines[i * 4 + 3]}\n")
        for (i in 0 until out.ringFloats / 4) sb.append("R ${out.ringLines[i * 4]} ${out.ringLines[i * 4 + 1]} ${out.ringLines[i * 4 + 2]} ${out.ringLines[i * 4 + 3]}\n")
        for (i in 0 until out.polyCount) {
            sb.append("P ${out.polyColor[i]} ${out.polyStroke[i]}")
            val off = out.polyStart[i] * 2
            for (k in 0 until out.polySize[i] * 2) sb.append(' ').append(out.polyXY[off + k])
            sb.append('\n')
        }
        file.writeText(sb.toString())
    }

    @Test
    fun writePreviewImages() {
        val dir = File("build/ramp_preview")
        assumeTrue("미리보기 폴더 없음", dir.isDirectory)
        val assets = File(System.getProperty("ramp.assets") ?: dir.resolve("assets").takeIf { it.isDirectory }?.path ?: "src/main/assets")
        var groups: List<ObjGroup> = assets.resolve("sejong_costco_custom.obj").inputStream().use { ObjMeshLoader.load(it) }
        val zone = ZoneJsonParser.parse(assets.resolve("sejong_costco_zone.json").readText(Charsets.UTF_8))
        val extra = dir.resolve("synthetic_ramp.obj")
        if (groups.none { it.name.startsWith(TacticalScene.RAMP_PREFIX) } && extra.isFile) groups = groups + extra.inputStream().use { ObjMeshLoader.load(it) }
        val poses = dir.resolve("poses.txt").takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() && !it.startsWith("#") }.orEmpty()
        // `night` 파일이 있으면 야간 팔레트, 없으면 주간.
        val scene = TacticalScene.build(groups, zone, if (dir.resolve("night").exists()) Palette.NIGHT else Palette.DAY)
        val r = TacticalRenderer(scene)
        val path = scene.mainPath!!
        val o = FloatArray(4)
        // 기본: 경로 시작(OVERVIEW), 입구 60m 전·15m 전·3m 전(APPROACH)
        val shots = ArrayList<Triple<String, Float, Boolean>>()
        shots.add(Triple("overview", 0f, false))
        shots.add(Triple("overview_mid", path.lengthM * 0.6f, false))
        for (d in floatArrayOf(90f, 60f, 30f, 15f, 3f)) shots.add(Triple("approach_${d.toInt()}m", path.lengthM - d, true))
        for ((w, h) in listOf(1080 to 1700, 1920 to 720, 1082 to 1210)) {
            for ((name, arc, approach) in shots) {
                path.pointAt(arc, o)
                val rig = CameraRig().apply { setOverviewFromPath(path); aspect = w.toFloat() / h }
                val s = RenderState().apply {
                    hasVehicle = true; vehicleX = o[0]; vehicleZ = o[1]; vehicleHeadingDeg = RenderMath.headingOf(o[2], o[3]); progressM = arc
                }
                rig.update(0f, approach, s.vehicleX, s.vehicleZ, s.vehicleHeadingDeg, true)
                val b = CameraBasis().apply { lookAt(rig.eye[0], rig.eye[1], rig.eye[2], rig.target[0], rig.target[1], rig.target[2]) }
                paint(r.render(b, w, h, s), w, h, dir.resolve("${name}_${w}x$h.frame.txt"), scene)
            }
            // 사용자 지정 자세: "이름 ex ey ez tx ty tz"
            for (line in poses) {
                val t = line.trim().split(Regex("\\s+"))
                val b = CameraBasis().apply { lookAt(t[1].toFloat(), t[2].toFloat(), t[3].toFloat(), t[4].toFloat(), t[5].toFloat(), t[6].toFloat()) }
                paint(r.render(b, w, h, RenderState()), w, h, dir.resolve("${t[0]}_${w}x$h.frame.txt"), scene)
            }
        }
    }
}
