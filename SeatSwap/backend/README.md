# SeatSwap Backend (Spring Boot)

## 패키지 구조
- domain       — JPA 엔티티 (User·Performance·PerformanceSession·Ticket, 교환 희망 쪽 ExchangeRequest·ExchangeWantRange·ExchangeWantSession)와 enum·값 객체 (UserRole·TicketStatus·ExtraType·ExchangeRequestStatus·SeatKey). 공연장은 Performance.venueName 텍스트(V2에서 venue 테이블 삭제). 펼친 희망 좌석(exchange_want_seat)은 엔티티 없이 `ExchangeWantSeatRepository`(JdbcTemplate)가 다룬다
- repository   — JpaRepository
- service      — 비즈니스 로직
- controller   — REST API + WebSocket(STOMP)
- dto          — request/response
- config       — Security, WebSocket 설정
- security     — JWT 발급/검증
- exception    — 커스텀 예외 + 전역 핸들러

## 현재 상태
- 2026-10-07 방향 전환으로 좌석표 트랙 코드와 교환·채팅·후기 등 빈 스켈레톤(컨트롤러·서비스·저장소)을 삭제했다. 좌석표 코드는 git 태그 `archive/seatmap-track-20261007`에 보관되어 있다.
- 엔티티는 User·Performance·PerformanceSession·Ticket 4종이다. Ticket은 구역·열·번(표시용 label + 정규화 key)·상태(ACTIVE/INACTIVE)를 가지며 티켓 등록·조회·내리기 API가 있다(FR-03).
- **회원가입/로그인/JWT 인증(FR-01)은 구현 완료**:
  - `security/JwtTokenProvider` — access/refresh 토큰 발급·검증 (jjwt 0.12.5)
  - `security/JwtAuthenticationFilter` — Authorization 헤더 검증 후 SecurityContext 설정
  - `security/CustomUserDetailsService` — 이메일 기준 사용자 조회
  - `config/SecurityConfig` — JWT 필터 등록, CORS(개발용 localhost:5173 허용), `/api/auth/**` permitAll, `/api/admin/**`는 ADMIN 권한 (해당 컨트롤러는 아직 없음)
  - `service/AuthService`, `controller/AuthController` — POST /api/auth/signup, /login, /refresh
  - 요청 DTO는 `jakarta.validation`으로 기본 검증(이메일 형식, 비밀번호 8자 이상) 적용
- **공연·회차 등록/조회(FR-02)는 구현 완료** (`PerformanceController`, `PerformanceService`). 공연은 회차와 함께 등록하며, 등록 후에는 아무도 제목·공연장·회차를 수정하거나 공연·회차를 삭제할 수 없다(수정은 추후 관리자 수정 제안으로만, 후속). 공연장은 공연의 텍스트 속성 `venueName`(필수, 1~100자)이다.
- 남은 스켈레톤: `config/WebSocketConfig`는 클래스 선언과 `TODO: registerStompEndpoints(), configureMessageBroker()`만 있다 (채팅용, 미구현).
- **티켓 등록(FR-03)은 구현 완료** (`TicketController`, `TicketService`, `SeatKeyNormalizer`). 좌석 1개를 텍스트(구역 필수, 열·번은 숫자 또는 문자)로 등록한다. 같은 회차·구역·열·번의 활성 티켓은 1개(DB `uk_ticket_active_seat`), 사용자당 활성 티켓 20개 상한(`users` 행 FOR UPDATE로 직렬화), 회차 당일 끝(다음날 0시 KST)까지만 등록할 수 있다. 교환 희망 범위·매칭·예약은 아직 없다(V4 이후). 내릴 때 예약 잠금 검사는 V4 구현 시 `TicketService.ensureCanDeactivate`에 추가한다.
- **교환 희망 조건 등록(FR-04 교환 요청)은 구현 완료** (`ExchangeRequestController`, `ExchangeRequestService`, `WantSeatExpander`, Flyway V4). 티켓 하나에 요청 1개(추가금 유형·희망 회차 우선순위·희망 좌석 범위)를 등록·조회·수정·삭제한다. 범위는 (구역, 열 from~to, 번 from~to)로 입력하면 개별 좌석으로 펼쳐 `exchange_want_seat`에 저장한다. 후보 매칭·예약·채팅·차단·이력은 아직 없다(V5 이후). 티켓을 내리면(`DELETE /api/tickets/{id}`) 그 티켓의 교환 요청은 CLOSED로 바뀐다.
- 테스트는 254건이다.

