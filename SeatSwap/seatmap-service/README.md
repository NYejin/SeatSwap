# SeatSwap Seatmap Recognition Service (FastAPI)

Spring Boot 메인 서버와 분리된 좌석맵 이미지 인식 전용 마이크로서비스.

## 구조
- app/api      — FastAPI 라우터
- app/core     — OpenCV 검출(detection.py) + OCR/행열 매핑(ocr.py)
- app/schemas  — Pydantic 요청/응답 모델

## 현재 상태
- 라우터/스키마 틀만 생성, detection.py의 핵심 알고리즘(배경 제외 + connectedComponents)만
  실제 로직 포함 (실측 검증된 코드) — 나머지는 TODO

## 실행
pip install -r requirements.txt
uvicorn main:app --reload --port 8001
