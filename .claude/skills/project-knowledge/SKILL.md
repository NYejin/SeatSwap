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
| 공연/좌석맵 등록 | 공연 등록(링크 입력 시 제목·공연장·날짜 범위만 미리 채움, 회차는 직접 입력), 좌석맵은 사용자 이미지 업로드/이미지 주소 입력 (2026-10-07 변경, 링크 자동 수집 불가) |
| 좌석 자동 인식 | OpenCV+OCR로 좌표·행/열 번호 자동 추출 |
| 좌석맵 터치 선택 | SVG 오버레이로 본인 좌석 선택 |
| 오류 신고/보정 | OFFICIAL 좌석표: 동일 정정 2건 이상 시 자동 반영, 1건은 검토 대기 / DRAFT: 수정 즉시 반영 + 수정 로그 |
| 교환 요청/매칭 | 단순 교환 또는 차액 거래, 1:1 신청·수락 |
| 실시간 채팅 | WebSocket(STOMP) |
| 거래 상태 관리 | 제안→수락→교환완료 |
| 리뷰/신뢰도 | 거래 완료 후 상호 리뷰, 신뢰도 점수 반영 |

전체 FR/NFR/UC 상세 번호와 우선순위·담당자는 `산출물/04_요구사항정의서` 참고
(팀 프로젝트 양식: 구분/서비스/기능명/기능설명/개발상태/개발우선순위/담당자/선행기능/비고).

## 4. 좌석맵 인식 파이프라인 (산출물/03, seatmap-recognition-pattern 스킬과 동일 내용)

1. 이미지 확보 — (2026-10-07 변경) 사용자가 올린 이미지(/recognize) 또는 입력한 이미지 주소(/recognize-url). 원본은 저장하지 않고 좌표만 저장
2. 좌석 블록 검출 — 배경(흰색 계열) 제외 + connectedComponentsWithStats. 색상/판매상태 매핑 안 함
3. 행 번호 인식 — y좌표 클러스터링 + Tesseract OCR
4. 열 번호 부여 — 행 내 x좌표 순 정렬, 통로를 건너도 번호를 이어씀(aisleMode=continue 기본, 2026-10-07). 실제와 다르면 사용자가 수정 요청·수정
5. 오류 보정 — OFFICIAL 좌석표는 동일 정정 2건 이상 시 자동 반영

### 좌석맵 확보·좌석표 흐름 (2026-10-07 결정, CLAUDE.md와 동일)
- 링크 스크래핑 불가: 멜론·YES24·티켓링크는 공개 페이지에 좌석맵이 없고(예매·보안문자 뒤), 인터파크는 robots.txt가 전면 금지. 로그인·캡차 우회·내부 API 조사는 하지 않음
- 좌석표 상태: 공연장 정식 등록 전에는 사용자 이미지 인식 결과를 `DRAFT`(공연장당 하나, 재사용·수정)로 저장 → 관리자가 공연장·공연을 정식 등록하면 `OFFICIAL`, 이후 그 공연장 공연에 자동 연결
- DRAFT는 확인용으로만 표시. 교환 매칭은 본인 좌석 정보 + 희망 좌석 범위로 하고, 좌표 기반 선택·매칭은 OFFICIAL에서만
- 모든 수정은 로그(누가·언제·전후). 악의적 수정은 신고나 관리자 확인이 있을 때만 제재(자동 제재 없음). 관리자는 DB에서 ADMIN 직접 부여로 시작
- 이미지 보관: 원본·주소 저장 안 함, 좌표는 원본 픽셀 기준, 화면은 SVG. 필요 시 핫링크/서비스 보관으로 확장(컬럼은 Flyway로 추가)
- 링크 자동 입력: 공개 페이지(JSON-LD·og)에서 제목·공연장·날짜 범위만(회차 시각은 직접 입력), 자동 등록 없음, 인터파크 제외

실측 검증(과거 기획 단계): 딥러닝 없이 약 207~210개 좌석 블록 정확 검출 확인됨 (실제 멜론티켓 이미지 기준). 2026-10-07 feature/seatmap-service는 합성 이미지 정확도 100%이나 실제 이미지 검증은 아직 못함.

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

12개 엔티티: User, Venue, Performance, PerformanceSession, Ticket, SeatMapLayout, SeatCorrection,
ExchangeRequest, ExchangeMatch, ChatRoom, Message, Review.
(2026-10-06 공연 회차 `PerformanceSession` 추가로 11 → 12. 08_ERD 원본 png는 아직 11개 기준 —
반영할 변경 목록은 erd-conventions 스킬 "08_ERD 원본 반영 대기" 절)

