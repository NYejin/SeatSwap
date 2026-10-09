# SeatSwap Backend (Spring Boot)

## 패키지 구조
- domain       — JPA 엔티티 (User·Performance·PerformanceSession·Ticket, 교환 희망 쪽 ExchangeRequest·ExchangeWantRange·ExchangeWantSession, 매칭 ExchangeMatch)와 enum·값 객체 (UserRole·TicketStatus·ExtraType·ExchangeRequestStatus·ExchangeMatchStatus·ExchangeMatchAction·SeatKey). 예약 잠금(exchange_ticket_lock)은 엔티티 없이 `ExchangeTicketLockRepository`(JdbcTemplate)가 다룬다 공연장은 Performance.venueName 텍스트(V2에서 venue 테이블 삭제). 펼친 희망 좌석(exchange_want_seat)은 엔티티 없이 `ExchangeWantSeatRepository`(JdbcTemplate)가 다룬다
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
- **교환 희망 조건 등록(FR-04 교환 요청)은 구현 완료** (`ExchangeRequestController`, `ExchangeRequestService`, `WantSeatExpander`, Flyway V4). 티켓 하나에 요청 1개(추가금 유형·희망 회차 우선순위·희망 좌석 범위)를 등록·조회·수정·삭제한다. 범위는 (구역, 열 from~to, 번 from~to)로 입력하면 개별 좌석으로 펼쳐 `exchange_want_seat`에 저장한다. **매칭 후보 조회**(`GET /api/exchange/requests/{id}/candidates`, 읽기 전용)는 구현됐다. 티켓을 내리면(`DELETE /api/tickets/{id}`) 그 티켓의 교환 요청은 CLOSED로 바뀐다.
- **매칭 생성·예약(FR-04 교환 흐름 일부)은 구현 완료** (`ExchangeMatchController`, `ExchangeMatchService`, Flyway V5). 후보를 골라 매칭(채팅 단계, CHATTING)을 만들고, 양쪽이 '이 사람과 교환할게요'를 누르면 RESERVED가 되어 두 티켓이 잠긴다(**현재 구현 사실. 8차 답변(2026-10-09)으로 '둘 중 한 명이 예약하면 RESERVED, 한 명이 예약을 취소하면 CHATTING 복귀' 방식으로 대체되어 `reserve`/`unreserve`로 바뀔 예정, 미구현**). 거절·취소는 양쪽 완료 전 누구나 가능하다. **아직 없는 것**: 교환 완료(COMPLETED, 양쪽 '교환 완료'), 채팅 메시지·방, 교환 이력, 사용자 차단. (내 매칭 조회 `GET /api/exchange/matches/me`·`/{id}`는 구현됨) 아래 '매칭 API' 참고.
- (2026-10-09 리뷰 반영 후 최신 수치: 기본 `./gradlew test`는 전체 450건 중 79건을 건너뛰고 0 실패(실행 371건), `SEATSWAP_IT_REQUIRED=true`와 `SEATSWAP_IT_*`를 주면 467건 모두 실행·0 실패(건너뜀 0). 직전(V6/V7 첫 반영) 수치는 439건/456건. 아래 수치는 이전 기준.) (2026-10-08 내 매칭 조회 추가 후 수치: 기본 `./gradlew test`는 전체 408건 중 60건을 건너뛰고 0 실패, `SEATSWAP_IT_REQUIRED=true`와 환경변수를 주면 425건 모두 실행·0 실패. 새 `ExchangeMatchQueryMysqlTest` 12건 포함. 아래 옛 수치는 이전 기준.) 기본 `./gradlew test`는 343건을 실행하고 48건을 건너뛴다(실제 MySQL이 필요한 `ExchangeCandidateQueryTest`와 `ExchangeMatchMysqlTest`는 `SEATSWAP_IT_JDBC_URL`이 없으면 통째로 건너뛰며 테스트 리포트에 `[SKIPPED ...]` 메시지가 남는다). 환경변수를 주면 건너뛴 48건이 모두 돌아 408건 전부 실행된다. `SEATSWAP_IT_REQUIRED=true`(또는 `CI` 환경변수가 있으면)는 건너뛰지 않고 실패한다. 주의: Gradle은 환경변수를 입력으로 보지 않아 이전 결과를 재사용하므로 환경을 바꿔 다시 돌릴 때는 `./gradlew cleanTest test`를 쓴다. 아래 '매칭 후보 조회 검증' 참고.

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
| POST | /api/exchange/requests | 희망 조건 등록 (201). 내 ACTIVE 티켓만, 티켓당 미삭제 요청 1개 (삭제한 뒤에는 같은 티켓에 새로 등록 가능) |
| GET | /api/exchange/requests/me | 내 요청 목록 (id 오름차순, **삭제된 요청 제외**). `?ticketId=`로 티켓별. 펼친 좌석 목록은 담지 않고 `wantSeatCount`(겹침 제거 후 개수)만 |
| PATCH | /api/exchange/requests/{id} | 범위(추가금 포함)·희망 회차 전체 교체 (200). 펼친 좌석을 전부 지우고 같은 트랜잭션에서 다시 만든다. 이 요청의 CHATTING 매칭은 시스템 취소, RESERVED가 있으면 409 `ACTIVE_MATCH_EXISTS`, 삭제된 요청은 409 `REQUEST_DELETED` |
| DELETE | /api/exchange/requests/{id} | **소프트 삭제** (204): status `DELETED` + `deleted_at`, 범위·좌석·회차 행은 같은 트랜잭션에서 삭제. 이미 삭제됐으면 멱등 204. CHATTING 매칭은 시스템 취소(`canceledBy: SYSTEM`), RESERVED가 있으면 409 `ACTIVE_MATCH_EXISTS`, 교환 완료(COMPLETED) 매칭이 있어도 삭제 가능(매칭 행·추가금 스냅샷은 남는다) |

요청 본문 (POST는 `ticketId` 추가, PATCH는 `ticketId` 없음):

```json
{"ticketId": 500,
 "wantSessions": [{"sessionId": 8, "priority": 1}, {"sessionId": 7, "priority": 2}],
 "ranges": [{"zone": "1층 A", "rowFrom": "3", "rowTo": "4", "colFrom": "3", "colTo": "5", "extraType": "NEG", "extraAmount": -10000},
            {"zone": "1층 A", "rowFrom": "5", "rowTo": "5", "colFrom": "1", "colTo": "2", "extraType": "ANY"}]}
```

- **추가금은 희망 범위 단위다(V6)**: 범위마다 `extraType`(필수)과 `extraAmount`가 있고 요청 단위 추가금은 없다. `extraType`은 `X`(추가금 X) / `ANY`(상관없음) / `POS`(받아야만 교환, 금액 > 0) / `NEG`(낼 의향, 금액 < 0). X/ANY는 `extraAmount`를 보내면 400, POS는 0보다 큰 값, NEG는 0보다 작은 값(오류 키 `ranges[i].extraType`/`ranges[i].extraAmount`). 판정은 유형만 보며 불성립은 POS–POS·POS–X·X–POS뿐(X–X·NEG–NEG 성립, 확정)이다. 금액은 매칭 계산에 쓰지 않는 참고 표시용이며 + 는 내가 받을 금액, − 는 내가 낼 수 있는 금액이다.
- 희망 회차 `wantSessions`는 최소 1개, 내 티켓과 같은 공연의 회차만(내 티켓의 회차도 가능), 중복 불가. `priority`는 1(가장 높음)~999이며 같은 값도 허용한다.
- 범위: 구역은 필수·범위 대상 아님. 숫자 열·번만 `from~to` 범위이고 문자 열·번은 `from == to`(하나씩 추가, 대소문자 무시). 시작 > 끝, 0 이하(`0`, `-1`), 숫자-문자 혼합, 문자 from ≠ to는 필드 오류 400. 정규화는 티켓과 같은 `SeatKeyNormalizer`(공백 제거·대문자·앞 0 제거·끝의 '열'/'번' 제거, 숫자 상한 999)이며 오류 키에 범위 인덱스가 붙는다(`ranges[0].colTo`, `wantSessions[1].sessionId`).
- 범위끼리 겹치면 **합집합**이다(같은 좌석은 한 번만 저장). 단 **겹치는 좌석의 추가금(유형 또는 금액)이 다르면 422 `WANT_EXTRA_CONFLICT`**이고 `conflicts: [[i, j], ...]`(충돌하는 범위 인덱스 쌍, 최대 20쌍)를 담는다. 유형·금액이 완전히 같은 겹침은 허용한다. 좌석 한 행이 하나의 추가금만 가지므로(`exchange_want_seat` PK 불변) 후보 SQL의 조인 행 수는 늘지 않는다. 응답의 `ranges`는 입력한 범위를 그대로(추가금 `extraType/extraAmount` 포함) 돌려주되 zone은 표시용 원문, 열·번 from/to는 정규화 값이다(`03열` → `3`).
- **희망 회차가 내 티켓의 회차 하나뿐일 때만**, 내 티켓의 좌석(구역·열·번)이 펼친 희망 좌석에 포함되면 422 `WANT_INCLUDES_OWN_SEAT`. 같은 회차에서는 같은 좌석의 활성 티켓이 1개(`uk_ticket_active_seat`)이고 본인끼리는 매칭되지 않기 때문이다. 희망 회차에 다른 회차가 하나라도 있으면 내 좌석 위치가 범위에 들어 있어도 허용한다(다른 회차의 같은 자리 교환).
- 티켓 등록과 같은 마감 정책: 내 티켓의 회차가 마감(회차 당일 끝, 다음날 0시 KST)을 지났으면 등록·수정 모두 422 `SESSION_CLOSED`, 희망 회차가 이미 마감된 회차면 400 `wantSessions[i].sessionId`. 시계는 `SessionTimePolicy`(Clock 하나)만 쓴다.
- 열·번의 부호 붙은 정수형(`-3`, `+3`, 유니코드 마이너스 U+2212, 전각 부호)은 티켓 좌석과 희망 범위 모두 400(`<열|번>은 부호 없는 숫자(1 이상)로 입력해주세요.`)이다. 숫자 사이가 아닌 `A-3` 같은 값은 문자로 허용한다.
- 수정은 티켓이 INACTIVE이거나 요청이 CLOSED이면 422 `TICKET_NOT_ACTIVE`. 수정·삭제 전 열린 매칭 처리는 `ExchangeRequestService.cancelChattingOrRejectReserved` 한 곳에 있다: 이 요청의 열린 매칭 id를 오름차순으로 읽어 하나씩 `FOR UPDATE`로 잠근 뒤(OR 조건의 한 방 FOR UPDATE는 무관한 행까지 잠글 수 있어 쓰지 않음), RESERVED가 있으면 409 `ACTIVE_MATCH_EXISTS`(아무것도 바꾸지 않음), 아니면 CHATTING을 모두 시스템 취소한다(`canceled_by` NULL). 모든 검증이 끝난 뒤에 실행하므로 검증 실패 시 채팅은 그대로다. 티켓 행은 잠그지 않는다.