## 인증 API

| Method | Path | 설명 |
|---|---|---|
| POST | /api/auth/signup | 회원가입 (email, password, nickname) → 생성된 사용자 정보 반환 |
| POST | /api/auth/login | 로그인 (email, password) → accessToken, refreshToken 반환 |
| POST | /api/auth/refresh | refreshToken으로 accessToken 재발급 |

## 공연·공연장·회차·내 정보 API

모두 로그인(Bearer 토큰)이 필요하다.

| Method | Path | 설명 |
|---|---|---|
| GET | /api/users/me | 내 정보 조회 |
| GET | /api/performances | 공연 목록 (제목 검색 query, page, size, asOf) |
| GET | /api/performances/lookup | 링크(sourceUrl)로 기존 공연 조회 ({exists, performanceId}) |
| GET | /api/performances/{id} | 공연 상세 |
| POST | /api/performances | 공연 등록 (201, 같은 링크가 있으면 409 + performanceId) |

## 티켓 API

모두 로그인이 필요하고 본인 티켓만 다룬다.

| Method | Path | 설명 |
|---|---|---|
| POST | /api/tickets | 티켓 등록 (body `{sessionId, zone, row, col}`, 201). 같은 좌석의 활성 티켓이 있으면 409 `{message, code: SEAT_ALREADY_REGISTERED \| MY_TICKET_ALREADY_REGISTERED}`(보유자 정보 없음), 활성 티켓 상한 초과 422 `{code: TICKET_LIMIT_REACHED, message}`, 지난 회차·없는 회차·좌석 입력 오류 400(필드 키 `sessionId`/`zone`/`row`/`col`) |
| GET | /api/tickets/me | 내 활성 티켓 (회차 시각 오름차순, 공연 제목·공연장 이름·회차 시각 포함) |
| DELETE | /api/tickets/{id} | 티켓 내리기(소프트 삭제 → INACTIVE, 204). 이미 내린 티켓도 204, 본인 티켓이 아니면 404. 그 티켓의 교환 요청은 CLOSED로 바뀐다 |

오류 형식은 API마다 다르다 (프론트는 상태 코드와 키로 구분한다).

| 상황 | 상태 | 본문 |
|---|---|---|
| 입력 검증(@Valid)·좌석 입력 오류·없는/지난 회차 | 400 | `{필드: 메시지}` — 좌석 입력 오류는 zone/row/col 오류를 한 번에 모아서 반환, `sessionId` 오류(없는 회차·마감)는 별도 |
| 본문 누락·JSON/타입 불일치(`/api/tickets/abc` 포함) | 400 | `{message}` |
| 인증 없음·토큰 오류 | 401 | `{message}` |
| 남의 티켓 내리기·없는 티켓 | 404 | `{message}` (존재 여부 비노출) |
| 같은 좌석 중복 | 409 | `{message, code}` |
| 활성 티켓 상한 | 422 | `{code, message}` |

(회차 마감 지난 등록은 현행대로 400 `sessionId`이며 422 `SESSION_CLOSED`로 바꾸지 않았다. 프론트 작업 때 재검토.)

좌석 정규화(비교 키): NFKC, 공백 제거, 영문 대문자. 제어·서식·제로폭·사설·미할당 문자(`\p{C}`)와 변이 선택자는 제거하지 않고 `<구역|열|번>에 사용할 수 없는 문자가 있습니다.`로 거부한다(label·key 공통). 숫자(아랍-인도 숫자 등 Nd)는 ASCII로 바꾸며 `03A`처럼 섞인 값은 그대로 허용한다(앞 0 제거는 순수 숫자만). 열은 끝의 '열', 번은 끝의 '번' 제거, 숫자는 앞 0 제거(`03` → `3`). 숫자는 1 이상 상한 이하(기본 999), 문자(`A`, `가`)도 허용한다. 구역 접미사('구역', '층')는 지우지 않는다. 표시용 원문은 공백만 정리해 `*_label`에 저장한다.
설정(`application.yml` `ticket.*`, 환경변수): `TICKET_MAX_ACTIVE_PER_USER`(20), `TICKET_MAX_ROW_NUMBER`(999), `TICKET_MAX_COL_NUMBER`(999).

