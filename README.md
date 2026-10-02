# SeatSwap

같은 공연의 티켓을 보유한 사람들끼리 좌석을 맞교환하거나, 좌석 등급 차이에 따른 추가금액을 주고받으며 자리를 교환할 수 있는 개인 포트폴리오 웹 서비스입니다.

기존 티켓 재판매(스캘핑)와 달리, **이미 유효한 티켓을 보유한 사용자 간의 이동**이라는 점에서 차별화됩니다.

## 핵심 시나리오

- **단순 교환**: 같은 공연 내에서 두 사용자가 좌석을 1:1로 맞바꿈
- **차액 거래**: 좌석 등급 차이가 있을 경우 한쪽이 추가 금액을 지불하며 교환
- 매칭은 추천 알고리즘이 아닌 **단순 1:1 신청/수락** 구조

## 기술적으로 가장 도전적이었던 부분 — 좌석맵 자동 인식

티켓팅 사이트 좌석맵 이미지를 스크래핑한 뒤, **딥러닝 없이 고전적 컴퓨터비전**(배경 제외 + `connectedComponentsWithStats`)만으로 개별 좌석 좌표를 검출합니다. 실제 멜론티켓 개별좌석도 이미지로 검증한 결과, 약 207~210개 좌석 블록을 정확히 검출했습니다. 이후 Tesseract OCR로 행 번호를 인식하고, x좌표 순서로 열 번호를 자동 부여합니다. 인식 오류는 사용자 신고 기반으로 보정되며(동일 정정 2건 이상 시 자동 반영), 한 번 보정된 공연장 좌석맵은 이후 재사용됩니다.

## 기술 스택

| 구분 | 기술 | 선정 이유 |
|---|---|---|
| Frontend | React, TypeScript, Vite | 인터랙티브 UI(좌석맵 오버레이, 채팅)에 적합, 타입 안전성 |
| Backend | Spring Boot, Spring Security(JWT), JPA, WebSocket(STOMP) | 11개 엔티티의 관계형 데이터 처리, 성숙한 인증 생태계 |
| DB | MySQL | FK 관계가 많은 구조에 적합 |
| 이미지 인식 서버 | FastAPI, OpenCV, Tesseract OCR | Python이 이미지/OCR 생태계의 중심, 메인 서버와 책임 분리 |

백엔드(`backend`)와 좌석 인식 서버(`seatmap-service`)를 별도 프로세스로 분리한 이유 등, 더 자세한 설계 근거는 [`CLAUDE.md`](./CLAUDE.md)와 [`산출물/03_프로젝트계획서`](./산출물/03_프로젝트계획서)에 정리되어 있습니다.

## 구현 현황

- [x] 기획/설계 문서화 (프로젝트계획서, 요구사항정의서, WBS, ERD)
- [x] Spring Boot / React / FastAPI 스켈레톤 및 Docker Compose 구성
- [x] 회원가입 / 로그인 / JWT 인증 (access·refresh 토큰, 자동 재발급)
- [ ] 공연 등록 및 좌석맵 인식 파이프라인
- [ ] 교환 요청 / 1:1 매칭
- [ ] 실시간 채팅
- [ ] 거래 상태 관리 / 리뷰·신뢰도

## 폴더 구조

```
00_3rd_project/
├── CLAUDE.md          # 프로젝트 전역 컨텍스트 (핵심 설계 결정 요약)
├── .claude/           # Claude Code 하네스 (서브에이전트 6종, 스킬 6종)
├── SeatSwap/           # 소스코드
│   ├── backend/            # Spring Boot
│   ├── frontend/            # React + Vite
│   ├── seatmap-service/     # FastAPI + OpenCV/OCR
│   └── docker-compose.yml
└── 산출물/             # 문서 산출물
    ├── 03_프로젝트계획서
    ├── 04_요구사항정의서
    ├── 05_WBS
    ├── 07_작업일지
    ├── 08_ERD
    └── ...
```

## 로컬에서 실행하기

Docker Compose로 전체 스택(MySQL + backend + seatmap-service + frontend)을 한 번에 띄울 수 있습니다.

```bash
cd SeatSwap
cp .env.example .env   # 값 채우기
docker compose up --build
```

| 서비스 | 주소 |
|---|---|
| 프론트엔드 | http://localhost:5173 |
| 백엔드 API | http://localhost:8080/api |
| 좌석 인식 서비스 | http://localhost:8001 |

자세한 실행 방법(개별 가상환경 실행, 트러블슈팅 등)은 [`SeatSwap/README.md`](./SeatSwap/README.md)를 참고하세요.

## AI 활용 개발 (Claude Code 하네스)

이 프로젝트는 [Claude Code](https://claude.com/claude-code) 서브에이전트·스킬 체계(`.claude/agents`, `.claude/skills`)를 구성해 세션이 바뀌어도 아키텍처 결정과 컨벤션이 일관되게 유지되도록 개발했습니다.

- `backend-dev` / `frontend-dev` / `seatmap-vision-engineer`: 영역별 구현 담당
- `db-schema-architect`: ERD/엔티티 설계
- `doc-writer`: 산출물 문서 동기화
- `code-reviewer`: 구현 완료 후 리뷰
- `project-knowledge` 스킬: 기획 문서(docx/xlsx)의 핵심 내용을 텍스트로 요약해, 바이너리 문서를 직접 열지 않고도 에이전트가 프로젝트 맥락을 파악할 수 있도록 함

자세한 설계 배경은 [`CLAUDE.md`](./CLAUDE.md)에 정리되어 있습니다.

## 산출물 문서

| 문서 | 설명 |
|---|---|
| [03 프로젝트계획서](./산출물/03_프로젝트계획서) | 개요, 범위, 기술스택 선정 이유, 일정, 리스크 |
| [04 요구사항정의서](./산출물/04_요구사항정의서) | 기능/비기능 요구사항, 유스케이스 |
| [05 WBS](./산출물/05_WBS) | 단계별 일정 |
| [08 ERD](./산출물/08_ERD) | 11개 엔티티 관계도 |
| [07 작업일지](./산출물/07_작업일지) | 개발 진행 기록 |
