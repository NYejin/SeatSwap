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
