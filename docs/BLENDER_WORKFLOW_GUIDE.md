# BLENDER_WORKFLOW_GUIDE — 유도선·위험요소 핀 편집과 앱 반영

Blender 4.2 에서 유도선 제어점(`Path_*`, `PathEV_*`)과 위험요소 핀(`Pin_*`)을 옮기고, 스크립트 한 줄로 앱 데이터(`sejong_costco_zone.json`)에 반영하는 절차.

## 0. 파일

| 파일 | 역할 |
|---|---|
| `gis_data/sejong_costco_300m.obj` | 파이프라인이 만든 원본. **직접 수정하지 않는다** (재생성 시 덮어써짐) |
| `gis_data/sejong_costco_custom.obj` | Blender 편집본. 처음에는 원본의 복사본이다 |
| `gis_data/sejong_costco_zone.json` | 앱·Unity 가 읽는 Zone 데이터. 동기화 스크립트가 갱신한다 |

위 이름은 `scripts/extract_shp_to_tactical.py` 의 실제 출력과 일치한다(2026-11-14 재생성 기준). 앱 assets 에는 `sejong_costco_custom.obj` 와 `sejong_costco_zone.json` 이 복사된다. 검증 스크립트는 `scripts/verify/` 에 있다.

## 1. 좌표 규칙 (반드시 이해할 것)

- 앱 로컬 좌표: x = 동, z = 북, y = 위, 단위 m, 원점 = 위도 36.467786, 경도 127.269966 (옥상 주차장 램프 입구 = `MAIN_PARKING`).
- OBJ 파일 안에는 `v <x> <y> <-z>` 로 저장돼 있다 (파일 X = 동, Y = 위, Z = −북).
- Blender 기본 Import 설정(Forward −Z, Up Y)으로 열면 **동 = +X, 북 = +Y, 위 = +Z** 로 보인다. 즉 Blender 뷰포트의 Top 뷰(숫자패드 7)가 북쪽이 위인 지도다.
- Blender 단위 1 = 1m.

| 하고 싶은 일 | Blender 조작 |
|---|---|
| 동쪽으로 5m | `G` `X` `5` `Enter` |
| 서쪽으로 5m | `G` `X` `-5` `Enter` |
| 북쪽으로 5m | `G` `Y` `5` `Enter` |
| 남쪽으로 5m | `G` `Y` `-5` `Enter` |

높이(Blender Z)는 위치 판정에 쓰이지 않는다. 동기화 스크립트는 객체 바운딩 박스 중심의 수평 좌표만 읽는다.

## 2. Import

1. 새 파일(File → New → General)에서 기본 큐브·카메라·라이트를 지운다.
2. **File → Import → Wavefront (.obj)** → `gis_data/sejong_costco_custom.obj`.
3. 축 설정은 **기본값 그대로**: Forward Axis **−Z**, Up Axis **Y**. Scale 1.0.
4. 확인: Top 뷰에서 `ORIGIN_RAMP_MARKER` 가 월드 원점(0, 0)에 있고, `Path_01` (북쪽 약 365m, 좌표 (-19.4, 364.3)) 에서 시작해 `Path_08` 이 원점에서 끝나야 한다. `RAMP_MAIN` 은 원점에서 남남서쪽으로 본관 외곽선 안쪽까지 뻗어 있어야 한다.

## 3. 절대 하지 말 것

- **월드 원점 이동 금지.** 씬 전체를 옮기거나, `ORIGIN_RAMP_MARKER`·`TARGET_COSTCO_BUILDING`·`SURROUND_BUILDINGS`·`ROAD_SURFACE_300M`·`RAMP_MAIN` 을 움직이지 않는다. 원점을 바꾸려면 Blender 가 아니라 `extract_shp_to_tactical.py --origin LAT LON` 으로 전체를 다시 만든다.
- **객체 이름 변경 금지.** `Path_01`~`Path_08`, `PathEV_01`~, `Pin_Barrier_01`, `Pin_NoTurn_01`, `Pin_WaitLane_01` 이름이 곧 JSON 키다. Blender 가 복제 시 붙이는 `.001` 같은 접미사는 스크립트가 허용하지만, 같은 이름의 객체가 둘 생기지 않게 한다.
- **`RAMP_MAIN` 은 움직이지 않는 객체다.** 실주행 로그의 위치·고도로 만든 램프 모델(수평 95.0m, 높이 14.0m, 폭 6m, 면 19개)이고, 일부러 본관 외곽선 안쪽에 겹쳐 놓았다(중심선 95.0m 중 79.8m). 겹친다고 바깥으로 빼지 않는다. 동기화 스크립트는 `Path_*`·`PathEV_*`·`Pin_*` 만 읽으므로 Blender 에서 `RAMP_MAIN` 을 옮겨도 Zone JSON 에는 반영되지 않고 화면의 램프만 입구와 어긋난다. 램프 위치를 바꾸려면 `extract_shp_to_tactical.py` 로 다시 만든다.
- 객체를 지우지 않는다. 없는 객체는 JSON 의 기존값이 유지되고 경고가 뜬다.
- Path·Pin 상자 메쉬의 모양을 한쪽으로만 늘리지 않는다. **바운딩 박스 중심 = 위치** 이므로 한쪽만 늘리면 위치가 바뀐 것으로 읽힌다.
- Rotation 은 의미가 없다. 돌리지 않는다.

## 4. Path·Pin 수정

