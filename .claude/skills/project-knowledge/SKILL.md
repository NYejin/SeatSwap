---
name: project-knowledge
description: 산출물/03~05,08에 있는 프로젝트계획서·요구사항정의서·WBS·ERD의 핵심 내용을 요약한 참고 문서. docx/xlsx 원본을 직접 열지 못하는 상황에서도 이 스킬 하나로 서비스 개요·기능 목록·일정·데이터 모델을 파악할 수 있다. 기능을 구현하거나 설계 질문에 답할 때, 또는 산출물 문서 내용이 필요한데 원본을 열기 어려울 때 먼저 참고한다. 원본과 내용이 달라지면(산출물 문서를 수정하면) 이 스킬도 함께 갱신해야 한다.
---

# 프로젝트 지식 요약 (산출물 문서 기반)

이 파일은 `산출물/03_프로젝트계획서`, `산출물/04_요구사항정의서`, `산출물/05_WBS`,
`산출물/08_ERD` 원본(docx/xlsx/png)의 핵심 내용을 텍스트로 옮겨둔 요약본이다.
Claude Code는 바이너리 문서를 직접 파싱하지 못하므로, 에이전트들은 작업 중
이 문서 내용이 필요하면 원본을 열어보려 하기 전에 **이 스킬을 먼저 참고**한다.
더 정확하거나 최신 정보가 필요하면 산출물 원본을 직접 확인한다.

---

## 1. 서비스 개요 (산출물/03)

같은 공연의 티켓을 보유한 사람들끼리 좌석을 맞교환하거나, 좌석 등급 차이에 따른
추가금액을 주고받으며 자리를 교환하는 개인 포트폴리오 웹 서비스.

- **핵심 시나리오**: 단순 교환(1:1 맞교환) / 차액 거래(등급 차이 시 추가금 지불). 매칭은
  추천 알고리즘이 아닌 **단순 1:1 신청/수락**.
- **타겟 플랫폼**: 모바일 웹(반응형, 필요 시 PWA). 네이티브 앱 제외.
- **제외 범위**: 실결제(PG) 연동 없음, 티켓팅 사이트 공식 API 연동 없음, 좌석 실시간 재고 연동 없음.

## 2. 기술 스택 및 선정 이유 (산출물/03)

| 구분 | 기술 | 선정 이유 요약 |
|---|---|---|
| Frontend | React, TypeScript, Vite | 기존 숙련도, 인터랙티브 UI(좌석맵/채팅) 적합, 타입 안전성, 채용시장 범용성 |
| Backend | Spring Boot, Security(JWT), JPA, WebSocket(STOMP) | 기존 Java/Spring 경험, 11개 엔티티 관계형 데이터 적합, Security/JWT 생태계 성숙, WebSocket 내장 |
| DB | MySQL | FK 관계 많은 구조라 RDBMS 적합, JPA 호환성, 기존 사용 경험 |
| 이미지 인식 서버 | FastAPI, OpenCV, Tesseract | Python이 이미지/OCR 생태계 중심, 메인 서버와 책임 분리, 비동기 처리에 강함 |

### backend와 seatmap-service를 분리한 이유 (산출물/03 8.1절과 동일 내용)
- **책임 성격이 다름**: backend는 인증·거래/매칭 상태 관리 같은 트랜잭션 중심 로직, seatmap-service는
  이미지 한 장을 좌표 JSON으로 바꾸는 단발성 변환 작업. 한 서버에 섞으면 핵심 비즈니스 로직과
  이미지 전처리 코드의 경계가 흐려짐
- **장애 격리**: 외부 사이트에서 가져온 이미지를 다루는 특성상 예측 어려운 입력(비정상 이미지,
  메모리 급증 등)에 노출되기 쉬움. 분리해두면 좌석 인식 쪽 문제가 로그인·결제 등 핵심 기능에
  영향을 주지 않음
- **포트폴리오 관점**: 책임 분리 + 서비스 간 REST 통신 구조를 실제로 구현한 경험 자체가 설명 거리가 됨
- **트레이드오프**: 서비스가 2개로 늘어 배포/통신 복잡도가 증가함 — docker-compose.yml로 한 번에
  띄우도록 구성해 완화함

