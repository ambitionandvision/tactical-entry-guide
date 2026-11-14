// Kotlin → Unity 수신기 (TEAM_WORKSPACE 5.7). GameObject 이름은 반드시 "PleosBridge".
//   UnityPlayer.UnitySendMessage("PleosBridge", "OnZone", zoneJson)
//   UnityPlayer.UnitySendMessage("PleosBridge", "OnVehicleState", json)  // 11개 키
//   UnityPlayer.UnitySendMessage("PleosBridge", "OnActive", "true"|"false")
// 장면에 수신기가 없으면 첫 장면 로드 후 자동 생성한다. 에디터·단독 실행에서는 Resources 의 zone 으로 미리보기한다.
using System.Collections.Generic;
using UnityEngine;

namespace PleosTactical
{
    public class PleosBridgeReceiver : MonoBehaviour
    {
        public const string GameObjectName = "PleosBridge";

        [Tooltip("Assets/Models 의 OBJ 프리팹(선택). 없으면 zone 외곽선을 돌출해 디오라마를 만든다.")]
        public GameObject dioramaModelPrefab;
        public TacticalCameraController cameraController;
        public float snapWindowM = 60f;
        public float markerSmoothSec = 0.35f;

        public ZoneDioramaData Zone { get; private set; }
        public VehicleStateData LastState { get; private set; }
        public bool Active { get; private set; } = true;

        private GuideRibbonRenderer mainRibbon;
        private GuideRibbonRenderer evRibbon;
        private Transform dioramaRoot;
        private Transform vehicleMarker;
        private Material vehicleMaterial;
        private Vector3 markerTarget;
        private float markerHeading;
        private float lastProgress;
        private string activeEntryId = ZoneDioramaData.MainEntryId;
        private bool hasZoneFromHost;

        private static readonly Color VehicleColor = new Color(1f, 1f, 1f, 1f);
        private static readonly Color VehicleWarnColor = new Color(1f, 0.25f, 0.2f, 1f);

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        private static void EnsureReceiver()
        {
            if (FindObjectOfType<PleosBridgeReceiver>() != null) return;
            new GameObject(GameObjectName).AddComponent<PleosBridgeReceiver>();
        }

        private void Awake()
        {
            gameObject.name = GameObjectName; // UnitySendMessage 대상 이름 고정
            if (cameraController == null)
            {
                var cam = Camera.main;
                if (cam == null)
                {
                    var go = new GameObject("TacticalCamera") { tag = "MainCamera" };
                    cam = go.AddComponent<Camera>();
                }
                cam.clearFlags = CameraClearFlags.SolidColor;
                cam.backgroundColor = new Color(0.043f, 0.055f, 0.071f);
                cam.nearClipPlane = 0.3f;
                cam.farClipPlane = 2500f;
                cameraController = cam.GetComponent<TacticalCameraController>();
                if (cameraController == null) cameraController = cam.gameObject.AddComponent<TacticalCameraController>();
            }
            if (FindObjectOfType<Light>() == null)
            {
                var l = new GameObject("Sun").AddComponent<Light>();
                l.type = LightType.Directional;
                l.transform.rotation = Quaternion.Euler(50f, 30f, 0f);
            }
            mainRibbon = new GameObject("MainParkingRibbon").AddComponent<GuideRibbonRenderer>();
            mainRibbon.transform.SetParent(transform, false);
            evRibbon = new GameObject("EvChargingRibbon").AddComponent<GuideRibbonRenderer>();
            evRibbon.transform.SetParent(transform, false);
            vehicleMarker = BuildVehicleMarker();
        }

        private void Start()
        {
            if (!hasZoneFromHost)
            {
                var preview = ZoneDioramaData.LoadFromResources();
                if (preview != null) ApplyZone(preview);
            }
        }

        // ---- 5.7 수신 메서드 ----

        public void OnZone(string zoneJson)
        {
            var z = ZoneDioramaData.FromJson(zoneJson);
            if (z == null) return;
            hasZoneFromHost = true;
            ApplyZone(z);
        }

