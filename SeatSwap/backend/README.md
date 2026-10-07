# SeatSwap Backend (Spring Boot)

## 패키지 구조
- domain       — JPA 엔티티 11종 (08_ERD 기준)
- repository   — JpaRepository
- service      — 비즈니스 로직
- controller   — REST API + WebSocket(STOMP)
- dto          — request/response
- config       — Security, WebSocket 설정
- security     — JWT 발급/검증
- exception    — 커스텀 예외 + 전역 핸들러

## 현재 상태
- 11개 엔티티, Repository, Service, Controller **틀(스켈레톤)** 생성됨 — 대부분 TODO
- **회원가입/로그인/JWT 인증(FR-01)은 실제 구현 완료**:
  - `security/JwtTokenProvider` — access/refresh 토큰 발급·검증 (jjwt 0.12.5)
  - `security/JwtAuthenticationFilter` — Authorization 헤더 검증 후 SecurityContext 설정
  - `security/CustomUserDetailsService` — 이메일 기준 사용자 조회
  - `config/SecurityConfig` — JWT 필터 등록, CORS(개발용 localhost:5173 허용), `/api/auth/**` permitAll
  - `service/AuthService`, `controller/AuthController` — POST /api/auth/signup, /login, /refresh
  - 요청 DTO는 `jakarta.validation`으로 기본 검증(이메일 형식, 비밀번호 8자 이상) 적용

## 인증 API

| Method | Path | 설명 |
|---|---|---|
| POST | /api/auth/signup | 회원가입 (email, password, nickname) → 생성된 사용자 정보 반환 |
| POST | /api/auth/login | 로그인 (email, password) → accessToken, refreshToken 반환 |
| POST | /api/auth/refresh | refreshToken으로 accessToken 재발급 |

그 외 모든 API는 `Authorization: Bearer {accessToken}` 헤더가 필요하다 (SecurityConfig 기준).

## DB 마이그레이션 (Flyway)

- 마이그레이션 파일: `src/main/resources/db/migration/V{n}__{snake_description}.sql` (V1 = 새 기준선: `users`, `venue`, `performance`, `performance_session`, `ticket` 5개 테이블, 한국어 주석)
- 적용 이력: `SELECT * FROM flyway_schema_history;` (docker: `docker exec seatswap-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" seatswap -e "SELECT * FROM flyway_schema_history"'`)
- 규칙: 스키마 변경은 새 V 파일로만, 적용된 파일 수정 금지, `ddl-auto: validate`, 엔티티 변경과 마이그레이션을 함께 작성
- 앱 기동 시 자동 적용된다.
- 2026-10-07에 좌석표 트랙을 걷어내며 기준선을 새로 만들었다. 이전 V1~V3(12개+좌석표 테이블)와 좌석표 코드는 git 태그
  `archive/seatmap-track-20261007`에 보관되어 있다. **이전 스키마가 남은 로컬 DB는 `docker compose down -v`로 볼륨을 지운 뒤 다시 띄운다**
  (지우지 않으면 `flyway_schema_history`에 남은 옛 V1 체크섬이 달라 기동하지 않는다).
- MySQL 최소 버전 8.0.16 — 그 미만은 CHECK 제약을 문법만 받고 강제하지 않는다 (현재 docker 이미지는 mysql:8.0).

## 관리자 권한

- 관리자는 DB에서 직접 부여한다: `UPDATE users SET role = 'ADMIN' WHERE email = '...';` — **반드시 대문자 `ADMIN`으로만**.
  `users.role`, `venue.status`는 `utf8mb4_bin` 컬럼이라 소문자(`admin`)는 CHECK 제약에서 거부된다.
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
1. 프론트(ProtectedRoute, api/client.ts)와 연동 테스트 — 로그인 응답의 accessToken 저장/재발급 흐름
2. dto/request, dto/response를 나머지 도메인(공연/티켓/교환 등)에도 채우며 Controller 바디 구현
3. ExchangeService의 신청/수락 상태 전이 로직 구현
4. 배포 시 SecurityConfig의 CORS allowed-origin을 실제 프론트 도메인으로 교체
