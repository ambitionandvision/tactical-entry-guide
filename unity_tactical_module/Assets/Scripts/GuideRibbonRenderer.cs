// 바닥 네온 유도 리본 + UV 스크롤 화살표 + 회차불가 붉은 반투명 Barrier.
// 스플라인은 TEAM_WORKSPACE 5.4: 제어점 전체를 지나는 centripetal Catmull-Rom (alpha 0.5),
// 양 끝 끝점 반사, 호 길이 2m 재샘플. Kotlin CentripetalSpline.kt·Python 과 같은 식이다.
// motionRestricted 면 화살표 흐름을 멈추고 정적으로 둔다(주의분산 가이드).
using System.Collections.Generic;
using UnityEngine;

namespace PleosTactical
{
    /// <summary>centripetal Catmull-Rom 샘플링과 스냅. 순수 함수(MonoBehaviour 아님).</summary>
    public static class TacticalSpline
    {
        public const float Alpha = 0.5f;
        private const int DensePerSegment = 32;

        /// <summary>세그먼트 i (P[i]→P[i+1]) 의 u∈[0,1] 지점. P 는 (x=동, y=북) 평면 좌표.</summary>
        public static Vector2 EvalSegment(IList<Vector2> p, int i, float u)
        {
            int n = p.Count;
            Vector2 p1 = p[i];
            Vector2 p2 = p[i + 1];
            Vector2 p0 = i > 0 ? p[i - 1] : 2f * p1 - p2;
            Vector2 p3 = i + 2 < n ? p[i + 2] : 2f * p2 - p1;

            float t0 = 0f;
            float t1 = t0 + Knot(p0, p1);
            float t2 = t1 + Knot(p1, p2);
            float t3 = t2 + Knot(p2, p3);
            float t = t1 + (t2 - t1) * u;

            Vector2 a1 = LerpK(p0, p1, t0, t1, t);
            Vector2 a2 = LerpK(p1, p2, t1, t2, t);
            Vector2 a3 = LerpK(p2, p3, t2, t3, t);
            Vector2 b1 = LerpK(a1, a2, t0, t2, t);
            Vector2 b2 = LerpK(a2, a3, t1, t3, t);
            return LerpK(b1, b2, t1, t2, t);
        }

        /// <summary>제어점 → 호 길이 spacing 간격 샘플(월드 x,0,z)과 누적 호 길이. 마지막 샘플은 마지막 제어점.</summary>
        public static void Sample(IList<Vector2> controls, float spacing, List<Vector3> outPoints, List<float> outArcs)
        {
            outPoints.Clear();
            outArcs.Clear();
            int n = controls.Count;
            if (n == 0) return;
            if (n == 1)
            {
                outPoints.Add(new Vector3(controls[0].x, 0f, controls[0].y));
                outArcs.Add(0f);
                return;
            }
            var dense = new List<Vector2>((n - 1) * DensePerSegment + 1);
            for (int i = 0; i < n - 1; i++)
                for (int j = 0; j < DensePerSegment; j++)
                    dense.Add(EvalSegment(controls, i, (float)j / DensePerSegment));
            dense.Add(controls[n - 1]);
            var ds = new float[dense.Count];
            for (int i = 1; i < dense.Count; i++) ds[i] = ds[i - 1] + Vector2.Distance(dense[i - 1], dense[i]);
            float total = ds[ds.Length - 1];
            float step = Mathf.Max(spacing, 0.01f);
            int count = (int)(total / step) + 1 + (total % step > 1e-3f ? 1 : 0);
            int seg = 0;
            for (int m = 0; m < count; m++)
            {
                float a = Mathf.Min(m * step, total);
                while (seg < dense.Count - 2 && ds[seg + 1] < a) seg++;
                float l = ds[seg + 1] - ds[seg];
                float t = l > 1e-6f ? Mathf.Clamp01((a - ds[seg]) / l) : 0f;
                Vector2 q = Vector2.Lerp(dense[seg], dense[seg + 1], t);
                outPoints.Add(new Vector3(q.x, 0f, q.y));
                outArcs.Add(a);
            }
            Vector2 last = controls[n - 1];
            outPoints[count - 1] = new Vector3(last.x, 0f, last.y);
            outArcs[count - 1] = total;
        }

