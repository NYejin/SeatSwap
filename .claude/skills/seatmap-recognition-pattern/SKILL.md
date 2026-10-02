---
name: seatmap-recognition-pattern
description: 좌석맵 이미지 인식(좌석 블록 검출, 행/열 번호 매핑, 오류 보정) 관련 코드를 SeatSwap/seatmap-service에서 작성하거나 리뷰할 때 반드시 참고. 이미 실측 검증된 접근 방식을 담고 있으므로 새로운 방식(딥러닝 등)으로 임의 변경하지 않는다.
---

# 좌석맵 인식 파이프라인 패턴

## 검증된 접근 (실제 멜론티켓 개별좌석도 이미지로 테스트 완료)

- 딥러닝 불필요. **색상 임계값 또는 배경-제외 방식 + `cv2.connectedComponentsWithStats`** 조합으로 충분.
- 실측 결과: 약 207~210개 좌석 블록, 평균 크기 약 18x18px, 오탐(노이즈) 거의 없음.
- 색상별 상태(판매/예약가능) 매핑은 **하지 않는다**. 이 서비스는 이미 판매 완료된 좌석의
  배치만 재현하면 되므로, "사각형이 어디 있는가"만 중요하다.

## 참조 코드 패턴 (SeatSwap/seatmap-service/app/core/detection.py에 실제 구현됨)

```python
import cv2

img = cv2.imread(path)
bg_mask = cv2.inRange(img, (240,240,240), (255,255,255))  # 흰색 배경 제외
fg_mask = cv2.bitwise_not(bg_mask)
num_labels, labels, stats, centroids = cv2.connectedComponentsWithStats(fg_mask, connectivity=8)

seats = []
for i in range(1, num_labels):
    x, y, w, h, area = stats[i]
    if 10 < w < 30 and 10 < h < 30 and area > 20:   # 좌석 크기 필터
        seats.append((x, y, w, h, centroids[i]))
```

## 행/열 매핑

1. y좌표 기준 정렬 후, 인접 블록 간 y차이가 임계값(예: 10px) 이내면 같은 행으로 클러스터링
2. 행 옆 숫자 라벨은 Tesseract OCR로 인식 (`pytesseract.image_to_string`, 숫자만 나오도록 `config='--psm 7 digits'` 권장)
3. 같은 행 내에서 x좌표 오름차순 정렬 → 순번 부여
4. x간격이 평균 간격의 1.5배 이상 벌어지면 "통로"로 간주해 번호 스킵 여부를 관리자/사용자 보정 대상으로 표시

## 오류 보정 규칙 (변경 금지)

- `SeatCorrection` 레코드로 신고 접수
- **동일한 (좌표, 정정값) 조합이 2건 이상**이면 자동으로 `SeatMapLayout`에 반영
- 1건이면 `status = pending`으로 두고 관리자 확인 대기

## 서비스 분리

이 로직은 `SeatSwap/backend`(Spring Boot) 메인 서버가 아니라 `SeatSwap/seatmap-service`
(별도 FastAPI 프로세스)에 둔다. Spring Boot는 결과 좌표 JSON을 REST로 받아 저장하는
클라이언트 역할만 한다.