### 교환 요청 오류 형식 (위 표에 더해)

| 상황 | 상태 | 본문 |
|---|---|---|
| 필드 검증·추가금 규칙·범위/회차 오류 | 400 | `{필드: 메시지}` — `ticketId`, `wantSessions`, `ranges`, `wantSessions[i].sessionId/priority`, `ranges[i].zone/rowFrom/rowTo/colFrom/colTo/extraType/extraAmount`. 추가금 오류와 범위 오류는 한 번에 모아서 반환 |
| 남의 티켓·남의 요청 | 403 | `{message}` |
| 없는 티켓·없는 요청 | 404 | `{message}` |
| 티켓에 이미 요청 있음 (동시 요청의 유일 제약 위반 포함) | 409 | `{message, code: REQUEST_ALREADY_EXISTS}` |
| 내린 티켓/CLOSED 요청 | 422 | `{code: TICKET_NOT_ACTIVE, message}` |
| 겹치는 범위의 추가금이 다름 | 422 | `{code: WANT_EXTRA_CONFLICT, message, conflicts: [[0,1], ...]}` |
| 예약(RESERVED)된 매칭이 있는 요청의 수정·삭제 | 409 | `{message, code: ACTIVE_MATCH_EXISTS}` |
| 삭제된 요청의 수정·후보 조회·내 요청으로 제안(상대 요청이 삭제된 제안은 422 `NOT_A_CANDIDATE`) | 409 | `{message, code: REQUEST_DELETED}` (삭제 API 자체는 멱등 204) |
| 내 좌석이 희망 좌석에 포함 | 422 | `{code: WANT_INCLUDES_OWN_SEAT, message}` |
| 펼친 희망 좌석 수 상한 초과 | 422 | `{code: WANT_SEAT_LIMIT_EXCEEDED, message, count, limit}` — count는 구역별 직사각형 합집합 크기(응답의 `wantSeatCount`와 같은 의미)이며 좌표 압축으로 펼치기 전에 계산해 거부 |
| 내 티켓의 회차가 마감됨 | 422 | `{code: SESSION_CLOSED, message}` |
| 범위 개수 상한 초과 | 422 | `{code: WANT_RANGE_LIMIT_EXCEEDED, message, count, limit}` |

서버 안전 상한 설정(`application.yml` `exchange.want.*`, 사용자 대상 상한이 아니라 DoS 방어용): `EXCHANGE_WANT_MAX_SEATS`(5000, 요청당 펼친 좌석), `EXCHANGE_WANT_MAX_RANGES`(50, 요청당 범위). 열·번 숫자 상한은 `ticket.max-row-number`/`max-col-number`(999)를 재사용한다. DTO의 `@Size`(회차 100, 범위 1000)는 비정상적으로 큰 본문을 거르는 거친 한도이며 초과 시 400이다.

동시성: 등록은 **티켓 행 `FOR UPDATE`**(트랜잭션의 첫 쿼리)로 직렬화하고 `uk_exchange_request_live_ticket` 위반도 같은 409로 바꾼다. 수정·삭제는 **요청 행 `FOR UPDATE`**(`PESSIMISTIC_WRITE`)로 직렬화한다. 티켓 내리기도 티켓 행을 잠가(잠금 순서 티켓 → 요청) 등록·수정과 엇갈리지 않는다. 락 대기 실패는 503 `BUSY`.

## 매칭 API (후보 선택 -> 채팅 -> 예약)

모두 로그인이 필요하다. 매칭은 조건 일치 판정으로 찾은 후보를 사용자가 골라 시작하며 점수화·랭킹·신뢰도는 쓰지 않는다. 흐름: 후보 선택 -> 채팅(CHATTING, 한 요청에 여러 개 동시 가능) -> 양쪽 '이 사람과 교환할게요'(RESERVED, 두 티켓 잠금, **티켓당 예약 1개**; 현재 구현. 8차 답변(2026-10-09)으로 한 명이 예약해도 RESERVED, 한 명이 예약 취소하면 CHATTING 복귀하는 방식으로 대체 예정) -> (후속) 양도 후 각자 '교환 완료' -> COMPLETED. 취소는 양쪽 완료 전 누구나 가능하고 상태만 원상태로 돌아간다(재매칭 불가는 차단·신고뿐이며 둘 다 후속).

| Method | Path | 설명 |
|---|---|---|
| POST | /api/exchange/requests/{id}/proposals | 후보를 골라 매칭 생성 (201, 본문 `{"targetRequestId": 800}`). `{id}`는 내 요청. 응답은 아래 매칭 응답 |
| POST | /api/exchange/matches/{id}/accept | 내 쪽 예약 동의 (200). 한쪽만 누르면 CHATTING 유지, 양쪽이 누르면 RESERVED + 두 티켓 잠금. 이미 눌렀다면 멱등 200 (현재 구현. 8차 답변(2026-10-09)으로 `POST .../reserve`(한 명이 누르면 RESERVED)와 `POST .../unreserve`(RESERVED -> CHATTING 복귀)로 대체 예정, 미구현) |
| POST | /api/exchange/matches/{id}/reject | 제안받은 쪽(b)의 거절 (200, 결과 CANCELED). 제안한 쪽이 부르면 403 |
| POST | /api/exchange/matches/{id}/cancel | 참여자 누구나 취소 (200, 결과 CANCELED). RESERVED였다면 잠금 해제 |
| GET | /api/exchange/matches/me | 내 매칭 목록(읽기 전용). `role=SENT\|RECEIVED\|ALL`(기본 ALL; 보낸=내가 제안자 a측, 받은=b측), `status=CHATTING\|RESERVED\|COMPLETED\|CANCELED`(선택, 반복 또는 쉼표로 여러 개), `page`(0부터), `size`(기본 20, 1 미만은 400 필드 오류, 100 초과는 100으로 보정). `updated_at` 내림차순(동률 id 내림차순). 잘못된 role·status·page는 400 필드 오류. 응답은 PageResponse |
| GET | /api/exchange/matches/{id} | 내 매칭 단건(목록 항목과 같은 모양). 비참여자·없는 매칭은 똑같이 404 `매칭을 찾을 수 없습니다.` |

호출 예시 (`T`는 로그인 토큰):

```bash
curl -s -X POST localhost:8080/api/exchange/requests/721/proposals -H "Authorization: Bearer $T" \
     -H 'Content-Type: application/json' -d '{"targetRequestId":722}'      # 201 {"id":401,"status":"CHATTING",...}
curl -s -X POST localhost:8080/api/exchange/matches/401/accept -H "Authorization: Bearer $T"   # 내 쪽 동의
curl -s -X POST localhost:8080/api/exchange/matches/401/cancel -H "Authorization: Bearer $T"   # 취소
```

매칭 응답 (POST 응답·목록·단건이 모두 같은 모양, 호출자 기준): `id, status(CHATTING|RESERVED|COMPLETED|CANCELED), mySide(A=제안자|B), role(SENT=내가 a측|RECEIVED=b측), myRequestId, myTicketId, mySeat{zone,row,col,sessionId,startsAt}, counterpartRequestId, counterpartTicketId, counterpartSeat{...}, counterpartNickname, myExtraType, myExtraAmount, counterpartExtraType, counterpartExtraAmount, myRequestDeleted, counterpartRequestDeleted, myReservedAt, counterpartReservedAt(null이면 아직 안 누름), canceledBy(ME|COUNTERPART|SYSTEM, CANCELED일 때만), canceledAt, createdAt, updatedAt`. 좌석의 zone·row·col은 사용자가 입력한 표시용 원문이고 `startsAt`은 `yyyy-MM-dd'T'HH:mm`. 상대의 이메일 등 개인정보는 내려가지 않고 닉네임만 있다. 추가금 유형은 X/ANY/POS/NEG이며 금액은 참고용이다. **추가금은 매칭을 만들 때 저장한 스냅샷**(`exchange_match.a/b_extra_*`: 내 범위 중 상대 좌석을 포함한 범위의 값, 상대 범위 중 내 좌석을 포함한 범위의 값)이라 이후 요청을 수정·삭제해도 바뀌지 않는다. `myRequestDeleted/counterpartRequestDeleted`는 그 쪽 요청이 삭제(DELETED)됐는지다(매칭 기록은 남는다). (이전 POST 응답의 필드는 그대로 두고 필드만 추가했다.)