        public void OnVehicleState(string json)
        {
            var s = VehicleStateData.FromJson(json);
            if (s == null) return;
            LastState = s;
            if (!string.IsNullOrEmpty(s.entryId) && s.entryId != activeEntryId) SetActiveEntry(s.entryId);

            var ribbon = ActiveRibbon;
            var raw = new Vector3(s.x, 0f, s.z);
            Vector3 pos = raw;
            float heading = s.headingDeg;
            if (s.attached && ribbon.Samples.Count >= 2)
            {
                // Kotlin 이 이미 스냅했지만 Unity 리본과 정확히 겹치도록 같은 스플라인에 다시 투영한다.
                Vector3 snapped, tangent;
                float progress, lateral;
                float snapDist = Zone != null ? Zone.triggers.snapDistM : 15f;
                if (TacticalSpline.Snap(ribbon.Samples, ribbon.Arcs, raw, lastProgress, snapWindowM, snapDist,
                        out snapped, out tangent, out progress, out lateral) && lateral <= snapDist)
                {
                    pos = snapped;
                    heading = LocalCoordConverter.HeadingOf(tangent);
                    lastProgress = progress;
                }
            }
            markerTarget = pos;
            markerHeading = heading;
            vehicleMaterial.color = s.offRouteWarning ? VehicleWarnColor : VehicleColor;
            mainRibbon.MotionRestricted = s.motionRestricted;
            evRibbon.MotionRestricted = s.motionRestricted;

            cameraController.SetVehicle(pos, heading);
            if (!string.IsNullOrEmpty(s.cameraMode)) cameraController.SetMode(s.IsApproach);
            else cameraController.UpdateModeByDistance(s.distanceToEntryM);
        }

        public void OnActive(string value)
        {
            Active = value == "true";
            var cam = cameraController != null ? cameraController.GetComponent<Camera>() : null;
            if (cam != null) cam.enabled = Active;
            // 비활성(Phase 2 밖, 백그라운드) 동안 렌더 부하를 낮춘다.
            Application.targetFrameRate = Active ? 30 : 5;
        }

        // ---- 디오라마 ----

        private GuideRibbonRenderer ActiveRibbon
        {
            get { return activeEntryId == ZoneDioramaData.EvEntryId ? evRibbon : mainRibbon; }
        }

        private void ApplyZone(ZoneDioramaData z)
        {
            Zone = z;
            cameraController.followViewDistM = z.triggers.followViewDistM;
            cameraController.followViewExitDistM = z.triggers.followViewExitDistM;
            mainRibbon.Build(z.guidePathPoints, z.hazardPins);
            evRibbon.Build(z.evGuidePathPoints, z.hazardPins);
            BuildDiorama(z);
            lastProgress = 0f;
            SetActiveEntry(activeEntryId);
        }

        private void SetActiveEntry(string entryId)
        {
            activeEntryId = entryId == ZoneDioramaData.EvEntryId ? ZoneDioramaData.EvEntryId : ZoneDioramaData.MainEntryId;
            mainRibbon.gameObject.SetActive(activeEntryId == ZoneDioramaData.MainEntryId);
            evRibbon.gameObject.SetActive(activeEntryId == ZoneDioramaData.EvEntryId);
            lastProgress = 0f;
            cameraController.SetOverviewPath(ActiveRibbon.Samples);
        }

        private void BuildDiorama(ZoneDioramaData z)
        {
            if (dioramaRoot != null) Destroy(dioramaRoot.gameObject);
            dioramaRoot = new GameObject("Diorama").transform;
            dioramaRoot.SetParent(transform, false);

            GameObject model = dioramaModelPrefab;
#if UNITY_EDITOR
            if (model == null)
                model = UnityEditor.AssetDatabase.LoadAssetAtPath<GameObject>("Assets/Models/sejong_costco_custom.obj");
#endif
            if (model != null)
            {
                // OBJ 는 X 반전으로 임포트되므로 루트를 Y 180° 회전해 (동, 위, 북) 을 맞춘다(5.1).
                var inst = Instantiate(model, dioramaRoot);
                inst.transform.localPosition = Vector3.zero;
                inst.transform.localRotation = LocalCoordConverter.ObjModelRootRotation;
                // 위치 표식 상자(Path/Pin)는 리본·핀으로 대신 그린다.
                foreach (Transform child in inst.GetComponentsInChildren<Transform>(true))
                {
                    if (child == inst.transform) continue;
                    string n = child.name;
                    if (n.StartsWith("Path_") || n.StartsWith("PathEV_") || n.StartsWith("Pin_")) child.gameObject.SetActive(false);
                }
            }
            else
            {
                BuildExtrudedBuildings(z);
            }
            BuildPins(z);
        }

