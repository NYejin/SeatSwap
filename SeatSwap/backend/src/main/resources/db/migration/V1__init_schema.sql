-- =====================================================================
-- V1: SeatSwap 초기 스키마 (12개 테이블)
--
-- 기준: ddl-auto=update 로 Hibernate가 만들어 운영 중이던 MySQL 8.0 스키마
--       (SHOW CREATE TABLE)와 JPA 엔티티 정의. 산출물/08_ERD 기준선(12개 엔티티).
--
-- 규칙
--  - 이 파일은 이미 적용된 뒤에는 절대 수정하지 않는다. 변경은 새 V{n}__*.sql 로 추가한다.
--  - 이미 테이블이 있는 기존 DB는 baseline-on-migrate(baseline-version=1)로 이 V1을
--    "적용된 것으로 간주"하고 건너뛴다. 빈 DB에서만 실제로 실행된다.
--  - FK/UNIQUE 제약 이름은 기존 DB와 동일하게 Hibernate가 생성한 이름을 그대로 쓴다.
--    (기존 DB와 신규 DB의 제약 이름이 달라지면 이후 DROP FOREIGN KEY 같은
--     마이그레이션이 환경마다 실패하므로, 보기 좋은 이름 대신 일치를 택했다.)
--  - 시각 컬럼(datetime)은 모두 KST(Asia/Seoul) 벽시계 시각으로 저장한다.
--  - 테이블 기본 collation: utf8mb4_0900_ai_ci (명시해서 서버 기본값에 의존하지 않는다)
-- =====================================================================

-- ---------------------------------------------------------------------
-- 회원
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    created_at  DATETIME(6)  DEFAULT NULL,                -- 가입 시각 (KST)
    email       VARCHAR(255) NOT NULL,                    -- 로그인 ID
    nickname    VARCHAR(255) NOT NULL,
    password    VARCHAR(255) NOT NULL,                    -- BCrypt 해시
    trust_score DOUBLE       DEFAULT NULL,                -- 신뢰도 점수
    PRIMARY KEY (id),
    CONSTRAINT UK6dotkott2kjsp8vw4d0m25fb7 UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 공연장 (공유 기준 데이터, 좌석맵 재사용 단위)