주요 관계:
- Venue 1:N SeatMapLayout / 1:N Performance
- Performance 1:N PerformanceSession (회차: 날짜·시간), User 1:N Performance (등록자)
- SeatMapLayout 1:N SeatCorrection / 1:N Ticket
- User 1:N Ticket, PerformanceSession 1:N Ticket (Ticket은 공연이 아니라 회차를 참조)
- Ticket 1:1 ExchangeRequest
- ExchangeRequest 1:N ExchangeMatch (A측/B측)
- ExchangeMatch 1:1 ChatRoom, ChatRoom 1:N Message
- ExchangeMatch 1:N Review

공연·회차·공연장 규칙 (2026-10-06 결정):
- 공연 등록: 로그인 사용자 누구나, 티켓팅 링크(sourceUrl) 입력. 중복 판정은 링크 정규화 값 `source_key` unique
  (사이트별 상품 ID `{site}:{productId}`, 미지원 사이트는 일반 URL 정규화)
- 회차: 공연 1:N, `starts_at` 분 단위, (공연, 일시) unique. **좌석 교환은 같은 회차끼리만**
- 공연장: 검색 후 선택, 없으면 추가. 중복 판정은 이름 정규화 값 `normalized_name` unique
  (공백·구두점·대소문자·전각 차이 흡수). SeatMapLayout은 Venue 단위 재사용(NFR-03)