        public static List<Vector2> ToPlane(GuidePointData[] pts)
        {
            var list = new List<Vector2>(pts == null ? 0 : pts.Length);
            if (pts != null) foreach (var p in pts) list.Add(new Vector2(p.x, p.z));
            return list;
        }

        /// <summary>
        /// 점 p(월드)를 샘플 폴리라인에 투영. 직전 진행거리 ±window 를 먼저 찾고(역주행 점프 방지),
        /// 그 안의 최근접이 snapDist 보다 멀면 전체를 찾는다.
        /// </summary>
        public static bool Snap(List<Vector3> pts, List<float> arcs, Vector3 p, float lastProgress, float window, float snapDist,
                                out Vector3 snapped, out Vector3 tangent, out float progress, out float lateral)
        {
            snapped = p; tangent = Vector3.forward; progress = 0f; lateral = float.MaxValue;
            if (pts.Count < 2) return false;
            bool found = SnapRange(pts, arcs, p, lastProgress - window, lastProgress + window, ref snapped, ref tangent, ref progress, ref lateral);
            if (!found || lateral > snapDist)
                SnapRange(pts, arcs, p, float.MinValue, float.MaxValue, ref snapped, ref tangent, ref progress, ref lateral);
            return true;
        }

        private static bool SnapRange(List<Vector3> pts, List<float> arcs, Vector3 p, float fromArc, float toArc,
                                      ref Vector3 snapped, ref Vector3 tangent, ref float progress, ref float lateral)
        {
            bool found = false;
            float best = float.MaxValue;
            for (int i = 0; i < pts.Count - 1; i++)
            {
                if (arcs[i + 1] < fromArc || arcs[i] > toArc) continue;
                Vector3 a = pts[i];
                Vector3 d = pts[i + 1] - a;
                d.y = 0f;
                float l2 = d.sqrMagnitude;
                float t = l2 > 1e-9f ? Mathf.Clamp01(((p.x - a.x) * d.x + (p.z - a.z) * d.z) / l2) : 0f;
                Vector3 q = new Vector3(a.x + d.x * t, 0f, a.z + d.z * t);
                float dist = (q.x - p.x) * (q.x - p.x) + (q.z - p.z) * (q.z - p.z);
                if (dist < best)
                {
                    best = dist;
                    found = true;
                    snapped = q;
                    tangent = l2 > 1e-9f ? d / Mathf.Sqrt(l2) : Vector3.forward;
                    progress = arcs[i] + (arcs[i + 1] - arcs[i]) * t;
                }
            }
            if (found) lateral = Mathf.Sqrt(best);
            return found;
        }

        private static float Knot(Vector2 a, Vector2 b)
        {
            return Mathf.Max(Mathf.Pow((b - a).sqrMagnitude, Alpha * 0.5f), 1e-4f);
        }

        private static Vector2 LerpK(Vector2 a, Vector2 b, float ta, float tb, float t)
        {
            float w = tb - ta;
            return w < 1e-9f ? a : a * ((tb - t) / w) + b * ((t - ta) / w);
        }
    }

    /// <summary>한 입구의 유도선 리본과 그 경로의 회차불가 Barrier.</summary>
    public class GuideRibbonRenderer : MonoBehaviour
    {
        public float widthM = 3.6f;
        public float arrowSpacingM = 10f;
        public float flowSpeedMps = 6f;
        public float heightOffsetM = 0.15f;
        public Color ribbonColor = new Color(0f, 0.9f, 1f, 0.8f);
        public Color barrierColor = new Color(1f, 0.23f, 0.19f, 0.35f);
        public float barrierLengthM = 24f;
        public float barrierHeightM = 3f;