-- ---------------------------------------------------------------------
CREATE TABLE venue (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    address         VARCHAR(255) DEFAULT NULL,
    created_at      DATETIME(6)  NOT NULL,
    name            VARCHAR(100) NOT NULL,
    normalized_name VARCHAR(100) NOT NULL,                -- 중복 판정용 정규화 이름 (기본 collation ai_ci 유지)
    PRIMARY KEY (id),
    CONSTRAINT uk_venue_normalized_name UNIQUE (normalized_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 공연 (티켓팅 링크 기준 등록, 날짜는 performance_session 으로 분리)
-- ---------------------------------------------------------------------
CREATE TABLE performance (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    created_at    DATETIME(6)   NOT NULL,
    -- 링크 정규화 키. URL은 대소문자를 구분하므로 utf8mb4_bin (500자*4byte=2000byte, 인덱스 한도 3072 이내)
    source_key    VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_url    VARCHAR(2048) NOT NULL,
    title         VARCHAR(200)  NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,
    registrant_id BIGINT        NOT NULL,                 -- 등록자 -> users
    venue_id      BIGINT        NOT NULL,                 -- 공연장 -> venue
    PRIMARY KEY (id),
    CONSTRAINT uk_performance_source_key UNIQUE (source_key),
    KEY FK6p310v5n1wwgdqry9ksyx39nf (registrant_id),
    KEY FKlx8rv5t5hrt28t6v29osmacai (venue_id),
    CONSTRAINT FK6p310v5n1wwgdqry9ksyx39nf FOREIGN KEY (registrant_id) REFERENCES users (id),
    CONSTRAINT FKlx8rv5t5hrt28t6v29osmacai FOREIGN KEY (venue_id) REFERENCES venue (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 공연 회차 (Performance 1:N). 같은 회차의 티켓끼리만 교환 가능
-- ---------------------------------------------------------------------
CREATE TABLE performance_session (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    created_at     DATETIME(6) NOT NULL,
    starts_at      DATETIME(6) NOT NULL,                  -- 공연 시작 시각 (분 단위 절삭, KST)
    performance_id BIGINT      NOT NULL,                  -- -> performance
    PRIMARY KEY (id),
    CONSTRAINT uk_performance_session_performance_starts_at UNIQUE (performance_id, starts_at),
    KEY idx_performance_session_starts_at (starts_at),
    CONSTRAINT FKmkyy6hirggrmacpvn0jr70xdk FOREIGN KEY (performance_id) REFERENCES performance (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 좌석맵 레이아웃 (공연장 단위로 저장·재사용)
-- ---------------------------------------------------------------------
CREATE TABLE seat_map_layout (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    image_url   VARCHAR(255) DEFAULT NULL,
    ocr_status  VARCHAR(255) DEFAULT NULL,                -- seatmap-service 인식 상태
    seat_json   LONGTEXT,                                 -- seatmap-service가 반환한 좌표 JSON
    zone_name   VARCHAR(255) DEFAULT NULL,
    venue_id    BIGINT       NOT NULL,                    -- -> venue
    PRIMARY KEY (id),
    KEY FKfv7ims1exshvqpdo80ee82nf0 (venue_id),
    CONSTRAINT FKfv7ims1exshvqpdo80ee82nf0 FOREIGN KEY (venue_id) REFERENCES venue (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 티켓 (회차 + 좌석)
-- ---------------------------------------------------------------------
CREATE TABLE ticket (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    col_label              VARCHAR(255) DEFAULT NULL,     -- 열(번호)
    row_label              VARCHAR(255) DEFAULT NULL,     -- 행
    performance_session_id BIGINT       NOT NULL,         -- -> performance_session
    seatmap_id             BIGINT       NOT NULL,         -- -> seat_map_layout
    user_id                BIGINT       NOT NULL,         -- 보유자 -> users
    PRIMARY KEY (id),
    KEY FKr9sms242y9nbmnf5yjlqsb5xe (performance_session_id),
    KEY FKf5kf0kvx9d06av8q6uvmwk0tc (seatmap_id),
    KEY FKmvugyjf7b45u0juyue7k3pct0 (user_id),
    CONSTRAINT FKf5kf0kvx9d06av8q6uvmwk0tc FOREIGN KEY (seatmap_id) REFERENCES seat_map_layout (id),
    CONSTRAINT FKmvugyjf7b45u0juyue7k3pct0 FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT FKr9sms242y9nbmnf5yjlqsb5xe FOREIGN KEY (performance_session_id) REFERENCES performance_session (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 좌석 오류 정정 신고 (동일 정정 2건 이상이면 자동 반영)
-- ---------------------------------------------------------------------
CREATE TABLE seat_correction (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    corrected_label VARCHAR(255) DEFAULT NULL,
    original_label  VARCHAR(255) DEFAULT NULL,
    status          VARCHAR(255) DEFAULT NULL,            -- enum 문자열
    vote_count      INT          DEFAULT NULL,
    reporter_id     BIGINT       NOT NULL,                -- 신고자 -> users
    seatmap_id      BIGINT       NOT NULL,                -- -> seat_map_layout
    PRIMARY KEY (id),
    KEY FK19vbques1kh5k1gwmgg6c6rjl (reporter_id),
    KEY FK4b4cmqjgoc2xw6ala4hxbpyog (seatmap_id),
    CONSTRAINT FK19vbques1kh5k1gwmgg6c6rjl FOREIGN KEY (reporter_id) REFERENCES users (id),
    CONSTRAINT FK4b4cmqjgoc2xw6ala4hxbpyog FOREIGN KEY (seatmap_id) REFERENCES seat_map_layout (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 교환 요청 (티켓과 1:1 — 티켓당 요청 1개)
-- ---------------------------------------------------------------------
CREATE TABLE exchange_request (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    created_at        DATETIME(6)  DEFAULT NULL,
    desired_condition VARCHAR(255) DEFAULT NULL,
    extra_payment     INT          DEFAULT NULL,          -- 차액
    status            VARCHAR(255) DEFAULT NULL,          -- enum 문자열
    ticket_id         BIGINT       NOT NULL,              -- -> ticket
    PRIMARY KEY (id),
    CONSTRAINT UK8i0dgsjkd4rr1wwvatnf778ch UNIQUE (ticket_id),
    CONSTRAINT FKoo8s9n45m1b7f0yvfrn7g3ed2 FOREIGN KEY (ticket_id) REFERENCES ticket (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 교환 매칭 (두 교환 요청 A/B 를 잇는 단순 1:1 매칭 레코드)
-- ---------------------------------------------------------------------
CREATE TABLE exchange_match (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    matched_at   DATETIME(6)  DEFAULT NULL,
    status       VARCHAR(255) DEFAULT NULL,               -- enum 문자열
    request_a_id BIGINT       NOT NULL,                   -- -> exchange_request (A측)
    request_b_id BIGINT       NOT NULL,                   -- -> exchange_request (B측)
    PRIMARY KEY (id),
    KEY FKnvwjdejjwu0pf0qbsrvr62t7l (request_a_id),
    KEY FKednge034djni5injyt7agdoms (request_b_id),
    CONSTRAINT FKednge034djni5injyt7agdoms FOREIGN KEY (request_b_id) REFERENCES exchange_request (id),
    CONSTRAINT FKnvwjdejjwu0pf0qbsrvr62t7l FOREIGN KEY (request_a_id) REFERENCES exchange_request (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 채팅방 (매칭당 1개)
-- ---------------------------------------------------------------------
CREATE TABLE chat_room (
    id       BIGINT NOT NULL AUTO_INCREMENT,
    match_id BIGINT NOT NULL,                             -- -> exchange_match
    PRIMARY KEY (id),
    CONSTRAINT UKldkcrcykqgmmafcfe1i82f9r UNIQUE (match_id),
    CONSTRAINT FK4wqogk36c381xx2sxgcx7evi2 FOREIGN KEY (match_id) REFERENCES exchange_match (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 채팅 메시지
-- ---------------------------------------------------------------------
CREATE TABLE message (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    content     LONGTEXT,
    sent_at     DATETIME(6) DEFAULT NULL,
    chatroom_id BIGINT      NOT NULL,                     -- -> chat_room
    sender_id   BIGINT      NOT NULL,                     -- 발신자 -> users
    PRIMARY KEY (id),
    KEY FK3dp0e0jr98c8rye4whnei24j (chatroom_id),
    KEY FKbi5avhe69aol2mb1lnm6r4o2p (sender_id),
    CONSTRAINT FK3dp0e0jr98c8rye4whnei24j FOREIGN KEY (chatroom_id) REFERENCES chat_room (id),
    CONSTRAINT FKbi5avhe69aol2mb1lnm6r4o2p FOREIGN KEY (sender_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 후기 (매칭 완료 후에만 생성 — 서비스에서 검사)
-- ---------------------------------------------------------------------
CREATE TABLE review (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    `comment`   VARCHAR(255) DEFAULT NULL,
    rating      INT          DEFAULT NULL,
    match_id    BIGINT       NOT NULL,                    -- -> exchange_match
    reviewer_id BIGINT       NOT NULL,                    -- 작성자 -> users
    target_id   BIGINT       NOT NULL,                    -- 평가 대상 -> users
    PRIMARY KEY (id),
    KEY FK12qj620tqdmq8w8fcsuoiw04v (match_id),
    KEY FK29sgaw0fsbkrgfd8gv15j9vvk (reviewer_id),
    KEY FKgl80drgmr1ssg0rrt3sn9v1mm (target_id),
    CONSTRAINT FK12qj620tqdmq8w8fcsuoiw04v FOREIGN KEY (match_id) REFERENCES exchange_match (id),
    CONSTRAINT FK29sgaw0fsbkrgfd8gv15j9vvk FOREIGN KEY (reviewer_id) REFERENCES users (id),
    CONSTRAINT FKgl80drgmr1ssg0rrt3sn9v1mm FOREIGN KEY (target_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
