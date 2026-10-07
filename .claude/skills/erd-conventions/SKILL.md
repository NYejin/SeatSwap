---
name: erd-conventions
description: DB 엔티티/ERD 관련 작업(신규 테이블, 관계 수정, JPA 엔티티 작성) 시 반드시 참고. 현재 새 V1 기준선(users·venue·performance·performance_session·ticket 5개 테이블)과 네이밍·제약 규칙을 담고 있다. 좌석표·수정 로그·제재 설계는 2026-10-07 트랙 동결로 삭제되어 태그 archive/seatmap-track-20261007에 보관된다. 기준 다이어그램은 산출물/08_ERD/erd.dot.
---

# ERD 컨벤션

## 기준선 (5개 테이블, 새 V1)

User(users), Venue(venue), Performance(performance), **PerformanceSession**(performance_session), Ticket(ticket)

- 2026-10-07 방향 전환으로 좌석표 트랙과 아직 구현하지 않은 교환·채팅·후기 테이블을 걷어내고 **새 V1 하나**(`V1__init_schema.sql`)로 기준선을 다시 만들었다. 이전 V1~V3(12개+좌석표·수정 로그 테이블)는 삭제됐고, 좌석표 코드와 이전 마이그레이션·erd.dot은 git 태그 `archive/seatmap-track-20261007`에 보관되어 있다.
- users에 `role`(USER/ADMIN), venue에 `status`(UNVERIFIED/VERIFIED)·`verified_by`·`verified_at`이 있다. `ticket`은 `performance_session_id`·`user_id`와 텍스트 좌석 `row_label`·`col_label`만 가지며 `seatmap_id`는 없다. 구역·희망 범위·추가금 등 매칭용 컬럼과 교환·채팅·후기 테이블은 교환 도메인 설계 후 새 V 파일로 추가한다.
- 기준선 다이어그램은 `산출물/08_ERD/erd.dot` (2026-10-08 새로 작성, 5개 테이블 + 교환 도메인 '설계 예정' 주석 노드 하나). 새 V 파일이 추가되면 erd.dot도 함께 갱신한다.
- 새 V1은 빈 DB에서만 실행된다. 이전 스키마가 남은 로컬 DB는 `docker compose down -v`로 비운 뒤 적용한다.

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
  - Flyway baseline 설정은 없다(새 V1은 빈 DB 전용). 이전 스키마가 남은 로컬 DB는 `docker compose down -v`로 비운다.
  - 이력 확인: `SELECT * FROM flyway_schema_history;` (자세한 절차는 `SeatSwap/backend/README.md` "DB 마이그레이션")
- 상태값 컬럼은 `status`로 통일 (enum: PENDING/ACCEPTED/COMPLETED 등 문자열 저장)

