-- =====================================================================
-- V3: 좌석표 수정 로그(seat_map_revision / seat_map_revision_item), 정정 신고 개편(seat_correction),
--     좌석표 목록 보강(seat_map_layout.seat_count + 인덱스)
--
-- 변경 요약
--  - seat_map_layout: seat_count(좌석 수, 목록 응답용) 추가 + 기존 행은 JSON_LENGTH(seat_json)으로 채움,
--    일일 등록 제한 집계용 인덱스 (created_by, created_at)
--  - seat_map_revision: 좌석표 수정 로그 헤더. append-only (애플리케이션이 INSERT만 한다, 엔티티에 setter 없음).
--    revision_no 는 seat_map_layout.version(@Version)과 같은 값을 쓴다 (수정마다 version 이 오르므로 결번은 있어도 중복은 없다).
--  - seat_map_revision_item: 좌석·필드 단위 전후 값 (좌석은 seatmap-service 의 안정 식별자 seat_uid 로 가리킨다)
--  - seat_correction 개편: 1신고 = 1행 (vote_count 삭제, 건수는 집계), 대상 좌석·필드·정규화 값·신고 시점 버전·검토 정보 추가,
--    status 를 NOT NULL + CHECK (PENDING/APPLIED/REJECTED/SUPERSEDED).
--    같은 사용자의 중복 신고 방지는 '대기(PENDING) 중인 신고'에만 건다: 생성 컬럼 pending_key + UNIQUE
--    (V2 draft_key 방식). 종결(APPLIED/SUPERSEDED/REJECTED)된 뒤에는 같은 사용자가 같은 정정을 다시 신고할 수 있다.
--
-- 규칙
--  - 상태·enum 컬럼은 V2처럼 utf8mb4_bin + CHECK (기본 collation ai_ci 면 소문자 값이 CHECK 를 통과해 enum 매핑이 500 이 된다).
--    seat_uid 도 키 값이라 utf8mb4_bin 으로 둔다.
--  - 시각 컬럼(datetime)은 KST(Asia/Seoul) 벽시계 시각 (JPA Auditing 이 Clock 기준으로 채운다).
--  - V1/V2 는 수정하지 않는다.
--
-- 적용 전 사전 점검 (seat_correction 구 스키마의 행은 대상 좌석(uid)을 알 수 없어 이관할 수 없다):
--   SELECT COUNT(*) FROM seat_correction;   -- 0 이어야 한다.
--   이 파일은 맨 앞에서 seat_correction 에 행이 있으면 SIGNAL 로 즉시 실패시킨다 (아무것도 바꾸기 전에).
--   MySQL 은 DDL 이 트랜잭션에 묶이지 않아 중간에 실패하면 앞선 변경이 남으므로, 실패를 맨 앞으로 모았다.
-- 요구 버전: MySQL 8.0.16 이상 (CHECK 강제)
-- =====================================================================

-- ---------------------------------------------------------------------
-- 사전 가드: seat_correction 에 행이 있으면 아무것도 바꾸기 전에 실패한다 (임시 프로시저 + SIGNAL, 끝나면 DROP).
-- 가드에서 실패하면 프로시저가 남을 수 있으므로 재시도에 대비해 먼저 DROP IF EXISTS 한다.
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS v3_guard_seat_correction_empty;

DELIMITER //
CREATE PROCEDURE v3_guard_seat_correction_empty()
BEGIN
    IF (SELECT COUNT(*) FROM seat_correction) > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V3: seat_correction has rows; old rows cannot be migrated. Empty or back up the table first.';
    END IF;
END//
DELIMITER ;

CALL v3_guard_seat_correction_empty();
DROP PROCEDURE v3_guard_seat_correction_empty;

-- ---------------------------------------------------------------------
-- 좌석표: 좌석 수 + 일일 등록 제한 집계 인덱스
-- ---------------------------------------------------------------------
ALTER TABLE seat_map_layout
    ADD COLUMN seat_count INT NOT NULL DEFAULT 0;              -- seat_json 배열 길이. 라벨 수정은 좌석 수를 바꾸지 않는다

-- JSON 배열일 때만 길이를 쓴다 (깨졌거나 배열이 아니면 0). CASE 로 JSON_TYPE 이 잘못된 JSON 에 평가되지 않게 한다
UPDATE seat_map_layout
   SET seat_count = CASE
           WHEN JSON_VALID(seat_json) THEN
               CASE WHEN JSON_TYPE(seat_json) = 'ARRAY' THEN JSON_LENGTH(seat_json) ELSE 0 END
           ELSE 0
       END
 WHERE seat_json IS NOT NULL;

ALTER TABLE seat_map_layout
    ADD INDEX idx_seat_map_layout_created_by_at (created_by, created_at);

