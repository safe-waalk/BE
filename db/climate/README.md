# 기후 경로 데이터 (폭염/호우)

폭염·호우 경보를 "이쪽으로 가세요" 보행 경로로 바꾸기 위해 로컬 PostGIS + pgRouting DB에
보행 그래프와 기후 레이어를 적재하는 스크립트 모음이다. 대상 범위는 한성대입구역~한성대 일대다.

- 적재 bbox: `S=37.572, W=126.993, N=37.600, E=127.024` (`bbox.env` 하나만 고치면 모든 스크립트에 반영된다.
  단 `fetch-data.sh`에는 같은 값이 따로 적혀 있다)
- 시연 구간: 한성대입구역(37.5884, 127.0062) → 한성대학교(37.5826, 127.0103)

## 파일

| 파일 | 역할 |
|---|---|
| `fetch-data.sh` | 원천 데이터를 `osm-data/`로 내려받는다(이미 받아 두었으면 건너뛴다) |
| `bbox.env` | 적재 bbox 상수. 모든 스크립트가 이 값을 쓴다 |
| `schema.sql` | 확장(postgis, pgrouting), 계약 테이블, 인덱스. 기존 `db/schema.sql`의 안전 레이어 테이블도 `IF NOT EXISTS`로 같이 만든다 |
| `build_graph.py` | OSM 타일 → 보행 그래프(간선·꼭짓점) CSV, 그늘 공급원 CSV, 꼭짓점 고도 |
| `unzip_flood.py` | 침수흔적도 zip을 ASCII 파일명으로 푼다(zip 안 한글 파일명이 CP949라 그대로 풀면 깨진다) |
| `prepare_shelters.py` | 무더위쉼터 CSV(CP949) → bbox 안 쉼터만 UTF-8 CSV로 |
| `load_tables.sql` | 스테이징(`climate_work.*`) → 계약 테이블 |
| `compute_costs.sql` | `shade_ratio`, `in_flood_zone`, `near_shelter`, `heat_cost`, `rain_cost` 계산 |
| `verify.sql` | 검증 쿼리(행 수, 분포, 시연 구간 세 모드 경로 비교). 읽기 전용이라 따로 돌려도 된다 |
| `load.sh` | 위 단계를 처음부터 끝까지 실행한다 |

Python 스크립트는 표준 라이브러리만 쓴다(psycopg2 없이 `psql`의 `\copy`로 적재).

## 실행

```bash
cd BE
./db/climate/fetch-data.sh   # 원천 데이터가 없을 때만
./db/climate/load.sh         # 로컬 DB(localhost:5433, safewalk/safewalk)에 적재 + 검증 출력
```

필요한 것: `psql`, `ogr2ogr`/`ogrinfo`(GDAL), `python3`, 실행 중인 `be-db-1`
(`pgrouting/pgrouting:17-3.5-3.8`. 확장 파일이 들어 있어 `CREATE EXTENSION`만 하면 된다).

환경변수(모두 선택):

| 변수 | 기본값 | 설명 |
|---|---|---|
| `OSM_DATA` | `osm-data` (읽을 수 없으면 `osm-data/work/input`) | 입력 디렉터리 |
| `WORK` | `osm-data/work/climate` | 중간 산출물(그래프 CSV, 침수 SHP/CSV, 쉼터 CSV, `verify.txt`) |
| `PGHOST`/`PGPORT`/`PGUSER`/`PGDATABASE`/`PGPASSWORD` | `localhost`/`5433`/`safewalk`/`safewalk`/`safewalk` | 대상 DB |
| `FLOOD_S_SRS_<연도>` | 없음 | 그 연도 SHP에 `.prj`가 없을 때만 원본 좌표계를 지정(없으면 중단한다) |
| `FLOOD_NEAR_M` | `10` | 침수흔적에서 몇 m 이내를 지나는 간선을 `in_flood_zone`으로 볼지. `0`이면 교차 기준 |

`load.sh`는 **몇 번 돌려도 같은 결과**가 된다. 계약 테이블(`ways`, `ways_vertices_pgr`, `flood_trace`,
`cooling_shelter`)은 한 트랜잭션 안에서 비우고 다시 채운다. id는 입력 순서대로 매기므로 실행할 때마다 같다.
스테이징 스키마 `climate_work`는 매번 새로 만들고, 실행이 끝난 뒤에도 디버깅용으로 남겨 둔다
(`climate_work.shade`에 그늘 면, `climate_work.edge5186`에 5186 간선이 들어 있다).
기존 안전 레이어 테이블(`crime_zone`, `cctv`, `security_light`, `safety_bell`, `report`)은 **만들기만 하고 비우지 않는다**.

