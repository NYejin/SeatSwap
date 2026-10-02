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
  LoginPage(입력 검증, 백엔드 400 `{message}`/`{field:msg}` 에러 표시, 모바일 우선 CSS Modules).
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
- **FR-01 401/403 구분 + 401 재발급 인터셉터 구현, 3차 리뷰 반영 완료 (2026-10-03, 커밋 전)**
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
- FR-01 후속 과제:
  - [backend] 내 정보 조회 `GET /api/users/me` (현재 프론트는 JWT 클레임 id/email만 사용)
  - [frontend] Header 로그인 상태 메뉴/로그아웃
  - 3차 리뷰 반영분 Docker 재기동 후 curl·통합 itest 재검증
  - 2차 리뷰 반영분 Docker 스택 재기동 후 E2E 재검증, 브라우저에서 가입/로그인 화면 직접 확인
  - 필터에서 전파된 예외(DB 장애 등)는 Spring 기본 `/error` 포맷(`{timestamp,status,error,path}`)으로 나감 —
    `{message}` 포맷 통일 검토, CORS 헤더 유지 여부 실측
  - 403 경로는 역할 기반 규칙이 없어 슬라이스 테스트로만 확인
  - (참고) Spring Boot 3.3.0의 Security 6.3.0은 CVE-2025-22228 영향 버전 — 72바이트 차단으로
    완화했으나 패치 버전 업그레이드 검토
  - (완료 2026-10-02) `/error` permitAll + 공통 예외 핸들러, SignupPage
  - (완료 2026-10-03) 401 AuthenticationEntryPoint, 401 재발급·재시도 인터셉터
  - (결정 2026-10-03) 프론트 테스트 러너(vitest) 도입 안 함 → 해당 후속 과제 종료
- 다음 단계: 위 후속 과제, 나머지 도메인(공연/티켓/교환/채팅) 구현
- 참고: 2026-10-02 기준 저장소에 `산출물/` 03/04/05/08 원본이 없음. 원본 확보 전까지 1~6절은 이 스킬이 유일한 텍스트 출처
- 작업일지(산출물/07): 날짜별 `YYYY-MM-DD.md` 파일, 이어지는 작업 묶음은 시작일 파일에 `## 날짜` 섹션을 추가.
  현재 `2026-07-26.md`(본문 헤더 2026-07-23, 기획 단계), `2026-10-02.md`(2026-10-02 + 2026-10-03 섹션)
- 결정 (2026-10-03): 프론트 테스트 러너(vitest)는 도입하지 않음 — 인터셉터 분기는 저장소 밖 임시 스크립트로만 검증된 상태