```json
{"id":401,"status":"RESERVED","mySide":"B","role":"RECEIVED","myRequestId":721,"myTicketId":611,
 "mySeat":{"zone":"A구역","row":"1","col":"1","sessionId":7,"startsAt":"2026-11-01T19:00"},
 "counterpartRequestId":722,"counterpartTicketId":612,
 "counterpartSeat":{"zone":"B구역","row":"2","col":"3","sessionId":8,"startsAt":"2026-11-02T19:00"},
 "counterpartNickname":"상대3","myExtraType":"X","myExtraAmount":null,"counterpartExtraType":"NEG","counterpartExtraAmount":-10000,
 "myRequestDeleted":false,"counterpartRequestDeleted":false,
 "myReservedAt":"2026-10-08T12:00:05","counterpartReservedAt":"2026-10-08T11:30:00","canceledBy":null,"canceledAt":null,
 "createdAt":"2026-10-08T11:00:00","updatedAt":"2026-10-08T12:00:05"}
```

**내 매칭 조회 구현 메모.** `ExchangeMatchQueryRepository`(JdbcTemplate, `STRAIGHT_JOIN`)가 매칭 1건당 한 번의 조인으로 양쪽 티켓 좌석·회차·추가금 스냅샷·요청 삭제 여부·닉네임을 읽는다(목록은 COUNT 1회 + 목록 1회, 단건 1회로 행 수와 무관). 읽기 전용 트랜잭션·잠금 없음. POST 응답(제안·수락·거절·취소)도 같은 조인으로 만들며 쓰기 트랜잭션 안에서 읽어 방금 쓴 상태를 그대로 돌려준다. **인덱스는 새로 만들지 않았다.** EXPLAIN(매칭 5,400행): SENT=`idx_exchange_match_user_a` ref, RECEIVED=`idx_exchange_match_user_b` ref, ALL=`index_merge` union(user_a, user_b), 나머지 8개 조인은 모두 PK `eq_ref`. 정렬은 사용자당 소수의 행에 대한 filesort라 전용 인덱스는 필요 없다. 사용자당 매칭이 수천 건이 되면 후속 마이그레이션에서 `(user_a_id, updated_at)`/`(user_b_id, updated_at)` 인덱스 또는 id 선조회 후 조인을 검토한다(주의: FK 인덱스에 갱신 컬럼을 넣지 말 것 규칙과 충돌하므로 FK용 단일 인덱스는 유지하고 별도로 추가). 목록은 INNER JOIN 8개, COUNT는 `exchange_match`만 세며 FK 때문에 고아 행이 없다는 전제다. **users 익명화·티켓 삭제를 도입하면 LEFT JOIN 또는 COUNT에도 같은 조인을 쓴다.** 주의: 완료(COMPLETED) 교체가 구현되면 좌석은 '현재' 티켓 자리이므로 교환 전 자리는 교환 이력 스냅샷이 담당한다.

**제안 시 재검증(쌍 단위, 후보 SQL과 같은 판정)**: 같은 공연, 상대 티켓의 회차 ∈ 내 희망 회차·내 티켓의 회차 ∈ 상대 희망 회차, 상대 좌석 ∈ 내 희망 좌석·내 좌석 ∈ 상대 희망 좌석, 추가금 유형 호환, 양쪽 요청 OPEN·티켓 ACTIVE, 상대 회차 마감 전, 다른 사용자, 양쪽 티켓 예약 잠금 없음. 후보 화면이 오래돼 조건이 바뀌었으면 422.

**허용/불허 상태 전이 표** (`ExchangeMatch.isAllowed`, `ExchangeMatchService` Javadoc과 같은 표. 불허는 모두 409 `MATCH_STATE_CONFLICT`):

| 상태 \ 동작 (현재 구현. 8차 답변(2026-10-09)으로 accept는 reserve/unreserve로, RESERVED의 reject·cancel 허용은 확인 필요 Q-17) | propose | accept | reject(b측) | cancel | complete |
|---|---|---|---|---|---|
| CHATTING | 409(같은 쌍 열린 매칭) | 허용 | 허용 | 허용 | 불허(예약 전) |
| RESERVED | 409(같은 쌍 열린 매칭) | 멱등 200 | 허용(잠금 해제) | 허용(잠금 해제) | 허용 (미구현) |
| COMPLETED | 허용(새 매칭) | 409 | 409 | 409 | 409 |
| CANCELED | 허용(새 매칭) | 409 | 409 | 409 | 409 |

propose의 상태는 '같은 요청 쌍의 가장 최근 매칭' 기준이다. 열린 매칭(CHATTING·RESERVED)이 없으면 취소·완료된 뒤에도 같은 쌍으로 다시 만들 수 있다.

### 매칭 오류 형식 (위 표에 더해)

| 상황 | 상태 | 본문 |
|---|---|---|
| `targetRequestId` 누락 | 400 | `{targetRequestId: 메시지}` |
| 남의 요청으로 제안 / 제안한 쪽의 reject | 403 | `{message}` |
| 없는 요청·없는 매칭, **매칭 참여자가 아닌 사용자**(없는 매칭과 같은 404, 존재 은닉) | 404 | `{message}` |
| 같은 쌍의 열린 매칭이 이미 있음 (반대 방향 제안 포함) | 409 | `{message, code: MATCH_ALREADY_OPEN, matchId}` |
| 상태 전이 불허 (이미 CANCELED/COMPLETED 등) | 409 | `{message, code: MATCH_STATE_CONFLICT, status, action}` |
| 열린 매칭이 있는 요청 수정·삭제 (`PATCH/DELETE /api/exchange/requests/{id}`) | 409 | `{message, code: ACTIVE_MATCH_EXISTS}` |
| (제거됨, 2026-10-09 7차 답변) 완료된 매칭 기록이 있는 요청 삭제 — 소프트 삭제라 이제 허용되며 `MATCH_HISTORY_EXISTS`는 쓰지 않는다 | - | - |
| 내 요청이 닫힘/티켓 내림 | 422 | `{code: TICKET_NOT_ACTIVE, message}` |
| 내 회차 마감 (propose만; 이미 시작한 채팅의 accept에는 적용하지 않음) | 422 | `{code: SESSION_CLOSED, message}` |
| 후보 조건 불충족 (자기 자신·같은 사용자·상대 요청 닫힘·좌석/회차/추가금 불일치) **또는 상대 티켓이 예약 잠금**(후보에서 빠진 것과 같게 취급) | 422 | `{code: NOT_A_CANDIDATE, message}` |

**예약 잠금(같은 원인)에서 나오는 코드 묶음** — 한 티켓은 동시에 한 매칭에서만 예약되며, 상황별로 코드가 다르다.

| 코드 | 상태 | 언제 |
|---|---|---|
| `TICKET_LOCKED` | 422 | **내** 티켓이 예약 잠금인데 제안하거나 후보를 조회할 때. 상대 티켓이 잠긴 경우에는 쓰지 않는다(`NOT_A_CANDIDATE`로 합쳐 상대의 예약 상태를 노출하지 않는다) |
| `TICKET_ALREADY_RESERVED` | 409 | 매칭 참여자가 accept 하는데 이 매칭의 티켓이 이미 다른 매칭에서 예약됨 (참여자 사이의 화면이라 노출해도 되는 정보) |
| `TICKET_RESERVED` | 409 | 예약 잠금이 걸린 티켓을 내리려 할 때 (`DELETE /api/tickets/{id}`) |

**제안 검증 순서**: 남의 요청 403 -> 없는 요청 404 -> 같은 쌍 열린 매칭 409 -> 내 요청 닫힘/내린 티켓 422 `TICKET_NOT_ACTIVE` -> 내 회차 마감 422 -> **후보 판정**(불충족·상대 티켓 잠금 포함) 422 `NOT_A_CANDIDATE` -> 내 티켓 잠금 422 `TICKET_LOCKED`. 후보가 아닌 상대의 티켓 예약 상태는 어떤 응답으로도 드러나지 않는다.

### 연동 규칙

- **티켓 내리기**: 예약 잠금이 있으면 409 `TICKET_RESERVED`. 없으면 기존대로 그 티켓의 요청을 CLOSED로 닫고, 그 티켓이 참여한 **CHATTING 매칭은 시스템 취소**(`canceled_by_id` NULL, 응답 `canceledBy: SYSTEM`)한다.
- **요청 수정·삭제**: 그 요청이 a측이든 b측이든 열린 매칭(CHATTING·RESERVED)이 있으면 409. 삭제는 취소된 매칭 행을 먼저 지운다(FK, 남길 기록 없음). COMPLETED 매칭이 있으면 409(이력 보존).
- **후보 조회**: 같은 요청 쌍의 열린 매칭이 있는 상대와 예약 잠금 티켓은 후보에서 빠진다. 취소·완료된 매칭은 보지 않는다(재매칭 허용). 내 티켓이 잠겨 있으면 조회 자체가 422다. 차단 제외는 `user_block`이 없어 아직 없다.

### 동시성 (요청 행 잠금 기반)

