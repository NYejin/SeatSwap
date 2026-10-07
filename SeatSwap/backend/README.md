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
- 좌석 인식(OpenCV/OCR)은 이 서버가 아니라 ../seatmap-service(FastAPI)에서 처리,
  이 서버는 결과 좌표 JSON을 받아 SeatMapLayout에 저장하는 역할만 한다

## 인증 API

| Method | Path | 설명 |
|---|---|---|
| POST | /api/auth/signup | 회원가입 (email, password, nickname) → 생성된 사용자 정보 반환 |
| POST | /api/auth/login | 로그인 (email, password) → accessToken, refreshToken 반환 |
| POST | /api/auth/refresh | refreshToken으로 accessToken 재발급 |

그 외 모든 API는 `Authorization: Bearer {accessToken}` 헤더가 필요하다 (SecurityConfig 기준).

## DB 마이그레이션 (Flyway)

- 마이그레이션 파일: `src/main/resources/db/migration/V{n}__{snake_description}.sql` (V1 = 12개 테이블 초기 스키마, 한국어 주석)
- 적용 이력: `SELECT * FROM flyway_schema_history;` (docker: `docker exec seatswap-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" seatswap -e "SELECT * FROM flyway_schema_history"'`)
- 규칙: 스키마 변경은 새 V 파일로만, 적용된 파일 수정 금지, `ddl-auto: validate`, 엔티티 변경과 마이그레이션을 함께 작성
- 앱 기동 시 자동 적용된다. 빈 DB는 V1부터 실행, Flyway 도입 전 DB(테이블은 있고 이력 테이블 없음)는
  `baseline-on-migrate`로 V1을 "적용된 것으로 간주"(BASELINE 행 기록)하고 V2부터 적용한다.
- 기존 DB 편입 절차: ① 백업(`mysqldump seatswap`) ② backend 이미지 재빌드·재기동 ③ `flyway_schema_history`에
  `1 | << Flyway Baseline >> | BASELINE` 행 확인. 롤백: 이전 이미지로 되돌리면 된다(스키마는 변경되지 않고 이력 테이블만 추가됨.
  원하면 `DROP TABLE flyway_schema_history`). 이전 이미지는 이력 테이블을 무시하므로 문제없다. 단, 이 롤백은 V1(baseline)만 있는 상태에서만 유효하다. V2 이상이 적용된 DB에 이전 이미지(`ddl-auto: update`)를 올리면 스키마가 앞서 있어 위험하므로 백업 복원으로 되돌린다.

## V3 적용 시 주의 (수정 로그·정정 신고 개편)

- V3(`V3__add_seatmap_revision_and_correction.sql`)는 `seat_map_layout.seat_count`·인덱스 추가, `seat_map_revision`·`seat_map_revision_item` 신설, `seat_correction` 개편(`vote_count` 삭제, 대상 좌석·필드 컬럼 추가)을 한다.
- **가드**: 구 `seat_correction` 행은 대상 좌석(uid)을 알 수 없어 이관할 수 없으므로, 파일 맨 앞에서 `seat_correction`에 행이 있으면 `SIGNAL`로 즉시 실패한다(아무것도 바꾸기 전에). 적용 전 확인:
  ```sql
  SELECT COUNT(*) FROM seat_correction;   -- 0이어야 한다
  ```
- 가드로 실패하면 `flyway_schema_history`에 실패 행이 남아 앱이 기동하지 않는다. 절차: ① `seat_correction`을 비우거나 백업한다(`mysqldump`) ② 실패 이력을 `flyway repair`로 지운다(또는 `DELETE FROM flyway_schema_history WHERE success = 0;`) ③ 앱을 다시 기동하면 V3가 처음부터 다시 적용된다. 가드가 실패하면 임시 프로시저(`v3_guard_seat_correction_empty`)가 남을 수 있으나 재시도 때 먼저 DROP 한다.
- MySQL은 DDL이 트랜잭션에 묶이지 않으므로, 가드 이후 단계에서 중간 실패하면 앞선 변경이 남는다. 이 경우 백업을 복원한 뒤 다시 시도한다.

## 관리자 권한 / V2 적용 시 주의

- 관리자는 DB에서 직접 부여한다: `UPDATE users SET role = 'ADMIN' WHERE email = '...';` — **반드시 대문자 `ADMIN`으로만**.
  `users.role`, `venue.status`, `seat_map_layout.status`는 `utf8mb4_bin` 컬럼이라 소문자(`admin`)는 CHECK 제약에서 거부된다.
  가입은 항상 USER이며 요청 본문의 role은 무시된다. role은 토큰에 넣지 않고 매 요청 DB에서 읽는다(변경 즉시 반영).
- **MySQL 최소 버전 8.0.16** — 그 미만은 CHECK 제약을 문법만 받고 강제하지 않는다 (현재 docker 이미지는 mysql:8.0).
- V2 적용 전 사전 점검(행이 있는 DB에서 새 UNIQUE가 실패하지 않도록):
  ```sql
  SELECT COUNT(*) FROM seat_map_layout;                          -- 0이면 안전
  SELECT venue_id, COALESCE(zone_name,'') z, COUNT(*) FROM seat_map_layout
    GROUP BY venue_id, z HAVING COUNT(*) > 1;                    -- 결과가 있으면 정리 후 적용
  SELECT COUNT(*) FROM seat_map_layout WHERE image_url IS NOT NULL;  -- V2가 image_url 컬럼을 삭제함
  ```

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
