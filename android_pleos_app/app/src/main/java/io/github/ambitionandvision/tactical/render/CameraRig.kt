package io.github.ambitionandvision.tactical.render

import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OVERVIEW(45° 쿼터뷰, 경로 전체) ↔ APPROACH(차량 후방 팔로우) 카메라.
 * 모드가 바뀌면 그 순간의 카메라에서 새 모드 자세로 1.5 초 이징 전환한다. 할당 없음.
 */
class CameraRig {

    /** 화면 짧은 변이 담는 시야각. portrait·landscape 모두 같은 규칙으로 프레이밍된다. */
    var fovMinDeg = 60f

    /** 화면 가로/세로. 바뀌면 OVERVIEW 거리를 다시 맞추고 APPROACH 는 세로 화면일수록 더 내려다본다. */
    var aspect = 1f
        set(value) {
            val v = if (value.isFinite() && value > 0f) value else 1f
            if (kotlin.math.abs(v - field) > 1e-3f) { field = v; fitDirty = true }
        }

    // OVERVIEW 대상 (경로 경계 원)
    private var ovCx = 0f
    private var ovCz = 0f
    private var ovRadius = 300f
    private var ovYawDeg = 0f
    private var ovPath: SampledPath? = null
    private var ovDist = Float.NaN
    private var fitDirty = true
    /** OVERVIEW 가 실제로 바라보는 지면 점(화면 중심 보정 후). */
    private var fitCx = 0f
    private var fitCz = 0f
    private val fitBasis = CameraBasis()
    private val fitCam = FloatArray(3)
    private val fitPose = FloatArray(6)

    // 현재 출력 자세 (eye, target)
    val eye = FloatArray(3)
    val target = FloatArray(3)

    private val from = FloatArray(6)
    private val desired = FloatArray(6)
    private var approach = false
    private var transitionStartSec = Float.NaN
    private var initialized = false

    /** OVERVIEW 가 담을 경로의 경계 원과 바라볼 방위(경로 시작 → 입구). */
    fun setOverviewTarget(centerX: Float, centerZ: Float, radiusM: Float, yawDeg: Float) {
        ovCx = centerX; ovCz = centerZ; ovRadius = radiusM.coerceAtLeast(20f); ovYawDeg = yawDeg
        ovPath = null
        fitDirty = true
    }

