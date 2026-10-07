-- =====================================================================
-- V2: 관리자 role, 공연장 정식 등록 상태, 좌석표 DRAFT/OFFICIAL 상태
--
-- [V1 주석 정정] V1의 performance_session 주석("같은 회차의 티켓끼리만 교환 가능")은
--   낡은 설명이다. 교환 범위는 '공연(Performance) 단위'다 (2026-10-07 결정).
--   같은 공연의 다른 회차 티켓끼리도 교환할 수 있다. (V1은 수정하지 않는다.)
--
-- 변경 요약
--  - users.role (USER/ADMIN). 가입은 항상 USER, ADMIN은 DB에서 수동 부여
--  - venue.status/verified_by/verified_at. 공연장 정식 등록은 '좌석표 등록 시에만' 가능하므로
--    venue VERIFIED 와 seat_map_layout OFFICIAL 은 항상 같이 바뀐다 (서비스 불변식, 제약으로는 걸지 않음)
--  - seat_map_layout: status/version/이미지 크기/생성자·승격자/시각 컬럼 추가, image_url 삭제
--    (원본 이미지는 저장하지 않기로 했다. 삭제 시점에 image_url 값은 0건이었음)
--  - ticket.seatmap_id NULL 허용 (좌석표 없이 티켓을 먼저 등록할 수 있게)
--
-- 좌석표 유일성 규칙
--  - DRAFT: 공연장 + 구역(zone_name)당 하나. 구역별로 여러 개는 허용한다.
--    zone_name 이 NULL 일 수 있고 MySQL UNIQUE 는 NULL 을 서로 다른 값으로 보므로,
--    생성 컬럼 draft_key 에서 COALESCE(zone_name, '') 로 NULL 을 빈 문자열로 통일한다.
--    DRAFT 가 아닌 행은 draft_key 가 NULL 이라 UNIQUE 에서 제외된다.
--  - OFFICIAL: 지금은 한 공연장에 여러 개 허용한다 (제약 없음).
--    TODO: 추후 '공연장당 OFFICIAL 1개'로 바꿀 예정. 그때 새 V 파일에서
--          OFFICIAL 용 생성 컬럼 + UNIQUE 를 추가한다 (기존 데이터 정리 필요).
--
-- 대소문자: role/status 컬럼은 utf8mb4_bin 으로 둔다. 기본 collation(ai_ci)이면 소문자 'admin' 이
--   CHECK 와 draft_key 의 status 비교를 통과해 버리고, 이후 enum 매핑에서 전 요청이 500 이 된다.
--   (ADMIN 부여는 반드시 대문자로)
-- 요구 버전: MySQL 8.0.16 이상 (그 미만은 CHECK 를 문법만 받고 강제하지 않는다).
--
-- 적용 전 사전 점검 (V2 가 새 UNIQUE 를 추가하므로, 행이 있는 DB 에서는 먼저 확인):
--   SELECT COUNT(*) FROM seat_map_layout;                       -- 0 이어야 안전. 행이 있으면 아래 중복 확인
--   SELECT venue_id, COALESCE(zone_name,'') z, COUNT(*) FROM seat_map_layout
--     GROUP BY venue_id, z HAVING COUNT(*) > 1;                 -- 결과가 있으면 DRAFT 유일성 위반 → 정리 후 적용
--   SELECT COUNT(*) FROM seat_map_layout WHERE image_url IS NOT NULL;  -- image_url 은 이 파일에서 삭제됨
--
-- 시각 컬럼(datetime)은 KST(Asia/Seoul) 벽시계 시각. 기존 행 보정은 DB 세션 시간대의 NOW(6)
-- 를 쓰므로, 행이 있는 DB에 적용한다면 세션 time_zone 이 KST 인지 확인한다 (현재 seat_map_layout 0건).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 회원: 권한
-- ---------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN role VARCHAR(20) COLLATE utf8mb4_bin NOT NULL DEFAULT 'USER',  -- USER / ADMIN (bin: CHECK가 대소문자 구분)
    ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'));