## 교환 희망 조건 API

모두 로그인이 필요하고 본인 티켓·요청만 다룬다. **남의 티켓/요청은 403, 없는 id는 404**다(티켓 API는 존재 비노출 404였으나 이쪽은 403).

| Method | Path | 설명 |
|---|---|---|
| POST | /api/exchange/requests | 희망 조건 등록 (201). 내 ACTIVE 티켓만, 티켓당 요청 1개 |
| GET | /api/exchange/requests/me | 내 요청 목록 (id 오름차순). `?ticketId=`로 티켓별. 펼친 좌석 목록은 담지 않고 `wantSeatCount`(겹침 제거 후 개수)만 |
| PATCH | /api/exchange/requests/{id} | 범위·희망 회차·추가금 전체 교체 (200). 펼친 좌석을 전부 지우고 같은 트랜잭션에서 다시 만든다 |
| DELETE | /api/exchange/requests/{id} | 하드 삭제 (204). 범위·좌석·회차는 DB `ON DELETE CASCADE`로 함께 삭제 |

요청 본문 (POST는 `ticketId` 추가, PATCH는 `ticketId` 없음):

```json
{"ticketId": 500, "extraType": "NEG", "extraAmount": -10000,
 "wantSessions": [{"sessionId": 8, "priority": 1}, {"sessionId": 7, "priority": 2}],
 "ranges": [{"zone": "1층 A", "rowFrom": "3", "rowTo": "4", "colFrom": "3", "colTo": "5"}]}
```

- 추가금은 요청 단위다. `extraType`은 `X`(추가금 X) / `ANY`(상관없음) / `POS`(받아야만 교환, 금액 > 0) / `NEG`(낼 의향, 금액 < 0). X/ANY는 `extraAmount`를 보내면 400. 금액은 매칭 계산에 쓰지 않는 참고 표시용이며 + 는 내가 받을 금액, − 는 내가 낼 수 있는 금액이다.
- 희망 회차 `wantSessions`는 최소 1개, 내 티켓과 같은 공연의 회차만(내 티켓의 회차도 가능), 중복 불가. `priority`는 1(가장 높음)~999이며 같은 값도 허용한다.
- 범위: 구역은 필수·범위 대상 아님. 숫자 열·번만 `from~to` 범위이고 문자 열·번은 `from == to`(하나씩 추가, 대소문자 무시). 시작 > 끝, 0 이하(`0`, `-1`), 숫자-문자 혼합, 문자 from ≠ to는 필드 오류 400. 정규화는 티켓과 같은 `SeatKeyNormalizer`(공백 제거·대문자·앞 0 제거·끝의 '열'/'번' 제거, 숫자 상한 999)이며 오류 키에 범위 인덱스가 붙는다(`ranges[0].colTo`, `wantSessions[1].sessionId`).
- 범위끼리 겹치면 **합집합**이다(같은 좌석은 한 번만 저장, 거부 아님). 응답의 `ranges`는 입력한 범위를 그대로 돌려주되 zone은 표시용 원문, 열·번 from/to는 정규화 값이다(`03열` → `3`).
- **희망 회차가 내 티켓의 회차 하나뿐일 때만**, 내 티켓의 좌석(구역·열·번)이 펼친 희망 좌석에 포함되면 422 `WANT_INCLUDES_OWN_SEAT`. 같은 회차에서는 같은 좌석의 활성 티켓이 1개(`uk_ticket_active_seat`)이고 본인끼리는 매칭되지 않기 때문이다. 희망 회차에 다른 회차가 하나라도 있으면 내 좌석 위치가 범위에 들어 있어도 허용한다(다른 회차의 같은 자리 교환).
- 티켓 등록과 같은 마감 정책: 내 티켓의 회차가 마감(회차 당일 끝, 다음날 0시 KST)을 지났으면 등록·수정 모두 422 `SESSION_CLOSED`, 희망 회차가 이미 마감된 회차면 400 `wantSessions[i].sessionId`. 시계는 `SessionTimePolicy`(Clock 하나)만 쓴다.
- 열·번의 부호 붙은 정수형(`-3`, `+3`, 유니코드 마이너스 U+2212, 전각 부호)은 티켓 좌석과 희망 범위 모두 400(`<열|번>은 부호 없는 숫자(1 이상)로 입력해주세요.`)이다. 숫자 사이가 아닌 `A-3` 같은 값은 문자로 허용한다.
- 수정은 티켓이 INACTIVE이거나 요청이 CLOSED이면 422 `TICKET_NOT_ACTIVE`. 수정·삭제 전 '진행 중인 제안 있으면 409' 검사는 `ExchangeRequestService.ensureNoActiveProposal` 한 곳에 훅만 있고 지금은 항상 통과한다(매칭 테이블이 아직 없음).

