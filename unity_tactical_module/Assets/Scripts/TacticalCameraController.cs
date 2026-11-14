// OVERVIEW(45° 쿼터뷰, 경로 전체) ↔ APPROACH(차량 후방 팔로우) 카메라.
// 전환 기준: 입구까지 100m 이하면 APPROACH, 115m 초과면 OVERVIEW (히스테리시스).
// Kotlin 이 cameraMode 를 보내면 그 값을 우선한다. 모드가 바뀌면 1.5초 동안 Lerp(위치)/Slerp(회전) 이징 전환.
using UnityEngine;

namespace PleosTactical
{
    [RequireComponent(typeof(Camera))]
    public class TacticalCameraController : MonoBehaviour
    {
        public float followViewDistM = 100f;
        public float followViewExitDistM = 115f;
        public float transitionSec = 1.5f;
        public float overviewPitchDeg = 45f;
        public float followBackM = 26f;
        public float followHeightM = 13f;
        public float followAheadM = 30f;
        /// <summary>전환이 끝난 뒤 차량 추종 지수 평활 시간상수(초).</summary>
        public float followSmoothSec = 0.35f;

        public bool IsApproach { get; private set; }
        public bool IsTransitioning { get { return transitionT < 1f; } }

        private Camera cam;
        private Vector3 overviewCenter;
        private float overviewRadius = 300f;
        private float overviewYawDeg;
        private Vector3 vehiclePos;
        private float vehicleHeadingDeg;
        private bool hasVehicle;

        private Vector3 fromPos;
        private Quaternion fromRot;
        private float transitionT = 1f;
        private bool initialized;

        private void Awake()
        {
            cam = GetComponent<Camera>();
        }

        /// <summary>경로 샘플 전체가 들어오도록 OVERVIEW 대상(경계 원, 시작→끝 방위)을 정한다.</summary>
        public void SetOverviewPath(System.Collections.Generic.IList<Vector3> samples)
        {
            if (samples == null || samples.Count == 0) return;
            var min = samples[0];
            var max = samples[0];
            foreach (var p in samples) { min = Vector3.Min(min, p); max = Vector3.Max(max, p); }
            overviewCenter = new Vector3((min.x + max.x) / 2f, 0f, (min.z + max.z) / 2f);
            float r = 0f;
            foreach (var p in samples) r = Mathf.Max(r, Vector3.Distance(new Vector3(p.x, 0f, p.z), overviewCenter));
            overviewRadius = Mathf.Max(20f, r + 15f);
            overviewYawDeg = LocalCoordConverter.HeadingOf(samples[samples.Count - 1] - samples[0]);
        }

        public void SetVehicle(Vector3 position, float headingDeg)
        {
            vehiclePos = position;
            vehicleHeadingDeg = headingDeg;
            hasVehicle = true;
        }

        /// <summary>입구까지 거리로 모드를 정한다(100/115m 히스테리시스).</summary>
        public void UpdateModeByDistance(float distanceToEntryM)
        {
            if (!IsApproach && distanceToEntryM <= followViewDistM) SetMode(true);
            else if (IsApproach && distanceToEntryM > followViewExitDistM) SetMode(false);
        }

        public void SetMode(bool approach)
        {
            if (approach == IsApproach) return;
            IsApproach = approach;
            fromPos = transform.position;
            fromRot = transform.rotation;
            transitionT = 0f;
        }

        private void LateUpdate()
        {
            Vector3 pos;
            Quaternion rot;
            DesiredPose(out pos, out rot);
            if (!initialized)
            {
                initialized = true;
                transform.SetPositionAndRotation(pos, rot);
                return;
            }
            if (transitionT < 1f)
            {
                transitionT = Mathf.Min(1f, transitionT + Time.deltaTime / Mathf.Max(0.01f, transitionSec));
                float e = EaseInOutCubic(transitionT);
                transform.SetPositionAndRotation(Vector3.Lerp(fromPos, pos, e), Quaternion.Slerp(fromRot, rot, e));
            }
            else
            {
                float a = 1f - Mathf.Exp(-Time.deltaTime / Mathf.Max(0.01f, followSmoothSec));
                transform.SetPositionAndRotation(Vector3.Lerp(transform.position, pos, a), Quaternion.Slerp(transform.rotation, rot, a));
            }
        }

        private void DesiredPose(out Vector3 pos, out Quaternion rot)
        {
            if (IsApproach && hasVehicle)
            {
                // 세로 화면일수록 높이 올리고 가까이 본다(하늘 영역 축소).
                float portrait = cam != null ? Mathf.Clamp01(1f / Mathf.Max(0.1f, cam.aspect) - 1f) : 0f;
                Vector3 fwd = LocalCoordConverter.HeadingToRotation(vehicleHeadingDeg) * Vector3.forward;
                pos = vehiclePos - fwd * (followBackM - 4f * portrait) + Vector3.up * (followHeightM + 11f * portrait);
                Vector3 target = vehiclePos + fwd * (followAheadM - 12f * portrait);
                rot = Quaternion.LookRotation(target - pos, Vector3.up);
                return;
            }
            // 경계 구가 화면 짧은 변 시야각에 들어오는 거리
            float vFov = cam != null ? cam.fieldOfView : 60f;
            float aspect = cam != null ? cam.aspect : 1f;
            float hFov = 2f * Mathf.Atan(Mathf.Tan(vFov * Mathf.Deg2Rad / 2f) * aspect) * Mathf.Rad2Deg;
            float half = Mathf.Min(vFov, hFov) / 2f;
            float dist = overviewRadius / Mathf.Sin(half * Mathf.Deg2Rad) * 1.05f;
            Quaternion look = Quaternion.Euler(overviewPitchDeg, overviewYawDeg, 0f);
            pos = overviewCenter - look * Vector3.forward * dist;
            rot = look;
        }

        public static float EaseInOutCubic(float t)
        {
            t = Mathf.Clamp01(t);
            return t < 0.5f ? 4f * t * t * t : 1f - Mathf.Pow(-2f * t + 2f, 3f) / 2f;
        }
    }
}
