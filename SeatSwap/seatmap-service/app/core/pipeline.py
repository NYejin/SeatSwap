"""
인식 파이프라인: 이미지 바이트 -> 좌석 좌표 JSON.
(1) 블록 검출(붙은 덩어리 분할) -> (2) y 클러스터링으로 행 묶기 -> (3) 행 라벨 OCR
-> (4) 큰 수직 간격으로 구역(층) 분할 -> (5) 구역별 행 번호(1부터 또는 라벨대로) -> (6) 행 내 x순 열 번호(통로 감지).
영구 저장 없음 — 입력 바이트는 호출이 끝나면 버려진다.
"""
from __future__ import annotations

import statistics
import time

import cv2

from app.core import ocr, sections
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

    # 행 라벨 OCR (좌석 밖 글자 덩어리 -> 못 읽으면 행 양끝 영역)
    read: list[int | None] = [None] * len(rows)
    conf: list[float] = [0.0] * len(rows)
    cands: list[list[tuple[int, float]]] = [[] for _ in rows]
    use_ocr = ocr.tesseract_available()
    if use_ocr:
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        pitch = statistics.median(b["w"] for b in blocks) * 1.3
        tokens = ocr.find_label_tokens(gray, blocks, det.extra.get("rejected", []), *det.seat_size)
        ocr_deadline = time.monotonic() + OCR_TOTAL_BUDGET
        for i, row in enumerate(rows):
            remaining = ocr_deadline - time.monotonic()
            if remaining <= 0:
                warnings.append({"code": "OCR_TIME_BUDGET",
                                 "message": f"행 번호 인식 시간 예산을 넘겨 {len(rows) - i}개 행은 읽지 않았습니다."})
                break
            read[i], conf[i], _side = ocr.read_row_label(gray, row, pitch, min(ocr.OCR_CALL_TIMEOUT, remaining),
                                                         tokens=tokens, cands=cands[i])
    else:
        warnings.append({"code": "OCR_UNAVAILABLE", "message": "Tesseract를 사용할 수 없어 행 번호를 순번으로 대체했습니다."})

    # 구역(층) 분할: 큰 수직 간격이 경계, 애매한 간격은 라벨이 1로 다시 시작할 때만 경계
    gaps = sections.row_gaps(rows)
    restart_at = {i for i, _r in gaps if any(v == 1 for v, _c in cands[i])}
    starts, uncertain = sections.split_rows(gaps, restart_at)
    if uncertain:
        warnings.append({"code": "SECTION_SPLIT_UNCERTAIN",
                         "message": f"행 간격이 큰 지점 {len(uncertain)}곳이 층 구분인지 한 층 안의 통로인지 확실하지 않아 "
                                    "나누지 않았습니다. 층이 여러 개라면 확인이 필요합니다."})
    bounds = list(zip(starts, starts[1:] + [len(rows)]))

    row_numbers: list[int] = []
    row_src: list[str] = []
    row_section: list[int] = []
    outliers_total = 0
    dup = False
    for sec, (lo, hi) in enumerate(bounds, start=1):
        sec_read = list(read[lo:hi])
        if any(cands[lo:hi]):  # 표결: 구역 안의 1,2,3... 규칙과 맞는 판독만 인정 (OCR 오판독 교정)
            voted = ocr.consensus_row_numbers(cands[lo:hi])
            if any(v is not None for v in voted):
                sec_read = voted
                for k in range(lo, hi):
                    if voted[k - lo] is not None:
                        conf[k] = max((c for v, c in cands[k] if v == voted[k - lo]), default=conf[k])
        nums, src, outl = ocr.resolve_row_numbers(sec_read)
        outliers_total += len(outl)
        dup = dup or len(set(nums)) != len(nums)
        row_numbers += nums
        row_src += src
        row_section += [sec] * (hi - lo)
    for k in range(len(rows)):
        if row_src[k] != "ocr":
            conf[k] = 0.0
    for (_lo, _hi), (lo2, _h2) in zip(bounds, bounds[1:]):
        if row_src[lo2] == "ocr" and row_src[lo2 - 1] == "ocr" and row_numbers[lo2] == row_numbers[lo2 - 1] + 1:
            warnings.append({"code": "SECTION_SPLIT_UNCERTAIN",
                             "message": "구역을 나눴지만 아래 구역의 행 번호가 위 구역에서 이어집니다. 층 구분이 맞는지 확인이 필요합니다."})
            break

    seats: list[dict] = []
    row_infos: list[dict] = []
    aisle_total = 0
    sec_seats: dict[int, list[dict]] = {}
    for i, row in enumerate(rows):
        cols, aisles = ocr.assign_columns(row, aisle_mode=aisle_mode)
        aisle_total += len(aisles)
        for s, c in zip(row, cols):
            seat = {"uid": s["uid"], "row": row_numbers[i], "col": c, "x": s["x"], "y": s["y"], "w": s["w"],
                    "h": s["h"], "section": row_section[i]}
            seats.append(seat)
            sec_seats.setdefault(row_section[i], []).append(seat)
        row_infos.append({
            "row": row_numbers[i], "rowSource": row_src[i], "labelConfidence": round(conf[i], 1),
            "seatCount": len(row), "aisles": aisles, "section": row_section[i],
        })
    section_infos = []
    for sec, (lo, hi) in enumerate(bounds, start=1):
        ss = sec_seats[sec]
        x0, y0 = min(s["x"] for s in ss), min(s["y"] for s in ss)
        x1, y1 = max(s["x"] + s["w"] for s in ss), max(s["y"] + s["h"] for s in ss)
        section_infos.append({"section": sec, "rowCount": hi - lo, "seatCount": len(ss),
                              "ocrRowsRead": sum(1 for k in range(lo, hi) if row_src[k] == "ocr"),
                              "bbox": {"x": x0, "y": y0, "w": x1 - x0, "h": y1 - y0}})

    if use_ocr:
        unread = sum(1 for s in row_src if s != "ocr")
        if unread:
            warnings.append({"code": "ROW_LABEL_UNREAD",
                             "message": f"{unread}개 행의 번호를 읽지 못해(신뢰도 미달 포함) 추정/순번으로 채웠습니다. 확인이 필요합니다."})
    if outliers_total:
        warnings.append({"code": "ROW_LABEL_OUTLIER",
                         "message": f"{outliers_total}개 행의 번호가 나머지 행의 증가/감소 규칙과 어긋납니다(OCR 오류 또는 건너뛴 번호). 확인이 필요합니다."})
    if dup:
        warnings.append({"code": "ROW_NUMBER_DUPLICATED",
                         "message": "같은 구역 안에서 같은 행 번호가 여러 행에 부여되었습니다(구역 분리 또는 OCR 오류 가능)."})
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
        "sections": section_infos,
        "stats": {"blockCount": len(blocks), "rowCount": len(rows),
                  "ocrRowsRead": sum(1 for s in row_src if s == "ocr"),
                  "discardedComponents": det.discarded,
                  "splitSeats": det.extra.get("recovered", 0),
                  "sectionCount": len(bounds)},
        "warnings": warnings,
    }