단계: 입력 확인 → 확장/스키마(+ `ways` 컬럼이 계약과 같은지 확인) → 그래프 빌드·스테이징 →
침수흔적도 → 무더위쉼터 → 계약 테이블로 옮기기 → 비용 계산(NULL·0 이하 비용이 있으면 롤백) → 검증 출력.

## 데이터 출처와 라이선스

| 데이터 | 출처 | 라이선스 / 표기 |
|---|---|---|
| 도로·보행로·건물·가로수·공원 | OpenStreetMap, API 0.6 `map` 요청을 0.008도 격자 4x4 타일로 | ODbL 1.0, "© OpenStreetMap contributors" |
| 고도 | SRTM 1-arc-second (`N37E126`, `N37E127`), AWS Terrain Tiles(skadi) 공개 버킷 | 미국 정부 저작물(퍼블릭 도메인). 출처: NASA/USGS SRTM |
| 무더위쉼터 | 서울 열린데이터광장 OA-21065 (서울시 무더위쉼터, CSV) | 서울 열린데이터광장 이용조건(데이터셋 페이지의 공공누리 표시를 따른다) |
| 침수흔적도 | 서울 열린데이터광장 OA-15636 (서울시 침수흔적도, 연도별 SHP. 2015·2021년 없음) | 공공누리 제1유형(출처표시) |

## 보행 그래프 (`build_graph.py`)

osm2pgrouting은 `tunnel`·`covered` 태그를 버리므로 쓰지 않고 OSM XML을 직접 파싱한다.

- **중복 제거**: 타일 경계가 겹쳐 같은 node/way/relation이 여러 타일에 나온다. id로 합치고, 같은 id면 `version`이 큰 것을 남긴다.
- **보행 가능 way**: `highway` ∈ footway, path, pedestrian, steps, cycleway, track, residential, living_street,
  service, unclassified, road, tertiary(_link), secondary(_link), primary(_link).
  motorway·trunk·construction·proposed 등은 목록에 없어서 빠진다.
  `foot=no`면 제외하고, `foot=yes/designated/permissive`면 `access`와 상관없이 포함한다. 그 밖에는 `access=private/no`면 제외한다.
- **분할**: 서로 다른 보행 way 2개 이상에 나오는 node, way의 시작·끝 node, 한 way 안에서 두 번 나오는 node에서 자른다.
  잘린 조각 하나가 `ways` 한 행이고, 조각 안의 중간 형상점은 `the_geom`에 그대로 남는다.
  시작과 끝이 같은 고리 조각은 가운데 node에서 한 번 더 잘라 `source = target` 간선을 만들지 않는다.
- **bbox**: 모든 node가 bbox 밖인 조각은 버린다. bbox 경계를 넘는 조각은 통째로 남긴다.
- **꼭짓점**: 분할점 node만 `ways_vertices_pgr`에 넣는다. `id`는 1부터 새로 매기고 `osm_id`에 원래 node id를 둔다.
- **`length_m`**: 형상점 사이 haversine 거리의 합(지구 반지름 6,371,008.8m). 0.01m보다 짧으면 0.01m로 둔다
  (같은 좌표에 다른 node가 겹친 경우). `cost = reverse_cost = length_m`.
  `verify.sql`이 `ST_Length(the_geom::geography)`(타원체)와의 차이를 출력한다.
- **`underpass`**: `tunnel`이 `no`·`building_passage`가 아닌 값(yes, covered 등)이거나 `layer`가 음수면 지하로 본다.
  `building_passage`는 건물 1층을 관통하는 지상 통로라 지하로 보지 않고 `covered`로만 처리한다.
  그중 `highway`가 footway/path/pedestrian/steps/cycleway면 `PEDESTRIAN`(지하보도), 나머지는 `ROAD`(지하차도),
  지하가 아니면 `NONE`. cycleway는 국내에서 대부분 보행자 겸용이라 보행 쪽에 넣었다.
- **`covered`**: `covered`가 `no`가 아닌 값이거나 `tunnel=building_passage`.