### 교환 요청 오류 형식 (위 표에 더해)

| 상황 | 상태 | 본문 |
|---|---|---|
| 필드 검증·추가금 규칙·범위/회차 오류 | 400 | `{필드: 메시지}` — `ticketId`, `extraType`, `extraAmount`, `wantSessions`, `ranges`, `wantSessions[i].sessionId/priority`, `ranges[i].zone/rowFrom/rowTo/colFrom/colTo`. 추가금 오류와 범위 오류는 한 번에 모아서 반환 |
| 남의 티켓·남의 요청 | 403 | `{message}` |
| 없는 티켓·없는 요청 | 404 | `{message}` |
| 티켓에 이미 요청 있음 (동시 요청의 유일 제약 위반 포함) | 409 | `{message, code: REQUEST_ALREADY_EXISTS}` |
| 내린 티켓/CLOSED 요청 | 422 | `{code: TICKET_NOT_ACTIVE, message}` |
| 내 좌석이 희망 좌석에 포함 | 422 | `{code: WANT_INCLUDES_OWN_SEAT, message}` |
| 펼친 희망 좌석 수 상한 초과 | 422 | `{code: WANT_SEAT_LIMIT_EXCEEDED, message, count, limit}` — count는 구역별 직사각형 합집합 크기(응답의 `wantSeatCount`와 같은 의미)이며 좌표 압축으로 펼치기 전에 계산해 거부 |
| 내 티켓의 회차가 마감됨 | 422 | `{code: SESSION_CLOSED, message}` |
| 범위 개수 상한 초과 | 422 | `{code: WANT_RANGE_LIMIT_EXCEEDED, message, count, limit}` |

서버 안전 상한 설정(`application.yml` `exchange.want.*`, 사용자 대상 상한이 아니라 DoS 방어용): `EXCHANGE_WANT_MAX_SEATS`(5000, 요청당 펼친 좌석), `EXCHANGE_WANT_MAX_RANGES`(50, 요청당 범위). 열·번 숫자 상한은 `ticket.max-row-number`/`max-col-number`(999)를 재사용한다. DTO의 `@Size`(회차 100, 범위 1000)는 비정상적으로 큰 본문을 거르는 거친 한도이며 초과 시 400이다.

동시성: 등록은 **티켓 행 `FOR UPDATE`**(트랜잭션의 첫 쿼리)로 직렬화하고 `uk_exchange_request_ticket` 위반도 같은 409로 바꾼다. 수정·삭제는 **요청 행 `FOR UPDATE`**(`PESSIMISTIC_WRITE`)로 직렬화한다. 티켓 내리기도 티켓 행을 잠가(잠금 순서 티켓 → 요청) 등록·수정과 엇갈리지 않는다. 락 대기 실패는 503 `BUSY`.

그 외 모든 API는 `Authorization: Bearer {accessToken}` 헤더가 필요하다 (SecurityConfig 기준).

## DB 마이그레이션 (Flyway)

