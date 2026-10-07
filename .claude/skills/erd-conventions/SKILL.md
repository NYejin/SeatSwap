---
name: erd-conventions
description: DB 엔티티/ERD 관련 작업(신규 테이블, 관계 수정, JPA 엔티티 작성) 시 반드시 참고. 현재 확정된 12개 엔티티 기준선과 네이밍 규칙을 담고 있다. 기준 이미지는 산출물/08_ERD에 있다.
---

# ERD 컨벤션

## 기준선 (12개 엔티티)

User, Venue, Performance, **PerformanceSession**, Ticket, SeatMapLayout, SeatCorrection,
ExchangeRequest, ExchangeMatch, ChatRoom, Message, Review

- 2026-10-06 변경: `PerformanceSession`(공연 회차) 추가로 11개 → 12개.
- 기준선 다이어그램은 `산출물/08_ERD/erd.dot` (신규 작성 완료). 현재(V1) 12개 테이블(V2~V4 구현 후 16개 예정) + 예정(V2~V4) 변경을
  함께 그리며, 예정 부분은 노란 배경/주황 헤더로 구분한다. **V2~V4는 구현 후 현재(V1)로 승격**한다 (V2는 구현 완료·master 병합 대기 — 병합 후 승격, 지금은 예정 표기 유지)
  (승격 시 해당 표기를 흰색으로 되돌리고 이 문서의 기준선을 갱신). 예정 신규 테이블: `seat_map_revision`,
  `seat_map_revision_item`(V3), `abuse_report`, `user_sanction`(V4) (컬럼은 확정 설계안 기준).
- V1 SQL의 `performance_session` 주석("같은 회차의 티켓끼리만 교환")은 **공연 단위로 정정됨(V2 주석)**. V1 파일은 수정하지 않는다.
- 기존 ERD.png(11개 기준)는 폐기 대상이며, graphviz `dot`이 있는 환경에서 `erd.dot`으로 재생성한다.

## 네이밍 규칙
- 엔티티명: PascalCase 단수형 (`Ticket`, not `Tickets`)
- FK 컬럼: `{참조엔티티_snake}_id` (예: `venue_id`, `seatmap_id`, `performance_session_id`).
  같은 엔티티를 역할로 참조하면 역할명 사용 (`registrant_id`, `reporter_id`, `sender_id`)
- unique 제약·인덱스는 이름을 명시한다: `uk_{테이블}_{컬럼...}`, `idx_{테이블}_{컬럼...}`
  (서비스에서 DataIntegrityViolation을 제약 이름으로 구분할 수 있게)
- 중복 판정용 정규화 값은 원문과 별도 컬럼(`normalized_name`, `source_key`)에 저장하고 거기에 unique를 건다
- **collation**: 테이블 기본은 `utf8mb4_0900_ai_ci`(대소문자·악센트 무시). 구분이 필요한 키 컬럼만 예외로 둔다
  - `performance.source_key` = `utf8mb4_bin` (`@Collate("utf8mb4_bin")`, 2026-10-06 리뷰 H2). URL 경로·쿼리는
    대소문자를 구분하므로 ai_ci면 대소문자만 다른 링크가 같은 공연으로 합쳐진다. 키에 비ASCII(인코딩 안 된
    경로, 디코딩된 상품 ID)가 들어올 수 있어 `ascii_bin`은 쓰지 않는다(저장 오류 위험). 500자 × 4바이트 = 2000바이트로
    인덱스 한도 3072바이트 이내
  - `venue.normalized_name` = 기본 `utf8mb4_0900_ai_ci` **의도적으로 유지**. 이름은 대소문자·악센트 차이를
    같은 공연장으로 보는 게 맞다(Java 정규화보다 DB가 더 넓게 합치지만, 중복 조회도 DB에서 같은 collation으로
    하므로 판정이 어긋나지 않는다)
- **시간 기준 KST**: 모든 `LocalDateTime` 컬럼은 Asia/Seoul 벽시계 시각으로 저장한다
  - 현재 시각은 `ClockConfig`의 `Clock`(Asia/Seoul)에서만 얻는다. 엔티티·서비스에서 `LocalDateTime.now()`(JVM TZ 의존)
    직접 호출 금지
  - `created_at`/`updated_at`은 JPA Auditing으로 채운다: 엔티티에 `@EntityListeners(AuditingEntityListener.class)` +
    `@CreatedDate`/`@LastModifiedDate`, `JpaAuditingConfig`의 DateTimeProvider가 `LocalDateTime.now(clock)` 반환.
    팩토리 메서드에서 시각을 직접 넣지 않는다 (저장 전에는 null)
  - 방어선: backend Dockerfile `ENV TZ=Asia/Seoul` + `-Duser.timezone=Asia/Seoul`, docker-compose backend `TZ: Asia/Seoul`
  - 테스트는 `Clock.fixed`로 고정 (`AuditingClockTest` 참고)