## 고도 (`ways_vertices_pgr.elevation_m`)

SRTM hgt(gzip된 big-endian int16, 3601x3601, 1도x1도, 첫 행이 북쪽 끝)에서 **쌍선형 보간**한다.
행 = (위도 하한 + 1 − lat) x 3600, 열 = (lon − 경도 하한) x 3600.

**void(-32768) 처리**: 보간에 쓰는 주변 4개 격자점 중 void인 것은 빼고, 남은 점의 가중치를 합이 1이 되게 다시 나눠 보간한다.
4개가 모두 void면 `NULL`이다. SRTM 1" 원 해상도는 약 30m라 골목 단위 고저차는 뭉개진다.

## 그늘 비율 (`shade_ratio`, 0.0~1.0)

EPSG:5186(미터)에서 계산한다.

그늘 공급원:

| 종류 | OSM | 그늘 면 |
|---|---|---|
| 건물 | `building=*`(`no` 제외, 지하 건물 제외) 닫힌 way와 multipolygon 관계 | 바닥 면 + 버퍼 `min(높이 x 0.5, 15m)` |
| 가로수 | `natural=tree` 점 / `natural=tree_row` 선 | 반경 3.5m 버퍼 |
| 수림·공원 | `natural=wood`, `landuse=forest`, `leisure=park` | 면 그대로 |

건물 높이는 `height` → 없으면 `building:levels x 3.2m` → 둘 다 없으면 9m.
multipolygon 관계는 구성 way의 선을 모아 `ST_BuildArea`로 면을 만든다(안쪽 고리는 구멍이 된다).
타일 밖 구성 way가 빠져 고리가 닫히지 않으면 그 관계는 면이 만들어지지 않아 빠진다.

```
shade_ratio = |간선 ∩ (간선과 겹치는 그늘 면들의 합집합)| / |간선|      (0~1로 자름)
underpass = 'PEDESTRIAN' 이거나 covered = true 이면 shade_ratio = 1.0
```

간선과 그늘 면마다 교차 선분을 구한 뒤 그 선분들을 `ST_Union`으로 녹여 겹친 그늘이 두 번 세어지지 않게 한다.
분모는 같은 5186 길이를 쓴다(이 지역 축척 오차는 0.01% 미만이라 `length_m`과 사실상 같다).

**한계 — 태양 고도·방위를 반영하지 않은 기하학적 근사다.**
- 그림자를 건물 둘레 전 방향에 같은 폭으로 둘렀다. 실제 그림자는 태양 반대쪽으로만 생기고
  시각에 따라 길이가 바뀐다(서울 하지 정오 태양 고도 약 76°에서 그림자 길이는 높이의 약 0.25배, 오후 늦게는 몇 배).
  0.5배는 한낮~오후 중간쯤을 가정한 값이다.
- 간선은 도로 중심선이다. 넓은 도로는 보도가 건물 쪽에 붙어 있어도 중심선이 그림자 밖이면 그늘 0으로 나온다.
- 공원 면 전체를 그늘로 본다(잔디밭 같은 열린 공간 포함). 가로수 수관은 반경 3.5m로 일정하게 잡았다.
- OSM에 높이가 없는 건물이 대부분이라 기본값 9m(약 3층)이 많이 쓰인다(건물 4,140개 중 3,585개).
- **OSM에 건물이 안 그려진 구역은 실제로는 건물이 빽빽해도 그늘 0으로 나온다.** 시연 최단경로 31개 간선 중
  17개(삼선교로·삼선교로4길·삼선교로10다길 일대)는 주변 20m 안에 OSM 건물이 하나도 없다.

## 침수흔적도 (`flood_trace`)

연도별 zip을 `unzip_flood.py`로 `flood_<연도>_<n>.*`로 풀고, `ogrinfo`로 `.prj` 좌표계를 출력한 다음
`ogr2ogr -spat <bbox> -spat_srs EPSG:4326 -clipdst <bbox> -t_srs EPSG:4326 -makevalid`로 **bbox만 잘라** 4326으로 바꾼다.
SQL에서 다시 `ST_MakeValid` → 면만 추출(`ST_CollectionExtract(…, 3)`) → `ST_Multi`로 MultiPolygon을 만들고,
빈 지오메트리는 버린다. 원본 피처 하나가 한 행이다. `.prj`가 없는 연도가 있으면 중단한다
(2026-11 기준 14개 파일 모두 `.prj`가 있고 좌표계는 EPSG:5179 KGD2002 Unified CS다).

