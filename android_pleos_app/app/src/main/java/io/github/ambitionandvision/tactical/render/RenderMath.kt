package io.github.ambitionandvision.tactical.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tactical3DFallbackView 의 순수 수학. Android 의존 없음, 할당 없음 (JVM 단위 테스트 대상).
 *
 * 월드 프레임: x=동, y=위, z=북 (TEAM_WORKSPACE 5.1).
 * 카메라 공간: x=화면 오른쪽, y=화면 위, z=시선 방향 깊이(앞이 +).
 * 방위각(heading): 북=0°, 시계방향(동=90°). 전방 벡터 = (sin h, 0, cos h).
 */
object RenderMath {

    /**
     * 근평면 z = [near] 에 대한 Sutherland-Hodgman 클리핑.
     * [src] 는 카메라 공간 x,y,z 반복 [n] 개. 결과를 [dst] 에 쓰고 정점 수를 반환한다 (0 이면 완전히 뒤).
     * [dst] 용량은 2n * 3 이상이어야 한다(오목 다각형은 교차점마다 정점이 늘어 최대 1.5n). 오목 다각형도 단일 평면 클리핑은 올바르게 동작한다.
     */
    fun clipPolygonNear(src: FloatArray, n: Int, near: Float, dst: FloatArray): Int {
        if (n <= 0) return 0
        var out = 0
        var px = src[(n - 1) * 3]
        var py = src[(n - 1) * 3 + 1]
        var pz = src[(n - 1) * 3 + 2]
        var pIn = pz >= near
        for (i in 0 until n) {
            val cx = src[i * 3]
            val cy = src[i * 3 + 1]
            val cz = src[i * 3 + 2]
            val cIn = cz >= near
            if (cIn != pIn) {
                val t = (near - pz) / (cz - pz)
                dst[out * 3] = px + (cx - px) * t
                dst[out * 3 + 1] = py + (cy - py) * t
                dst[out * 3 + 2] = near
                out++
            }
            if (cIn) {
                dst[out * 3] = cx
                dst[out * 3 + 1] = cy
                dst[out * 3 + 2] = cz
                out++
            }
            px = cx; py = cy; pz = cz; pIn = cIn
        }
        return out
    }

    /**
     * 선분 근평면 클리핑. [seg] = (ax,ay,az,bx,by,bz) 를 제자리에서 잘라낸다. 완전히 뒤면 false.
     */
    fun clipSegmentNear(seg: FloatArray, near: Float): Boolean {
        val az = seg[2]
        val bz = seg[5]
        if (az < near && bz < near) return false
        if (az >= near && bz >= near) return true
        val t = (near - az) / (bz - az)
        val ix = seg[0] + (seg[3] - seg[0]) * t
        val iy = seg[1] + (seg[4] - seg[1]) * t
        if (az < near) {
            seg[0] = ix; seg[1] = iy; seg[2] = near
        } else {
            seg[3] = ix; seg[4] = iy; seg[5] = near
        }
        return true
    }

    /** 원근 투영. 화면 좌표 (px, 아래가 +y) 를 [out]\[off], [out]\[off+1] 에 쓴다. z 는 near 이상이어야 한다. */
    fun project(x: Float, y: Float, z: Float, focalPx: Float, cx: Float, cy: Float, out: FloatArray, off: Int) {
        val inv = 1f / z
        out[off] = cx + x * focalPx * inv
        out[off + 1] = cy - y * focalPx * inv
    }

    /** 화면 좌표 다각형의 부호 있는 넓이 × 2 (화면 y 가 아래 방향이므로 양수 = 화면상 시계방향). */
    fun signedArea2(xy: FloatArray, off: Int, n: Int): Float {
        var s = 0f
        var j = n - 1
        for (i in 0 until n) {
            val xi = xy[off + i * 2]
            val yi = xy[off + i * 2 + 1]
            val xj = xy[off + j * 2]
            val yj = xy[off + j * 2 + 1]
            s += xj * yi - xi * yj
            j = i
        }
        return s
    }

    /** 화면 좌표 다각형 정점 순서를 제자리에서 뒤집는다. */
    fun reverse2(xy: FloatArray, off: Int, n: Int) {
        var i = 0
        var j = n - 1
        while (i < j) {
            val ax = xy[off + i * 2]
            val ay = xy[off + i * 2 + 1]
            xy[off + i * 2] = xy[off + j * 2]
            xy[off + i * 2 + 1] = xy[off + j * 2 + 1]
            xy[off + j * 2] = ax
            xy[off + j * 2 + 1] = ay
            i++; j--
        }
    }

    /**
     * 뒷면 판정. 바깥 법선 (nx,ny,nz) 인 면 위의 점 (px,py,pz) 를 눈 (ex,ey,ez) 이 뒤에서 보면 true.
     */
    fun isBackFacing(nx: Float, ny: Float, nz: Float, px: Float, py: Float, pz: Float, ex: Float, ey: Float, ez: Float): Boolean =
        nx * (ex - px) + ny * (ey - py) + nz * (ez - pz) <= 0f