    /** 경로 샘플로 OVERVIEW 대상을 계산한다 (경계 원 중심·반경, 시작점→끝점 방위). */
    fun setOverviewFromPath(path: SampledPath) {
        val n = path.count
        if (n == 0) return
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = path.xz[i * 2]; val z = path.xz[i * 2 + 1]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }
        val cx = (minX + maxX) / 2f
        val cz = (minZ + maxZ) / 2f
        var r2 = 0f
        for (i in 0 until n) {
            val dx = path.xz[i * 2] - cx; val dz = path.xz[i * 2 + 1] - cz
            val d = dx * dx + dz * dz
            if (d > r2) r2 = d
        }
        val yaw = RenderMath.headingOf(path.xz[(n - 1) * 2] - path.xz[0], path.xz[(n - 1) * 2 + 1] - path.xz[1])
        setOverviewTarget(cx, cz, sqrt(r2) + 15f, yaw)
        ovPath = path
    }

    /**
     * OVERVIEW 자세: 45° 내려다본다. 거리는 경로 전체 샘플이 현재 [aspect] 화면의 [FIT_MARGIN] 안에
     * 들어오는 최소값(이분 탐색, 화면 크기가 바뀔 때만 재계산·할당 없음). 경로가 없으면 경계 구 기준.
     */
    fun overviewPose(out: FloatArray) {
        if (fitDirty) {
            fitDirty = false
            ovDist = fitOverviewDistance()
        }
        poseAt(ovDist, out)
    }

    private fun sphereDistance(): Float =
        ovRadius / sin(Math.toRadians(fovMinDeg / 2.0)).toFloat() * 1.05f

    private fun fitOverviewDistance(): Float {
        fitCx = ovCx; fitCz = ovCz
        val path = ovPath ?: return sphereDistance()
        val tanMin = kotlin.math.tan(Math.toRadians(fovMinDeg / 2.0)).toFloat()
        // 짧은 변 반폭 = 1 로 정규화한 화면 반폭·반높이
        val halfW = (if (aspect >= 1f) aspect else 1f) * tanMin * FIT_MARGIN
        val halfH = (if (aspect >= 1f) 1f else 1f / aspect) * tanMin * FIT_MARGIN
        var dist = sphereDistance() * 1.5f
        // 원근 때문에 경계 상자 중심이 화면 중심에 오지 않으므로, 투영 범위의 중심을 지면에 역투영해 다시 맞춘다.
        repeat(4) {
            dist = searchDistance(path, halfW, halfH)
            recenter(path, dist)
        }
        return searchDistance(path, halfW, halfH)
    }

    private fun searchDistance(path: SampledPath, halfW: Float, halfH: Float): Float {
        var lo = 10f
        var hi = sphereDistance() * 3f
        if (!fits(path, hi, halfW, halfH)) return hi
        repeat(28) {
            val mid = (lo + hi) / 2f
            if (fits(path, mid, halfW, halfH)) hi = mid else lo = mid
        }
        return hi
    }

    /** 거리 [dist] 에서 경로의 정규화 화면 범위 중심을 지면(y=0)에 역투영해 바라보는 점으로 삼는다. */
    private fun recenter(path: SampledPath, dist: Float) {
        poseAt(dist, fitPose)
        val b = fitBasis
        b.lookAt(fitPose[0], fitPose[1], fitPose[2], fitPose[3], fitPose[4], fitPose[5])
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until path.count) {
            b.toCamera(path.xz[i * 2], 0f, path.xz[i * 2 + 1], fitCam, 0)
            if (fitCam[2] < 1f) return
            val nx = fitCam[0] / fitCam[2]
            val ny = fitCam[1] / fitCam[2]
            if (nx < minX) minX = nx; if (nx > maxX) maxX = nx
            if (ny < minY) minY = ny; if (ny > maxY) maxY = ny
        }
        val nx = (minX + maxX) / 2f
        val ny = (minY + maxY) / 2f
        val dx = b.fx + b.rx * nx + b.ux * ny
        val dy = b.fy + b.ry * nx + b.uy * ny
        val dz = b.fz + b.rz * nx + b.uz * ny
        if (dy > -1e-4f) return
        val t = -b.ey / dy
        fitCx = b.ex + dx * t
        fitCz = b.ez + dz * t
    }

    private fun fits(path: SampledPath, dist: Float, halfW: Float, halfH: Float): Boolean {
        poseAt(dist, fitPose)
        val b = fitBasis
        b.lookAt(fitPose[0], fitPose[1], fitPose[2], fitPose[3], fitPose[4], fitPose[5])
        for (i in 0 until path.count) {
            b.toCamera(path.xz[i * 2], 0f, path.xz[i * 2 + 1], fitCam, 0)
            val z = fitCam[2]
            if (z < 1f) return false
            if (kotlin.math.abs(fitCam[0]) > halfW * z || kotlin.math.abs(fitCam[1]) > halfH * z) return false
        }
        return true
    }

    private fun poseAt(dist: Float, out: FloatArray) {
        val fwdX = RenderMath.sinDeg(ovYawDeg)
        val fwdZ = RenderMath.cosDeg(ovYawDeg)
        val c45 = 0.70710677f
        out[0] = fitCx - fwdX * dist * c45
        out[1] = dist * c45
        out[2] = fitCz - fwdZ * dist * c45
        out[3] = fitCx; out[4] = 0f; out[5] = fitCz
    }

    /** APPROACH 자세: 차량 후방 상공에서 진행 방향 앞을 본다. 세로 화면일수록 높이고 가까이 본다(하늘 영역 축소). */
    fun approachPose(vx: Float, vz: Float, headingDeg: Float, out: FloatArray) {
        val fx = RenderMath.sinDeg(headingDeg)
        val fz = RenderMath.cosDeg(headingDeg)
        val portrait = (1f / aspect - 1f).coerceIn(0f, 1f)
        val back = FOLLOW_BACK_M - 4f * portrait
        val ahead = FOLLOW_AHEAD_M - 12f * portrait
        out[0] = vx - fx * back
        out[1] = FOLLOW_HEIGHT_M + 11f * portrait
        out[2] = vz - fz * back
        out[3] = vx + fx * ahead
        out[4] = 0f
        out[5] = vz + fz * ahead
    }

    /**
     * 시각 [nowSec] 의 카메라를 [eye]/[target] 에 갱신한다.
     * [approachMode] 가 직전 호출과 다르면 현재 자세에서 1.5 초 전환을 시작한다.
     */
    fun update(nowSec: Float, approachMode: Boolean, vx: Float, vz: Float, headingDeg: Float, hasVehicle: Boolean) {
        val wantApproach = approachMode && hasVehicle
        if (wantApproach) approachPose(vx, vz, headingDeg, desired) else overviewPose(desired)
        if (!initialized) {
            initialized = true
            approach = wantApproach
            write(desired, 1f)
            return
        }
        if (wantApproach != approach) {
            approach = wantApproach
            from[0] = eye[0]; from[1] = eye[1]; from[2] = eye[2]
            from[3] = target[0]; from[4] = target[1]; from[5] = target[2]
            transitionStartSec = nowSec
        }
        if (!transitionStartSec.isNaN()) {
            val t = (nowSec - transitionStartSec) / TRANSITION_SEC
            if (t >= 1f) {
                transitionStartSec = Float.NaN
                write(desired, 1f)
            } else {
                write(desired, RenderMath.easeInOutCubic(t))
            }
        } else {
            write(desired, 1f)
        }
    }

    val isTransitioning: Boolean get() = !transitionStartSec.isNaN()

    fun reset() {
        initialized = false
        transitionStartSec = Float.NaN
    }

    private fun write(to: FloatArray, u: Float) {
        if (u >= 1f) {
            eye[0] = to[0]; eye[1] = to[1]; eye[2] = to[2]
            target[0] = to[3]; target[1] = to[4]; target[2] = to[5]
            return
        }
        eye[0] = from[0] + (to[0] - from[0]) * u
        eye[1] = from[1] + (to[1] - from[1]) * u
        eye[2] = from[2] + (to[2] - from[2]) * u
        target[0] = from[3] + (to[3] - from[3]) * u
        target[1] = from[4] + (to[4] - from[4]) * u
        target[2] = from[5] + (to[5] - from[5]) * u
    }

    companion object {
        const val TRANSITION_SEC = 1.5f
        /** OVERVIEW 에서 경로가 차지할 화면 비율(가장자리 여백 = 1 − 이 값). */
        const val FIT_MARGIN = 0.86f
        const val FOLLOW_BACK_M = 26f
        const val FOLLOW_HEIGHT_M = 13f
        const val FOLLOW_AHEAD_M = 30f
    }
}

