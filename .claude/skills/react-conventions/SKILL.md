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