- 마이그레이션 파일: `src/main/resources/db/migration/V{n}__{snake_description}.sql` (V1 = 새 기준선 5개 테이블, V2 = `venue` 삭제·`performance.venue_name` 추가, V3 = `ticket` 좌석(구역·열·번)·상태 컬럼과 활성 좌석 유일 제약, V4 = 교환 희망 쪽 테이블 4개, 한국어 주석)
- 적용 이력: `SELECT * FROM flyway_schema_history;` (docker: `docker exec seatswap-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" seatswap -e "SELECT * FROM flyway_schema_history"'`)
- 규칙: 스키마 변경은 새 V 파일로만, 적용된 파일 수정 금지, `ddl-auto: validate`, 엔티티 변경과 마이그레이션을 함께 작성
- 앱 기동 시 자동 적용된다.
- 2026-10-07에 좌석표 트랙을 걷어내며 기준선을 새로 만들었다. 이전 V1~V3(12개+좌석표 테이블)와 좌석표 코드는 git 태그
  `archive/seatmap-track-20261007`에 보관되어 있다. **이전 스키마가 남은 로컬 DB는 `docker compose down -v`로 볼륨을 지운 뒤 다시 띄운다**
  (지우지 않으면 `flyway_schema_history`에 남은 옛 V1 체크섬이 달라 기동하지 않는다).
- **V2 적용 안내 (`venue` 삭제)**: 공연장은 `performance.venue_name` 텍스트가 되고 `venue` 테이블은 삭제된다.
  - 적용 전 점검: `SELECT COUNT(*) FROM performance p LEFT JOIN venue v ON v.id = p.venue_id WHERE v.id IS NULL;` 이 0이어야 한다(venue에 연결되지 않은 공연). 사라질 정보(`SELECT * FROM venue;`)도 확인한다.
  - 백업 권장: 적용 전 `mysqldump`로 `venue`·`performance`를 받아 둔다.
  - 되돌릴 수 없는 손실: `venue.address`, `status`, `verified_by`, `verified_at`, `normalized_name`.
  - 가드 실패(venue 매칭 없는 공연이 있어 SIGNAL로 중단)한 경우: 임시 프로시저 `v2_drop_venue`와 NULL 허용 `venue_name` 컬럼이 남을 수 있다. 데이터를 고친 뒤 `flyway repair`로 실패 기록을 지우고 다시 적용하면 남은 단계부터 이어서 진행된다(프로시저는 재실행 시 먼저 DROP 된다).
- **V3 적용 안내 (`ticket` 좌석 컬럼)**: `zone_label/zone_key`, `row_key`, `col_key`, `status`, `created_at/updated_at`, 생성 컬럼 `active_flag`, `uk_ticket_active_seat`(회차·구역·열·번·active_flag), `idx_ticket_user_status`를 추가하고 `row_label`/`col_label`을 NOT NULL VARCHAR(20)으로 바꾼다.
  - 가드: `ticket`에 행이 있으면 아무것도 바꾸기 전에 SIGNAL로 실패한다(새 NOT NULL 구역 컬럼에 채울 값이 없음). 적용 전 `SELECT COUNT(*) FROM ticket;`가 0인지 확인한다. 행이 있어 실패했다면 테스트 행을 지우고 `flyway repair`(실패 기록 삭제) 후 다시 적용한다. 임시 프로시저 `v3_guard_ticket_empty`가 남을 수 있으나 재실행 시 먼저 DROP 한다.
  - 주의: `./gradlew bootRun`은 `backend/.env`를 읽어 `SPRING_DATASOURCE_URL`을 덮어쓴다. 임시 DB로 검증하려면 `bootRun`이 아니라 `bootJar` 후 `java -jar`로 환경변수를 지정해 실행한다.
- **V4 적용 안내 (교환 희망 테이블)**: `exchange_request`(티켓당 1개 `uk_exchange_request_ticket`, 추가금 유형·금액 CHECK), `exchange_want_range`(입력한 범위, 수정 화면 복원용), `exchange_want_seat`(펼친 희망 좌석, PK = request_id + 구역·열·번 키), `exchange_want_session`(희망 회차 + 우선순위, PK = request_id + 회차)를 만든다. 자식 3개는 요청 삭제 시 `ON DELETE CASCADE`다. 기존 데이터를 건드리지 않아 가드가 없다. 차단·매칭·예약 잠금·채팅·이력 테이블은 V5 이후다. `exchange_want_range`의 `row_from/row_to/col_from/col_to`에는 정규화 키를 저장한다.
- MySQL 최소 버전 8.0.16 — 그 미만은 CHECK 제약을 문법만 받고 강제하지 않는다 (현재 docker 이미지는 mysql:8.0).

## 관리자 권한

