// sejong_costco_zone.json (TEAM_WORKSPACE 5.2) 과 OnVehicleState 본문(5.7) 의 JsonUtility 모델.
// 키 이름은 Kotlin TacticalModels.kt 와 같다. JsonUtility 는 중첩 배열을 못 읽으므로 점은 항상 {x,z} 객체다.
using System;
using UnityEngine;

namespace PleosTactical
{
    [Serializable]
    public class LatLonData
    {
        public double lat;
        public double lon;
    }

    [Serializable]
    public class PointXZ
    {
        public float x;
        public float z;
    }

    [Serializable]
    public class EntryData
    {
        public string id;
        public string label;
        public string[] fuelKinds;
        public PointXZ position;
        public string recommendedLane;
    }

    [Serializable]
    public class BuildingData
    {
        public string id;
        /// <summary>"TARGET" 또는 "SURROUND"</summary>
        public string role;
        public float heightM;
        public PointXZ[] footprint;
    }

    [Serializable]
    public class RoadData
    {
        public string id;
        public float widthM;
        public int lanes;
        public PointXZ[] centerline;
    }

    [Serializable]
    public class GuidePointData
    {
        public string id;
        public float x;
        public float z;
    }

    [Serializable]
    public class HazardPinData
    {
        public string id;
        /// <summary>"BARRIER", "NO_TURN", "WAIT_LANE"</summary>
        public string type;
        public float x;
        public float z;
        public string label;
        /// <summary>현장 실측 확인 여부. 저작(가정) 배치는 false.</summary>
        public bool fieldVerified;
    }

    [Serializable]
    public class StaticNoticesData
    {
        /// <summary>정적 안내 배지 전용. 차량 전고와 비교하지 않는다.</summary>
        public float heightLimitM;
        public string barrierNote;
    }

    [Serializable]
    public class TriggerData
    {
        public float tacticalDistM = 300f;
        public float tacticalSlowDistM = 350f;
        public float tacticalSpeedKmh = 30f;
        public float tacticalExitDistM = 400f;
        public float followViewDistM = 100f;
        public float followViewExitDistM = 115f;
        public float arrivedDistM = 15f;
        public float arrivedStopDistM = 30f;
        public float arrivedStopSpeedKmh = 3f;
        public float arrivedStopHoldSec = 3f;
        public float snapDistM = 15f;
        public float offRouteWarnDistM = 20f;
    }

    [Serializable]
    public class ZoneDioramaData
    {
        public const string MainEntryId = "MAIN_PARKING";
        public const string EvEntryId = "EV_CHARGING_ENTRY";
        public const string DefaultResourceName = "sejong_costco_zone";

        public int schemaVersion;
        public string zoneId;
        public string displayName;
        public LatLonData origin;
        public float radiusM;
        public EntryData[] entries;
        public BuildingData[] buildings;
        public RoadData[] roads;
        public GuidePointData[] guidePathPoints;
        public GuidePointData[] evGuidePathPoints;
        public HazardPinData[] hazardPins;
        public StaticNoticesData staticNotices;
        public TriggerData triggers;

        /// <summary>JSON → 모델. 실패하면 null (호출측이 기존 상태 유지).</summary>
        public static ZoneDioramaData FromJson(string json)
        {
            if (string.IsNullOrEmpty(json)) return null;
            try
            {
                var z = JsonUtility.FromJson<ZoneDioramaData>(json);
                if (z == null) return null;
                if (z.entries == null) z.entries = new EntryData[0];
                if (z.buildings == null) z.buildings = new BuildingData[0];
                if (z.roads == null) z.roads = new RoadData[0];
                if (z.guidePathPoints == null) z.guidePathPoints = new GuidePointData[0];
                if (z.evGuidePathPoints == null) z.evGuidePathPoints = new GuidePointData[0];
                if (z.hazardPins == null) z.hazardPins = new HazardPinData[0];
                if (z.triggers == null) z.triggers = new TriggerData();
                if (z.origin == null) z.origin = new LatLonData { lat = LocalCoordConverter.DefaultOriginLat, lon = LocalCoordConverter.DefaultOriginLon };
                return z;
            }
            catch (Exception e)
            {
                Debug.LogWarning("[PleosBridge] zone JSON 해석 실패: " + e.Message);
                return null;
            }
        }

        /// <summary>Assets/Resources/sejong_costco_zone.json (에디터 미리보기·단독 실행용).</summary>
        public static ZoneDioramaData LoadFromResources(string name = DefaultResourceName)
        {
            var asset = Resources.Load<TextAsset>(name);
            return asset == null ? null : FromJson(asset.text);
        }

        public EntryData FindEntry(string id)
        {
            foreach (var e in entries)
                if (e != null && e.id == id) return e;
            return entries.Length > 0 ? entries[0] : null;
        }

        /// <summary>입구별 유도선 제어점. EV 입구는 evGuidePathPoints, 나머지는 guidePathPoints.</summary>
        public GuidePointData[] PathFor(string entryId)
        {
            return entryId == EvEntryId && evGuidePathPoints.Length > 0 ? evGuidePathPoints : guidePathPoints;
        }
    }

    /// <summary>OnVehicleState 본문. Kotlin UnityBridgeProtocol.vehicleStateJson 과 같은 11개 키.</summary>
    [Serializable]
    public class VehicleStateData
    {
        public float x;
        public float z;
        /// <summary>나침반 방위(0=북, 90=동). Unity Y 회전각과 같다.</summary>
        public float headingDeg;
        public float speedKmh;
        public string phase;
        /// <summary>"OVERVIEW" 또는 "APPROACH"</summary>
        public string cameraMode;
        public bool attached;
        public bool offRouteWarning;
        public float distanceToEntryM;
        public string entryId;
        public bool motionRestricted;

        public static VehicleStateData FromJson(string json)
        {
            if (string.IsNullOrEmpty(json)) return null;
            try { return JsonUtility.FromJson<VehicleStateData>(json); }
            catch (Exception e)
            {
                Debug.LogWarning("[PleosBridge] 차량 상태 JSON 해석 실패: " + e.Message);
                return null;
            }
        }

        public bool IsApproach { get { return cameraMode == "APPROACH"; } }
    }
}