- 수정/삭제: 공연은 등록자만(공연장 변경·삭제는 티켓 0건일 때만, 링크 수정 불가), 회차 추가는 누구나,
  회차 수정·삭제는 공연 등록자만 + 티켓 0건일 때만, 공연장은 일반 사용자 수정·삭제 불가

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
- **내 정보 조회 `/me` + 마이페이지 + 인증 주체 userId 전환, 6차 리뷰 반영 완료 (2026-10-03, feature/user-me 브랜치, PR #6으로 master 머지)**
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
- **Spring Boot 3.3.0 → 3.3.13 업그레이드로 CVE-2025-22228 해결 (2026-10-03, chore/upgrade-spring-security 브랜치, PR #5로 master 머지)**
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
- **공연·공연장·회차 등록/조회(FR-02) 구현 + 7차 리뷰 반영 완료 (2026-10-06, feature/performance 브랜치, 커밋·푸시 예정)**
  - 스키마 12개 엔티티, 규칙은 6절 참고. 시각은 KST 일원화(JpaAuditingConfig + Clock(Asia/Seoul), Dockerfile·compose TZ=Asia/Seoul)
  - [backend] 공연장 검색·추가(같은 이름 재추가는 200으로 기존 반환), 공연 목록(asOf로 기준 시각 고정)·상세·lookup·등록·수정·삭제,
    회차 추가·수정·삭제. 공연 중복(링크) 409 + performanceId, 회차 중복 409, 과거 회차 400, 수정·삭제는 등록자만(403),
    티켓이 있으면 공연장 변경·삭제·회차 변경 불가(409). 오류 포맷 400/401/403/404/409 통일
  - [backend] SourceKeyResolver·TicketingSite: 인터파크/멜론/YES24/티켓링크는 `{site}:{productId}`, 그 외는 호스트+경로+정렬 쿼리+프래그먼트
    (합쳐지는 것보다 놓치는 쪽 선택), SSRF 대비 허용 호스트 목록(좌석맵 수집 단계용)
  - [backend] 동시성: 공연 행 PESSIMISTIC_WRITE로 변경·삭제·회차 변경 직렬화, 제약 이름으로 중복 판별,
    분류 안 된 DataIntegrityViolation은 409, `spring.jpa.open-in-view=false`. source_key는 utf8mb4_bin(대소문자 구분)
  - [frontend] 홈(공개 라우트: 비로그인 서비스 소개 / 로그인 공연 검색·목록·더 보기), 공연 등록 5단계(링크 확인·제목·공연장·회차·등록),
    공연 상세(보호 라우트, 등록자만 수정·삭제, 페이지 내 확인 상자, 외부 링크 호스트 표시 + 비공식 예매처 안내),
    AuthTextField→TextField·components/ui.ts 공용화, 포커스 링 대비 5.96:1, 중립 배경 surface 토큰
  - 테스트: 백엔드 161건 통과. 7차 리뷰 높음 2 / 중간 8 / 낮음 13 반영
  - DB 조치: Docker DB를 새 스키마로 재생성(users 유지), source_key를 utf8mb4_bin으로 ALTER, 기존 행 시각 +9시간 보정
  - 검증: 실제 서버 smoke 통과, 프론트 itest 13/13. 브라우저 육안 확인은 미실시. 사용자가 등록한 공연 데이터는 보존
  - 결정: 공연은 로그인 사용자 누구나 등록, 홈 공개·공연 상세 보호 라우트, 링크 기반 공연정보 자동 입력(아래 다음 단계 3번),
    정정 정책 보류, 메인 페이지 Phase 1 구현 보류. venue.normalized_name은 ai_ci 유지, idx_performance_session_starts_at은 미사용이나 유지
  - 이슈: ddl-auto update는 컬럼 길이·NOT NULL·collation 변경·컬럼 삭제를 반영하지 못해 이번엔 수동 DDL·테이블 재생성 처리
  - 후속: 회차 추가 개수 상한·스팸 정리, 공연장 무제한 생성·중복 정리, 예매처별로 같은 공연이 갈라지는 문제(같은 공연장+비슷한 제목 안내),
    티켓 등록 시 공연 행 잠금(M6), 실제 MySQL 통합 테스트 없음(Testcontainers는 의존성 승인 필요), Flyway 도입 검토(의존성 승인 필요),
    좌석맵 수집 시 SSRF 대비, 브라우저에서 홈·공연 등록·공연 상세 확인,
    [docs] 04 FR-02 하위 항목(공연장 검색·추가, 링크 조회, 회차)·08_ERD 원본에 PerformanceSession·source_key 반영 (원본 확보 후)
- 다음 단계 (2026-10-06 확정 순서):
  1. 공연·공연장·회차 등록/조회(`feature/performance`) 마무리 — 커밋·푸시·머지
  2. 좌석맵 인식 서비스 1차 구현 — 안전한 요청 기반(SSRF 방어: https·443, 호스트 정확 일치, 사설 IP 차단, 리다이렉트 재검증,
     크기·시간 제한)과 사이트별 어댑터(`TicketingSite`)를 함께 구현
  3. 같은 어댑터로 공연정보(제목·공연장·회차) 읽기 → 등록 화면에 미리 채워 사용자가 확인한 뒤 등록
     (CLAUDE.md "링크 기반 공연정보 자동 입력" 결정 참고). 악의적 수정·허위 정보 방지책은 추후 결정
  4. 티켓 등록 + 좌석맵에서 내 좌석 선택
- 후속 과제 (2026-10-06 결정): 공연 정보 정정 정책(등록자 외 수정 수단, 리뷰 M3) — 보류, 상세는 `산출물/04_요구사항정의서/FR-02_공연정보_정정정책_후속과제.md`
- 완료 (2026-10-06): 시간대 수정 전에 저장된 `created_at`/`updated_at`을 KST로 +9시간 보정 (users 2, venue 1, performance 1, performance_session 1행, `starts_at`은 제외)
- 보류 (참고용): 메인 페이지 Phase 1 명세 — react-conventions 스킬 "계획된 화면 명세" 참고, 당장 구현하지 않음
- **2026-10-07 결정·진행 (docs/seatmap-decisions, 상세는 위 4절 및 산출물/07_작업일지/2026-10-07.md)**
  - 좌석맵은 사용자 이미지 업로드/주소 입력이 정식 경로, DRAFT/OFFICIAL 좌석표 흐름, 열 번호 continue 기본, 링크 자동 입력은 제목·공연장·날짜 범위만
  - 별도 브랜치에 구현됐으나 **아직 미병합(진행 중/브랜치 반영 대기)**: fix/session-time-step(회차 시각 10분 단위 입력),
    chore/flyway-migration(Flyway 도입, V1), feature/seatmap-service(Phase 0/1: safe_fetch SSRF 방어, 멜론 어댑터,
    검출·OCR·열번호 파이프라인, API 5종, pytest 77건)
  - FR-02 정정 정책 문서: 관리자 정식 등록 + 수정 로그로 방향 결정, 상태는 '부분 결정'
  - 산출물 원본(04 xlsx/05 WBS/08 ERD) 반영 대기 항목은 2026-10-07.md 끝의 '원본 반영 대기' 목록 참고
- 참고: 2026-10-02 기준 저장소에 `산출물/` 03/04/05/08 원본이 없음. 원본 확보 전까지 1~6절은 이 스킬이 유일한 텍스트 출처
- 작업일지(산출물/07): 날짜별 `YYYY-MM-DD.md` 파일, 이어지는 작업 묶음은 시작일 파일에 `## 날짜` 섹션을 추가.
  현재 `2026-07-26.md`(본문 헤더 2026-07-23, 기획 단계), `2026-10-02.md`(2026-10-02 + 2026-10-03 + 2026-10-06 섹션), `2026-10-07.md`
- 결정 (2026-10-03): 프론트 테스트 러너(vitest)는 도입하지 않음 — 인터셉터 분기는 저장소 밖 임시 스크립트로만 검증된 상태