## 관계 원칙
- **[좌석표 트랙 동결·보관] 아래 `SeatMapLayout`·수정 로그·정정 신고·제재·익명화 관련 불릿은 좌석표를 다시 붙일 때를 위한 보존용 설계 기록이다. 현재 스키마에는 해당 테이블이 없고(태그 `archive/seatmap-track-20261007`에 보관), 그 설계에서 나온 일반 패턴(PENDING 한정 UNIQUE, append-only 로그, SIGNAL 가드)만 새 작업에도 참고한다.**
- `SeatMapLayout`은 **Venue(공연장) 단위**로 저장하고 재사용한다. Performance마다 새로 만들지 않는다
  (같은 공연장이면 좌석 배치가 동일 — NFR-03 재사용성).
  - 좌석표 상태 (2026-10-07 사용자 결정, V2 구현·병합 후 코드 삭제, 태그 보관): **DRAFT는 공연장+구역(zone)당 1개**(구역별 여러 개 허용,
    DB 유일성은 생성 컬럼 `draft_key`), **OFFICIAL은 지금은 여러 개 허용하고 추후 공연장당 1개로 제한**(변경 예정).
    공연장 `status`(UNVERIFIED/VERIFIED)와 좌석표 OFFICIAL은 항상 함께 바뀐다(정식 등록은 좌석표 등록 시에만). 공연에는 정식 상태 없음.
  - `seat_map_layout.image_url`은 삭제(원본 이미지 미보관). `ticket.seatmap_id`는 **NULL 허용**(이후 새 V1에서 컬럼 자체가 빠짐) — 티켓은 좌석표 없이 먼저 등록하고
    교환글 등록 시 DRAFT 좌석표를 업로드한다. 서비스의 "좌석표 venue = 공연 venue" 검사는 seatmap_id가 있을 때만 한다.
  - 수정 로그(`seat_map_revision`, `seat_map_revision_item`)·정정 신고(`seat_correction`)는 좌석을 `seat_uid`(seatmap-service의 안정 식별자)로 가리킨다.
    수정 로그는 **append-only**(수정·삭제하지 않음). 신고(`abuse_report`)는 항상 로그에 남기고 관리자가 확인한다.
  - **V3 구현 사실** (2026-10-07, 이후 코드·마이그레이션 삭제, 태그 보관): `seat_map_layout.seat_count`·idx(created_by, created_at) 추가. `seat_map_revision`의 `revision_no`는 `seat_map_layout.version`과 같은 값(결번은 있어도 중복 없음), UK(seatmap_id, revision_no), action_type·layout_status는 `utf8mb4_bin`+CHECK. `seat_correction`은 1신고=1행(`vote_count` 삭제), status NOT NULL+CHECK(PENDING/APPLIED/REJECTED/SUPERSEDED).
  - **PENDING 한정 중복 방지 패턴**: 종결 뒤에는 재신고를 허용해야 하므로 `status='PENDING'`일 때만 키 문자열을 만드는 생성 컬럼(`pending_key`, STORED, 아니면 NULL)에 UNIQUE를 건다 (NULL은 UNIQUE 대상 제외, V2 `draft_key`와 같은 방식).
  - **append-only 로그 패턴**: 수정 로그는 INSERT만 한다(엔티티에 setter 없음, FK는 ON DELETE RESTRICT). 수정 로그가 있는 좌석표는 삭제할 수 없다 — 단 현재 코드는 최초 인식 로그(RECOGNIZED)만 있으면 로그째 삭제한다(미결정, soft delete는 V4 설계 후보).
  - **SIGNAL 가드 패턴**: 데이터를 이관할 수 없는 변경(예: V3의 `seat_correction` 구 스키마 행)은 파일 맨 앞에서 임시 프로시저+`SIGNAL SQLSTATE '45000'`으로 즉시 실패시킨다(MySQL은 DDL이 트랜잭션에 묶이지 않아 중간 실패 시 앞선 변경이 남기 때문). 실패 후에는 `flyway repair` 후 재시도.
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
    2026-10-08 확정: 매칭은 사용자가 원하는(희망) 회차끼리만, 후보는 사용자가 설정한 회차 우선순위로 노출. 추가금은 3차 답변으로 유무([추가금 X]/[상관없음])만 판정(합 규칙 폐기). **미정/확인 필요**: 금액 표시용 유지, 희망 범위·회차 우선순위의 컬럼 설계 (스키마 변경 시 db-schema-architect 경유).
  - (좌석표 보관) `Ticket.seatMapLayout.venue`는 `Ticket.performanceSession.performance.venue`와 같아야 한다 (서비스에서 검사).
