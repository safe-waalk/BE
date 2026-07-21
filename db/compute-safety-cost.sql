-- Safety cost 배치 계산 스크립트
-- load-osm-walk-network.sh 실행 후 1회 수동 실행:
--   psql "$SUPABASE_DB_URL" -f BE/db/compute-safety-cost.sql
-- 419k edges 처리로 수 분 소요 예상

-- 장시간 실행 허용 (배치 작업)
SET statement_timeout = 0;

ALTER TABLE ways ADD COLUMN IF NOT EXISTS safety_score SMALLINT;
ALTER TABLE ways ADD COLUMN IF NOT EXISTS safety_cost  DOUBLE PRECISION;

-- ST_DWithin(geom::geography, ...) 은 geometry GiST 인덱스를 우회하므로 geography 전용 인덱스 필요
-- 없으면 419k edges 처리에 수십 시간 소요됨
CREATE INDEX IF NOT EXISTS idx_crime_zone_geom_geo   ON crime_zone     USING GIST ((geom::geography));
CREATE INDEX IF NOT EXISTS idx_cctv_geom_geo         ON cctv           USING GIST ((geom::geography));
CREATE INDEX IF NOT EXISTS idx_light_geom_geo        ON security_light USING GIST ((geom::geography));
CREATE INDEX IF NOT EXISTS idx_bell_geom_geo         ON safety_bell    USING GIST ((geom::geography));

UPDATE ways w
SET
    safety_score = scores.score,
    safety_cost  = w.cost * (1.0 + (100 - scores.score) / 100.0 * 2.0)
FROM (
    SELECT
        w2.gid,
        GREATEST(0, LEAST(100,
            ROUND(
                70.0

                -- 범죄 등급 패널티 (최대 -35)
                - COALESCE(
                    (SELECT MAX(grade)::float
                     FROM crime_zone
                     WHERE ST_DWithin(geom::geography, mid::geography, 150)),
                    0.0
                  ) / 10.0 * 35.0

                -- CCTV 근접 보너스 (최대 +15)
                + LEAST(1.0, GREATEST(0.0,
                    (150.0 - COALESCE(
                        (SELECT MIN(ST_Distance(geom::geography, mid::geography))
                         FROM cctv
                         WHERE ST_DWithin(geom::geography, mid::geography, 150)),
                        150.0
                    )) / 150.0
                  )) * 15.0

                -- CCTV 밀도 보너스 (최대 +10)
                + LEAST(1.0,
                    (SELECT COUNT(*)::float FROM cctv
                     WHERE ST_DWithin(geom::geography, mid::geography, 150)
                    ) / 6.0
                  ) * 10.0

                -- 보안등 근접 보너스 (최대 +10)
                + LEAST(1.0, GREATEST(0.0,
                    (100.0 - COALESCE(
                        (SELECT MIN(ST_Distance(geom::geography, mid::geography))
                         FROM security_light
                         WHERE ST_DWithin(geom::geography, mid::geography, 100)),
                        100.0
                    )) / 100.0
                  )) * 10.0

                -- 보안등 밀도 보너스 (최대 +10)
                + LEAST(1.0,
                    (SELECT COUNT(*)::float FROM security_light
                     WHERE ST_DWithin(geom::geography, mid::geography, 100)
                    ) / 8.0
                  ) * 10.0

                -- 안심벨 근접 보너스 (최대 +5)
                + LEAST(1.0, GREATEST(0.0,
                    (100.0 - COALESCE(
                        (SELECT MIN(ST_Distance(geom::geography, mid::geography))
                         FROM safety_bell
                         WHERE ST_DWithin(geom::geography, mid::geography, 100)),
                        100.0
                    )) / 100.0
                  )) * 5.0

                -- 안심벨 밀도 보너스 (최대 +3)
                + LEAST(1.0,
                    (SELECT COUNT(*)::float FROM safety_bell
                     WHERE ST_DWithin(geom::geography, mid::geography, 100)
                    ) / 3.0
                  ) * 3.0

            )::integer
        )) AS score
    FROM ways w2,
         LATERAL (
             SELECT ST_LineInterpolatePoint(w2.the_geom, 0.5)::geography AS mid
         ) m
    WHERE w2.the_geom IS NOT NULL
) scores
WHERE w.gid = scores.gid;