- 잠금 순서는 **항상 티켓(id 오름차순) -> 요청(id 오름차순) -> 매칭**이다. `users` 행은 티켓 이후에 잡는다(`TicketService.create`가 users 행을 먼저 잡고 티켓은 INSERT만 하므로 지금은 순환이 없다). **새 코드에서 users 행을 X 잠금한 뒤 티켓·요청을 잠그지 않는다**(`TicketService.deactivate`, `ExchangeRequestService`와 같은 규약). 요청·매칭의 불변 컬럼(티켓 id, 사용자 id)을 트랜잭션 밖에서 먼저 읽어 잠금 대상을 정하고, 잠금 읽기가 트랜잭션의 첫 쿼리들이 되게 한다(MySQL REPEATABLE READ 스냅샷이 잠금 뒤에 시작해야 최신 상태를 본다).
- 같은 쌍 동시 제안은 하나만 201이고 나머지는 열린 매칭을 보고 409(최종 방어선은 `uk_exchange_match_open_pair`). 같은 티켓을 건 두 매칭이 동시에 양쪽 수락을 완료하려 하면 하나만 RESERVED이고 나머지는 409(최종 방어선은 `exchange_ticket_lock` PK).
- **교착 사례(해결됨)**: FK가 쓰는 인덱스를 `(ticket_a_id, status)` 같은 복합 인덱스로 만들면 `status`만 바꾸는 UPDATE(티켓 내림으로 인한 시스템 취소)도 InnoDB가 FK 인덱스 변경으로 보고 부모 `ticket` 행에 S 잠금을 걸어, 잠금 순서 밖에서 다른 티켓을 잡다가 양쪽 수락과 교착이 났다(실제 MySQL 경쟁 테스트에서 재현). 그래서 V5의 FK 인덱스는 모두 단일 컬럼이다. 이후 `exchange_match`에 인덱스를 더할 때 FK 컬럼에 `status` 같은 갱신 컬럼을 붙이지 않는다.
- 락 대기 실패·교착은 503 `BUSY`로 응답한다.

### COMPLETED 시점의 티켓 처리 규칙 (이번 범위 밖, 후속 구현 시 따른다; 8차 답변(2026-10-09)으로 '갱신'에서 '교환됨 + 새 티켓'으로 대체)

**(8차 답변 2026-10-09 확정, 구현 예정) 티켓 처리는 COMPLETED 시점(양쪽이 '교환 완료'를 누르는 순간)에 한 트랜잭션에서 기존 두 티켓을 `EXCHANGED`로 바꾸고 각자 새 자리 티켓을 INSERT한다**(소유자는 그대로, 회차·구역·열·번은 상대의 기존 티켓 값). 두 기존 티켓을 먼저 `ACTIVE -> EXCHANGED`로 바꿔 `active_flag`를 NULL로 만들어 `uk_ticket_active_seat`에서 빼고 나서 새 티켓을 INSERT하므로 유일 제약 위반이 없다. 그 전에는 각자 완료 시각(`a/b_completed_at`)만 기록한다. 매칭 행의 좌석은 교환 전 자리이고 교환 후 자리는 새 티켓·교환 이력(`old_ticket_id`/`new_ticket_id`)에서 본다. 교환 이력 스냅샷 2행을 남기고, 기존 티켓에 걸린 요청은 닫으며 같은 티켓들의 다른 열린 CHATTING 매칭은 시스템 취소하고 잠금을 푼다(확인 필요 Q-15). ~~이전 확정(3차 답변)은 두 티켓의 좌석·회차를 교체하되 A를 임시 INACTIVE -> B를 A 자리로 -> A를 B 자리 + ACTIVE 순으로 처리하는 것이었고 이 규칙은 8차 답변으로 대체됨.~~ 이때도 잠금 순서는 티켓 -> 요청 -> 매칭이다. 한쪽만 완료하고 방치돼도 자동 완료·취소는 없고 7일 경과 알림만 보낸다.

## 매칭 후보 조회 API

로그인 필요, 본인 요청만. 조건 일치 판정으로 후보를 찾을 뿐 **점수·랭킹·신뢰도는 없다**(우선순위는 사용자가 정한 희망 회차 priority).

| Method | Path | 설명 |
|---|---|---|
| GET | /api/exchange/requests/{id}/candidates?page=0&size=20 | 내 요청과 서로 조건이 맞는 상대 요청 목록. size 기본 20, 최대 100(초과·0 이하는 보정), page 0부터(음수 400) |

오류(403/404 구분은 같은 `/api/exchange/requests/{id}` 계열 API와 일관되게 유지한다. 티켓 API의 존재 비노출 404와는 다르다): 남의 요청 403 `{message}`, 없는 요청 404 `{message}`, 요청 CLOSED 또는 내 티켓 INACTIVE 422 `{code: TICKET_NOT_ACTIVE}`, 내 티켓 회차 마감 422 `{code: SESSION_CLOSED}`, 인증 없음 401.

판정(전부 만족해야 후보):
- 같은 공연이고, 상대 티켓의 회차 ∈ 내 희망 회차이고 내 티켓의 회차 ∈ 상대 희망 회차.
- 상대 티켓의 (구역, 열, 번)이 내 희망 좌석에 있고, 내 티켓의 (구역, 열, 번)이 상대 희망 좌석에 있다(`exchange_want_seat` PK 점조회).
- 추가금은 **범위(좌석) 단위로 유형만** 본다: 내 희망 좌석 중 상대 티켓 좌석 행의 유형(wa)과 상대 희망 좌석 중 내 티켓 좌석 행의 유형(wb)을 비교한다. 불성립은 POS-POS, POS-X(양방향)뿐이고 나머지(X-X, NEG-NEG 포함)는 성립이다(2026-10-08 확정). 금액은 판정에 쓰지 않는다.
- 삭제된(DELETED) 요청은 후보가 되지 않는다. 상대 요청은 `b.live_flag = 1 AND b.status = 'OPEN'`으로 조인한다(`uk_exchange_request_live_ticket` 점조회).
- 상대 티켓 ACTIVE, 상대 요청 OPEN, 상대 사용자는 나와 달라야 함(내 요청은 자동 제외), 상대 회차가 마감 전(회차 당일 끝=`starts_at` 다음날 0시 KST, 쿼리에서는 `starts_at >= 오늘 0시`로 같은 뜻).
- (V5) 같은 요청 쌍의 열린 매칭(CHATTING·RESERVED)이 있는 상대와 예약 잠금 티켓은 제외한다. 취소·완료된 매칭은 보지 않는다. 내 티켓이 예약 잠금이면 조회 자체가 422 `TICKET_LOCKED`. 차단 제외는 아직 없다(`user_block` 미구현).
- 정렬: 내 희망 회차 priority 오름차순 -> 상대 요청 등록 최신순 -> 요청 id 내림차순. (같은 회차 우선 같은 별도 규칙 없음) 의도는 '사용자가 정한 회차 우선순위 안에서 최신 요청 우선'이다.

응답 예시(`PageResponse`: content, page, size, totalElements, totalPages):

```json
{"content":[{"requestId":885,"ticketId":885,"zone":"1F","row":"10","col":"21","sessionId":1,
  "startsAt":"2026-11-07T19:00","nickname":"nick885","wantPriority":1,
  "extraType":"NEG","extraAmount":-8000,"myExtraType":"POS","myExtraAmount":5000,
  "settlementHint":{"min":5000,"max":8000},"requestedAt":"2026-10-07T06:59:22"}],
 "page":0,"size":20,"totalElements":11,"totalPages":1}
```

- `zone/row/col`은 상대가 입력한 표시용 원문, `wantPriority`는 내 희망 회차 중 상대 티켓 회차의 우선순위, `myExtraType/myExtraAmount`는 내 희망 범위 중 상대 좌석을 포함한 범위의 추가금, `extraType/extraAmount`는 상대 희망 범위 중 내 좌석을 포함한 범위의 추가금이다. 이메일 등 개인정보와 신뢰도는 담지 않는다(닉네임만).
- `settlementHint`는 **참고 표시용**이며 매칭 여부와 무관하다. 한쪽 POS(받아야 하는 최소 m)·다른 쪽 NEG(낼 수 있는 최대 p=-금액)이고 둘 다 금액이 있을 때 p>=m이면 `{min:m, max:p}`, p<m이거나 다른 조합이면 null이다(POS-NEG는 금액이 안 맞아도 후보가 된다). 부호: POS=받을 금액 양수, NEG=낼 수 있는 금액 음수.
- 구현: 읽기 전용 트랜잭션, 요청·티켓 조회 2회 + 후보 SELECT 1회(닉네임·회차까지 한 번의 조인) + 총계 COUNT 1회로 후보 수와 무관한 상수 쿼리 수(N+1 없음). 잠금을 잡지 않아 잠금 순서(티켓 -> 요청)와 무관하다.

그 외 모든 API는 `Authorization: Bearer {accessToken}` 헤더가 필요하다 (SecurityConfig 기준).

## DB 마이그레이션 (Flyway)

- 마이그레이션 파일: `src/main/resources/db/migration/V{n}__{snake_description}.sql` (V1 = 새 기준선 5개 테이블, V2 = `venue` 삭제·`performance.venue_name` 추가, V3 = `ticket` 좌석(구역·열·번)·상태 컬럼과 활성 좌석 유일 제약, V4 = 교환 희망 쪽 테이블 4개, V5 = 매칭·예약 잠금 테이블 2개, V6 = 추가금을 요청에서 희망 범위로 이동, V7 = 요청 소프트 삭제, 한국어 주석)
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
- **V6 적용 안내 (추가금을 희망 범위 단위로 이동, `V6__exchange_extra_per_range.sql`)**: `exchange_want_range`·`exchange_want_seat`에 `extra_type`(NOT NULL)·`extra_amount`와 CHECK(`ck_exchange_want_range_*`, `ck_exchange_want_seat_*`), `exchange_match`에 매칭 시점 스냅샷 4컬럼 `a/b_extra_type`·`a/b_extra_amount`와 CHECK를 추가하고, `exchange_request.extra_type/extra_amount`와 그 CHECK 2개를 제거한다. 이관은 요청 단위였던 값을 그 요청의 모든 범위·좌석에, 매칭 스냅샷은 a/b 각 요청의 값으로 복사한다(무손실; 범위가 0개인 요청의 추가금만 사라지는데 서비스로는 만들 수 없다). V6 맨 앞에 레거시 가드가 있다: 요청의 (POS/NEG인데 금액 NULL, 부호 불일치, X/ANY인데 금액 있음) 행이 있으면 아무것도 바꾸기 전에 SIGNAL로 실패하며(원본 보존, 사전 점검 SQL과 복구 안내는 파일 상단 주석), 고친 뒤 `flyway repair` 후 재적용한다. 백필은 요청 컬럼이 남아 있는 동안 항상 덮어쓴다(부분 적용 뒤 원본을 고쳐도 반영). CHECK는 POS/NEG에 `extra_amount IS NOT NULL`을 명시했다(식이 NULL이면 CHECK가 통과해 V4의 요청 CHECK는 POS + 금액 NULL을 막지 못했다).
  - 되돌릴 수 없는 단계는 마지막 요청 컬럼 DROP 하나다. 적용 전 `mysqldump` 권장, 건수 확인: `SELECT COUNT(*) FROM exchange_request;`.
  - 재실행 가능(V2처럼 INFORMATION_SCHEMA 가드 프로시저): 중간에 실패하면 원인을 고치고 `flyway repair` 후 다시 적용하면 남은 단계부터 이어진다. 임시 프로시저 `v6_extra_per_range`는 재실행 시 먼저 DROP 한다.