### backend / seatmap-service를 별도 서비스로 분리한 이유

- **언어 선택**: OpenCV·Tesseract 등 이미지 처리/OCR 생태계가 Python 중심으로 성숙해 있어, Java로 구현하는 것보다 훨씬 효율적
- **책임 분리**: backend는 트랜잭션 중심(인증, 거래/매칭 상태 관리, 데이터 정합성), seatmap-service는 단발성 변환 작업(이미지 1장 → 좌표 JSON). 한 서버에 섞으면 핵심 비즈니스 로직과 이미지 전처리 코드의 경계가 흐려짐
- **장애 격리**: 외부 사이트에서 가져온 이미지를 다루는 특성상 예측 어려운 상황(비정상 이미지, 메모리 사용량 급증 등)이 생길 수 있음. 분리해두면 좌석 인식 쪽 문제가 로그인·결제 등 핵심 기능에 영향을 주지 않음
- **포트폴리오 관점**: 책임을 분리한 마이크로서비스 구조(서비스 간 REST 통신)를 실제로 구현한 경험 자체가 설명 포인트가 됨
- **트레이드오프**: 서비스가 2개로 늘어 배포·통신 복잡도가 증가함 — `SeatSwap/docker-compose.yml`로 전체를 한 번에 띄우도록 구성해 완화함

## 3. 핵심 기능 (MVP) — 산출물/03, 산출물/04 FR 요약

| 기능 | 설명 |
|---|---|
| 회원/인증 | 이메일 회원가입·로그인, JWT |
| 공연/좌석맵 등록 | 공연 등록, 티켓팅 링크 입력 → 좌석맵 이미지 자동 수집 |
| 좌석 자동 인식 | OpenCV+OCR로 좌표·행/열 번호 자동 추출 |
| 좌석맵 터치 선택 | SVG 오버레이로 본인 좌석 선택 |
| 오류 신고/보정 | 동일 신고 2건 이상 시 자동 반영, 1건은 검토 대기 |
| 교환 요청/매칭 | 단순 교환 또는 차액 거래, 1:1 신청·수락 |
| 실시간 채팅 | WebSocket(STOMP) |
| 거래 상태 관리 | 제안→수락→교환완료 |
| 리뷰/신뢰도 | 거래 완료 후 상호 리뷰, 신뢰도 점수 반영 |

전체 FR/NFR/UC 상세 번호와 우선순위·담당자는 `산출물/04_요구사항정의서` 참고
(팀 프로젝트 양식: 구분/서비스/기능명/기능설명/개발상태/개발우선순위/담당자/선행기능/비고).

## 4. 좌석맵 인식 파이프라인 (산출물/03, seatmap-recognition-pattern 스킬과 동일 내용)

1. 이미지 확보 — 좌석맵 이미지 URL만 가져옴 (페이지 전체 크롤링 아님)
2. 좌석 블록 검출 — 배경(흰색 계열) 제외 + connectedComponentsWithStats. 색상/판매상태 매핑 안 함
3. 행 번호 인식 — y좌표 클러스터링 + Tesseract OCR
4. 열 번호 부여 — 행 내 x좌표 순 정렬, 통로 구간 결번 처리
5. 오류 보정 — 동일 정정 2건 이상 시 자동 반영

실측 검증: 딥러닝 없이 약 207~210개 좌석 블록 정확 검출 확인됨 (실제 멜론티켓 이미지 기준).

## 5. 일정 단계 (산출물/05_WBS 요약)

1. 기획 — 완료
2. 환경설정 — Spring Boot/React 스켈레톤, DB 스키마 (완료)
3. 회원/인증
4. 좌석맵 파이프라인
5. 공연/티켓 관리
6. 교환 매칭
7. 채팅
8. 거래/신뢰도
9. 배포
10. 문서화(완료보고서)

세부 날짜/기간은 `산출물/05_WBS` xlsx 참고.

## 6. 데이터 모델 (산출물/08_ERD 요약)

11개 엔티티: User, Venue, Performance, Ticket, SeatMapLayout, SeatCorrection,
ExchangeRequest, ExchangeMatch, ChatRoom, Message, Review.