        private void BuildExtrudedBuildings(ZoneDioramaData z)
        {
            var surroundMat = MaterialFactory.Transparent(new Color(0.49f, 0.54f, 0.6f, 0.32f));
            var targetMat = MaterialFactory.Opaque(new Color(1f, 0.69f, 0.13f, 1f));
            foreach (var b in z.buildings)
            {
                if (b == null || b.footprint == null || b.footprint.Length < 3) continue;
                var go = new GameObject(b.id);
                go.transform.SetParent(dioramaRoot, false);
                go.AddComponent<MeshFilter>().sharedMesh = ExtrudeMesh(b.footprint, b.heightM);
                go.AddComponent<MeshRenderer>().sharedMaterial = b.role == "TARGET" ? targetMat : surroundMat;
            }
        }

        /// <summary>외곽선 돌출. 지붕은 부채꼴 삼각분할(볼록 가정, 오목하면 일부 겹침), 벽은 면마다 독립 정점.</summary>
        private static Mesh ExtrudeMesh(PointXZ[] fp, float h)
        {
            int n = fp.Length;
            var verts = new List<Vector3>();
            var tris = new List<int>();
            // 시계/반시계 판정 (위에서 볼 때 시계방향이 Unity 앞면)
            float area = 0f;
            for (int i = 0; i < n; i++)
            {
                var a = fp[i]; var b = fp[(i + 1) % n];
                area += a.x * b.z - b.x * a.z;
            }
            bool ccw = area > 0f; // x동·z북 평면에서 반시계
            for (int i = 0; i < n; i++) verts.Add(new Vector3(fp[i].x, h, fp[i].z));
            for (int i = 1; i < n - 1; i++)
            {
                if (ccw) { tris.Add(0); tris.Add(i + 1); tris.Add(i); }
                else { tris.Add(0); tris.Add(i); tris.Add(i + 1); }
            }
            for (int i = 0; i < n; i++)
            {
                var a = fp[i]; var b = fp[(i + 1) % n];
                int k = verts.Count;
                verts.Add(new Vector3(a.x, 0f, a.z));
                verts.Add(new Vector3(b.x, 0f, b.z));
                verts.Add(new Vector3(b.x, h, b.z));
                verts.Add(new Vector3(a.x, h, a.z));
                if (ccw) { tris.Add(k); tris.Add(k + 2); tris.Add(k + 1); tris.Add(k); tris.Add(k + 3); tris.Add(k + 2); }
                else { tris.Add(k); tris.Add(k + 1); tris.Add(k + 2); tris.Add(k); tris.Add(k + 2); tris.Add(k + 3); }
            }
            var mesh = new Mesh { name = "Extruded" };
            mesh.SetVertices(verts);
            mesh.SetTriangles(tris, 0);
            mesh.RecalculateNormals();
            mesh.RecalculateBounds();
            return mesh;
        }

        private void BuildPins(ZoneDioramaData z)
        {
            foreach (var p in z.hazardPins)
            {
                if (p == null) continue;
                Color c;
                switch (p.type)
                {
                    case "BARRIER": c = new Color(1f, 0.76f, 0.03f); break;
                    case "NO_TURN": c = new Color(1f, 0.23f, 0.19f); break;
                    default: c = new Color(0.18f, 0.5f, 1f); break;
                }
                var go = new GameObject(p.id);
                go.transform.SetParent(dioramaRoot, false);
                go.transform.localPosition = new Vector3(p.x, 0f, p.z);
                go.AddComponent<MeshFilter>().sharedMesh = PinMesh();
                go.AddComponent<MeshRenderer>().sharedMaterial = MaterialFactory.Opaque(c);
            }
        }

        private static Mesh pinMesh;