- (교환 도메인 설계 예정, 현재 테이블 없음) `ExchangeRequest`는 `Ticket`과 1:1 — 티켓 하나당 교환 요청은 하나만 유효.
- `ExchangeMatch`는 두 개의 `ExchangeRequest`(A측/B측)를 참조하는 매칭(제안) 레코드다. 매칭은 **조건 일치 판정으로 후보를 찾고 양쪽 수락으로
  확정**하는 모델이며(2026-10-07 변경, 기존 "신청/수락 기반으로 고정"을 대체), 추천 점수·랭킹·신뢰도 등 **추천 알고리즘용 컬럼은 추가하지 않는다**.
  2026-10-08 확정 흐름은 매칭 → 채팅 → 교환 후 각자 수락 → 확정/완료. 조건 일치는 쿼리로 판정하고 저장하지 않는다.
  한 요청에 진행 중인 제안을 동시에 1개로 제한할지는 **확인 필요**(제약 방식은 설계 시 결정).
- 후기(`Review`)·신뢰도는 만들지 않기로 했다(2026-10-08). 사용자 신고는 교환 핵심 흐름 이후 추가한다.

## 텍스트 좌석 입력 기반 매칭 스키마 방향 (2026-10-07 가안, 2026-10-08 확정 답변 반영 — 테이블·컬럼 이름은 여전히 가안)

CLAUDE.md '확정 결정 — 텍스트 좌석 입력 기반 매칭 세부'(2026-10-08)에 따른 재설계 대상이다. 아래 테이블·컬럼 이름은 **가안**이며, 설계·확정은 `db-schema-architect`가
CLAUDE.md '확인 필요' 목록을 사용자에게 확인한 뒤 진행한다. 적용된 V1은 수정하지 않고 **다음 번호(V2)로 추가**한다
(이전 V4 예정이던 제재·신고(`abuse_report`·`user_sanction`)는 동결·삭제 — 번호를 선점하지 않는다).

- 좌석 키는 **공연(회차) 단위의 (구역, 열, 번) 텍스트**. **공연장 단위 구역 테이블(`venue_zone` 등)은 두지 않고 구역 자동완성도 전제하지 않는다**(2026-10-08 확정, 구역 등록·자동완성은 좌석표 기능과 함께 후속).
  좌표·seat `uid`·좌석표 `section` 번호는 키가 아니며 새 테이블은 `seat_map_layout`에 FK를 두지 않는다. **`venue` 테이블은 삭제 확정**(2026-10-08 2·3차 답변: 공연장은 공연 정보의 필수 텍스트 한 칸, 검색·추가·목록 필터·VERIFIED 삭제. `performance`에 공연장 이름 텍스트 컬럼 추가, `venue_id` 제거. V2 마이그레이션은 별도 작업으로 곧 시작)
- `ticket`: 구역·열·번(텍스트, 열 표기는 사용자가 입력하는 숫자/문자) 추가, 기존 `row_label`/`col_label` 문자열의 처리는 설계 시 결정.
  **같은 회차·구역·열·번의 활성 티켓은 1개만** 허용(활성 상태 조건이 있는 유일 제약 필요)하고 사용자당 활성 티켓 수 상한(예 20)은 서비스에서 검사한다. 지정석·1매 제한은 두지 않는다
