# 기술 규격 (SPEC)

앱, GIS 파이프라인, Unity 모듈이 함께 따르는 좌표·데이터·모듈 규격이다. 소스 코드 주석의 `TEAM_WORKSPACE N.N` 은 이 문서의 같은 번호 절을 가리킨다 (예: `TEAM_WORKSPACE 5.4` → 아래 5.4 스플라인).

수치는 2026-11-14 기준이다. 대상 시설 이름은 시설명 표기일 뿐 제휴·후원과 무관하다.

## 3.2 Pleos Connect SDK (실제 AAR·공식 문서 확인)

저장소 `https://nexus-playground.pleos.ai/repository/maven-releases/` (인증 불필요).

```kotlin
implementation("ai.pleos.playground:Vehicle:2.3.2")
implementation("ai.pleos.playground:NaviHelper:3.0.5")
implementation("ai.pleos.playground:FusedLocation:1.1.3")
```

| API | 실제 시그니처 |
|---|---|
| Vehicle | `Vehicle(context)`, `initialize()`, `release()`, `getEvBattery()`, `getFuel()`, `getCarInfo()` |
| EvBattery | `checkEvBatteryStateOfChargeCapabilities(onComplete: (CapabilityConfig)->Unit, onFailed: (Exception)->Unit)`, `getEvBatteryStateOfCharge(onComplete: (Int?)->Unit, onFailed)` |
| Fuel | `checkFuelPercentCapabilities(onComplete, onFailed)`, `getFuelPercent(onComplete, onFailed)` |
| CarInfo | **`getVINInfo`, `getCRNInfo` 와 capability 조회뿐. 연료 타입·전고 API 없음.** |
| FusedLocation | `FusedLocation(context)`, `isSupported()`, `initialize()`, `release()`, `registerFusedLocationCallback(l)`, `unregisterFusedLocationCallback(l)`, `getLastKnownLocation(l)`, `addProximityAlert(ProximityAlertRequest, ProximityAlertListener)`, `removeProximityAlert(id)`, `showLocationAccessBlockedPopup()` |
| FusedLocationListener | `onChanged(location: LocationData?)`, `onError(exception: Exception)` |
| ProximityAlertListener | `onRegistered(alertId)`, `onTransition(event: ProximityAlertEvent)`, `onError(e)` |
| LocationData | `latitude, longitude, altitude: Double`, `heading, confidence: Float`, `timestampMillis: Long`. **속도 필드 없음.** |
| ProximityAlertRequest | `ProximityAlertRequest(latitude, longitude, radiusMeters: Float, transitionTypes: Set<ProximityTransition>)`, `ProximityTransition.ENTER/EXIT` |
| NaviHelper | `NaviHelper(context)`, `initialize()`, `initialize(onSuccess, onError)`, `release()`, `addListener`, `removeListener`, `getDestinationInfo()`, `getRouteStateInfo()`, `getCurrentLocationInfo()` (결과는 리스너 콜백으로 옴) |
| NaviHelperEventListener | 기본 구현 인터페이스. `onDestinationInfo`, `onDestinationArrived`, `onRouteStateInfo`, `onNavigationStatus`, `onCurrentLocationInfo` 등 |
| DestinationInfo | `name, address, latitude, longitude, displayLatitude, displayLongitude, remainDistance, remainTime, tollFare, poiId, poiProvider, poiSubId` |

- 패키지: `ai.pleos.playground.vehicle`, `.vehicle.api`, `ai.pleos.playground.location.fused`(`.model`, `.listener`), `ai.pleos.playground.navi.helper`(`.listener`), `ai.pleos.playground.navi.data`.
- 공개 샘플: `github.com/Pleos-Playground/pleos-sample-{vehicle,navi,fusedlocation}-app`.
- 권한(사전 협의 대상, 불필요한 선언 금지): `pleos.car.permission.FUSED_LOCATION`(런타임 요청), `pleos.car.permission.CAR_ENERGY`, `pleos.car.permission.NAVI_ROUTE`. `CAR_INFO` 는 쓰지 않으므로 선언하지 않는다.
- SDK 라이선스(42dot): 디컴파일·재배포 금지. AAR 을 저장소에 넣지 않는다.