        /// <summary>기둥(0.4m×6m) + 7.6m 높이의 정팔면체 머리. 면마다 독립 정점(평면 음영).</summary>
        private static Mesh PinMesh()
        {
            if (pinMesh != null) return pinMesh;
            var verts = new List<Vector3>();
            var tris = new List<int>();
            void Tri(Vector3 a, Vector3 b, Vector3 c)
            {
                int k = verts.Count;
                verts.Add(a); verts.Add(b); verts.Add(c);
                tris.Add(k); tris.Add(k + 1); tris.Add(k + 2);
            }
            float s = 0.2f, h = 6f;
            var b0 = new Vector3(-s, 0, -s); var b1 = new Vector3(s, 0, -s); var b2 = new Vector3(s, 0, s); var b3 = new Vector3(-s, 0, s);
            var t0 = b0 + Vector3.up * h; var t1 = b1 + Vector3.up * h; var t2 = b2 + Vector3.up * h; var t3 = b3 + Vector3.up * h;
            Tri(b0, t0, t1); Tri(b0, t1, b1);
            Tri(b1, t1, t2); Tri(b1, t2, b2);
            Tri(b2, t2, t3); Tri(b2, t3, b3);
            Tri(b3, t3, t0); Tri(b3, t0, b0);
            Tri(t0, t3, t2); Tri(t0, t2, t1);
            var c0 = new Vector3(0, 7.6f, 0);
            float r = 1.6f;
            var top = c0 + Vector3.up * r; var bot = c0 - Vector3.up * r;
            var ring = new[] { c0 + Vector3.right * r, c0 + Vector3.forward * r, c0 + Vector3.left * r, c0 + Vector3.back * r };
            for (int i = 0; i < 4; i++)
            {
                var a = ring[i]; var b = ring[(i + 1) % 4];
                Tri(top, b, a);
                Tri(bot, a, b);
            }
            pinMesh = new Mesh { name = "HazardPin" };
            pinMesh.SetVertices(verts);
            pinMesh.SetTriangles(tris, 0);
            pinMesh.RecalculateNormals();
            pinMesh.RecalculateBounds();
            return pinMesh;
        }

        /// <summary>진행 방향(+Z)으로 뾰족한 화살촉 프리즘.</summary>
        private Transform BuildVehicleMarker()
        {
            var go = new GameObject("VehicleMarker");
            go.transform.SetParent(transform, false);
            var outline = new[] { new Vector3(0f, 0f, 2.8f), new Vector3(1.5f, 0f, -1.9f), new Vector3(0f, 0f, -1.0f), new Vector3(-1.5f, 0f, -1.9f) };
            var verts = new List<Vector3>();
            var tris = new List<int>();
            float y0 = 0.2f, y1 = 1.3f;
            // 윗면 (위에서 볼 때 시계방향)
            int k = verts.Count;
            foreach (var p in outline) verts.Add(p + Vector3.up * y1);
            tris.AddRange(new[] { k, k + 1, k + 2, k, k + 2, k + 3 });
            for (int i = 0; i < 4; i++)
            {
                var a = outline[i]; var b = outline[(i + 1) % 4];
                k = verts.Count;
                verts.Add(a + Vector3.up * y0); verts.Add(b + Vector3.up * y0); verts.Add(b + Vector3.up * y1); verts.Add(a + Vector3.up * y1);
                // 양면(오목 윤곽이라 감기 방향을 단정하지 않는다)
                tris.AddRange(new[] { k, k + 2, k + 1, k, k + 3, k + 2, k, k + 1, k + 2, k, k + 2, k + 3 });
            }
            var mesh = new Mesh { name = "VehicleMarker" };
            mesh.SetVertices(verts);
            mesh.SetTriangles(tris, 0);
            mesh.RecalculateNormals();
            mesh.RecalculateBounds();
            go.AddComponent<MeshFilter>().sharedMesh = mesh;
            vehicleMaterial = MaterialFactory.Transparent(VehicleColor);
            go.AddComponent<MeshRenderer>().sharedMaterial = vehicleMaterial;
            go.SetActive(false);
            return go.transform;
        }

        private void Update()
        {
            if (LastState == null) return;
            if (!vehicleMarker.gameObject.activeSelf)
            {
                vehicleMarker.gameObject.SetActive(true);
                vehicleMarker.SetPositionAndRotation(markerTarget, LocalCoordConverter.HeadingToRotation(markerHeading));
                return;
            }
            // 1Hz 측위 사이를 부드럽게. 60m 이상 점프(슬라이더 이동 등)는 즉시 이동.
            if ((markerTarget - vehicleMarker.position).sqrMagnitude > 60f * 60f)
            {
                vehicleMarker.SetPositionAndRotation(markerTarget, LocalCoordConverter.HeadingToRotation(markerHeading));
                return;
            }
            float a = 1f - Mathf.Exp(-Time.deltaTime / Mathf.Max(0.01f, markerSmoothSec));
            vehicleMarker.SetPositionAndRotation(
                Vector3.Lerp(vehicleMarker.position, markerTarget, a),
                Quaternion.Slerp(vehicleMarker.rotation, LocalCoordConverter.HeadingToRotation(markerHeading), a));
        }
    }
}