- **V7 적용 안내 (요청 소프트 삭제, `V7__exchange_request_soft_delete.sql`)**: `exchange_request.status`에 `DELETED` 추가(CHECK), `deleted_at`, 생성 컬럼 `live_flag = IF(status='DELETED', NULL, 1)`, 유일 키 `uk_exchange_request_ticket(ticket_id)`를 `uk_exchange_request_live_ticket(live_flag, ticket_id)`로 교체하고 FK 전용 `idx_exchange_request_ticket(ticket_id)`를 둔다. 유일 키의 맨 앞이 `live_flag`인 이유는 FK 인덱스 규칙(갱신 컬럼이 FK 인덱스에 들어가면 UPDATE가 부모 행에 S 잠금을 걸어 교착)이다. 기존 행은 모두 `live_flag = 1`이라 위반이 없고 데이터 가드는 없다. 재실행 가능. 되돌리기(UNIQUE(ticket_id) 복원)는 DELETED 행이 있으면 실패하므로 롤포워드로 처리한다.
  - `closeByTicketId`는 `status = 'OPEN'` 조건을 유지해야 DELETED를 CLOSED로 되살리지 않는다(테스트로 고정).
  - 검증(2026-10-09, 임시 MySQL 8.0.46): V1→V7 순서 적용 + `ddl-auto: validate` 통과. 요청 4행·범위 5·좌석 6·매칭 2행이 있는 V5 상태에서 V7로 올리는 이관(범위·좌석·매칭 스냅샷 값 일치, 요청 컬럼·옛 제약 제거, `NOT NULL` 전환)과 history 삭제 후 재적용(재실행)은 `MigrationV6V7MysqlTest`(별도 DB `*_mig_it`)가 자동으로 검증한다.
- 차단(`user_block`)·채팅(`chat_message`)·교환 이력(`exchange_history`)은 **V8 이후**다(예전 문서의 'V6 이후'는 V6/V7이 추가금·소프트 삭제에 쓰이면서 밀렸다).
- **V3 적용 안내 (`ticket` 좌석 컬럼)**: `zone_label/zone_key`, `row_key`, `col_key`, `status`, `created_at/updated_at`, 생성 컬럼 `active_flag`, `uk_ticket_active_seat`(회차·구역·열·번·active_flag), `idx_ticket_user_status`를 추가하고 `row_label`/`col_label`을 NOT NULL VARCHAR(20)으로 바꾼다.
  - 가드: `ticket`에 행이 있으면 아무것도 바꾸기 전에 SIGNAL로 실패한다(새 NOT NULL 구역 컬럼에 채울 값이 없음). 적용 전 `SELECT COUNT(*) FROM ticket;`가 0인지 확인한다. 행이 있어 실패했다면 테스트 행을 지우고 `flyway repair`(실패 기록 삭제) 후 다시 적용한다. 임시 프로시저 `v3_guard_ticket_empty`가 남을 수 있으나 재실행 시 먼저 DROP 한다.
  - 주의: `./gradlew bootRun`은 `backend/.env`를 읽어 `SPRING_DATASOURCE_URL`을 덮어쓴다. 임시 DB로 검증하려면 `bootRun`이 아니라 `bootJar` 후 `java -jar`로 환경변수를 지정해 실행한다.
- **V4 적용 안내 (교환 희망 테이블)**: `exchange_request`(티켓당 1개 `uk_exchange_request_ticket`, 추가금 유형·금액 CHECK), `exchange_want_range`(입력한 범위, 수정 화면 복원용), `exchange_want_seat`(펼친 희망 좌석, PK = request_id + 구역·열·번 키), `exchange_want_session`(희망 회차 + 우선순위, PK = request_id + 회차)를 만든다. 자식 3개는 요청 삭제 시 `ON DELETE CASCADE`다. 기존 데이터를 건드리지 않아 가드가 없다. 차단·매칭·예약 잠금·채팅·이력 테이블은 V5 이후다. `exchange_want_range`의 `row_from/row_to/col_from/col_to`에는 정규화 키를 저장한다.
- **V5 적용 안내 (매칭·예약 잠금)**: `exchange_match`(후보를 골라 시작한 매칭. a = 제안자, b = 고른 상대. 요청·티켓·사용자 a/b, 상태 4종 CHATTING/RESERVED/COMPLETED/CANCELED, `a/b_reserved_at`·`a/b_completed_at`, `canceled_by_id`(NULL = 시스템 취소)·`canceled_at`, 생성 컬럼 `request_low_id`/`request_high_id`/`open_flag`와 `uk_exchange_match_open_pair`로 같은 쌍의 열린 매칭 1개)와 `exchange_ticket_lock`(PK ticket_id, 티켓당 예약 1개, `match_id` FK ON DELETE CASCADE)을 만든다. 기존 데이터를 건드리지 않아 가드가 없다. 차단(`user_block`)·채팅(`chat_message`)·이력(`exchange_history`)은 후속 V 파일이다. FK 인덱스는 단일 컬럼이어야 한다(위 '동시성'의 교착 사례).
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
- 내릴 때 '교환 요청이 있으면 409' 대신 요청을 CLOSED로 닫는 쪽으로 구현했다(설계 1.2: CLOSED = 티켓 내림). (V5 반영 완료) 예약 잠금이 있으면 `TicketService.ensureCanDeactivate`가 409 `TICKET_RESERVED`로 막고, 내릴 때 그 티켓의 CHATTING 매칭은 시스템 취소한다.

## 후속 메모 (교환 희망 조건)
- (V5 반영 완료) `ExchangeRequestService.ensureNoActiveProposal`이 열린 매칭이 있으면 409 `ACTIVE_MATCH_EXISTS`를 던진다(수정·삭제 공통).
- 후보 조회는 `exchange_want_seat` PK(request_id, 구역, 열, 번)와 `uk_ticket_active_seat`를 쓴다(설계 2절 SQL). 요청 CLOSED/티켓 INACTIVE는 후보 SQL의 `status='OPEN'`·`active_flag=1`로 걸러진다.
- 펼친 좌석 INSERT는 500행씩 다중 행 `INSERT`(JdbcTemplate)다. 상한(5,000) 요청의 등록·수정은 로컬 MySQL 기준 약 150~200ms였다.
- 요청 목록의 `ranges` 열·번은 정규화 값이다(원문 표기 복원이 필요하면 V5에서 label 컬럼을 추가한다).
- 알려진 한계(L4): 수정(PATCH)은 범위·좌석·회차를 전부 지우고 다시 만드는 방식이라 상한 근처(5,000석) 요청은 매번 5,000행을 다시 쓴다(약 150ms). 희망 좌석에는 회차가 없어 "같은 자리의 다른 회차만 원하고 같은 회차의 같은 자리는 원하지 않는" 구분은 할 수 없다. 두 한계 모두 현재 설계(1.3~1.5)의 결과다.

## 후속 메모 (매칭 후보 조회)
- **V5 반영 상태: 같은 쌍 열린 매칭·예약 잠금 제외는 적용 완료, 차단(`user_block`)만 남았다.** 차단 테이블을 만들 때 아래 첫 조각을 `EXCLUSIONS`에 추가한다. 확장 지점은 `ExchangeCandidateRepository.additionalExclusions()` 한 곳이며, 후보 SELECT와 COUNT 양쪽에 같이 붙는다. 아래 조각을 `AND`로 이어 붙이면 된다(별칭 a=내 요청, b=상대 요청, ta=내 티켓, tb=상대 티켓, 파라미터 없음).

```sql
-- 차단 (양방향)
AND NOT EXISTS (SELECT 1 FROM user_block ub2
                WHERE (ub2.blocker_id = ta.user_id AND ub2.blocked_id = tb.user_id)
                   OR (ub2.blocker_id = tb.user_id AND ub2.blocked_id = ta.user_id))
-- 이미 같은 요청 쌍으로 열린 채팅
AND NOT EXISTS (SELECT 1 FROM exchange_match mo
                WHERE mo.request_low_id  = LEAST(a.id, b.id)
                  AND mo.request_high_id = GREATEST(a.id, b.id)
                  AND mo.open_flag = 1)
-- 상대 티켓이 예약 잠금 (예약이 취소되면 잠금이 풀려 복귀)
AND NOT EXISTS (SELECT 1 FROM exchange_ticket_lock l WHERE l.ticket_id = tb.id)
```
  주의: `users ub` 별칭이 이미 쓰이므로 차단 서브쿼리 별칭은 `ub2`다. 지시문의 '진행 중 제안이 있는 요청 제외'는 확정 설계(설계 2절)에 없다. 한 요청에 채팅이 여러 개 동시에 열릴 수 있으므로 상대 요청에 다른 채팅이 있다는 이유로는 빼지 않고, 같은 쌍의 열린 채팅과 잠금만 뺀다.
  내 티켓이 예약 잠금이면 후보 조회 자체를 막는 검사(`ExchangeCandidateService`, 422 `TICKET_LOCKED`)는 적용 완료. 신고 연동은 신고 기능 착수 시.