- `exchange_request`(가칭): 새 V1에는 아직 없는 테이블이다(이전 설계의 `desired_condition` 문자열·`extra_payment`는 삭제됨). 희망 범위·희망 회차는 자식 테이블로 둔다. Ticket 1:1 유지
- `exchange_want_range`(가칭): 요청 1:N. 구역, 열 범위, 번 범위, 희망 회차와 **사용자 설정 우선순위**, 추가금(3차 답변: 매칭은 유무만 확인하는 [추가금 X]/[상관없음] 선택; 이전 [추가금 없음]/[제시]와 '두 값의 합 ≤ 0 성립' 규칙은 폐기 — 2026-10-08). 금액을 표시용으로 남길 경우 부호는 **+ = 내가 받을 금액, − = 내가 낼 수 있는 금액**이며 유지 여부와 [추가금 X]-금액 제시 충돌 판정(기본값: 불성립)은 **확인 필요**
- `exchange_want_seat`(가칭): 범위를 펼친 개별 좌석(파생 데이터, range 1:N). 숫자 열·번 범위(`3~5`)는 펼치고 문자 열은 하나씩 따로 추가(3차 답변 확정). **구역은 필수 별도 입력이며 펼침 대상이 아니다**. 펼침 상한은 지금 두지 않는다(좌석표 기능과 함께 후속). 매칭 조인용 복합 인덱스(공연·구역·열·번)
- `exchange_match`(가칭): 점수·랭킹·신뢰도 컬럼은 두지 않는다. 흐름은 매칭 → 채팅 → 각자 수락 → 확정/완료이므로 양측 수락 상태를 담는다. **후보 목록에서 사용자가 골라 채팅을 시작**하므로 한 요청(티켓)에 채팅(제안)은 여러 개 동시에 열 수 있고, **동시 '예약'만 티켓당 1개**로 제한한다(예약 정의는 확정: 양쪽 '이 사람과 교환할게요'로 두 티켓 잠금, 제약 방식은 설계 시 결정). 상태 가안: 후보 선택/채팅 → 예약 → 양도 → 각자 완료 → 완료, 또는 취소(같은 상대 재매칭 불가, 범위 확인 필요)
- `user_block`(가칭): 차단자·피차단자 (2026-10-08 새 요구). 차단하면 후보에서 제외되고 채팅 불가. 컬럼·유일 제약은 가안
- `exchange_history`(가칭): 마이페이지 '교환 이력'용. 완료 시 `(기존 자리) -> (바꾼 자리)`를 **자리 정보 스냅샷**(공연·회차·구역·열·번 텍스트)으로 저장. 완료는 티켓팅 사이트에서 양도 후 각자 '교환 완료'. 완료 시 내 Ticket의 좌석·회차를 새 자리로 갱신하고 스냅샷도 남긴다(3차 답변 확정). 한쪽만 완료 시 알림/만료는 확인 필요(공연 시작 후 자동 마감은 없음)
- 후기·신뢰도 테이블은 만들지 않는다. 사용자 신고 테이블은 교환 핵심 흐름 이후 설계한다
- 후보 조회는 쿼리(두 요청의 희망 좌석·소유 좌석 교차 + 희망 회차 + 추가금 조건)로 하고 결과를 저장하지 않는다
- (보관) 이전 설계의 `seat_map_layout.zone_name`은 좌석표 전용 값이었다. 좌석표를 다시 붙일 때 구역 등록·자동완성과 함께 연결을 설계한다(그때 "이미지 덩어리 = 이 구역" 지정 단계가 필요)

## 08_ERD 반영 현황

`산출물/08_ERD/erd.dot`은 2026-10-08 방향 전환 후 **현재 5개 테이블 새 V1 기준으로 새로 작성**했다 (users·venue·performance·performance_session·ticket, V1 SQL의 컬럼·UK·CHECK 반영).
교환 도메인은 '설계 예정' 주석 노드 하나뿐이며, 삭제된 좌석표·수정 로그·제재 테이블은 그리지 않는다(설계는 태그 `archive/seatmap-track-20261007`의 이전 erd.dot과 마이그레이션에 보관).
ERD.png는 graphviz `dot`이 있는 환경에서 `dot -Tpng erd.dot -o ERD.png`로 생성한다. 새 V 파일(교환 도메인 등)이 추가되면 erd.dot에 반영하고 이 절을 갱신한다.
시각 컬럼(created_at/updated_at/starts_at)은 KST 기준이라는 주석을 유지한다.

## 변경 시 절차
1. db-schema-architect가 변경안 설계
2. `.dot` 파일 수정 → `dot -Tpng erd.dot -o ERD.png`로 재생성 (한글 라벨 사용 시 `fonts-nanum` 설치 필요)
3. 영향 있으면 산출물/04_요구사항정의서도 함께 갱신
4. 엔티티 구현은 `SeatSwap/backend/src/main/java/com/seatswap/domain/`에 반영
