---
name: react-conventions
description: SeatSwap/frontend(React + TypeScript) 코드(화면, 컴포넌트, API 연동) 작성 시 반드시 참고할 폴더 구조와 컨벤션. 특히 좌석맵 오버레이 UI 관련 규칙을 포함한다.
---

# React 프론트엔드 컨벤션

## 위치
`SeatSwap/frontend/src/`

## 폴더 구조
```
src/
├── components   # 공용 컴포넌트
├── pages        # 라우트 단위 화면
├── api          # axios 클라이언트 (인터셉터로 JWT 처리)
├── types        # 공용 타입
└── hooks        # useAuth 등 커스텀 훅
```

## 좌석맵 오버레이 규칙
- 백엔드가 내려주는 좌표는 원본 이미지 픽셀 기준이다. 화면에 표시할 때 이미지의 실제
  렌더링 크기에 맞춰 좌표를 스케일링해야 한다 (반응형 대응 필수).
- 좌석 사각형은 SVG `<rect>` 오버레이로 그리고, 판매 상태에 따른 색상 구분은 하지 않는다
  (이 서비스는 이미 판매된 좌석만 다룸 — 선택됨/선택안됨만 구분).
- 좌석을 탭하면 항상 "오류 신고" 버튼을 함께 노출해, 인식이 잘못됐을 때 바로 신고할 수 있게 한다.

## 일반 규칙
- API 호출은 `api/` 아래 도메인별 파일로 분리 (`api/tickets.ts`, `api/exchange.ts` 등)
- `any` 타입 금지 — 백엔드 DTO에 대응하는 타입을 `types/`에 정의
- 라우팅은 `react-router-dom`, 인증 필요한 라우트는 공용 `<ProtectedRoute>`로 감싼다
- 모바일 터치 우선 설계 (버튼 최소 44px 터치 영역) — 플랫폼은 네이티브 앱이 아닌
  모바일 웹(반응형, 필요 시 PWA)으로 결정됨

## CSS 전략
- 전역 CSS나 CSS Module 대신 **Tailwind CSS v4 유틸리티 클래스**로 스타일링한다.
  이유: 페이지 수가 늘어나도 스타일 반복/클래스명 충돌이 없도록 하기 위한 결정.
- 설정: `@tailwindcss/vite` 플러그인(`vite.config.ts`) + `src/index.css`의 `@import "tailwindcss";`.
  `postcss.config.js`, `tailwind.config.js`는 두지 않는다.
- `src/index.css`에는 Tailwind import와 `@theme` 토큰(글꼴 `--font-sans`, 브레이크포인트, 색상)만 둔다.
  전역 규칙(요소 선택자 등)은 추가하지 않는다 — 기본 리셋은 Tailwind preflight가 담당.
- `.module.css`, `@apply`, 커스텀 CSS 클래스는 쓰지 않는다.
- **클래스명은 항상 완성된 문자열로 쓴다.** `bg-${color}-600`처럼 조각을 이어 붙이면 Tailwind가 소스에서
  클래스를 감지하지 못해 CSS가 생성되지 않는다. 조건부 스타일은 완성된 클래스 문자열 중 하나를 고르는 분기로 쓴다
  (예: `error ? "border-red-600 ..." : "border-gray-300 ..."`).
- 반복되는 클래스 조합은 컴포넌트로 묶거나(예: `AuthTextField`) 파일 상단/공용 상수로 모은다
  (예: `components/authFormClasses.ts`, Header의 `cls`). 조건부 클래스는 템플릿 문자열로 조합한다
  (clsx 등 추가 의존성 없음). 서로 덮어쓰는 클래스(색상, 같은 속성의 포커스 링 등)는 한 요소에 동시에 넣지 말고
  분기로 하나만 넣는다.
- **브레이크포인트는 `@theme`에서 px로 고정**한다: `--breakpoint-sm: 640px`, `--breakpoint-md: 768px`
  (모바일 메뉴 ↔ 데스크톱 전환), `--breakpoint-lg: 1024px`. JS에서 같은 기준이 필요하면(`matchMedia`)
  같은 px 값(`"(min-width: 768px)"`)을 쓰고 주석으로 연결한다.
- **색상 — 브랜드 토큰 (사용자 결정):** 브랜드 색은 `@theme`의 hex 토큰으로만 쓴다.
  - `primary` (메인 #8A2BE2 = `primary-600`, 50~900 스케일): 버튼 배경, 링크·로고·활성 메뉴 글자, 포커스 링
    (`primary-600/20`, `/40`), 연한 배경(`primary-50`). 눌림은 `primary-700`, 연한 배경 위 긴 글자는 `primary-800`.
  - `secondary` (보조 #BEA886 = `secondary-500`, 50/100/200/500/700): **글자색으로 쓰지 않는다** — 흰 배경 대비
    약 2.3:1로 WCAG AA(4.5:1) 미달. 배경·테두리·장식선 용도로만 절제해서 쓴다 (현재: 헤더 하단 경계선, 카드 상단 장식선).
  - `accent` (강조 #E8A33D = `accent-500`, 100/500/700): 흰 배경 글자 금지(2.16:1). 배지·하이라이트 배경 등에 쓰고
    그 위 글자는 `gray-900`. 쓸 곳이 없으면 억지로 넣지 않는다 (현재 사용처 없음).
  - `blue-*`, `purple-*` 등 **팔레트 이름을 브랜드 색 용도로 직접 쓰지 않는다** (브랜드 색이 바뀌어도 토큰만 고치면 되도록).
  - 새 글자/배경 조합을 만들면 WCAG AA 대비(본문 4.5:1)를 계산해 확인한다.
  - 중립·상태 색(`gray-*`, `red-*`)은 `@theme`에서 기존 hex(Tailwind v3 값)로 고정해 쓴다. 새 단계가 필요하면
    같은 방식으로 `@theme`에 hex를 추가한다 (v4 기본 팔레트는 oklch라 같은 이름도 색이 다름).
- 원본 디자인은 line-height를 지정하지 않았다(브라우저 기본 `normal`). 크기 클래스는 `text-sm/[normal]`처럼
  `normal` 키워드를 붙여 쓴다 (`leading-normal`·`/normal`은 1.5라 다르다).
- **지원 브라우저 하한**: Tailwind v4 요구사항에 따라 Safari 16.4+, Chrome 111+, Firefox 128+.
  이보다 오래된 브라우저에서는 스타일이 깨질 수 있다.
- 모바일 규칙 유지: 터치 영역 `min-h-11`(44px) 이상, 입력칸 글자 `text-base`(16px, iOS 확대 방지),
  키보드 포커스는 `focus-visible:` 링으로 표시.
