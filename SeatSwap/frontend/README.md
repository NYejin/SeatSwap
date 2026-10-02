# SeatSwap Frontend (React + TypeScript + Vite)

## 폴더 구조
- api         — axios 클라이언트 (client.ts에서 JWT 인터셉터 일괄 처리)
- components  — 공용 컴포넌트 (SeatMapOverlay, SeatMapErrorReportButton, ProtectedRoute 등)
- pages       — 라우트 단위 화면 (App.tsx 라우팅 참고)
- types       — 백엔드 DTO에 대응하는 타입
- hooks       — useAuth 등 커스텀 훅

## 현재 상태
- 라우팅 구조(App.tsx)와 페이지/컴포넌트/타입 **틀(스켈레톤)**만 생성됨
- 좌석맵 오버레이(SeatMapOverlay)는 react-conventions 스킬의 규칙(모바일 터치 우선,
  판매상태 색상 구분 없음)을 따라 구현 예정

## 다음 단계
1. api/client.ts에 401 재시도 인터셉터 추가
2. LoginPage/SignupPage 폼 구현 → authApi 연동
3. SeatMapOverlay 좌표 스케일링 로직 구현
