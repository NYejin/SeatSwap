# SeatSwap Frontend (React + TypeScript + Vite)

## 폴더 구조
- api         — axios 클라이언트 (client.ts에서 JWT 인터셉터 일괄 처리)
- components  — 공용 컴포넌트 (ProtectedRoute, TextField 등)
- pages       — 라우트 단위 화면 (App.tsx 라우팅 참고)
- types       — 백엔드 DTO에 대응하는 타입
- hooks       — useAuth 등 커스텀 훅

## 현재 상태
- 구현됨: 로그인(`/login`)·회원가입(`/signup`), 공연 목록(`/`), 공연 등록(`/performances/new`), 공연 상세(`/performances/:id`), 마이페이지(`/me`). 로그인이 필요한 화면은 ProtectedRoute로 보호하고, api/client.ts에서 401 재발급 인터셉터를 처리한다.
- 2026-10-07 방향 전환으로 교환·티켓 화면과 좌석표 화면은 삭제했다 (좌석표 코드는 태그 `archive/seatmap-track-20261007`에 보관). 교환·티켓 화면은 교환 도메인 설계 후 다시 작성할 예정이다.

## 다음 단계
1. 티켓 등록 화면 (텍스트 좌석 입력: 구역·열·번)
2. 교환 요청(희망 좌석 범위·추가금)과 자동 매칭 후보 화면
3. 채팅·후기 화면
