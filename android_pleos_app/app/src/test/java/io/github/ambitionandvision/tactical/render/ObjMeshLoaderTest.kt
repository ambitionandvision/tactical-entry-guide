package io.github.ambitionandvision.tactical.render

import io.github.ambitionandvision.tactical.engine.ObjMeshLoader
import io.github.ambitionandvision.tactical.engine.ObjParseException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjMeshLoaderTest {

    private fun load(s: String) = ObjMeshLoader.load(s.byteInputStream(Charsets.UTF_8))

    /** Blender 4.2 기본 Export 형태: 헤더 주석, mtllib, o, v, vn, vt, s, usemtl, f v/vt/vn. */
    @Test
    fun blenderStyleExport() {
        val obj = """
            # Blender 4.2.0
            # www.blender.org
            mtllib custom.mtl
            o Path_03
            v 10.000000 0.000000 -20.000000
            v 12.000000 0.000000 -20.000000
            v 12.000000 1.000000 -22.000000
            v 10.000000 1.000000 -22.000000
            vn -0.0000 1.0000 -0.0000
            vt 0.000000 0.000000
            vt 1.000000 0.000000
            vt 1.000000 1.000000
            vt 0.000000 1.000000
            s 0
            usemtl Material
            f 1/1/1 2/2/1 3/3/1 4/4/1
            o TARGET_COSTCO_BUILDING.001
            v 0.0 0.0 0.0
            v 1.0 0.0 0.0
            v 1.0 0.0 1.0
            vn 0.0 1.0 0.0
            f 5//1 6//1 7//1
        """.trimIndent()
        val g = load(obj)
        assertEquals(listOf("Path_03", "TARGET_COSTCO_BUILDING.001"), g.map { it.name })
        // 파일 Z = -북 → 월드 z = -Z
        assertArrayEquals(floatArrayOf(10f, 0f, 20f, 12f, 0f, 20f, 12f, 1f, 22f, 10f, 1f, 22f), g[0].vertices, 0f)
        assertArrayEquals(intArrayOf(0, 1, 2, 3), g[0].faces)
        assertArrayEquals(intArrayOf(4), g[0].faceSizes)
        // 두 번째 그룹은 자기 정점만, 0 기반 지역 인덱스
        assertEquals(3, g[1].vertexCount)
        assertArrayEquals(intArrayOf(0, 1, 2), g[1].faces)
        assertArrayEquals(floatArrayOf(0f, 0f, -0f, 1f, 0f, -0f, 1f, 0f, -1f), g[1].vertices, 0f)
    }

    @Test
    fun negativeIndicesAndNgonsAndAllFaceForms() {
        val obj = """
            o A
            v 0 0 0
            v 1 0 0
            v 1 0 -1
            v 0 0 -1
            v -1 0 -0.5
            f -5 -4/1 -3/2/3 -2//4 -1
            f 1 2 3
        """.trimIndent()
        val g = load(obj).single()
        assertArrayEquals(intArrayOf(5, 3), g.faceSizes)
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 0, 1, 2), g.faces)
        assertEquals(5, g.vertexCount)
        // (-1, 0, -0.5) → 월드 (-1, 0, 0.5)
        assertEquals(-1f, g.vertices[12], 0f)
        assertEquals(0.5f, g.vertices[14], 0f)
    }

    @Test
    fun groupsWithoutObjectTagSplitByG_andObjectTagWinsOverG() {
        val gOnly = load("v 0 0 0\nv 1 0 0\nv 0 1 0\ng ROAD_SURFACE_300M\nf 1 2 3\ng Pin_Barrier_01\nf 3 2 1\n")
        assertEquals(listOf("ROAD_SURFACE_300M", "Pin_Barrier_01"), gOnly.map { it.name })

        val both = load("o SURROUND_BUILDINGS\ng SURROUND_BUILDINGS_Material\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\ng other\nf 1 3 2\n")
        assertEquals(listOf("SURROUND_BUILDINGS"), both.map { it.name })
        assertEquals(2, both[0].faceCount)
    }

    @Test
    fun repeatedObjectNameMerges_lineContinuation_commentsAndEmptyGroupsDropped() {
        val obj = "o A\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3 # tri\no EMPTY\no A\nv 5 5 5\nf 2 3 \\\n 4\n"
        val g = load(obj)
        assertEquals(listOf("A"), g.map { it.name })
        assertEquals(2, g[0].faceCount)
        assertEquals(4, g[0].vertexCount)
    }

    @Test
    fun facesBeforeAnyGroupGoToDefault() {
        val g = load("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n")
        assertEquals("default", g.single().name)
    }

    @Test
    fun invalidIndexThrowsWithLineNumber() {
        try {
            load("v 0 0 0\nf 1 2 3\n")
            throw AssertionError("예외가 나야 한다")
        } catch (e: ObjParseException) {
            assertEquals(2, e.lineNumber)
        }
        try {
            load("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 0 1 2\n")
            throw AssertionError("예외가 나야 한다")
        } catch (e: ObjParseException) {
            assertTrue(e.message!!.contains("0"))
        }
    }
}