## 4. 초기 가정과 실제의 차이

| 초기 가정 | 실제 | 처리 |
|---|---|---|
| CarInfo 로 연료 타입 판별 | CarInfo 에 해당 API 없음 | `EvBattery` SoC capability 와 `Fuel` capability 조합으로 판별 (SoC만=EV, 둘 다=PHEV, Fuel만=ICE, 실패=UNKNOWN). 데모 토글로 덮어쓰기 |
| 시속은 측위에서 그대로 | LocationData 에 속도 없음, Vehicle 에 속도 getter 없음 | 연속 측위로 속도 추정 (`VehicleFix.speedKmh`) |
| 실차 CN8 GPX 로 검증 | 실주행 로그 없음. 우리가 합성한다 | GPX 는 **시뮬레이션**으로 명시. 기획서에 실주행 완료라고 쓰지 않는다. 실로그로 교체 가능한 구조로 만든다 |
| 정차하면 Phase 3 | 신호 대기에서 오판 | `arrivedStopDistM` 30m 이내에서 3km/h 미만 3초 유지일 때만 |
| 지상/지하 통합 입구 | 2016 기사 기준 3~4층이 주차장, 지하 여부 미확인 | 라벨은 "주차장 통합 입구". `id` 는 `MAIN_PARKING` 유지 |
| compileSdk 34 | Pleos 샘플은 35 를 쓰나 AAR minSdk 는 23 | 34 로 빌드된다 (compileSdk·targetSdk 34, minSdk 29) |
| "3번 영역" | 공식 개념 없음 | portrait(1창)·landscape(2창) 반응형 |
| 3D 애니메이션 상시 동작 | 주의분산 가이드 위반 소지 | `motionRestricted` 면 리본 흐름 애니메이션 정지(정적 화살표), 카메라는 차량 추종만 |
| 처음 지정한 좌표가 램프 입구 | 실주행 로그상 실제 입구에서 206m 떨어진 다른 블록 | 원점과 `MAIN_PARKING` 을 실제 램프 시작점으로 변경. 라벨 "옥상 주차장 램프 입구" |
| 우회전 후 30m 앞 차단기 (저작 가정) | 현장에 차단기·발권기 없음 | `Pin_Barrier_01` 은 이름·type 유지, 라벨 "높이 제한 2.1m". 안내 문구 "우회전 후 30m 램프 진입 · 차단기 없음" |
| 높이 제한 2.3m (가정) | 표지 2.1m | `heightLimitM` 2.1 |
| 본관 높이 20m (층수×5m) | 옥상 주차면이 도로면보다 약 14m 높음 | 14.0m. 램프 객체 `RAMP_MAIN` 추가 |
| 주 경로 GPX 는 합성 | 2026-11-13 실주행 로그 확보 | 주 경로는 실주행 로그 재생(라벨 "실차 GPX 주행 재생"), EV 는 합성 유지. 실차 탑재 검증은 여전히 미수행 |

## 5. 공용 계약

### 5.1 좌표