-- ---------------------------------------------------------------------
-- 공연장: 정식 등록 상태 (공연장 1:N 공연. 공연 자체에는 상태가 없다)
-- ---------------------------------------------------------------------
ALTER TABLE venue
    ADD COLUMN status      VARCHAR(20) COLLATE utf8mb4_bin NOT NULL DEFAULT 'UNVERIFIED',  -- UNVERIFIED / VERIFIED
    ADD COLUMN verified_by BIGINT      NULL,                            -- 정식 등록한 관리자 -> users
    ADD COLUMN verified_at DATETIME(6) NULL,                            -- 정식 등록 시각 (KST)
    ADD CONSTRAINT ck_venue_status CHECK (status IN ('UNVERIFIED', 'VERIFIED')),
    ADD CONSTRAINT fk_venue_verified_by FOREIGN KEY (verified_by) REFERENCES users (id);

-- ---------------------------------------------------------------------
-- 좌석표: 상태/버전/이미지 크기/생성·승격 정보
-- ---------------------------------------------------------------------
ALTER TABLE seat_map_layout
    ADD COLUMN status       VARCHAR(20) COLLATE utf8mb4_bin NOT NULL DEFAULT 'DRAFT',  -- DRAFT / OFFICIAL
    ADD COLUMN version      INT         NOT NULL DEFAULT 1,             -- JPA @Version(낙관적 락). 변경마다 증가, V3 revision_no와 겸용
    ADD COLUMN image_width  INT         NULL,                           -- 인식에 쓴 원본 이미지 가로(px). 좌표 기준
    ADD COLUMN image_height INT         NULL,                           -- 인식에 쓴 원본 이미지 세로(px)
    ADD COLUMN created_by   BIGINT      NULL,                           -- 최초 등록 사용자 -> users
    ADD COLUMN promoted_by  BIGINT      NULL,                           -- OFFICIAL 로 승격한 관리자 -> users
    ADD COLUMN promoted_at  DATETIME(6) NULL,                           -- 승격 시각 (KST)
    ADD COLUMN created_at   DATETIME(6) NULL,
    ADD COLUMN updated_at   DATETIME(6) NULL,
    ADD CONSTRAINT ck_seat_map_layout_status CHECK (status IN ('DRAFT', 'OFFICIAL')),
    ADD CONSTRAINT fk_seat_map_layout_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    ADD CONSTRAINT fk_seat_map_layout_promoted_by FOREIGN KEY (promoted_by) REFERENCES users (id);

-- 기존 행은 현재 시각으로 채운 뒤 NOT NULL 로 바꾼다 (현재 0건)
UPDATE seat_map_layout SET created_at = NOW(6), updated_at = NOW(6);

ALTER TABLE seat_map_layout
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL,
    -- DRAFT 유일성(공연장+구역당 하나)용 생성 컬럼. DRAFT 가 아니면 NULL (UNIQUE 대상 제외). 엔티티에는 매핑하지 않는다
    ADD COLUMN draft_key VARCHAR(300) GENERATED ALWAYS AS (
        CASE WHEN status = 'DRAFT' THEN CONCAT(venue_id, ':', COALESCE(zone_name, '')) ELSE NULL END
    ) STORED,
    ADD CONSTRAINT uk_seat_map_layout_draft_key UNIQUE (draft_key),
    ADD INDEX idx_seat_map_layout_venue_status (venue_id, status),
    DROP COLUMN image_url;

-- ---------------------------------------------------------------------
-- 티켓: 좌석표 없이도 등록 가능 (FK FKf5kf0kvx9d06av8q6uvmwk0tc 는 유지)
-- ---------------------------------------------------------------------
ALTER TABLE ticket
    MODIFY COLUMN seatmap_id BIGINT NULL;                      -- -> seat_map_layout (선택)