-- ---------------------------------------------------------------------
-- 수정 로그 헤더 (append-only)
-- ---------------------------------------------------------------------
CREATE TABLE seat_map_revision (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    seatmap_id    BIGINT       NOT NULL,                       -- -> seat_map_layout
    revision_no   INT          NOT NULL,                       -- 수정 후 seat_map_layout.version
    action_type   VARCHAR(30)  COLLATE utf8mb4_bin NOT NULL,   -- RECOGNIZED / USER_EDIT / CORRECTION_APPLIED / ADMIN_EDIT / PROMOTED / REVERTED
    layout_status VARCHAR(20)  COLLATE utf8mb4_bin NOT NULL,   -- 수정 시점 좌석표 상태 (DRAFT / OFFICIAL)
    actor_id      BIGINT       NULL,                           -- 수정한 사용자 -> users (NULL = 시스템 자동 반영)
    reason        VARCHAR(500) NULL,
    before_json   LONGTEXT     NULL,                           -- 전체 교체 시에만 (라벨 수정은 item 으로 충분해 NULL)
    after_json    LONGTEXT     NULL,
    created_at    DATETIME(6)  NOT NULL,                       -- KST
    PRIMARY KEY (id),
    CONSTRAINT uk_seat_map_revision_seatmap_no UNIQUE (seatmap_id, revision_no),
    KEY idx_seat_map_revision_actor_created (actor_id, created_at),
    CONSTRAINT ck_seat_map_revision_action_type CHECK (action_type IN
        ('RECOGNIZED', 'USER_EDIT', 'CORRECTION_APPLIED', 'ADMIN_EDIT', 'PROMOTED', 'REVERTED')),
    CONSTRAINT ck_seat_map_revision_layout_status CHECK (layout_status IN ('DRAFT', 'OFFICIAL')),
    CONSTRAINT fk_seat_map_revision_seatmap FOREIGN KEY (seatmap_id) REFERENCES seat_map_layout (id) ON DELETE RESTRICT,
    CONSTRAINT fk_seat_map_revision_actor FOREIGN KEY (actor_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 수정 로그 상세 (좌석·필드 단위 전후 값, append-only)
-- ---------------------------------------------------------------------
CREATE TABLE seat_map_revision_item (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    revision_id   BIGINT       NOT NULL,                       -- -> seat_map_revision
    seat_uid      VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL,   -- seatmap-service 좌석 식별자 (예: s0001)
    field_name    VARCHAR(20)  COLLATE utf8mb4_bin NOT NULL,   -- ROW_LABEL / COL_LABEL / X / Y / W / H / SEAT_ADDED / SEAT_REMOVED
    before_value  VARCHAR(255) NULL,
    after_value   VARCHAR(255) NULL,
    correction_id BIGINT       NULL,                           -- 이 변경을 만든 정정 신고 -> seat_correction (자동 반영 시)
    PRIMARY KEY (id),
    KEY idx_seat_map_revision_item_revision (revision_id),
    KEY idx_seat_map_revision_item_seat_uid (seat_uid),
    CONSTRAINT ck_seat_map_revision_item_field CHECK (field_name IN
        ('ROW_LABEL', 'COL_LABEL', 'X', 'Y', 'W', 'H', 'SEAT_ADDED', 'SEAT_REMOVED')),
    CONSTRAINT fk_seat_map_revision_item_revision FOREIGN KEY (revision_id) REFERENCES seat_map_revision (id) ON DELETE RESTRICT,
    CONSTRAINT fk_seat_map_revision_item_correction FOREIGN KEY (correction_id) REFERENCES seat_correction (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 정정 신고 개편: 1신고 = 1행, 건수는 집계 (동일 정정 2건 이상이면 자동 반영 — 임계값은 설정)
-- ---------------------------------------------------------------------
-- 하나의 ALTER 로 묶어 중간 실패로 반쯤 바뀐 상태가 남지 않게 한다 (행이 없음은 위 가드가 보장).
-- pending_key: 대기 중인 신고에만 값이 있는 생성 컬럼 (아니면 NULL → UNIQUE 대상 제외). 길이: 20+1+32+1+20+1+20+1+100 < 250
ALTER TABLE seat_correction
    DROP COLUMN vote_count,
    MODIFY COLUMN status VARCHAR(20) COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING',  -- PENDING / APPLIED / REJECTED / SUPERSEDED
    ADD COLUMN target_seat_uid     VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL,         -- 대상 좌석 uid
    ADD COLUMN target_field        VARCHAR(20)  COLLATE utf8mb4_bin NOT NULL,         -- ROW_LABEL / COL_LABEL
    ADD COLUMN normalized_value    VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,         -- 정정값 정규화 (중복·집계 키)
    ADD COLUMN layout_version      INT          NOT NULL,                             -- 신고 시점 seat_map_layout.version
    ADD COLUMN created_at          DATETIME(6)  NOT NULL,                             -- KST
    ADD COLUMN reviewed_by         BIGINT       NULL,                                 -- 검토한 관리자 -> users
    ADD COLUMN reviewed_at         DATETIME(6)  NULL,
    ADD COLUMN review_note         VARCHAR(500) NULL,                                 -- 검토 메모 / 자동 반영 보류 사유
    ADD COLUMN applied_revision_id BIGINT       NULL,                                 -- 반영된 수정 로그 -> seat_map_revision
    ADD COLUMN pending_key VARCHAR(250) COLLATE utf8mb4_bin GENERATED ALWAYS AS (
        CASE WHEN status = 'PENDING'
             THEN CONCAT(seatmap_id, ':', target_seat_uid, ':', target_field, ':', reporter_id, ':', normalized_value)
             ELSE NULL END
    ) STORED,
    ADD CONSTRAINT ck_seat_correction_status CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED', 'SUPERSEDED')),
    ADD CONSTRAINT ck_seat_correction_target_field CHECK (target_field IN ('ROW_LABEL', 'COL_LABEL')),
    ADD CONSTRAINT fk_seat_correction_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id),
    ADD CONSTRAINT fk_seat_correction_applied_revision FOREIGN KEY (applied_revision_id) REFERENCES seat_map_revision (id),
    -- 같은 사용자가 같은 좌석·필드에 같은 정정을 대기 중에 두 번 신고할 수 없다 (종결 후 재신고는 가능)
    ADD CONSTRAINT uk_seat_correction_pending_key UNIQUE (pending_key),
    -- 같은 좌석·필드의 상태별 신고 조회(자동 반영 집계, SUPERSEDED 처리)용
    ADD INDEX idx_seat_correction_aggregate (seatmap_id, target_seat_uid, target_field, status),
    -- 사용자별 24시간 신고 수 집계용
    ADD INDEX idx_seat_correction_reporter_created (reporter_id, created_at);