- **STRAIGHT_JOIN 유지 (근거와 트레이드오프).** 조인 순서를 내 요청 -> 내 희망 회차·좌석 -> 상대 티켓 -> 상대 요청 -> 상대 희망 회차·좌석으로 고정한다.
  - 근거: 힌트가 없으면 요청이 적을 때(100~1,000건) 옵티마이저가 `exchange_request`(상대 요청)를 `type=ALL`로 전체 스캔하며 시작했다. 그러면 작업량이 전체 요청 수에 비례한다. 고정하면 작업량이 내 희망 좌석 수 x 희망 회차 수에 비례하고, 전체 요청 수에는 거의 영향을 받지 않는다.
  - 트레이드오프: 희망 좌석이 아주 많은 요청은 고정 순서가 약간 느리다(희망 2,400석, 요청 1,000건: DB 시간 12ms -> 27ms). 희망 좌석이 적은 요청은 8ms -> 1ms로 빨라진다. 통계가 바뀌어도 계획이 흔들리지 않는다는 점을 택했다.
  - EXPLAIN 요약(요청 4,001건 / 희망 좌석 200만 행 / 내 희망 5,000석): a·ta·psa const, wsa `ref`(PK), psb eq_ref, wa `ref`(PK 앞부분, Using index), tb eq_ref(`uk_ticket_active_seat`), b eq_ref(`uk_exchange_request_ticket`), wsb·wb eq_ref(PK, Using index), ub eq_ref(PK). `type=ALL` 없음. 정렬은 결과 집합의 filesort뿐.
  - **keyset 페이징으로 바꿀 기준(재측정 후 판단):** ① 후보 요청의 서버 시간(DB)이 중앙값 300ms를 넘거나 p95가 1초를 넘을 때, ② 정상 사용자의 후보가 1만 건을 넘어 deep page(OFFSET)가 흔해질 때, ③ 희망 좌석 상한(5,000석)을 올릴 때. 전환 시 정렬 키(priority, created_at, id)를 커서로 쓰고 총계는 생략하거나 근사치로 바꾼다.
- 인덱스는 V4로 충분하다(새 마이그레이션 불필요). 역조회 `exchange_want_seat (zone_key, row_key, col_key)`는 지금 쓰지 않는다.
- **총계(COUNT)** 는 별도 쿼리로 한 번 더 돌린다. 결과에 영향이 없는 `users`(닉네임용, FK로 항상 존재) 조인을 뺀 `countSql()`을 쓰며 목록과 같은 판정 조인을 공유한다. `psa`는 FK로 항상 존재하지만 `psb.performance_id = psa.performance_id`(같은 공연 방어)에 쓰이므로 뺄 수 없다. 줄인 COUNT가 목록 전체 행 수와 같은지 `countMatchesListTotalAcrossMixedScenario`와 모든 개별 테스트의 count 단언으로 확인한다. 같은 읽기 전용 트랜잭션이라 목록과 총계는 한 연결을 쓴다(`listAndCountUseTheSameConnectionInsideAReadOnlyTransaction`로 확인; 서비스 전체의 트랜잭션 전파는 Spring 컨텍스트 테스트가 없어 이 메커니즘 수준의 검증이다).
- 시각 처리: 일시는 `LocalDateTime`으로 바인딩·읽는다(`Timestamp` 변환 없음). 앱·DB 모두 KST 벽시계 값이라 서버 JVM 시간대에 의존하지 않는다.
- 확장 조각 규칙(`additionalExclusions()`): 조각은 `AND`로 시작해야 하고 `?`(바인딩 파라미터)를 포함할 수 없다. 위반하면 `IllegalStateException`이며(`ExchangeCandidateSqlGuardTest`), 앞뒤 개행은 자동으로 붙는다.

## 후속 메모 (매칭 생성·예약)
- 남은 것: ① COMPLETED(양쪽 '교환 완료', 기존 두 티켓 EXCHANGED + 새 자리 티켓 INSERT, 위 규칙. 예약 방식 `reserve`/`unreserve` 전환도 8차 답변으로 구현 예정) ② 채팅 메시지·방(`chat_message`, WebSocketConfig) ③ 교환 이력(`exchange_history`, 마이페이지 `(기존 자리) -> (바꾼 자리)` 스냅샷) ④ 사용자 차단(`user_block`: 차단 시 후보 제외·채팅 불가, 두 사용자 사이의 열린 매칭은 시스템 취소하고 잠금 해제) ⑤ 7일 경과 알림. (내 매칭 목록·단건 조회 API는 완료)
- 회차 마감(당일 끝)이 지난 뒤에도 잠금이 남은 티켓의 자동 비활성은 스케줄러 구현 때 설계 6절 '확인 필요 3'(매칭 종료 뒤 비활성화)에 따른다.
- 같은 요청에 열린 매칭이 여러 개 있어도 요청 수정·삭제가 막히는 점(어느 한 쪽이라도 열린 매칭이 있으면 409)은 의도다. 채팅 중에 희망 조건이 바뀌는 것을 막는다.
- 상대 요청이 제안 직후 삭제되는 경쟁은 요청 행 잠금으로 막힌다(잠금 사이 삭제되면 404).

### 알려진 한계 (매칭 생성·예약)
- **요청 삭제 시 CANCELED 매칭 행을 하드 삭제한다 -> 상대방의 취소 기록이 사라진다.** 채팅·신고를 도입하기 전에 소프트 삭제 또는 FK `SET NULL` + 스냅샷으로 정책을 확정해야 한다(사용자 결정 사항이라 코드는 그대로 두었다. `exchange_match`가 요청을 FK로 참조하고 `chat_message`·신고가 `match_id`를 참조할 예정이므로 지금 정책 그대로는 쓸 수 없다).
- **차단·신고 전에는 낯선 사용자의 제안(CHATTING)이 b측 요청의 수정·삭제를 막는다.** 열린 매칭이 있는 요청은 409이므로, 아무 후보나 제안해 놓으면 상대가 먼저 취소/거절(reject)해야 자기 요청을 고칠 수 있다. 차단(`user_block`)·신고가 들어오면 완화한다(정책은 사용자 결정 사항이라 동작은 유지).
- (L2) `TicketService.deactivate`의 CHATTING 매칭 시스템 취소 UPDATE(`ticket_a_id=? and status=CHATTING`)는 REPEATABLE READ에서 `idx_exchange_match_ticket_a/b` 구간을 status 필터 전에 next-key로 잠가, 과거 CANCELED/COMPLETED 행과 인접 gap까지 잠글 수 있다(이웃 티켓 id의 INSERT가 잠시 지연될 수 있으나 교착은 아님). 규모가 커지면 `SELECT id ... FOR UPDATE`로 id를 뽑아 PK로 갱신하는 방식으로 바꾼다.
- (L3) 한 요청에 열린 매칭이 여러 개일 때 하나가 RESERVED가 되어도 나머지 CHATTING 매칭은 그대로 남는다(자동 취소 없음). 사용자가 직접 취소하거나 COMPLETED 시점에 시스템 취소한다. 또한 첫 accept 시점에도 티켓 잠금 검사를 하므로, 이미 동의한 쪽의 `reserved_at`은 남은 채 상대가 409(`TICKET_ALREADY_RESERVED`)를 받을 수 있다(어느 쪽 티켓이 잠겼는지는 응답에 싣지 않는다).
- (L7) V5의 `ck_exchange_match_canceled`는 CANCELED의 `canceled_at` 필수와 열린 상태의 `canceled_*` NULL만 강제한다. RESERVED는 `a/b_reserved_at` 둘 다 NOT NULL, COMPLETED는 `a/b_completed_at` 둘 다 NOT NULL 같은 상태별 시각 일관성은 DB가 강제하지 않는다. **V6에서 CHECK로 보강할 것을 제안한다**(적용된 V5는 수정하지 않는다).
- (L8, 해소) 응답 변환이 닉네임을 위해 `userRepository.findById`를 따로 호출하던 문제는 조인 한 번(`ExchangeMatchQueryRepository`)으로 바꿔 해소했다. 내 매칭 목록·단건 조회 API도 구현됐다. 회차 마감 후 잠금이 남은 티켓의 자동 비활성(스케줄러)은 아직 없다.

## 수동 검증 시나리오 (임시 MySQL, 재현용)

자동 통합 테스트(Testcontainers)는 두지 않고, 아래 절차로 실제 MySQL에서 확인한다. 개발 DB(3306)와 `backend/.env`를 쓰지 않도록 `bootRun`이 아니라 `bootJar` + `java -jar`로 환경변수를 직접 지정한다.

```bash
cd SeatSwap/backend && ./gradlew bootJar
docker run -d --name seatswap-tmp-v4 -e MYSQL_ROOT_PASSWORD=tmp -e MYSQL_DATABASE=seatswap -p 13306:3306 mysql:8.0 --character-set-server=utf8mb4
SERVER_PORT=18080 SPRING_DATASOURCE_URL=jdbc:mysql://localhost:13306/seatswap SPRING_DATASOURCE_USERNAME=root   SPRING_DATASOURCE_PASSWORD=tmp JWT_SECRET=tmp-verification-secret-key-0123456789-abcdefghijklmnop   java -Duser.timezone=Asia/Seoul -jar build/libs/backend-0.0.1-SNAPSHOT.jar      # 종료는 이 프로세스 PID만
# 끝나면: docker rm -f seatswap-tmp-v4
```