- **스키마 변경은 Flyway 마이그레이션으로만 (2026-10-07 도입)**: `ddl-auto: validate` — Hibernate는 스키마를 만들거나
  고치지 않고 엔티티와 일치하는지 검증만 한다(불일치 시 기동 실패).
  - 변경은 `SeatSwap/backend/src/main/resources/db/migration/V{n}__{snake_description}.sql`을 **새로 추가**해서만 한다
    (예: `V2__add_ticket_status.sql`). 번호는 마지막 번호 + 1.
  - **이미 적용된 파일은 수정 금지**(체크섬 불일치로 기동 실패). 잘못됐으면 새 V 파일로 고친다.
  - 새 엔티티·컬럼·인덱스·길이·NOT NULL·collation 변경은 엔티티 수정과 **같은 커밋에 마이그레이션 파일을 함께 작성**한다.
    과거 ddl-auto update 시절의 "길이·NOT NULL·collation·컬럼 삭제 미반영 → 수동 DDL" 문제는 이제 마이그레이션 파일로 해결한다.
  - 제약 이름은 명시한다(`uk_`/`idx_`/`fk_{테이블}_{컬럼}`). V1의 FK/일부 UNIQUE 이름은 Hibernate가 만든 임의 이름
    (예: `FK6p310v5n1wwgdqry9ksyx39nf`)을 기존 DB와 일치시키려고 그대로 쓴 것이므로, 이를 DROP/변경할 때는 그 이름을 쓴다.
  - 기존(Flyway 도입 전) DB는 `baseline-on-migrate` + `baseline-version: 1`로 V1을 적용된 것으로 간주한다.
  - 이력 확인: `SELECT * FROM flyway_schema_history;` (자세한 절차는 `SeatSwap/backend/README.md` "DB 마이그레이션")
- 상태값 컬럼은 `status`로 통일 (enum: PENDING/ACCEPTED/COMPLETED 등 문자열 저장)

## 관계 원칙
- `SeatMapLayout`은 **Venue(공연장) 단위**로 저장하고 재사용한다. Performance마다 새로 만들지 않는다
  (같은 공연장이면 좌석 배치가 동일 — NFR-03 재사용성).
  - 좌석표 상태 (2026-10-07 사용자 결정, V2 구현 완료·master 병합 대기): **DRAFT는 공연장+구역(zone)당 1개**(구역별 여러 개 허용,
    DB 유일성은 생성 컬럼 `draft_key`), **OFFICIAL은 지금은 여러 개 허용하고 추후 공연장당 1개로 제한**(변경 예정).
    공연장 `status`(UNVERIFIED/VERIFIED)와 좌석표 OFFICIAL은 항상 함께 바뀐다(정식 등록은 좌석표 등록 시에만). 공연에는 정식 상태 없음.
  - `seat_map_layout.image_url`은 삭제(원본 이미지 미보관). `ticket.seatmap_id`는 **NULL 허용** — 티켓은 좌석표 없이 먼저 등록하고
    교환글 등록 시 DRAFT 좌석표를 업로드한다. 서비스의 "좌석표 venue = 공연 venue" 검사는 seatmap_id가 있을 때만 한다.
  - 수정 로그(`seat_map_revision`, `seat_map_revision_item`)·정정 신고(`seat_correction`)는 좌석을 `seat_uid`(seatmap-service의 안정 식별자)로 가리킨다.
    수정 로그는 **append-only**(수정·삭제하지 않음). 신고(`abuse_report`)는 항상 로그에 남기고 관리자가 확인한다.
  - 제재(`user_sanction`)는 `SEATMAP_EDIT`(수정·신고 정지)/`ACCOUNT`(계정 정지) 두 종류, `imposed_by` NOT NULL(관리자만 부과, 자동 제재 없음).
  - 회원탈퇴는 물리 삭제가 아니라 **익명화**(로그·제재 FK 유지). OFFICIAL 좌석표는 사용자 직접 수정 불가(정정 신고로만), 관리자는 직접 수정 가능.