- 관리자는 DB에서 직접 부여한다: `UPDATE users SET role = 'ADMIN' WHERE email = '...';` — **반드시 대문자 `ADMIN`으로만**.
  `users.role`은 `utf8mb4_bin` 컬럼이라 소문자(`admin`)는 CHECK 제약에서 거부된다.
  가입은 항상 USER이며 요청 본문의 role은 무시된다. role은 토큰에 넣지 않고 매 요청 DB에서 읽는다(변경 즉시 반영).

## 로컬 환경변수 (.env)

`./gradlew bootRun`으로 로컬 실행 시, `backend/.env` 파일이 있으면 `build.gradle`의
`bootRun` 태스크가 자동으로 읽어서 환경변수로 주입한다 (Docker Compose처럼 `.env`를
그냥 쓸 수 있게 하기 위함). 최초 1회:

```bash
cp .env.example .env
# .env를 열어 JWT_SECRET 등 실제 값으로 채운다 (openssl rand -base64 32로 생성)
./gradlew bootRun
```

`.env`는 `.gitignore`에 등록되어 있어 커밋되지 않는다.

## 후속 메모 (티켓 등록)
- V4 후보 조회·스케줄러에서 회차 마감(당일 끝)이 지난 회차의 티켓은 제외하거나 비활성 처리한다.
- `POST /api/tickets` 레이트 리밋을 도입할 때 함께 포함한다.
- (V4 반영 완료) 티켓 내리기는 티켓 행 `FOR UPDATE`(`findByIdForUpdate`)로 바뀌었고 그 티켓의 교환 요청을 CLOSED로 닫는다. 여러 티켓을 잠그는 매칭·예약 단계에서는 ticket id 오름차순으로 잠근다(데드락 방지).
- 내릴 때 '교환 요청이 있으면 409' 대신 요청을 CLOSED로 닫는 쪽으로 구현했다(설계 1.2: CLOSED = 티켓 내림). 예약 잠금이 생기면 `TicketService.ensureCanDeactivate`에서 409로 막는다.

## 후속 메모 (교환 희망 조건)
- `ExchangeRequestService.ensureNoActiveProposal`: 매칭·채팅 구현 때 열린 매칭이 있으면 409 `ConflictException`을 던지도록 채운다(수정·삭제 공통 훅).
- 후보 조회는 `exchange_want_seat` PK(request_id, 구역, 열, 번)와 `uk_ticket_active_seat`를 쓴다(설계 2절 SQL). 요청 CLOSED/티켓 INACTIVE는 후보 SQL의 `status='OPEN'`·`active_flag=1`로 걸러진다.
- 펼친 좌석 INSERT는 500행씩 다중 행 `INSERT`(JdbcTemplate)다. 상한(5,000) 요청의 등록·수정은 로컬 MySQL 기준 약 150~200ms였다.
- 요청 목록의 `ranges` 열·번은 정규화 값이다(원문 표기 복원이 필요하면 V5에서 label 컬럼을 추가한다).
- 알려진 한계(L4): 수정(PATCH)은 범위·좌석·회차를 전부 지우고 다시 만드는 방식이라 상한 근처(5,000석) 요청은 매번 5,000행을 다시 쓴다(약 150ms). 희망 좌석에는 회차가 없어 "같은 자리의 다른 회차만 원하고 같은 회차의 같은 자리는 원하지 않는" 구분은 할 수 없다. 두 한계 모두 현재 설계(1.3~1.5)의 결과다.

## 수동 검증 시나리오 (임시 MySQL, 재현용)

자동 통합 테스트(Testcontainers)는 두지 않고, 아래 절차로 실제 MySQL에서 확인한다. 개발 DB(3306)와 `backend/.env`를 쓰지 않도록 `bootRun`이 아니라 `bootJar` + `java -jar`로 환경변수를 직접 지정한다.

```bash
cd SeatSwap/backend && ./gradlew bootJar
docker run -d --name seatswap-tmp-v4 -e MYSQL_ROOT_PASSWORD=tmp -e MYSQL_DATABASE=seatswap -p 13306:3306 mysql:8.0 --character-set-server=utf8mb4
SERVER_PORT=18080 SPRING_DATASOURCE_URL=jdbc:mysql://localhost:13306/seatswap SPRING_DATASOURCE_USERNAME=root   SPRING_DATASOURCE_PASSWORD=tmp JWT_SECRET=tmp-verification-secret-key-0123456789-abcdefghijklmnop   java -Duser.timezone=Asia/Seoul -jar build/libs/backend-0.0.1-SNAPSHOT.jar      # 종료는 이 프로세스 PID만
# 끝나면: docker rm -f seatswap-tmp-v4
```

