package io.github.ambitionandvision.tactical.engine

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * OBJ 한 오브젝트(`o`, 또는 `o` 가 없는 파일의 `g`).
 *
 * - [vertices]: x,y,z 반복. **월드 좌표**(x=동, y=위, z=북). 파일의 `v X Y Z` 는 (X, Y, -Z) 로 읽는다 (TEAM_WORKSPACE 5.1).
 * - [faces]: 이 그룹의 [vertices] 에 대한 0 기반 정점 인덱스를 면 순서대로 이어 붙인 것.
 * - [faceSizes]: 면별 정점 수(3 이상, n각형 그대로 유지).
 */
data class ObjGroup(val name: String, val vertices: FloatArray, val faces: IntArray, val faceSizes: IntArray) {
    val vertexCount: Int get() = vertices.size / 3
    val faceCount: Int get() = faceSizes.size

    override fun equals(other: Any?): Boolean =
        other is ObjGroup && name == other.name && vertices.contentEquals(other.vertices) &&
            faces.contentEquals(other.faces) && faceSizes.contentEquals(other.faceSizes)

    override fun hashCode(): Int =
        ((name.hashCode() * 31 + vertices.contentHashCode()) * 31 + faces.contentHashCode()) * 31 + faceSizes.contentHashCode()
}

/**
 * Wavefront OBJ 로더 (Blender 4.2 기본 Export 산출물 포함).
 *
 * 지원: `v`, `f`(v, v/vt, v/vt/vn, v//vn, 음수 인덱스, n각형), `o`, `g`, `\` 줄 이음, `#` 주석.
 * 무시: `vt`, `vn`, `vp`, `l`, `p`, `s`, `usemtl`, `mtllib` 등.
 *
 * 그룹 규칙: 파일에 `o` 가 하나라도 있으면 `o` 가 그룹 경계이고 `g` 는 무시한다
 * (Blender 는 `o` 아래에 재질별 `g` 를 쓰기도 한다). `o` 가 없으면 `g` 가 경계다.
 * 같은 이름이 다시 나오면 같은 그룹에 이어 붙인다. 면이 없는 그룹은 결과에서 빠진다.
 */
object ObjMeshLoader {

    fun load(stream: InputStream): List<ObjGroup> {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        val lines = ArrayList<String>()
        var hasObjectTag = false
        val pending = StringBuilder()
        reader.useLines { seq ->
            for (raw in seq) {
                if (raw.endsWith("\\")) {
                    pending.append(raw, 0, raw.length - 1).append(' ')
                    continue
                }
                val line = if (pending.isEmpty()) raw else pending.append(raw).toString().also { pending.setLength(0) }
                val t = line.trimStart()
                if (t.startsWith("o ") || t == "o" || t.startsWith("o\t")) hasObjectTag = true
                lines.add(line)
            }
        }
        if (pending.isNotEmpty()) lines.add(pending.toString())
        return parse(lines, hasObjectTag)
    }

    private class Builder(val name: String) {
        val globalToLocal = HashMap<Int, Int>()
        val vertices = FloatList()
        val faces = IntList()
        val faceSizes = IntList()
    }

    private fun parse(lines: List<String>, groupByObject: Boolean): List<ObjGroup> {
        val pos = FloatList() // 파일 전체 정점(월드 좌표)
        val builders = LinkedHashMap<String, Builder>()
        var current: Builder? = null
        val faceTmp = IntList()

        fun select(name: String) {
            val n = name.ifBlank { "default" }
            current = builders.getOrPut(n) { Builder(n) }
        }

        for ((lineNo, line) in lines.withIndex()) {
            val tokens = tokenize(line)
            if (tokens.isEmpty()) continue
            when (tokens[0]) {
                "v" -> {
                    if (tokens.size < 4) throw ObjParseException(lineNo + 1, "정점 좌표가 3개 미만")
                    val x = parseFloat(tokens[1], lineNo)
                    val y = parseFloat(tokens[2], lineNo)
                    val z = parseFloat(tokens[3], lineNo)
                    pos.add(x); pos.add(y); pos.add(-z) // 파일 Z = -북
                }
                "o" -> if (groupByObject) select(tokens.drop(1).joinToString(" "))
                "g" -> if (!groupByObject) select(tokens.drop(1).joinToString(" "))
                "f" -> {
                    if (tokens.size < 4) throw ObjParseException(lineNo + 1, "면 정점이 3개 미만")
                    val b = current ?: Builder("default").also { builders["default"] = it; current = it }
                    val vCount = pos.size / 3
                    faceTmp.clear()
                    for (i in 1 until tokens.size) {
                        val tok = tokens[i]
                        val slash = tok.indexOf('/')
                        val idxStr = if (slash < 0) tok else tok.substring(0, slash)
                        val raw = idxStr.toIntOrNull() ?: throw ObjParseException(lineNo + 1, "면 인덱스 해석 실패: $tok")
                        val global = when {
                            raw > 0 -> raw - 1
                            raw < 0 -> vCount + raw
                            else -> throw ObjParseException(lineNo + 1, "OBJ 인덱스 0 은 허용되지 않음")
                        }
                        if (global < 0 || global >= vCount) throw ObjParseException(lineNo + 1, "정점 인덱스 범위 밖: $tok")
                        val local = b.globalToLocal.getOrPut(global) {
                            b.vertices.add(pos[global * 3]); b.vertices.add(pos[global * 3 + 1]); b.vertices.add(pos[global * 3 + 2])
                            b.globalToLocal.size
                        }
                        faceTmp.add(local)
                    }
                    for (i in 0 until faceTmp.size) b.faces.add(faceTmp[i])
                    b.faceSizes.add(faceTmp.size)
                }
                else -> Unit
            }
        }
        return builders.values.filter { it.faceSizes.size > 0 }
            .map { ObjGroup(it.name, it.vertices.toArray(), it.faces.toArray(), it.faceSizes.toArray()) }
    }

    private fun tokenize(line: String): List<String> {
        val hash = line.indexOf('#')
        val body = if (hash >= 0) line.substring(0, hash) else line
        return body.trim().split(WS).filter { it.isNotEmpty() }
    }

    private fun parseFloat(s: String, lineNo: Int): Float =
        s.toFloatOrNull() ?: throw ObjParseException(lineNo + 1, "숫자 해석 실패: $s")

    private val WS = Regex("\\s+")
}

class ObjParseException(val lineNumber: Int, message: String) : RuntimeException("OBJ $lineNumber 행: $message")

private class FloatList {
    private var a = FloatArray(1024)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    operator fun get(i: Int): Float = a[i]
    fun toArray(): FloatArray = a.copyOf(size)
}

private class IntList {
    private var a = IntArray(256)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    operator fun get(i: Int): Int = a[i]
    fun clear() { size = 0 }
    fun toArray(): IntArray = a.copyOf(size)
}