- 공연·회차·공연장 (2026-10-06 확정):
  - `Venue`: 공유 기준 데이터. 사용자는 검색 후 선택, 없으면 추가. 중복 키 `normalized_name` unique
    (NFKC → 공백·구두점(P*)·보이지 않는 문자 제거 → 소문자, `Venue.normalizeName`). 일반 사용자 수정·삭제 불가.
  - `Performance`: 로그인 사용자 누구나 티켓팅 링크로 등록. 날짜 컬럼 없음(회차로 분리).
    중복 키 `source_key` unique (링크 정규화 값 — 사이트별 `{site}:{productId}`, 미지원 사이트는 일반 URL 정규화.
    계산은 서비스 책임). `registrant_id` → users. 공연장+제목은 unique 아님(같은 공연장 재공연 존재).
  - `PerformanceSession`: Performance 1:N. `starts_at`(분 단위 절삭, KST 현지 시각),
    unique (`performance_id`, `starts_at`).
  - `Ticket`은 `PerformanceSession`을 참조한다 (`performance_session_id`). `performance_id`를 중복으로 두지 않는다.
    **교환 범위는 공연(Performance) 단위** (2026-10-07 결정, 이전의 "같은 회차끼리만" 규칙을 대체): 같은 공연의 다른 회차 티켓끼리도
    교환할 수 있다. Ticket은 `performance_session_id`로 회차를 참조하므로 공연은 `session.performance`를 통해 얻고,
    **매칭 판정은 session이 아니라 performance 기준**이다. 교환 후보 조회와 ExchangeRequest/ExchangeMatch 생성 시
    두 티켓의 `performanceSession.performance`가 같은지 서비스에서 검사한다 (회차가 같을 필요는 없음).
    회차마다 날짜가 다르므로 '좌석 위치' 외에 '회차 일시'(`starts_at`)가 교환 조건의 일부이며, 서로 다른 회차끼리 교환이
    성사되면 교환 후 각 티켓의 `performance_session_id`가 바뀐다 (이 변경을 이력으로 남길지 등은 미정).
    OFFICIAL 좌석표(SeatMapLayout)는 회차와 무관하게 공연장(Venue) 단위로 공유한다. DRAFT 공연은 좌표 대신 본인 좌석 정보 +
    희망 좌석 범위로 매칭하며, 희망 범위에 회차를 포함할 수 있다.
    **미정**: 차액 계산 방식, 같은 회차 우선 노출 여부, 희망 범위·회차 조건의 컬럼 설계 (스키마 변경 시 db-schema-architect 경유).
  - `Ticket.seatMapLayout.venue`는 `Ticket.performanceSession.performance.venue`와 같아야 한다 (서비스에서 검사).
- `ExchangeRequest`는 `Ticket`과 1:1 — 티켓 하나당 교환 요청은 하나만 유효.
- `ExchangeMatch`는 두 개의 `ExchangeRequest`(A측/B측)를 참조하는 단순 1:1 매칭 레코드다.
  추천 점수, 랭킹 등 알고리즘 매칭용 컬럼을 추가하지 않는다 (매칭 모델은 신청/수락 기반으로 고정).
- `Review`는 `ExchangeMatch` 완료 후에만 생성 가능하다.

## 08_ERD 반영 현황

`산출물/08_ERD/erd.dot` 신규 작성 완료 (아래 항목 전부 반영됨, 기록용으로 유지). ERD.png는 graphviz `dot` 미설치
환경이라 생성하지 못했다 — `dot`이 있는 환경에서 `dot -Tpng erd.dot -o ERD.png`로 생성한다.
V2~V4(예정) 변경은 구현 후 현재(V1)로 승격한다.
- 노드 추가: `PerformanceSession` (id, performance_id FK, starts_at, created_at / UK(performance_id, starts_at))
- 엣지 추가: Performance 1:N PerformanceSession, PerformanceSession 1:N Ticket
- 엣지 삭제: Performance 1:N Ticket
- Venue: name(100), normalized_name(100, UK), address, created_at
- Performance: performance_date 삭제, title(200, NN), source_url(2048, NN), source_key(500, UK, collation utf8mb4_bin),
  registrant_id FK → User (엣지 User 1:N Performance 추가), created_at, updated_at
- 시각 컬럼(created_at/updated_at/starts_at)은 KST 기준이라는 범례 또는 주석 추가
- Ticket: performance_id → performance_session_id
- 범례/제목의 엔티티 수 11 → 12

## 변경 시 절차
1. db-schema-architect가 변경안 설계
2. `.dot` 파일 수정 → `dot -Tpng erd.dot -o ERD.png`로 재생성 (한글 라벨 사용 시 `fonts-nanum` 설치 필요)
3. 영향 있으면 산출물/04_요구사항정의서도 함께 갱신
4. 엔티티 구현은 `SeatSwap/backend/src/main/java/com/seatswap/domain/`에 반영
