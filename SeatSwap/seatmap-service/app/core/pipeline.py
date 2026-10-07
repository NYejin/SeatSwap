"""
인식 파이프라인: 이미지 바이트 -> 좌석 좌표 JSON.
(1) 블록 검출 -> (2) y 클러스터링으로 행 묶기 -> (3) 행 라벨 OCR -> (4) 행 내 x순 열 번호(통로 감지).
영구 저장 없음 — 입력 바이트는 호출이 끝나면 버려진다.
"""
from __future__ import annotations

import statistics
import time

import cv2

from app.core import ocr
from app.core.detection import decode_image, detect
from app.errors import ImageTooComplexError, RecognitionFailedError

AISLE_MODES = ("continue", "skip")
MAX_ROWS = 300            # 행 수 상한 (초과하면 좌석맵이 아닌 이미지로 보고 거부)
OCR_TOTAL_BUDGET = 30.0   # 행 라벨 OCR 전체 시간 예산(초). 넘으면 남은 행은 보간/순번으로 처리


def recognize_image(data: bytes, aisle_mode: str = "continue") -> dict:
    img = decode_image(data)
    height, width = img.shape[:2]
    det = detect(img)
    blocks = det.seats
    if not blocks:
        raise RecognitionFailedError("이미지에서 좌석 블록을 찾지 못했습니다.")

    # 안정 식별자: 검출된 블록의 공간 정렬(y, x, w, h) 순서 기준 일련번호.
    # 행/열 번호 부여(OCR, aisleMode, 정정)와 무관하게 같은 이미지면 항상 같은 uid.
    for n, b in enumerate(sorted(blocks, key=lambda b: (b["y"], b["x"], b["w"], b["h"])), start=1):
        b["uid"] = f"s{n:04d}"

    rows = ocr.cluster_rows(blocks)
    if len(rows) > MAX_ROWS:
        raise ImageTooComplexError("행이 너무 많습니다. 좌석맵 이미지가 맞는지 확인하세요.")
    warnings: list[dict] = []

    # 행 라벨 OCR
    read: list[int | None] = [None] * len(rows)
    conf: list[float] = [0.0] * len(rows)
    use_ocr = ocr.tesseract_available()
    if use_ocr:
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        pitch = statistics.median(b["w"] for b in blocks) * 1.3
        ocr_deadline = time.monotonic() + OCR_TOTAL_BUDGET
        for i, row in enumerate(rows):
            remaining = ocr_deadline - time.monotonic()
            if remaining <= 0:
                warnings.append({"code": "OCR_TIME_BUDGET",
                                 "message": f"행 번호 인식 시간 예산을 넘겨 {len(rows) - i}개 행은 읽지 않았습니다."})
                break
            read[i], conf[i], _side = ocr.read_row_label(gray, row, pitch, min(ocr.OCR_CALL_TIMEOUT, remaining))
    else:
        warnings.append({"code": "OCR_UNAVAILABLE", "message": "Tesseract를 사용할 수 없어 행 번호를 순번으로 대체했습니다."})

    row_numbers, row_src, outliers = ocr.resolve_row_numbers(read)

    seats: list[dict] = []
    row_infos: list[dict] = []
    aisle_total = 0
    for i, row in enumerate(rows):
        cols, aisles = ocr.assign_columns(row, aisle_mode=aisle_mode)
        aisle_total += len(aisles)
        for s, c in zip(row, cols):
            seats.append({"uid": s["uid"], "row": row_numbers[i], "col": c, "x": s["x"], "y": s["y"], "w": s["w"], "h": s["h"]})
        row_infos.append({
            "row": row_numbers[i], "rowSource": row_src[i], "labelConfidence": round(conf[i], 1),
            "seatCount": len(row), "aisles": aisles,
        })

    if use_ocr:
        unread = sum(1 for s in row_src if s != "ocr")
        if unread:
            warnings.append({"code": "ROW_LABEL_UNREAD",
                             "message": f"{unread}개 행의 번호를 읽지 못해(신뢰도 미달 포함) 추정/순번으로 채웠습니다. 확인이 필요합니다."})
    if outliers:
        warnings.append({"code": "ROW_LABEL_OUTLIER",
                         "message": f"{len(outliers)}개 행의 번호가 나머지 행의 증가/감소 규칙과 어긋납니다(OCR 오류 또는 건너뛴 번호). 확인이 필요합니다."})
    if len(set(row_numbers)) != len(row_numbers):
        warnings.append({"code": "ROW_NUMBER_DUPLICATED",
                         "message": "같은 행 번호가 여러 행에 부여되었습니다(구역 분리 또는 OCR 오류 가능)."})
    if aisle_total:
        warnings.append({"code": "AISLE_DETECTED",
                         "message": f"통로로 보이는 간격 {aisle_total}곳이 있습니다(aisleMode={aisle_mode})."})
    if det.discarded > max(3, 0.2 * len(blocks)):
        warnings.append({"code": "BLOCKS_DISCARDED",
                         "message": f"좌석과 비슷한 요소 {det.discarded}개를 크기·모양이 달라 제외했습니다(범례, 글자, 크기가 다른 구역 가능)."})

    return {
        "image": {"width": width, "height": height},
        "seats": seats,
        "rows": row_infos,
        "stats": {"blockCount": len(blocks), "rowCount": len(rows),
                  "ocrRowsRead": sum(1 for s in row_src if s == "ocr"),
                  "discardedComponents": det.discarded},
        "warnings": warnings,
    }
