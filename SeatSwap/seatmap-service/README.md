# SeatSwap Seatmap Recognition Service (FastAPI)

Spring Boot 메인 서버와 분리된 좌석맵 이미지 인식 전용 마이크로서비스. API 계약은 [docs/API.md](docs/API.md).

## 구조
- app/safe_fetch.py — 안전한 외부 요청(SSRF 방어). 외부로 나가는 모든 요청은 여기만 거친다
- app/adapters     — 사이트별 어댑터(현재 멜론). 호스트 허용 목록·URL 조립·HTML 파싱을 선언
- app/core         — detection.py(블록 검출·붙은 덩어리 분할) / ocr.py(행 클러스터링·라벨 OCR·열 번호) / sections.py(구역 분할) / pipeline.py(조립)
- app/api, schemas — FastAPI 라우터, Pydantic 모델
- tests/           — pytest (외부 네트워크 없음, 합성 이미지 사용)

## 현재 상태
- 인식 파이프라인(검출[붙은 색 좌석 덩어리 분리] -> 행 OCR[블록 사이 통로 라벨 포함] -> 구역(층) 분할 -> 구역별 행 번호 -> 열 번호/통로), 업로드/이미지 주소/한 번에 분석 API, 멜론 어댑터 구현
- 인터파크(robots.txt 전면 금지)·YES24·티켓링크(공개 HTML에 좌석맵 없음)는 2026-10-07 조사 결과 어댑터를 만들지 않았다 (docs/API.md 표 참고).
  제목·공연장·날짜 범위는 YES24/티켓링크 공개 HTML의 JSON-LD(Event)에서 읽을 수 있으나 회차(시각)는 얻을 수 없다 -> 공연정보 자동 입력 단계에서 재검토
- 멜론 공개 상품 페이지에는 좌석맵 이미지가 없어(Phase 0 실측) 자동 수집은 대부분 422 -> 업로드/이미지 주소 경로가 주 경로
- 영구 저장 없음. 오류 신고 접수·판정(2건 임계값)·저장은 Spring Boot 담당이라 `/corrections` 스텁은 제거했다. 미구현: 공연정보 읽기(어댑터 자리만)
- 운영 설정(환경변수): `SEATMAP_INTERNAL_KEY`(설정 시 `X-Internal-Key` 필수, 미설정=개발 모드), `SEATMAP_ENABLE_DOCS=false`(/docs 끄기),
  `SEATMAP_LOG_LEVEL`. compose는 호스트 포트를 `127.0.0.1`로만 연다. 자세한 내용은 docs/API.md

## 실행
    pip install -r requirements.txt     # 시스템에 Tesseract 필요 (Docker 이미지에는 포함)
    uvicorn main:app --reload --port 8001

## 테스트
    pip install -r requirements-dev.txt
    python -m pytest          # Tesseract가 없으면 OCR 테스트는 skip

Docker 안에서 전체(OCR 포함) 실행: 컨테이너는 비root(uid 10001)이고 tests/는 이미지에 없다. 임시 디렉터리에 `app/`, `main.py`,
`tests/`, `pytest.ini`를 복사하고 `docker exec -u root`로 `pip install pytest==9.1.1 httpx==0.28.1` 후 그 디렉터리에서 `python -m pytest`.

의존성은 requirements.txt에 테스트 통과한 정확한 버전으로 고정되어 있다(올릴 때는 테스트를 다시 돌릴 것).