주요 관계:
- Venue 1:N SeatMapLayout / 1:N Performance
- SeatMapLayout 1:N SeatCorrection / 1:N Ticket
- User 1:N Ticket, Performance 1:N Ticket
- Ticket 1:1 ExchangeRequest
- ExchangeRequest 1:N ExchangeMatch (A측/B측)
- ExchangeMatch 1:1 ChatRoom, ChatRoom 1:N Message
- ExchangeMatch 1:N Review

상세 다이어그램은 `산출물/08_ERD/ERD.png` (및 `erd.dot` 소스) 참고.

## 7. 현재 진행 상태

- 기획/문서화 완료: 산출물/03, 04, 05, 08
- `SeatSwap/backend`, `SeatSwap/frontend`, `SeatSwap/seatmap-service` 코드 스켈레톤 생성 완료
  (도메인 엔티티 11종, Repository/Service/Controller 틀, 라우팅, Docker Compose 포함)
- **회원가입/로그인/JWT 인증(FR-01) 실제 구현 완료**: JwtTokenProvider, JwtAuthenticationFilter,
  CustomUserDetailsService, SecurityConfig(CORS 포함), AuthService/AuthController,
  POST /api/auth/signup·login·refresh
- **FR-01 프론트 로그인 연동 구현 + 리뷰 반영 완료 (2026-10-02, 커밋 7dcf8a0)**: authApi(login/signup/refresh),
  AuthProvider(localStorage 토큰 저장·복원, 탭 간 동기화), ProtectedRoute(state.from 복귀),
  LoginPage(입력 검증, 백엔드 400 `{message}`/`{field:msg}` 에러 표시, 모바일 우선 CSS Modules — 2026-10-03 Tailwind로 전환됨).
  code-reviewer 리뷰 반영 완료: access exp 60초 전 선제 재발급 타이머, single-flight refreshSession,
  refresh 400일 때만 토큰 삭제(네트워크/5xx는 보존), redirect 경로 검증(`//`·백슬래시 거부),
  필드 오류 중복 표시 제거, 인터셉터 없는 refresh 전용 authClient. `npm run build` 통과
- 프론트 tsconfig에 `noEmit` 추가 (tsc가 src에 .js를 생성해 vite가 옛 .js를 번들하던 문제 해결)
- **FR-01 회원가입 구현 + 2차 리뷰 반영 완료 (2026-10-02, 커밋 7dcf8a0)**
  - 프론트 SignupPage: 이메일/비밀번호/비밀번호 확인/닉네임, 클라이언트 검증 + 서버 필드 오류 표시,
    가입 후 자동 로그인 → 원래 경로 복귀, 자동 로그인 실패 시 /login 안내 + 이메일 프리필
  - 로그인/가입 공용 모듈: authValidation.ts, useAuthRedirect.ts, AuthTextField.tsx, AuthForm.module.css
  - 백엔드 가입 입력 규칙: 이메일 trim + 소문자 정규화(가입·로그인 공통), 최대 100자 /
    비밀번호 8~64자 + UTF-8 72바이트 이하(bcrypt 한계), 공백만 불가 / 닉네임 trim 후 2~20자,
    보이지 않는 문자(Cf/Cc, 한글 채움 문자) 불가, **닉네임 중복 허용**
  - 이메일 중복(동시 가입 레이스 포함) → 400 `{"email": "이미 가입된 이메일입니다."}`
    (DataIntegrityViolation은 email unique 위반만 중복으로 매핑, 그 외는 로그 + 500)
  - 공통 예외 처리: 깨진 JSON 400, Spring 표준 예외는 원래 상태코드 + 한국어 문구, 그 외 500,
    5xx 상태 유지, Security 예외 rethrow. `/error` permitAll. 오류 메시지 한국어로 통일
  - 요청 DTO toString 비밀번호 마스킹, 로그인 시 72바이트 넘는 비밀번호 즉시 실패
  - 백엔드 단위 테스트 23건(AuthInputNormalizer 7, GlobalExceptionHandler 5, AuthService 11),
    build.gradle에 `useJUnitPlatform()` 추가(없어서 테스트가 0건으로 건너뛰어졌음)
  - 검증: Docker 스택 E2E는 2차 리뷰 반영 전에만 수행. 반영 후에는 `npm run build`, `gradle build`
    (단위 테스트 포함)만 통과
  - 주의: 가입 검증 규칙이 DTO 어노테이션 / 서비스 상수 / 프론트 authValidation.ts 세 곳에 중복 —
    규칙 변경 시 세 곳 모두 수정