1. Object Mode 에서 옮길 객체를 Outliner 또는 뷰포트에서 선택한다.
2. 1장의 표대로 `G` + 축 + 거리로 옮긴다. 여러 객체를 함께 옮겨도 된다.
3. 유도선 제어점은 **모든 제어점을 지나는 Catmull-Rom 스플라인**으로 이어진다. 코너에 제어점을 몰아 두어야 곡선이 도로를 벗어나지 않는다(목표: 주행 궤적과의 최대 편차 5m 이하). 현재 값: EV 유도선 1.91m. 주 유도선은 Phase 2 구간에 직각 회전이 3개 있어 합성 기준 궤적 대비 6.18m 로 목표에 못 미친다. 실주행 로그 대비로는 최대 9.37m·평균 4.51m 이고 흡착 한계 15m 안이다. `Path_01` 은 입구에서 직선거리 355m 이상, 마지막 Path 는 입구 위치다.
4. 핀 위치는 현장 확인 전까지 저작(가정) 값이다. `Pin_Barrier_01` 은 이름과 유형은 그대로지만 이 시설에서는 라벨이 "높이 제한 2.1m" 다(램프 입구에 차단기가 없다). 표지와 수치는 현장에서 확인했고 핀 좌표는 측량하지 않았다. 옮겨도 `fieldVerified` 는 `false` 로 남는다. 현장 확인 후 JSON 에서 직접 `true` 로 바꾼다.

## 5. Apply Transforms

옮긴 객체를 모두 선택하고 **`Ctrl+A` → All Transforms** 를 실행한다. 이동값이 정점 좌표에 반영되고 객체 Location 이 0 이 된다. 이 단계를 거쳐야 내보낸 파일과 Blender 화면이 같은 값을 갖는다.

## 6. Export

1. **File → Export → Wavefront (.obj)** → `gis_data/sejong_costco_custom.obj` 에 덮어쓴다.
2. 설정
   - Forward Axis **−Z**, Up Axis **Y** (기본값, Import 와 같게)
   - Scale 1.0
   - Limit to Selected Only: **끔** (전체 객체 내보내기)
   - Object Groups / Material Groups: 끔 (객체는 `o` 태그로 기록된다)
   - Materials: 끔 (왕복 검증 스크립트도 `export_materials=False` 로 내보냈다)
   - Normals·UV: 켜도 끄도 된다. 앱 로더는 `v`, `v/vt`, `v/vt/vn`, `v//vn` 를 모두 읽는다.
   - Triangulated Mesh: 끔이어도 된다(로더가 n각형을 읽는다).
3. Materials 를 켜서 `.mtl` 파일이 생겨도 동기화에는 영향이 없다.

## 7. 앱에 반영 — 한 줄

```bash
python3 scripts/sync_blender_to_app.py
```

- `custom.obj` 의 `o`(없으면 `g`) 별 바운딩 박스 중심을 읽어 `Path_*`, `PathEV_*`, `Pin_*` 위치를 Zone JSON 에 쓴다. 좌표 변환은 `x = 파일X`, `z = −파일Z`.
- 바뀐 항목을 콘솔에 출력한다. 의도하지 않은 항목이 바뀌었으면 Blender 에서 되돌리고 다시 내보낸다.
- 갱신된 JSON 과 OBJ 를 `android_pleos_app/app/src/main/assets/`, `unity_tactical_module/Assets/Models/`, `unity_tactical_module/Assets/Resources/` 로 복사한다.
- 그다음 앱을 다시 빌드한다: `bash scripts/gradle_win.sh assembleDebug`.

## 8. 처음 상태로 되돌리기

`gis_data/sejong_costco_300m.obj` 를 `gis_data/sejong_costco_custom.obj` 로 복사한 뒤 7장의 스크립트를 다시 실행한다.

## 9. 왕복 검증 (파이프라인이 맞는지 확인하는 기준 시험)

절차: 원본 Import → `Path_03` 을 동쪽 +5m → Apply Transforms(`Ctrl+A` 와 같은 `transform_apply`) → Export → `sync_blender_to_app.py` → JSON 확인 → custom.obj·zone.json 원복. 위 4~7장과 같은 설정(Import 기본 축, Export 기본 축, Materials 끔)을 Blender 를 headless 로 실행해 자동으로 수행한다.

```bash
bash scripts/verify/blender_roundtrip.sh     # Blender 왕복 (Path_03 +5m → sync → 확인 → 원복)
python3 scripts/verify/self_check.py         # 산출물 전체 자가 점검 (OBJ Path/Pin 중심 == JSON 좌표 등)
```

실행 결과 (2026-11-14, Blender 4.2.22 LTS headless, 오브젝트 26개 — `RAMP_MAIN` 포함):

| 확인 항목 | 기대 | 결과 |
|---|---|---|
| Import 후 북쪽이 Blender +Y 인가 | 예 | 예 — `Path_01` (JSON x=-19.370, z=364.279) 로 판정 |
| `Path_03` 변화 | x +5, z 불변 | (142.318, 253.084) → (147.318, 253.084), Δx=+5.000, Δz=+0.000 |
| 다른 Path·Pin 변화 | 없음 | 나머지 20점 불변 |
| `ORIGIN_RAMP_MARKER` 중심 | (0, 0) | (0.000, 0.000) |
| `RAMP_MAIN` 때문에 생기는 동기화 경고 | 없음 | 없음 |
| 원복 후 동기화 | 변경 없음 | 변경 없음, custom.obj·zone.json 모두 원본과 바이트 동일. gis_data·assets·Unity 사본 12개 파일 SHA-256 불변 |

지도를 다시 만들 때(`extract_shp_to_tactical.py`)는 shapely 2.1 이상이 필요하다. 그보다 낮으면 스크립트가 `gis_data/` 쓰기를 거부한다.

수동 편집(4~7장)은 이 자동 시험과 같은 설정을 사람이 GUI 에서 하는 절차다.