        public readonly List<Vector3> Samples = new List<Vector3>();
        public readonly List<float> Arcs = new List<float>();
        public float LengthM { get { return Arcs.Count == 0 ? 0f : Arcs[Arcs.Count - 1]; } }

        /// <summary>true 면 화살표 흐름 정지(정적 화살표).</summary>
        public bool MotionRestricted { get; set; }

        private Material ribbonMaterial;
        private Material barrierMaterial;
        private GameObject ribbonObject;
        private readonly List<GameObject> barriers = new List<GameObject>();
        private float uvOffset;

        public void Build(GuidePointData[] controlPoints, HazardPinData[] pins)
        {
            Clear();
            TacticalSpline.Sample(TacticalSpline.ToPlane(controlPoints), 2f, Samples, Arcs);
            if (Samples.Count < 2) return;

            if (ribbonMaterial == null)
            {
                ribbonMaterial = MaterialFactory.Transparent(Color.white);
                ribbonMaterial.mainTexture = CreateArrowTexture(ribbonColor);
            }
            ribbonObject = new GameObject("GuideRibbon");
            ribbonObject.transform.SetParent(transform, false);
            ribbonObject.AddComponent<MeshFilter>().sharedMesh = BuildRibbonMesh();
            ribbonObject.AddComponent<MeshRenderer>().sharedMaterial = ribbonMaterial;

            if (pins == null) return;
            if (barrierMaterial == null) barrierMaterial = MaterialFactory.Transparent(barrierColor);
            foreach (var pin in pins)
            {
                if (pin == null || pin.type != "NO_TURN") continue;
                Vector3 snapped, tangent;
                float progress, lateral;
                TacticalSpline.Snap(Samples, Arcs, new Vector3(pin.x, 0f, pin.z), 0f, float.MaxValue, float.MaxValue,
                    out snapped, out tangent, out progress, out lateral);
                var go = new GameObject("Barrier_" + pin.id);
                go.transform.SetParent(transform, false);
                go.AddComponent<MeshFilter>().sharedMesh = BuildWallMesh(new Vector3(pin.x, 0f, pin.z), tangent);
                go.AddComponent<MeshRenderer>().sharedMaterial = barrierMaterial;
                barriers.Add(go);
            }
        }

        public void Clear()
        {
            Samples.Clear();
            Arcs.Clear();
            if (ribbonObject != null) Destroy(ribbonObject);
            ribbonObject = null;
            foreach (var b in barriers) if (b != null) Destroy(b);
            barriers.Clear();
        }

        private void Update()
        {
            if (ribbonMaterial == null || MotionRestricted) return;
            // v 좌표가 진행 방향 호 길이/간격이므로 오프셋을 줄이면 화살표가 진행 방향으로 흐른다.
            uvOffset = Mathf.Repeat(uvOffset - flowSpeedMps / arrowSpacingM * Time.deltaTime, 1f);
            ribbonMaterial.mainTextureOffset = new Vector2(0f, uvOffset);
        }

