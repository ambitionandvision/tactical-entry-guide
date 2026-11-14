# unity_tactical_module (Unity 2020.3.6f1)

Tactical Entry Guide 의 Unity 3D 디오라마 모듈. Android 앱은 Unity 없이도 동작하며(네이티브 `Tactical3DFallbackView`),
이 모듈은 UaaL(Unity as a Library) 통합이 가능해질 때 쓰는 대안 렌더러다. 현재 판정은 `bash scripts/spike_uaal_bridge.sh` 로 확인한다. 표의 절 번호(5.1 등)는 `docs/SPEC.md` 의 절이다.

## 구성

| 경로 | 내용 |
|---|---|
| `Assets/Scripts/ZoneDioramaData.cs` | zone JSON(5.2)·차량 상태(5.7) `JsonUtility` 모델 |
| `Assets/Scripts/LocalCoordConverter.cs` | 5.1 단일 수식, OBJ 축(루트 `Euler(0,180,0)`) |
| `Assets/Scripts/TacticalCameraController.cs` | OVERVIEW 45° ↔ APPROACH 후방 팔로우, 100/115m, 1.5초 Lerp/Slerp |
| `Assets/Scripts/GuideRibbonRenderer.cs` | centripetal Catmull-Rom(5.4) 리본, UV 스크롤 화살표, 회차불가 붉은 반투명 Barrier |
| `Assets/Scripts/PleosBridgeReceiver.cs` | `PleosBridge` 수신기: `OnZone`, `OnVehicleState`, `OnActive` |
| `Assets/Models/*.obj`, `Assets/Resources/sejong_costco_zone.json` | GIS 파이프라인 산출물 |
| `Packages/manifest.json`, `ProjectSettings/ProjectVersion.txt` | 폴더를 Unity 프로젝트로 열기 위한 최소본(내장 모듈만) |

## 동작

- 장면 파일이 없어도 첫 장면 로드 후 `PleosBridge` 오브젝트·카메라·조명을 자동 생성한다.
- 호스트(Kotlin)가 `OnZone` 을 보내기 전에는 `Resources/sejong_costco_zone.json` 으로 미리보기한다.
- 에디터에서는 `Assets/Models/sejong_costco_custom.obj` 를 디오라마로 쓰고, 빌드에서는 `dioramaModelPrefab` 이 비어 있으면 zone 외곽선을 돌출한다.
- `OnVehicleState` 의 `attached=true` 면 같은 스플라인에 다시 투영해 리본 위에 정확히 놓는다(직전 진행거리 ±60m 우선 탐색).
- `motionRestricted=true` 면 화살표 흐름을 멈춘다. `OnActive("false")` 면 카메라를 끄고 프레임레이트를 낮춘다.

## 검증 방법

```bash
bash scripts/spike_uaal_bridge.sh            # Go/No-go (기존 batchmode 로그 + 내장 Roslyn csc 참고 컴파일)
bash scripts/spike_uaal_bridge.sh --compile  # Unity batchmode 컴파일을 다시 시도
```

Unity batchmode 를 실행하면 `Library/`, `Temp/`, `Logs/`, `*.meta` 가 생긴다. `Library/`·`Temp/`·`Logs/` 는 저장소에 넣지 않는다.
