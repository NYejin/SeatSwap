# SeatSwap Backend (Spring Boot)

## 패키지 구조
- domain       — JPA 엔티티 4종 (User·Performance·PerformanceSession·Ticket)과 enum UserRole (공연장은 Performance.venueName 텍스트, V2에서 venue 테이블 삭제)
- repository   — JpaRepository
- service      — 비즈니스 로직
- controller   — REST API + WebSocket(STOMP)
- dto          — request/response
- config       — Security, WebSocket 설정
- security     — JWT 발급/검증
- exception    — 커스텀 예외 + 전역 핸들러

## 현재 상태
- 2026-10-07 방향 전환으로 좌석표 트랙 코드와 교환·채팅·후기 등 빈 스켈레톤(컨트롤러·서비스·저장소)을 삭제했다. 좌석표 코드는 git 태그 `archive/seatmap-track-20261007`에 보관되어 있다.
- 엔티티는 User·Performance·PerformanceSession·Ticket 4종이다. Ticket은 엔티티·저장소만 있고 티켓 등록 API는 아직 없다.
- **회원가입/로그인/JWT 인증(FR-01)은 구현 완료**:
  - `security/JwtTokenProvider` — access/refresh 토큰 발급·검증 (jjwt 0.12.5)
  - `security/JwtAuthenticationFilter` — Authorization 헤더 검증 후 SecurityContext 설정
  - `security/CustomUserDetailsService` — 이메일 기준 사용자 조회
  - `config/SecurityConfig` — JWT 필터 등록, CORS(개발용 localhost:5173 허용), `/api/auth/**` permitAll, `/api/admin/**`는 ADMIN 권한 (해당 컨트롤러는 아직 없음)
  - `service/AuthService`, `controller/AuthController` — POST /api/auth/signup, /login, /refresh
  - 요청 DTO는 `jakarta.validation`으로 기본 검증(이메일 형식, 비밀번호 8자 이상) 적용
- **공연·회차 등록/조회(FR-02)는 구현 완료** (`PerformanceController`, `PerformanceService`, `PerformanceSessionService`). 공연장은 공연의 텍스트 속성 `venueName`(필수, 1~100자)이며 등록 후 수정할 수 없다.
- 남은 스켈레톤: `config/WebSocketConfig`는 클래스 선언과 `TODO: registerStompEndpoints(), configureMessageBroker()`만 있다 (채팅용, 미구현).
- 테스트는 169건이다.

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
| PATCH | /api/performances/{id} | 공연 제목 수정 (공연장 이름은 수정 불가) |
| DELETE | /api/performances/{id} | 공연 삭제 |
| POST | /api/performances/{id}/sessions | 회차 추가 (201, 같은 시각이면 409) |
| PATCH | /api/performances/{id}/sessions/{sessionId} | 회차 일시 변경 (등록자만, 티켓 0건일 때만) |
| DELETE | /api/performances/{id}/sessions/{sessionId} | 회차 삭제 |

그 외 모든 API는 `Authorization: Bearer {accessToken}` 헤더가 필요하다 (SecurityConfig 기준).

## DB 마이그레이션 (Flyway)

- 마이그레이션 파일: `src/main/resources/db/migration/V{n}__{snake_description}.sql` (V1 = 새 기준선 5개 테이블, V2 = `venue` 삭제·`performance.venue_name` 추가, 한국어 주석)
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

## 다음 단계
1. 교환 도메인 설계 (좌석 키 = 공연·구역·열·번, 희망 범위·추가금·회차 조건 — CLAUDE.md '확정 전 기본안' 확인 후 확정) 및 스키마 V2
2. 티켓 등록 API (텍스트 좌석 입력)
3. 자동 매칭 (후보 제시, 양쪽 수락으로 확정)
4. 채팅(WebSocketConfig 구현)·후기
5. 배포 시 SecurityConfig의 CORS allowed-origin을 실제 프론트 도메인으로 교체
