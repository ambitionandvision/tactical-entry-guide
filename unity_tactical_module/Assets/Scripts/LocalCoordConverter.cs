// 로컬 좌표 변환 (TEAM_WORKSPACE 5.1). Python·Kotlin 과 같은 단일 수식:
//   x = (lon - lon0) * 111320 * cos(lat0)   (동, m)
//   z = (lat - lat0) * 110574               (북, m)
// Unity 월드 = (x=동, y=위, z=북). 나침반 방위(0=북, 시계방향) = Unity Y 회전각.
// OBJ 파일은 v <동> <위> <-북> 이고 Unity 가 OBJ 를 X 반전으로 읽으므로, 모델 루트를 Euler(0,180,0) 회전해야 월드와 맞는다.
using System;
using UnityEngine;

namespace PleosTactical
{
    public sealed class LocalCoordConverter
    {
        public const double MetersPerDegLon = 111320.0;
        public const double MetersPerDegLat = 110574.0;
        public const double DefaultOriginLat = 36.469512;
        public const double DefaultOriginLon = 127.270845;

        /// <summary>Assets/Models 의 OBJ 를 배치하는 루트 회전.</summary>
        public static readonly Quaternion ObjModelRootRotation = Quaternion.Euler(0f, 180f, 0f);

        public double OriginLat { get; private set; }
        public double OriginLon { get; private set; }
        private readonly double cosLat0;

        public LocalCoordConverter(double originLat, double originLon)
        {
            OriginLat = originLat;
            OriginLon = originLon;
            cosLat0 = Math.Cos(originLat * Math.PI / 180.0);
        }

        public static LocalCoordConverter FromZone(ZoneDioramaData zone)
        {
            if (zone == null || zone.origin == null) return new LocalCoordConverter(DefaultOriginLat, DefaultOriginLon);
            return new LocalCoordConverter(zone.origin.lat, zone.origin.lon);
        }

        /// <summary>위경도 → 로컬 (x=동, y=북) m.</summary>
        public Vector2 ToLocal(double lat, double lon)
        {
            double x = (lon - OriginLon) * MetersPerDegLon * cosLat0;
            double z = (lat - OriginLat) * MetersPerDegLat;
            return new Vector2((float)x, (float)z);
        }

        /// <summary>로컬 (x=동, z=북) m → 위경도.</summary>
        public void ToLatLon(double x, double z, out double lat, out double lon)
        {
            lat = OriginLat + z / MetersPerDegLat;
            lon = OriginLon + x / (MetersPerDegLon * cosLat0);
        }

        /// <summary>로컬 평면 좌표 → Unity 월드 좌표.</summary>
        public static Vector3 ToWorld(float x, float z, float y = 0f)
        {
            return new Vector3(x, y, z);
        }

        /// <summary>나침반 방위(도) → Unity 회전.</summary>
        public static Quaternion HeadingToRotation(float headingDeg)
        {
            return Quaternion.Euler(0f, headingDeg, 0f);
        }

        /// <summary>수평 방향 벡터 → 나침반 방위(도, 0..360).</summary>
        public static float HeadingOf(Vector3 dir)
        {
            float h = Mathf.Atan2(dir.x, dir.z) * Mathf.Rad2Deg;
            return h < 0f ? h + 360f : h;
        }

        /// <summary>OBJ 파일 정점(v X Y Z) → 월드. (X, Y, -Z).</summary>
        public static Vector3 ObjFileVertexToWorld(float fileX, float fileY, float fileZ)
        {
            return new Vector3(fileX, fileY, -fileZ);
        }
    }
}