        private Mesh BuildRibbonMesh()
        {
            int n = Samples.Count;
            var verts = new Vector3[n * 2];
            var uvs = new Vector2[n * 2];
            var tris = new int[(n - 1) * 6];
            float half = widthM / 2f;
            for (int i = 0; i < n; i++)
            {
                Vector3 a = Samples[Mathf.Max(0, i - 1)];
                Vector3 b = Samples[Mathf.Min(n - 1, i + 1)];
                Vector3 t = b - a;
                t.y = 0f;
                t = t.sqrMagnitude > 1e-9f ? t.normalized : Vector3.forward;
                Vector3 right = new Vector3(t.z, 0f, -t.x); // 진행 방향 오른쪽
                Vector3 c = Samples[i] + Vector3.up * heightOffsetM;
                verts[i * 2] = c - right * half;
                verts[i * 2 + 1] = c + right * half;
                float v = Arcs[i] / arrowSpacingM;
                uvs[i * 2] = new Vector2(0f, v);
                uvs[i * 2 + 1] = new Vector2(1f, v);
            }
            for (int i = 0; i < n - 1; i++)
            {
                int k = i * 6;
                int l0 = i * 2, r0 = i * 2 + 1, l1 = i * 2 + 2, r1 = i * 2 + 3;
                // 위에서 볼 때 시계방향(Unity 앞면)
                tris[k] = l0; tris[k + 1] = l1; tris[k + 2] = r0;
                tris[k + 3] = r0; tris[k + 4] = l1; tris[k + 5] = r1;
            }
            var mesh = new Mesh { name = "GuideRibbonMesh" };
            if (verts.Length > 65000) mesh.indexFormat = UnityEngine.Rendering.IndexFormat.UInt32;
            mesh.vertices = verts;
            mesh.uv = uvs;
            mesh.triangles = tris;
            mesh.RecalculateBounds();
            return mesh;
        }

        /// <summary>진행 방향을 따라 세운 양면 벽(두 감기 방향을 모두 넣는다).</summary>
        private Mesh BuildWallMesh(Vector3 center, Vector3 tangent)
        {
            Vector3 h = tangent * (barrierLengthM / 2f);
            Vector3 up = Vector3.up * barrierHeightM;
            var verts = new[] { center - h, center + h, center + h + up, center - h + up };
            var tris = new[] { 0, 2, 1, 0, 3, 2, 0, 1, 2, 0, 2, 3 };
            var mesh = new Mesh { name = "NoTurnBarrierMesh", vertices = verts, triangles = tris };
            mesh.RecalculateBounds();
            return mesh;
        }

        /// <summary>리본 색 바탕에 흰 쉐브론(진행 방향 +v) 하나가 있는 반복 텍스처.</summary>
        private static Texture2D CreateArrowTexture(Color baseColor)
        {
            const int size = 64;
            var tex = new Texture2D(size, size, TextureFormat.RGBA32, false) { wrapMode = TextureWrapMode.Repeat, name = "RibbonArrow" };
            var px = new Color[size * size];
            for (int y = 0; y < size; y++)
            {
                for (int x = 0; x < size; x++)
                {
                    float u = (x + 0.5f) / size - 0.5f;   // 가로 -0.5..0.5
                    float v = (y + 0.5f) / size;          // 진행 0..1
                    // 쉐브론: 꼭짓점 v=0.62, 팔 기울기 0.9, 두께 0.12, 가로 폭 ±0.32
                    float d = v - (0.62f - Mathf.Abs(u) * 0.9f);
                    bool arrow = Mathf.Abs(u) < 0.32f && d < 0f && d > -0.12f;
                    px[y * size + x] = arrow ? new Color(0.92f, 1f, 1f, 1f) : baseColor;
                }
            }
            tex.SetPixels(px);
            tex.Apply(false, false);
            return tex;
        }
    }

    /// <summary>항상 포함되는 내장 셰이더만 쓴다(외부 패키지 없음).</summary>
    public static class MaterialFactory
    {
        /// <summary>반투명·양면·무조명 (Sprites/Default: Cull Off, ZWrite Off). 투명 큐는 객체 단위 원→근 정렬된다.</summary>
        public static Material Transparent(Color color)
        {
            var shader = Shader.Find("Sprites/Default");
            if (shader == null) shader = Shader.Find("Unlit/Transparent");
            return new Material(shader) { color = color };
        }

        /// <summary>불투명 조명 재질(본관 하이라이트 등).</summary>
        public static Material Opaque(Color color)
        {
            var shader = Shader.Find("Legacy Shaders/Diffuse");
            if (shader == null) shader = Shader.Find("Standard");
            return new Material(shader) { color = color };
        }
    }
}