준비(curl): 가입·로그인으로 토큰 `$T`를 얻고, 공연(회차 2개 이상, 미래 일시)과 티켓을 만든 뒤 아래를 순서대로 확인한다. 아래 SQL은 `docker exec seatswap-tmp-v4 mysql -uroot -ptmp seatswap -e "..."`로 실행한다.

1. **V4 적용·validate**: 기동 로그에 `Successfully applied 4 migrations ... v4`, `SELECT constraint_name FROM information_schema.check_constraints WHERE constraint_schema='seatswap' AND constraint_name LIKE 'ck_exchange%';` 가 5건 (V6/V7 적용 뒤에는 목록이 바뀐다: 요청의 amount/extra_type CHECK 대신 범위·좌석·매칭·요청 status/deleted CHECK).
2. **CHECK 제약**: `INSERT INTO exchange_want_range(request_id,zone_label,zone_key,row_from,row_to,col_from,col_to,extra_type,extra_amount,sort_order) VALUES (1,'A','A','1','1','1','1','POS',-5,0);` -> ERROR 3819 `ck_exchange_want_range_amount` (V6 이후. V4 시점에는 요청 단위 `ck_exchange_request_amount`).
3. **청크 경계(500행씩 INSERT)**: 티켓마다 `POST /api/exchange/requests`로 1행 x 500번(500석), 500+1(501석), 2열 x 500번(1000석), 5열 x 999번 + 1열 x 5번(5000석)을 등록하고 `SELECT COUNT(*) FROM exchange_want_seat WHERE request_id=?;` 가 응답의 `wantSeatCount`와 같은지 확인.
4. **합집합 상한**: 같은 범위(5열 x 999번)를 2번 넣으면 201, `wantSeatCount` 4995. 5001석이 되는 입력은 422 `{count: 5001, limit: 5000}`, 999 x 999는 즉시(약 10ms) 422.
5. **하위 행 삭제**(V7 이후 소프트 삭제: 서비스가 지우고 요청은 `DELETED`로 남는다): `DELETE /api/exchange/requests/{id}` 204 뒤 `SELECT (SELECT COUNT(*) FROM exchange_want_seat WHERE request_id=?)+(SELECT COUNT(*) FROM exchange_want_range WHERE request_id=?)+(SELECT COUNT(*) FROM exchange_want_session WHERE request_id=?);` = 0.
6. **uk 위반 409**: 같은 티켓에 POST를 두 스레드로 동시에 보내면 201 1건 + 409 `REQUEST_ALREADY_EXISTS` 1건, `SELECT COUNT(*) FROM exchange_request WHERE ticket_id=?;` = 1.
7. **closeByTicketId**: 요청이 있는 티켓을 `DELETE /api/tickets/{id}`(204) 한 뒤 `SELECT status FROM exchange_request WHERE id=?;` = `CLOSED`, 그 요청 PATCH는 422 `TICKET_NOT_ACTIVE`.
8. **동시 PATCH 직렬화**: 같은 요청에 서로 다른 입력 2개를 동시에 PATCH(반복)한 뒤 `exchange_want_range` 1행, `exchange_want_seat`의 구역·개수, `exchange_want_session`, `extra_type/extra_amount`가 둘 중 한쪽 입력과 정확히 일치.
9. **H1/L1**: 희망 회차를 내 회차 하나로 하고 내 좌석을 포함하면 422, 다른 회차를 함께 넣으면 201. 행 `-3` 또는 `−3`은 400.

(마감된 회차의 422/400은 회차 등록이 지난 일시를 거부해 HTTP로 만들 수 없어 단위 테스트로만 검증한다.)

## 매칭 후보 조회 검증 (2026-10-08, 임시 MySQL 8.0 컨테이너, 끝난 뒤 삭제)

**SQL 테스트(실제 MySQL).** `ExchangeCandidateQueryTest`(37건: 한쪽만 일치 2, 추가금 4x4 16, 금액 불사용, 회차 판정·여러 회차·다른 공연, 좌석 키 정확도, 마감 경계(오늘 00:00 포함 / 어제 23:59 제외), 같은 사용자·내 요청, CLOSED/INACTIVE, 한 상대가 여러 회차·좌석에 동시에 일치해도 1행·count 1, 정렬(priority -> 최신, 동률이면 id DESC), 페이징, 쿼리 수=SELECT 1회, 줄인 COUNT = 목록 총계, 읽기 전용 트랜잭션에서 목록·COUNT가 같은 CONNECTION_ID)는 환경변수가 있을 때만 돈다(없으면 `[SKIPPED ...]` 메시지와 함께 건너뜀; `SEATSWAP_IT_REQUIRED=true` 또는 `CI`가 있으면 실패). 안전장치: JDBC URL을 파싱해 호스트가 localhost/127.0.0.1이고 DB 이름(쿼리스트링 제외)이 `_it`로 끝나는지, 연결 직후 `SELECT DATABASE()`도 `_it`로 끝나는지 확인한 뒤에만 Flyway clean/TRUNCATE를 한다(환경변수가 있는데 걸리면 skip이 아니라 실패). 개발 DB(`seatswap`, 3306)는 건드리지 않는다.

```bash
docker run -d --name seatswap-tmp-cand -e MYSQL_ROOT_PASSWORD=tmp -e MYSQL_DATABASE=seatswap_it -p 13307:3306 mysql:8.0 --character-set-server=utf8mb4
SEATSWAP_IT_JDBC_URL=jdbc:mysql://localhost:13307/seatswap_it SEATSWAP_IT_USER=root SEATSWAP_IT_PASSWORD=tmp ./gradlew test --tests '*ExchangeCandidateQueryTest'
docker rm -f seatswap-tmp-cand
```

**EXPLAIN** (요청 1,000건 / 희망 좌석 83만 행): 조인 11개 테이블 모두 const / ref / eq_ref이며 `type=ALL`(전체 스캔)이 없다. 내 희망 회차는 `exchange_want_session` PK, 내 희망 좌석은 `exchange_want_seat` PK 앞부분(`ref`), 상대 티켓은 `uk_ticket_active_seat` 점조회, 상대 요청은 `uk_exchange_request_ticket`, 상대 희망 회차·좌석은 PK 전체(`Using index`). 정렬은 결과 집합에서의 filesort뿐이다. 힌트 없이는 요청 1,000건에서도 `b`(상대 요청)가 `type=ALL`로 시작하는 계획이 나왔다(그래서 STRAIGHT_JOIN).

**성능** (jar 실행, 로컬 HTTP end-to-end, 45회 중 첫 5회 제외; 같은 조건에서 가벼운 `/api/users/me`가 약 14ms). 희망 좌석은 요청당 30~2,400석(평균 약 900), 회차 3개, 구역 2 x 30열 x 40번.

| 규모 | 내 요청 | 후보 수 | size | 중앙값 | p95 |
|---|---|---|---|---|---|
| 100건 | A (희망 100석) | 0 | 20 | 28ms | 45ms |
| 100건 | B (희망 2,400석) | 39 | 20 / 100 | 43ms / 45ms | 68ms / 72ms |
| 1,000건 | A (희망 100석) | 11 | 20 / 100 | 57ms / 42ms | 402ms(1건 튐) / 91ms |
| 1,000건 | B (희망 2,400석) | 299 | 20 / 100 | 68ms / 69ms | 114ms / 94ms |

DB 시간(EXPLAIN ANALYZE, 1,000건): A 약 1ms, B 약 27ms. 한계: 시드는 균일 분포 합성 데이터이고 동시 요청 부하는 재지 않았다.

### 대량 수동 검증 (희망 5,000석, 후보 4,000건) — 2026-10-08 실행

스크립트: `scripts/candidates-bulk-seed.sql`(시드), `scripts/candidates-bench.py`(응답 시간). 임시 MySQL 컨테이너와 `bootJar` + `java -jar`만 쓴다(개발 DB·`.env`·`bootRun` 금지). 끝나면 컨테이너를 삭제한다.

```bash
cd SeatSwap/backend && ./gradlew bootJar
docker run -d --name seatswap-tmp-bulk -e MYSQL_ROOT_PASSWORD=tmp -e MYSQL_DATABASE=seatswap -p 13307:3306 mysql:8.0 --character-set-server=utf8mb4
SERVER_PORT=18081 SPRING_DATASOURCE_URL=jdbc:mysql://localhost:13307/seatswap SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=tmp \
  JWT_SECRET=tmp-verification-secret-key-0123456789-abcdefghijklmnop java -Duser.timezone=Asia/Seoul -jar build/libs/backend-0.0.1-SNAPSHOT.jar   # 종료는 이 프로세스 PID만
# 사용자 1명 가입 후 로그인해 토큰을 파일(tokbulk)에 저장 (id=1 이 "나"가 된다)
curl -s -X POST localhost:18081/api/auth/signup -H 'Content-Type: application/json' -d '{"email":"bulk@t.com","password":"password123","nickname":"bulk"}'
curl -s -X POST localhost:18081/api/auth/login  -H 'Content-Type: application/json' -d '{"email":"bulk@t.com","password":"password123"}'   # accessToken -> tokbulk
docker exec -i seatswap-tmp-bulk mysql -uroot -ptmp seatswap < scripts/candidates-bulk-seed.sql      # 약 23초
python scripts/candidates-bench.py tokbulk 1 20          # size 20, 첫 페이지
python scripts/candidates-bench.py tokbulk 1 100         # size 100
python scripts/candidates-bench.py tokbulk 1 100 39      # 마지막 페이지(OFFSET 3,900)
# EXPLAIN: 후보 SQL(ExchangeCandidateRepository.candidateSql()/countSql())의 ? 를 값으로 바꿔 EXPLAIN ANALYZE 로 실행
docker rm -f seatswap-tmp-bulk
```

시드: 요청 4,001건, 희망 좌석 200만 행. 내 요청은 희망 5,000석(상한) x 희망 회차 3개, 상대 4,000명이 모두 후보다(추가금 X/ANY/POS/NEG 균등, 내 유형 ANY).