- **FR-01 401/403 구분 + 401 재발급 인터셉터 구현, 3차 리뷰 반영 완료 (2026-10-03, 커밋 33bca20, PR #1로 master 머지)**
  - 원칙: 401 = 미인증(재발급 대상), 403 = 권한 없음(재발급 안 함). refresh 일시 장애 시 로그아웃하지 않고 토큰 보존
  - [backend] JwtAuthenticationEntryPoint / JwtAccessDeniedHandler / SecurityErrorResponseWriter
    (SecurityConfig 등록): 401 `{"message":"로그인이 필요합니다."}`, 403 `{"message":"접근 권한이 없습니다."}`,
    401/403에도 CORS 헤더 유지, `/api/auth/**` 동작 유지
  - [backend] JwtAuthenticationFilter: 삭제된 사용자·JWT 파싱 오류(UsernameNotFoundException | JwtException)만
    401, 그 외 예외는 전파. 필터 서블릿 자동 등록 비활성화(이중 등록 방지). RefreshRequest 메시지 한국어화
  - [frontend] 401 인터셉터: 401 + 미재시도 + 인증 엔드포인트(`/auth/login|signup|refresh`, 화이트리스트) 아님
    → single-flight refresh → 1회 재시도. refresh 400·토큰 없음·재시도 후 401 → 토큰 삭제 + 로그아웃
    (→ /login, 원래 경로 유지). 네트워크/5xx → 토큰 유지 + "서버 오류/연결 불가" 표시, 5초 쿨다운.
    403은 재발급 안 함. tokenStorage.subscribe로 같은 탭 로그아웃 알림.
    모듈: authInterceptor.ts, session.ts, authClient.ts (순환 import 없음)
  - 테스트: 백엔드 총 42건(이번에 19건 추가) 통과, `npm run build` 통과. curl 15케이스·프론트 통합 5/5·
    목 시나리오 18/18 확인. 단 3차 리뷰 반영분은 빌드·단위 테스트·목 시나리오로만 검증(Docker 중지)
- **Header + 공통 레이아웃 구현, 4차 리뷰 반영 완료 (2026-10-03, 커밋 c77eea8, PR #2로 master 머지)**
  - AppLayout + Outlet으로 전 페이지 공통 레이아웃, 콘텐츠 영역 `<main>`. 공통 전역 스타일은 `src/index.css`
  - 로고 → `/`, 메뉴: 교환 목록(`/exchange`)·티켓 등록(`/tickets/new`), 현재 경로 강조(aria-current)
  - 로그인: 이메일(말줄임) + 마이페이지 + 로그아웃(홈 이동 후 로그아웃). `/me` API가 없어 닉네임 대신 이메일 표시
    (→ feature/user-me에서 /me 기반 닉네임 표시로 변경, 아래 항목 참고)
  - 비로그인: 로그인·회원가입 링크, 로그인 후 원래 페이지 복귀(로그인/회원가입 화면에서는 기존 복귀 경로 유지)
  - 초기 로딩 중 인증 영역 자리 유지(레이아웃 이동 방지)
  - 768px 미만 햄버거 메뉴(Esc·바깥 터치·경로 이동·화면 확대 시 닫힘, 포커스 관리), 상단 고정, 터치 영역 44px 이상.
    768px 기준은 640px에서 한 줄에 안 들어간다는 추정치 계산에 근거
  - 검증: `npm run build`, tsc(미사용 검사 포함) 통과, dev 서버 `/`·`/login`·`/signup`·`/exchange` 200.
    브라우저 클릭 확인은 미실시
- **브랜치 전략 (2026-10-03, CLAUDE.md에 추가, PR #3으로 master 머지)**: 접두사 feature/(새 기능), fix/(버그),
  chore/(설정·인프라), docs/(문서만)
- **프론트 스타일링 CSS Modules → Tailwind CSS v4 전환, 5차 리뷰 반영 완료 (2026-10-03, 커밋 33704a4, PR #4로 master 머지 — 브랜드 컬러 포함)**
  - 현재 프론트 스택: React + TypeScript + Vite + **Tailwind CSS v4** (tailwindcss, @tailwindcss/vite 4.3.3,
    vite 플러그인 등록, postcss/tailwind 설정 파일 없음). 2절 표는 03 계획서 기준이라 Tailwind가 없음
  - 규칙(react-conventions 스킬 "CSS 전략" 절): Tailwind 유틸리티 클래스만 사용(CSS Module·@apply 미사용),
    완성된 클래스 문자열만 사용, 브레이크포인트 640/768/1024px 고정(rem 아님 — JS matchMedia와 일치),
    지원 브라우저 Safari 16.4+ / Chrome 111+ / Firefox 128+
  - index.css: `@import "tailwindcss"` + `@theme`(폰트, 브랜드 색 토큰 primary #8A2BE2 / secondary #BEA886 / accent #E8A33D, gray·red hex 고정, 브레이크포인트 px 고정).
    AuthForm/Header/AppLayout.module.css 삭제, 반복 클래스 조합은 authFormClasses.ts 상수
  - 추가: 버튼·링크 focus-visible 포커스 링, 오류 입력칸 빨간 테두리·빨간 포커스 링
  - 5차 리뷰 높음 0 / 중간 2 / 낮음 9, 삭제된 CSS 규칙 누락 0건(빌드 CSS 대조)
  - 검증: tsc, vite build 통과, dev 서버 `/`·`/login`·`/signup`·`/exchange` 200. 브라우저 육안 확인 미실시
  - 결정: 브랜드 컬러 메인 #8A2BE2 / 보조 #BEA886(글자색 금지) / 강조 #E8A33D, blue 클래스는 primary 토큰으로 교체
- **내 정보 조회 `/me` + 마이페이지 + 인증 주체 userId 전환, 6차 리뷰 반영 완료 (2026-10-03, feature/user-me 브랜치, 커밋 전)**
  - [backend] `GET /api/users/me` → 200 `{id, email, nickname, trustScore}`. 미인증·삭제된 사용자·refresh token 사용 → 401
  - [backend] **인증 사용자 식별은 토큰 userId 기준** (email 기준에서 전환): AuthUserPrincipal, 필터는 토큰 sub로 findById.
    이유: 향후 이메일 변경 시 옛 토큰이 같은 이메일을 새로 쓰는 다른 사용자로 인증될 위험 차단.
    spring-boot-conventions 스킬에 "인증 사용자 식별은 userId", "인증 주체 소실은 AuthenticationException → 401" 조항 추가
  - [backend] 테스트 51건 통과
  - [frontend] 헤더: 닉네임 표시(로딩·실패 시 이메일), 햄버거 메뉴에서는 닉네임 옆에 이메일 작게, 마이페이지 버튼은
    메인색 채운 버튼(데스크톱은 메뉴 링크 유지), 마이페이지·로그아웃 한 줄 배치
  - [frontend] 마이페이지: 닉네임·이메일·신뢰도 점수·로그아웃, 로딩/오류/다시 시도. 내 티켓·거래 내역·받은 리뷰는
    "준비 중", **회원탈퇴 버튼은 "준비 중"으로 비활성화**
  - [frontend] 프로필 상태: 사용자 전환·로그아웃 시 늦게 온 응답 폐기, 공용 useLogout 훅. trustScore 타입 `number | null`
  - 6차 리뷰 높음 0 / 중간 3 / 낮음 8 반영
  - 검증: 프론트 통합(usersApi.me 200, 토큰 없음 401), curl(200, 401 케이스), `npm run build`·tsc 통과.
    userId 전환 이후분은 Docker 중지로 빌드·단위 테스트로만 검증. 브라우저 육안 확인은 사용자 몫
- **Spring Boot 3.3.0 → 3.3.13 업그레이드로 CVE-2025-22228 해결 (2026-10-03, chore/upgrade-spring-security 브랜치·별도 worktree, 커밋 전)**
  - dependency-management 1.1.4 → 1.1.7, Spring Security 6.3.10
  - BCrypt 72바이트 회귀 테스트 추가(이 브랜치 기준 총 49건 통과). 업그레이드 후에도 matches는 앞 72바이트만 비교 →
    **로그인 72바이트 사전 차단은 계속 유지해야 함** (테스트로 고정)
  - plain jar 생성 비활성화 (Docker 빌드 jar 다중 COPY 문제 방지). code-reviewer 리뷰 높음 0, 회귀 없음
  - mysql-connector-j 8.4.0 수동 지정 유지 (Boot 3.3.13 BOM 관리 버전 8.3.0이 더 낮음)
  - 결정: 3.3.13으로 일단 마무리, 추후 4.x 메이저 업그레이드 (3.3·3.4·3.5 라인 OSS 지원 종료, 3.3.13에도
    Security·Framework·Tomcat CVE 다수 잔존)
- FR-01 후속 과제:
  - 3차 리뷰 반영분 Docker 재기동 후 curl·통합 itest 재검증
  - 2차 리뷰 반영분 Docker 스택 재기동 후 E2E 재검증, 브라우저에서 가입/로그인 화면 직접 확인
  - 필터에서 전파된 예외(DB 장애 등)는 Spring 기본 `/error` 포맷(`{timestamp,status,error,path}`)으로 나감 —
    `{message}` 포맷 통일 검토, CORS 헤더 유지 여부 실측
  - 403 경로는 역할 기반 규칙이 없어 슬라이스 테스트로만 확인
  - (완료 2026-10-02) `/error` permitAll + 공통 예외 핸들러, SignupPage
  - 브라우저에서 Header 직접 확인 (햄버거 열고 닫기, 로그아웃 이동, 메뉴 강조, 640~1024px 폭 넘침 여부)
  - index.html viewport에 `viewport-fit=cover` 없음 → safe-area 여백 미적용 (켜려면 다른 화면 여백도 함께 조정)
  - 알림 아이콘 (백엔드 기능 필요)
  - 브라우저에서 Tailwind 전환 화면 확인 (360 / 640 / 768 / 1024px)
  - (완료 2026-10-03) 색 팔레트 방침 결정 — 브랜드 토큰
  - iOS 실기기에서 비활성 입력칸 흐림 정도 확인
  - (완료 2026-10-03) 401 AuthenticationEntryPoint, 401 재발급·재시도 인터셉터, Header 로그인 상태 메뉴/로그아웃
  - (결정 2026-10-03) 프론트 테스트 러너(vitest) 도입 안 함 → 해당 후속 과제 종료
  - (완료 2026-10-03) 내 정보 조회 `GET /api/users/me`, Spring Security CVE-2025-22228 패치 업그레이드(3.3.13 / 6.3.10)
  - [backend] Spring Boot 4.x 메이저 업그레이드 (Spring 7 / Security 7 / Hibernate 7 / Jackson 3, Gradle 업그레이드 가능성) — 배포 전
  - [backend] 회원탈퇴 기능
  - /me 요청 하나에 사용자 조회 2회(필터 + 서비스) — 필요 시 최적화
  - 브라우저에서 헤더·마이페이지 확인 (햄버거 메뉴 이메일·마이페이지 버튼, 회원탈퇴 비활성)
  - [docs] 04 요구사항정의서 FR-01 하위에 "내 정보 조회" 추가 필요 (원본 확보 후)
- 다음 단계: 위 후속 과제, 나머지 도메인(공연/티켓/교환/채팅) 구현
- 참고: 2026-10-02 기준 저장소에 `산출물/` 03/04/05/08 원본이 없음. 원본 확보 전까지 1~6절은 이 스킬이 유일한 텍스트 출처
- 작업일지(산출물/07): 날짜별 `YYYY-MM-DD.md` 파일, 이어지는 작업 묶음은 시작일 파일에 `## 날짜` 섹션을 추가.
  현재 `2026-07-26.md`(본문 헤더 2026-07-23, 기획 단계), `2026-10-02.md`(2026-10-02 + 2026-10-03 섹션)
- 결정 (2026-10-03): 프론트 테스트 러너(vitest)는 도입하지 않음 — 인터셉터 분기는 저장소 밖 임시 스크립트로만 검증된 상태