`year`는 피처의 `F_YR`(침수 연도) 필드를 쓰고, 없거나 연도 꼴이 아니면 zip 파일 연도를 쓴다.
2022년 파일에 `F_YR=2023`인 피처 399개가 섞여 있기 때문이다. 같은 연도에 모양까지 똑같은 폴리곤이 여러 파일에 있으면
하나만 남긴다. `source`에는 어느 연도 파일에서 왔는지 적는다.

## 무더위쉼터 (`cooling_shelter`)

CP949 CSV에서 `쉼터명칭`→`name`, `시설구분2`→`facility_type`, `도로명주소`→`address`,
`기본운영_시작시간`/`기본운영_종료시간`→`open_time`/`close_time`, `경도`/`위도`→좌표.
위경도가 뒤바뀐 행은 바로잡고, 위경도가 없는 행은 `X/Y좌표(EPSG:5186)`로 보완한다.
원본 CSV에서 가운뎃점이 `?`로 깨져 있어(`복지?문화?체육시설`) `facility_type`의 `?`만 `·`로 되돌린다.
쉼터명·주소의 깨진 글자는 원래 글자를 알 수 없어 그대로 둔다.
이름·주소·좌표가 모두 같은 중복 행은 하나만 남기고, bbox 안의 것만 적재한다. `source = '서울 열린데이터광장 OA-21065'`.

## 비용 공식

```
heat_cost = length_m * (1 + 1.5 * (1 - shade_ratio)) * (near_shelter ? 0.85 : 1.0)
rain_cost = length_m * (in_flood_zone ? 8 : 1) * (underpass <> 'NONE' ? 30 : 1)
```

- `in_flood_zone`: 간선이 어떤 연도의 `flood_trace` 폴리곤에서든 **10m 이내**를 지나면 true(연도 구분 없음,
  `ST_DWithin(geography, 10)`). 처음 계획은 "교차하면 true"였지만, 침수흔적은 침수된 **필지·건물 폴리곤**이라
  도로 중심선과 거의 교차하지 않는다. 교차 기준이면 5,703개 간선 중 17개(964m)만 true가 되고,
  시연 최단경로는 2011년 흔적에서 1.7m 떨어져 지나가는데도 false가 된다. 길가 필지가 침수됐으면 그 길도
  침수 이력 구간으로 보도록 10m로 바꿨다(`be`와 합의). `compute_costs.sql`의 `flood_near_m`
  (`psql -v flood_near_m=0`이면 교차 기준)으로 조정한다.
- `near_shelter`: 간선이 `cooling_shelter`에서 50m 이내(`ST_DWithin(geography, 50)`).
- `safety_score`/`safety_cost`는 NULL로 둔다. 기존 SAFE 모드는 `COALESCE(safety_cost, cost)`로 `cost`를 쓴다.
- `compute_costs.sql` 끝에서 비용이 NULL이거나 0 이하인 간선이 하나라도 있으면 예외를 던져 롤백한다.

계수는 `compute_costs.sql`에 있다. 바꾸면 이 절도 같이 고친다.

## 샘플·근사로 처리한 항목

- 그늘: 위 "한계" 그대로. 태양 위치·시각을 반영하지 않는 고정 버퍼 근사이고, 건물 높이 대부분이 기본값이다.
- 침수: 과거 침수 **흔적**(2010~2025, 2015·2021 없음)을 모두 합친 것이다. 실시간 강우·배수 용량이나 침수 깊이는 반영하지 않는다.
- 지하차도/지하보도: OSM `tunnel`·`layer` 태그에 의존한다. 태그가 빠진 지하 통로는 `NONE`으로 나온다.
  `tunnel=building_passage`(건물 1층 관통 통로, 이 범위 9개 간선)는 처음에 지하로 분류해 `rain_cost`에 x30이 붙었으나,
  비를 피할 수 있는 지상 통로라 2026-11-16에 지하 분류에서 뺐다(`covered=true`라 그늘 1.0은 유지).
- 고도: SRTM 30m 격자 보간이다(건물·고가 구조물 높이가 섞인 DSM 성격).
- 경보 자체(폭염/호우 특보)는 이 스크립트 범위 밖이다. BE의 `/api/climate/alert`가 시나리오로 시뮬레이션한다.