| 측정 | 결과 |
|---|---|
| 총계(totalElements) | 4,000 (totalPages 40, size 100 기준) |
| HTTP size 20, page 0 | 중앙값 135ms, p95 205ms (최대 877ms 1회, 첫 호출 235ms) |
| HTTP size 100, page 0 | 중앙값 148ms, p95 201ms |
| HTTP size 100, page 39 | 중앙값 158ms, p95 245ms |
| 기준 `/api/users/me` | 중앙값 약 17ms |
| DB 시간(EXPLAIN ANALYZE) | 목록 LIMIT 20: 약 100ms, 줄인 COUNT: 약 56ms |
| EXPLAIN | `type=ALL` 없음. a·ta·psa const, wsa ref, psb·tb·b·wsb·wb·ub eq_ref, wa ref(Using index) |

해석: 최악 규모(5,000석 x 3회차 = 15,000번 점조회, 후보 4,000건)에서도 서버 시간 약 130ms 수준이라 keyset 전환 기준(중앙값 300ms)에 한참 못 미친다. OFFSET이 커져도 판정 조인 비용이 대부분이라 page 39와 page 0 차이가 작다. 한계: 합성 데이터, 단일 클라이언트, 동시 부하 미측정.

### V6/V7 이후 재측정 (2026-10-09, 임시 MySQL 8.0.46, 요청 4,001건 / 희망 좌석 2,005,000행 / 후보 4,000건)

시드 `scripts/candidates-bulk-seed.sql`을 V6/V7 컬럼(좌석에 추가금)에 맞게 고쳐 같은 규모로 다시 쟀다(`bootJar` + `java -jar`, 임시 컨테이너, 끝난 뒤 삭제).

| 측정 | 결과 |
|---|---|
| EXPLAIN | `type=ALL` 없음. a·ta·psa const, wsa ref(PK), psb·tb·b·wsb·wb·ub eq_ref, wa ref(PK 앞부분). **b는 `uk_exchange_request_live_ticket`(key_len 10, ref `const,tb.id`) 점조회**, 서브쿼리 2개(`mo`, `l`)도 eq_ref. wa·wb가 `extra_type`을 읽어 `Using index`(커버링) 표기는 wa에서 사라졌고 wb는 `Using where` |
| HTTP size 20, page 0 | 중앙값 98ms, p95 116ms (최대 124ms) |
| HTTP size 100, page 0 | 중앙값 97ms, p95 126ms |
| HTTP size 100, page 39 (OFFSET 3,900) | 중앙값 107ms, p95 164ms |
| 기준 `/api/users/me` | 중앙값 약 10ms |
| DB 시간(EXPLAIN ANALYZE) | 목록 LIMIT 20: 약 125~130ms(웜), 줄인 COUNT: 약 57~96ms |

해석: V5 때(중앙값 135ms / p95 205ms)와 비슷하거나 조금 낫다(측정 머신 차이가 섞여 있어 '악화 없음' 정도로만 본다). 조인 행 수는 늘지 않았다(좌석당 행 1개 정책). 한계: 합성 데이터(요청 단위 추가금과 같은 값을 좌석 행에 반복), 단일 클라이언트, 동시 부하 미측정.

## 매칭 동시성 검증 (2026-10-08, 임시 MySQL 8.0 컨테이너, 끝난 뒤 삭제)

**실제 MySQL 통합 테스트** — `ExchangeMatchMysqlTest`(14건, Spring 컨텍스트 + 실제 서비스·트랜잭션)와 `ExchangeCandidateQueryTest`의 V5 부분(제외 조건 8건, 쌍 재검증 4건, V5 제약 3건)은 `SEATSWAP_IT_*` 환경변수가 있을 때만 돈다(`ExchangeCandidateQueryTest`와 같은 안전장치: localhost, DB 이름 `_it`, `SELECT DATABASE()` 재확인, 실행 때마다 Flyway clean). 경쟁 시나리오: 같은 쌍 양방향 동시 제안 8개 x 15라운드, 같은 티켓을 건 두 매칭의 동시 양쪽 수락 x 15, 수락·취소·거절 경쟁 x 15, 티켓 내리기 vs 양쪽 수락 x 40(내리는 쪽을 번갈아 id가 큰/작은 티켓으로), 요청 수정 vs 제안 x 15(둘 중 하나만 성공), 제안 vs 요청 삭제 x 15, 제안 vs 상대 티켓 내리기 x 15, RESERVED 취소 직후 새 제안·재예약 x 15, 티켓 등록·취소·수락·제안 동시 x 15, V5 제약 직접 INSERT 위반(ck_exchange_match_* 포함). 티켓 내리기 vs 수락은 라운드 번호로 정한 고정 지연으로 양쪽 승부가 모두 나오게 하고(내림 10 / 예약 30) 둘 다 발생함을 단언한다. 교착·락 대기 초과(`DataAccessException`)는 실패로 본다.

```bash
docker run -d --name seatswap-tmp-match -e MYSQL_ROOT_PASSWORD=tmp -e MYSQL_DATABASE=seatswap_it -p 13310:3306 mysql:8.0 --character-set-server=utf8mb4
SEATSWAP_IT_JDBC_URL=jdbc:mysql://localhost:13310/seatswap_it SEATSWAP_IT_USER=root SEATSWAP_IT_PASSWORD=tmp ./gradlew cleanTest test
```

**V6/V7 동시성 시나리오 추가 (`ExchangeMatchMysqlTest`, 같은 안전장치)**: 요청 수정 vs 제안 x 15(수정은 항상 성공, 제안이 먼저면 매칭은 시스템 취소, 수정이 먼저면 422 `NOT_A_CANDIDATE`; 두 승부 모두 발생을 단언), 요청 수정 vs 양쪽 수락 x 40(수정 성공 <=> 매칭 CANCELED·잠금 0, 수정 409 `ACTIVE_MATCH_EXISTS` <=> 매칭 RESERVED·잠금 2), 삭제 vs 새 요청 생성 2개 동시 x 15(미삭제 요청 <= 1, 교착 없음), 제안 vs 상대 요청 삭제 x 15(삭제는 항상 성공, 열린 매칭 0). 이 클래스를 3회 연속 돌려 모두 통과했고 MySQL 로그에 deadlock 0건이다. 결과 로그 예: `[update-vs-propose] proposedThenCanceled=12 updatedFirst=3`, `[update-vs-accept] updateWins=19 reservedWins=21`, `[propose-vs-delete] proposedThenCanceled=12 deletedFirst=3`.

**실제 HTTP 동시 요청** — `scripts/match-http-concurrency.py`. `bootJar` + `java -jar`(개발 DB·`.env`·`bootRun` 금지)로 같은 임시 컨테이너의 다른 DB(`seatswap_http`)에 앱을 띄우고(`TICKET_MAX_ACTIVE_PER_USER=1000`) 실행한다.

```bash
docker exec seatswap-tmp-match mysql -uroot -ptmp -e "CREATE DATABASE seatswap_http CHARACTER SET utf8mb4"
SERVER_PORT=18082 SPRING_DATASOURCE_URL=jdbc:mysql://localhost:13310/seatswap_http SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=tmp \
  JWT_SECRET=tmp-verification-secret-key-0123456789-abcdefghijklmnop TICKET_MAX_ACTIVE_PER_USER=1000 java -Duser.timezone=Asia/Seoul -jar build/libs/backend-0.0.1-SNAPSHOT.jar   # 종료는 이 프로세스 PID만
python scripts/match-http-concurrency.py 18082 50 seatswap-tmp-match seatswap_http
docker rm -f seatswap-tmp-match
```

결과(50라운드): 같은 쌍 동시 제안 8개 x 50 -> 201 정확히 50건, 409 `MATCH_ALREADY_OPEN` 350건. 같은 티켓을 건 두 매칭의 동시 양쪽 수락 4개 x 50 -> 매 라운드 RESERVED 정확히 1개·잠금 2행(200 139건, 409 61건). 수락/취소/거절 경쟁 4개 x 50, 티켓 내리기 vs 수락 3개 x 50(내리기 204 13건 / 409 37건)에서 5xx 0건, 서버 로그에 Deadlock/ERROR 0건, DB 불변식 위반(RESERVED 아닌 매칭의 잠금, 잠금이 2행 아닌 RESERVED, INACTIVE 티켓의 잠금, 같은 쌍 열린 매칭 중복, 두 RESERVED 매칭이 공유하는 티켓) 모두 0건.

## 다음 단계
1. 교환 도메인 구현 계속: (완료) V4 희망 범위·희망 좌석·희망 회차, 매칭 후보 조회, V5 매칭 생성·예약·거절·취소·예약 잠금, V6 추가금 범위 단위, V7 요청 소프트 삭제 / 남음: 교환 완료(COMPLETED)와 티켓 처리(기존 티켓 EXCHANGED + 새 티켓 INSERT, 8차 답변), 예약 방식 변경(`reserve`/`unreserve`, 8차 답변), 채팅, 교환 이력, 차단(`user_block`) 마이그레이션(**V8 이후**)과 후보 제외 조건(위 후속 메모) (설계안 `산출물/08_ERD/exchange-schema-design.md`)
2. 티켓 자동 비활성(회차 당일 끝 경과, 스케줄러)과 '내 티켓 인증'
3. (완료) 후보 제시 -> 매칭 생성 -> 양쪽 예약 동의 (8차 답변으로 '한 명 예약 / 한 명 취소 시 복귀' 방식으로 대체 예정) / 남음: 양도 후 각자 교환 완료로 확정
4. 채팅(WebSocketConfig 구현)·후기
5. 배포 시 SecurityConfig의 CORS allowed-origin을 실제 프론트 도메인으로 교체