- 로컬 프레임: x=동, z=북, y=위, 단위 m, 원점 `(36.467786, 127.269966)` (앱은 zone JSON 의 `origin` 을 읽는다).
- **단일 수식** (Python·Kotlin·C# 모두 동일, cos 인자는 라디안):
  `x = (lon - lon0) * 111320 * cos(lat0)` / `z = (lat - lat0) * 110574`.
  SHP 변환도 EPSG:5186 → WGS84 → 이 수식을 거친다. 그래야 GPS 측위와 지도가 일치한다.
  (110574 는 적도 부근 값이라 이 위도의 실제 값보다 약 0.34% 작다. 300m 에서 약 1m. 수식은 초기 기획 지정값이므로 유지한다.)
- **OBJ 축:** 파일에는 `v <x> <y> <-z>` 로 쓴다 (파일 X=동, Y=위, Z=-북, 오른손 Y-up).
  - Blender 기본 Import/Export(Forward -Z, Up Y) 를 쓰면 동=+X, 북=+Y, 위=+Z 로 정확히 보인다.
  - Kotlin 로더: `world = (vx, vy, -vz)`. 동기화 스크립트: `x = cx`, `z = -cz`.
  - Unity 는 OBJ 를 X 반전으로 읽으므로 모델 루트를 `Euler(0, 180, 0)` 회전해 world (x, y, z) = (동, 위, 북) 을 맞춘다.

### 5.2 `sejong_costco_zone.json`

Unity `JsonUtility` 호환을 위해 **중첩 배열 금지**, 점은 항상 `{"x":..,"z":..}` 객체다. 키는 `TacticalModels.kt` 와 동일.

```jsonc
{
  "schemaVersion": 1, "zoneId": "sejong_costco", "displayName": "코스트코 세종점",
  "origin": {"lat": 36.467786, "lon": 127.269966}, "radiusM": 300.0,
  "entries": [
    {"id":"MAIN_PARKING","label":"옥상 주차장 램프 입구","fuelKinds":["ICE","UNKNOWN"],
     "position":{"x":0.0,"z":0.0},"recommendedLane":"우측 끝 차로"},
    {"id":"EV_CHARGING_ENTRY","label":"전기차 충전소 인접 입구","fuelKinds":["EV","PHEV"],
     "position":{"x":-38.802,"z":-95.052},"recommendedLane":"우측 끝 차로"}
  ],
  "buildings": [{"id":"TARGET_COSTCO_BUILDING","role":"TARGET","heightM":14.0,"footprint":[{"x":0,"z":0}]}],
  "roads": [{"id":"R0001","widthM":15.0,"lanes":2,"centerline":[{"x":0,"z":0}]}],
  "guidePathPoints": [{"id":"Path_01","x":0,"z":0}],
  "evGuidePathPoints": [{"id":"PathEV_01","x":0,"z":0}],
  "hazardPins": [{"id":"Pin_Barrier_01","type":"BARRIER","x":0,"z":0,"label":"높이 제한 2.1m","fieldVerified":false}],
  "staticNotices": {"heightLimitM":2.1,"barrierNote":"우회전 후 30m 램프 진입 · 차단기 없음"},
  "triggers": {"tacticalDistM":300,"tacticalSlowDistM":350,"tacticalSpeedKmh":30,"tacticalExitDistM":400,
               "followViewDistM":100,"followViewExitDistM":115,"arrivedDistM":15,"arrivedStopDistM":30,
               "arrivedStopSpeedKmh":3,"arrivedStopHoldSec":3,"snapDistM":15,"offRouteWarnDistM":20}
}
```

JSON·OBJ 에 금지: UFID, 명칭, 용도, 도엽 코드, EPSG, 출처, 파일 경로. 허용: 위 스키마와 OBJ 축 주석, `fieldVerified`.

### 5.3 OBJ 오브젝트 (`o` 태그)

`ORIGIN_RAMP_MARKER`, `TARGET_COSTCO_BUILDING`, `SURROUND_BUILDINGS`, `ROAD_SURFACE_300M`, `Path_01`~`Path_08`, `PathEV_01`~`PathEV_NN`, `Pin_Barrier_01`, `Pin_NoTurn_01`, `Pin_WaitLane_01`. Path·Pin 은 작은 상자 메쉬이고 **바운딩 박스 중심 = 위치** 다. 전체 면 수 6,000 이하.

선택 객체 **`RAMP_MAIN`** (램프 경사 쐐기, 폭 6m, 면 60 이하, 낮은 끝이 `MAIN_PARKING` 입구이고 높은 끝의 높이가 본관 높이와 같다). 없어도 앱은 동작한다. 렌더러는 `RAMP_` 그룹을 본관 뒤에 그려 본관 위로 비쳐 보이게 한다. Blender 에서 움직이지 않는 객체다. `Pin_Barrier_01` 은 이 시설에서 램프 입구의 높이 제한을 뜻한다 (이름과 `type` 은 계약이라 유지).

### 5.4 스플라인

제어점 전체를 지나는 **centripetal Catmull-Rom** (alpha 0.5, 양 끝은 끝점 반사). Python·Kotlin·C# 이 같은 식을 쓴다. 호 길이 기준 2m 간격 재샘플.

### 5.5 GPX

- `gis_data/sejong_costco_cn8_drive.gpx` (MAIN_PARKING), `gis_data/sejong_costco_ev_drive.gpx` (EV_CHARGING_ENTRY). 파일명은 실로그 교체를 위해 유지.
- GPX 1.1, 1Hz, `<trkpt lat lon><ele/><time/><extensions><speed>m/s</speed><course>deg</course></extensions></trkpt>`.
- 합성 파일은 `<metadata><keywords>simulated</keywords><desc>…</desc></metadata>` 를 반드시 넣는다.
- 앱 라벨: `simulated` 면 **"GPX 주행 재생 (시뮬레이션)"**, 없으면(실로그로 교체되면) **"실차 GPX 주행 재생"**.

### 5.6 Kotlin 모듈 경계 (패키지 `io.github.ambitionandvision.tactical`, 2026-11-14 변경)

```kotlin
// engine/
class LocalSplineSnapper(controlPoints: List<GuidePoint>, cfg: TriggerConfig) { fun snap(x: Float, z: Float): SnapResult; val totalLengthM: Float; val samples: List<LocalPoint> }
class TacticalStateMachine(zone: ZoneData) { fun setFuelKind(k: FuelKind); fun setMotionRestricted(r: Boolean); fun update(fix: VehicleFix): TacticalFrame; fun reset() }
// sdk/PleosSdkAdapter.kt
// engine/ObjMeshLoader.kt
data class ObjGroup(val name: String, val vertices: FloatArray, val faces: IntArray, val faceSizes: IntArray)  // vertices = x,y,z(월드 좌표, z=북)
object ObjMeshLoader { fun load(stream: java.io.InputStream): List<ObjGroup> }
// ui/Tactical3DFallbackView.kt
class Tactical3DFallbackView(context: Context, attrs: AttributeSet? = null) : View(context, attrs), TacticalSurface { fun setMeshes(groups: List<ObjGroup>) }
// bridge/UnityBridgeManager.kt
class UnityBridgeManager(context: Context, fallback: TacticalSurface) : TacticalSurface { val isUnityAvailable: Boolean }
// ui/
class SummaryCardView(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) { fun bind(zone: ZoneData, frame: TacticalFrame?) }
class TacticalHudView(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) { fun onFrame(frame: TacticalFrame) }
class ArrivedOverlayView(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) { fun show(entry: EntryPoint); fun hide() }
class DemoControlBarView(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    interface Listener { fun onPlayPause(); fun onToggle2x(); fun onDistanceChanged(distanceM: Float); fun onFuelKindChanged(kind: FuelKind); fun onLaneDepartureTest() }
    var listener: Listener?
    fun setPlaying(playing: Boolean); fun set2x(enabled: Boolean); fun setDistance(distanceM: Float); fun setFuelKind(kind: FuelKind); fun setGpxLabel(label: String)
}
```

레이아웃 파일: `res/layout/activity_main.xml`. id: `tacticalSurfaceContainer`, `summaryCard`, `tacticalHud`, `arrivedOverlay`, `demoControlBar`. 

**렌더링 스레드:** `onFrame` 은 메인 스레드에서 호출된다.

### 5.7 Kotlin → Unity 브리지

`UnityPlayer.UnitySendMessage("PleosBridge", <method>, <json>)` (컴파일 의존 없이 리플렉션). 수신 GameObject 이름 `PleosBridge`.

- `OnZone(string zoneJson)`
- `OnVehicleState(string json)`: `{"x","z","headingDeg","speedKmh","phase","cameraMode","attached","offRouteWarning","distanceToEntryM","entryId","motionRestricted"}`
- `OnActive(string "true"|"false")`