    /**
     * 카메라 공간 경계 구 (cx,cy,cz, r) 가 시야 절두체와 겹칠 수 있으면 true.
     * [tanHalfX], [tanHalfY] 는 화면 반폭/초점거리, 반높이/초점거리.
     */
    fun sphereInFrustum(cx: Float, cy: Float, cz: Float, r: Float, near: Float, far: Float, tanHalfX: Float, tanHalfY: Float): Boolean {
        if (cz + r < near) return false
        if (cz - r > far) return false
        // 측면 평면: |x| <= z * tan. 평면 법선 정규화 계수 sqrt(1 + tan²).
        if (abs(cx) - cz * tanHalfX > r * sqrt(1f + tanHalfX * tanHalfX)) return false
        if (abs(cy) - cz * tanHalfY > r * sqrt(1f + tanHalfY * tanHalfY)) return false
        return true
    }

    /** keys[idx[i]] 기준 내림차순(먼 것 먼저) 힙 정렬. idx[from, to) 를 제자리에서 정렬한다. 할당 없음. */
    fun sortIndicesByKeyDesc(idx: IntArray, from: Int, to: Int, keys: FloatArray) {
        val n = to - from
        if (n < 2) return
        // 최소 힙을 만들고 최솟값을 뒤로 보내면 내림차순이 된다.
        var start = n / 2 - 1
        while (start >= 0) {
            siftDownMin(idx, from, start, n, keys)
            start--
        }
        var end = n - 1
        while (end > 0) {
            val t = idx[from]; idx[from] = idx[from + end]; idx[from + end] = t
            siftDownMin(idx, from, 0, end, keys)
            end--
        }
    }

    private fun siftDownMin(idx: IntArray, base: Int, start: Int, size: Int, keys: FloatArray) {
        var root = start
        while (true) {
            val child = root * 2 + 1
            if (child >= size) return
            var c = child
            if (child + 1 < size && keys[idx[base + child + 1]] < keys[idx[base + child]]) c = child + 1
            if (keys[idx[base + c]] < keys[idx[base + root]]) {
                val t = idx[base + root]; idx[base + root] = idx[base + c]; idx[base + c] = t
                root = c
            } else {
                return
            }
        }
    }

    /** 1.5 초 카메라 전환용 이징. 0..1 → 0..1, 양 끝 기울기 0. */
    fun easeInOutCubic(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return if (u < 0.5f) 4f * u * u * u else 1f - (-2f * u + 2f).let { it * it * it } / 2f
    }

    /** 방위각 a → b 최단 회전 보간 (도). 결과는 [0, 360). */
    fun lerpAngleDeg(a: Float, b: Float, t: Float): Float {
        var d = (b - a) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return normalizeDeg(a + d * t)
    }

    fun normalizeDeg(a: Float): Float {
        var r = a % 360f
        if (r < 0f) r += 360f
        return r
    }

    /** 수평 벡터 (dx=동, dz=북) 의 방위각(도). */
    fun headingOf(dx: Float, dz: Float): Float = normalizeDeg((atan2(dx.toDouble(), dz.toDouble()) * 180.0 / PI).toFloat())

    fun sinDeg(d: Float): Float = sin(d * DEG).toFloat()
    fun cosDeg(d: Float): Float = cos(d * DEG).toFloat()

    private const val DEG = PI / 180.0
}

/**
 * 재사용 카메라 기저 (할당 없음). lookAt 으로 설정하고 월드 점을 카메라 공간으로 변환한다.
 */
class CameraBasis {
    var ex = 0f; var ey = 0f; var ez = 0f
    var rx = 1f; var ry = 0f; var rz = 0f
    var ux = 0f; var uy = 1f; var uz = 0f
    var fx = 0f; var fy = 0f; var fz = 1f

    fun lookAt(eyeX: Float, eyeY: Float, eyeZ: Float, tx: Float, ty: Float, tz: Float) {
        ex = eyeX; ey = eyeY; ez = eyeZ
        var dx = tx - eyeX
        var dy = ty - eyeY
        var dz = tz - eyeZ
        var len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-6f) { dx = 0f; dy = 0f; dz = 1f; len = 1f }
        fx = dx / len; fy = dy / len; fz = dz / len
        // right = worldUp × forward (이 좌표계에서 북을 볼 때 동이 오른쪽이 된다)
        var cx = fz
        var cz = -fx
        var cl = sqrt(cx * cx + cz * cz)
        if (cl < 1e-5f) { cx = 1f; cz = 0f; cl = 1f } // 수직으로 내려다볼 때: 동쪽을 오른쪽으로
        rx = cx / cl; ry = 0f; rz = cz / cl
        // up = forward × right
        ux = fy * rz - fz * ry
        uy = fz * rx - fx * rz
        uz = fx * ry - fy * rx
    }

    /** 월드 점을 카메라 공간으로 변환해 [out]\[off..off+2] 에 쓴다. */
    fun toCamera(x: Float, y: Float, z: Float, out: FloatArray, off: Int) {
        val dx = x - ex
        val dy = y - ey
        val dz = z - ez
        out[off] = dx * rx + dy * ry + dz * rz
        out[off + 1] = dx * ux + dy * uy + dz * uz
        out[off + 2] = dx * fx + dy * fy + dz * fz
    }

    fun depthOf(x: Float, y: Float, z: Float): Float = (x - ex) * fx + (y - ey) * fy + (z - ez) * fz
}