/**
 * 1Hz 측위를 화면 프레임 사이에서 부드럽게 따라가는 차량 표시 자세. 할당 없음.
 * [JUMP_SNAP_M] 이상 떨어진 새 측위(슬라이더 이동 등)는 즉시 이동한다.
 */
class VehicleSmoother {
    var x = 0f; private set
    var z = 0f; private set
    var headingDeg = 0f; private set
    var hasValue = false; private set

    private var tx = 0f
    private var tz = 0f
    private var th = 0f

    fun setTarget(x: Float, z: Float, headingDeg: Float) {
        tx = x; tz = z; th = RenderMath.normalizeDeg(headingDeg)
        val dx = tx - this.x
        val dz = tz - this.z
        if (!hasValue || dx * dx + dz * dz > JUMP_SNAP_M * JUMP_SNAP_M) {
            this.x = tx; this.z = tz; this.headingDeg = th
            hasValue = true
        }
    }

    /** [dtSec] 만큼 목표로 지수 접근. */
    fun step(dtSec: Float) {
        if (!hasValue) return
        val a = 1f - exp(-dtSec.coerceIn(0f, 0.5f) / TAU_SEC)
        x += (tx - x) * a
        z += (tz - z) * a
        headingDeg = RenderMath.lerpAngleDeg(headingDeg, th, a)
    }

    fun clear() { hasValue = false }

    companion object {
        const val TAU_SEC = 0.35f
        const val JUMP_SNAP_M = 60f
    }
}
