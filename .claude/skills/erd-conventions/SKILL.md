---
name: erd-conventions
description: DB 엔티티/ERD 관련 작업(신규 테이블, 관계 수정, JPA 엔티티 작성) 시 반드시 참고. 현재 확정된 12개 엔티티 기준선과 네이밍 규칙을 담고 있다. 기준 이미지는 산출물/08_ERD에 있다.
---

# ERD 컨벤션

## 기준선 (12개 엔티티)

User, Venue, Performance, **PerformanceSession**, Ticket, SeatMapLayout, SeatCorrection,
ExchangeRequest, ExchangeMatch, ChatRoom, Message, Review

- 2026-10-06 변경: `PerformanceSession`(공연 회차) 추가로 11개 → 12개. 산출물/08_ERD/ERD.png 원본은
  아직 11개 기준이다 (아래 "08_ERD 원본 반영 대기" 참고).

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
- **ddl-auto: update 한계**: 기존 컬럼의 길이·NOT NULL·collation 변경, 컬럼 삭제는 반영되지 않는다.
  이런 변경을 하면 수동 DDL(ALTER) 또는 해당 테이블 drop 후 재생성(로컬은 MySQL 볼륨 초기화도 가능)이 필요하다 —
  변경 보고에 반드시 SQL을 함께 적는다
- 상태값 컬럼은 `status`로 통일 (enum: PENDING/ACCEPTED/COMPLETED 등 문자열 저장)

## 관계 원칙
- `SeatMapLayout`은 **Venue(공연장) 단위**로 저장하고 재사용한다. Performance마다 새로 만들지 않는다
  (같은 공연장이면 좌석 배치가 동일 — NFR-03 재사용성).
- 공연·회차·공연장 (2026-10-06 확정):
  - `Venue`: 공유 기준 데이터. 사용자는 검색 후 선택, 없으면 추가. 중복 키 `normalized_name` unique
    (NFKC → 공백·구두점(P*)·보이지 않는 문자 제거 → 소문자, `Venue.normalizeName`). 일반 사용자 수정·삭제 불가.
  - `Performance`: 로그인 사용자 누구나 티켓팅 링크로 등록. 날짜 컬럼 없음(회차로 분리).
    중복 키 `source_key` unique (링크 정규화 값 — 사이트별 `{site}:{productId}`, 미지원 사이트는 일반 URL 정규화.
    계산은 서비스 책임). `registrant_id` → users. 공연장+제목은 unique 아님(같은 공연장 재공연 존재).
  - `PerformanceSession`: Performance 1:N. `starts_at`(분 단위 절삭, KST 현지 시각),
    unique (`performance_id`, `starts_at`).
  - `Ticket`은 `PerformanceSession`을 참조한다 (`performance_session_id`). `performance_id`를 중복으로 두지 않는다.
    좌석 교환은 **같은 회차의 티켓끼리만** 가능 (ExchangeMatch 생성 시 서비스에서 검사).
  - `Ticket.seatMapLayout.venue`는 `Ticket.performanceSession.performance.venue`와 같아야 한다 (서비스에서 검사).
- `ExchangeRequest`는 `Ticket`과 1:1 — 티켓 하나당 교환 요청은 하나만 유효.
- `ExchangeMatch`는 두 개의 `ExchangeRequest`(A측/B측)를 참조하는 단순 1:1 매칭 레코드다.
  추천 점수, 랭킹 등 알고리즘 매칭용 컬럼을 추가하지 않는다 (매칭 모델은 신청/수락 기반으로 고정).
- `Review`는 `ExchangeMatch` 완료 후에만 생성 가능하다.

## 08_ERD 원본 반영 대기 (원본 .dot/png 확보 시 적용)

2026-10-06 기준 저장소에 `산출물/08_ERD` 원본이 없어 다이어그램을 갱신하지 못했다. 원본 확보 시 아래를 반영한다.
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