준비(curl): 가입·로그인으로 토큰 `$T`를 얻고, 공연(회차 2개 이상, 미래 일시)과 티켓을 만든 뒤 아래를 순서대로 확인한다. 아래 SQL은 `docker exec seatswap-tmp-v4 mysql -uroot -ptmp seatswap -e "..."`로 실행한다.

1. **V4 적용·validate**: 기동 로그에 `Successfully applied 4 migrations ... v4`, `SELECT constraint_name FROM information_schema.check_constraints WHERE constraint_schema='seatswap' AND constraint_name LIKE 'ck_exchange%';` 가 5건.
2. **CHECK 제약**: `INSERT INTO exchange_request(ticket_id,extra_type,extra_amount,status,created_at,updated_at) VALUES (1,'POS',-5,'OPEN',NOW(),NOW());` -> ERROR 3819 `ck_exchange_request_amount`.
3. **청크 경계(500행씩 INSERT)**: 티켓마다 `POST /api/exchange/requests`로 1행 x 500번(500석), 500+1(501석), 2열 x 500번(1000석), 5열 x 999번 + 1열 x 5번(5000석)을 등록하고 `SELECT COUNT(*) FROM exchange_want_seat WHERE request_id=?;` 가 응답의 `wantSeatCount`와 같은지 확인.
4. **합집합 상한**: 같은 범위(5열 x 999번)를 2번 넣으면 201, `wantSeatCount` 4995. 5001석이 되는 입력은 422 `{count: 5001, limit: 5000}`, 999 x 999는 즉시(약 10ms) 422.
5. **CASCADE**: `DELETE /api/exchange/requests/{id}` 204 뒤 `SELECT (SELECT COUNT(*) FROM exchange_want_seat WHERE request_id=?)+(SELECT COUNT(*) FROM exchange_want_range WHERE request_id=?)+(SELECT COUNT(*) FROM exchange_want_session WHERE request_id=?);` = 0.
6. **uk 위반 409**: 같은 티켓에 POST를 두 스레드로 동시에 보내면 201 1건 + 409 `REQUEST_ALREADY_EXISTS` 1건, `SELECT COUNT(*) FROM exchange_request WHERE ticket_id=?;` = 1.
7. **closeByTicketId**: 요청이 있는 티켓을 `DELETE /api/tickets/{id}`(204) 한 뒤 `SELECT status FROM exchange_request WHERE id=?;` = `CLOSED`, 그 요청 PATCH는 422 `TICKET_NOT_ACTIVE`.
8. **동시 PATCH 직렬화**: 같은 요청에 서로 다른 입력 2개를 동시에 PATCH(반복)한 뒤 `exchange_want_range` 1행, `exchange_want_seat`의 구역·개수, `exchange_want_session`, `extra_type/extra_amount`가 둘 중 한쪽 입력과 정확히 일치.
9. **H1/L1**: 희망 회차를 내 회차 하나로 하고 내 좌석을 포함하면 422, 다른 회차를 함께 넣으면 201. 행 `-3` 또는 `−3`은 400.

(마감된 회차의 422/400은 회차 등록이 지난 일시를 거부해 HTTP로 만들 수 없어 단위 테스트로만 검증한다.)

## 다음 단계
1. 교환 도메인 구현 계속: (완료) V4 희망 범위·희망 좌석·희망 회차 / 남음: V5 이후 차단·매칭 후보 조회·예약 잠금·이력 마이그레이션과 엔티티 (설계안 `산출물/08_ERD/exchange-schema-design.md`)
2. 티켓 자동 비활성(회차 당일 끝 경과, 스케줄러)과 '내 티켓 인증'
3. 자동 매칭 (후보 제시, 양쪽 수락으로 확정)
4. 채팅(WebSocketConfig 구현)·후기
5. 배포 시 SecurityConfig의 CORS allowed-origin을 실제 프론트 도메인으로 교체
