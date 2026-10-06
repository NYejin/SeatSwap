"""
행 번호 인식 (Tesseract OCR) + 열 번호 자동 부여.
y좌표 클러스터링 -> 행 묶기 -> 행 왼쪽(없으면 오른쪽) 라벨을 OCR -> 같은 행 내 x좌표 순 열 번호,
통로(간격 급증 구간)는 감지해 보고하고 aisle_mode로 결번 처리 여부를 고른다.
"""
from __future__ import annotations

import functools
import re
import statistics
from collections import Counter

import cv2
import numpy as np

try:
    import pytesseract
except ImportError:  # pragma: no cover
    pytesseract = None


MIN_OCR_CONF = 60.0       # 이 미만의 라벨 판독은 버리고 보간/순번으로 폴백
OCR_CALL_TIMEOUT = 3.0    # Tesseract 1회 호출 상한(초)


@functools.lru_cache(maxsize=1)
def tesseract_available() -> bool:
    if pytesseract is None:
        return False
    try:
        pytesseract.get_tesseract_version()
        return True
    except Exception:
        return False


def cluster_rows(seats: list[dict], y_ratio: float = 0.5) -> list[list[dict]]:
    """cy 기준 정렬 후, 현재 행 평균 cy와의 차이가 (중앙값 높이 * y_ratio) 이내면 같은 행. 위->아래 순."""
    if not seats:
        return []
    tol = max(3.0, statistics.median(s["h"] for s in seats) * y_ratio)
    rows: list[list[dict]] = []
    total = 0.0  # 현재 행 cy 누적합 (블록을 추가할 때마다 평균을 다시 합산하지 않는다: O(n log n))
    for s in sorted(seats, key=lambda s: s["cy"]):
        if rows and abs(s["cy"] - total / len(rows[-1])) <= tol:
            rows[-1].append(s)
            total += s["cy"]
        else:
            rows.append([s])
            total = s["cy"]
    for r in rows:
        r.sort(key=lambda s: s["x"])
    return rows


def _read_digits(gray_crop: np.ndarray, timeout: float = OCR_CALL_TIMEOUT) -> tuple[int | None, float]:
    if gray_crop.size == 0 or pytesseract is None:
        return None, 0.0
    big = cv2.resize(gray_crop, None, fx=3, fy=3, interpolation=cv2.INTER_CUBIC)
    _, bw = cv2.threshold(big, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)
    if bw.mean() < 127:  # 어두운 배경이면 글자가 흰색 -> 반전
        bw = cv2.bitwise_not(bw)
    bw = cv2.copyMakeBorder(bw, 12, 12, 12, 12, cv2.BORDER_CONSTANT, value=255)
    cfg = "--psm 7 -c tessedit_char_whitelist=0123456789"
    try:
        data = pytesseract.image_to_data(bw, config=cfg, output_type=pytesseract.Output.DICT,
                                         timeout=max(0.5, timeout))
    except Exception:  # 타임아웃(RuntimeError) 포함 -> 못 읽은 것으로 처리
        return None, 0.0
    parts = [(t.strip(), float(c)) for t, c in zip(data["text"], data["conf"]) if t.strip() and float(c) >= 0]
    text = "".join(t for t, _ in parts)
    # 1~3자리, 앞자리 0 금지("0", "000", "007" 거부) -> 행 번호는 1 이상
    if not re.fullmatch(r"[1-9]\d{0,2}", text):
        return None, 0.0
    conf = sum(c for _, c in parts) / len(parts)
    if conf < MIN_OCR_CONF:
        return None, 0.0
    return int(text), conf


def read_row_label(gray: np.ndarray, row: list[dict], pitch: float,
                   timeout: float = OCR_CALL_TIMEOUT) -> tuple[int | None, float, str]:
    """행 왼쪽, 안 되면 오른쪽 라벨 영역을 잘라 OCR. 반환: (값, 신뢰도, 위치 left/right/빈 문자열)."""
    H, W = gray.shape[:2]
    y0 = max(0, min(s["y"] for s in row) - 2)
    y1 = min(H, max(s["y"] + s["h"] for s in row) + 2)
    span = int(max(60, pitch * 3.5))
    first, last = row[0], row[-1]
    candidates = [
        ("left", max(0, first["x"] - span), max(0, first["x"] - 2)),
        ("right", min(W, last["x"] + last["w"] + 2), min(W, last["x"] + last["w"] + span)),
    ]
    best: tuple[int | None, float, str] = (None, 0.0, "")
    for side, x0, x1 in candidates:
        if x1 - x0 < 6:
            continue
        val, conf = _read_digits(gray[y0:y1, x0:x1], timeout)
        if val is not None and conf > best[1]:
            best = (val, conf, side)
        if val is not None and conf >= 70:
            break
    return best


def resolve_row_numbers(read: list[int | None]) -> tuple[list[int], list[str], list[int]]:
    """
    위->아래 순 OCR 값(없으면 None)으로 행 번호를 확정한다.
    읽힌 값들의 증가/감소 방향(step)을 추정해 못 읽은 행을 보간하고(inferred),
    그래도 못 정하면 순번(sequence)을 쓴다.
    반환: (행 번호, 출처 리스트, 이상치 행 인덱스).
    이상치: 읽힌 값이 4개 이상이고 대부분(60% 이상)이 하나의 직선(오프셋+방향*인덱스)에 놓일 때,
    그 직선에서 벗어난 읽힌 값. 값은 바꾸지 않고 경고용으로만 알린다(번호가 건너뛰는 공연장이 실제로 있음).
    """
    known = [(i, v) for i, v in enumerate(read) if v is not None]
    votes: Counter = Counter()
    for (i, a), (j, b) in zip(known, known[1:]):
        if abs(b - a) == (j - i):
            votes[1 if b > a else -1] += 1
    step = votes.most_common(1)[0][0] if votes else None
    outliers: list[int] = []
    if step is not None and len(known) >= 4:
        offset = Counter(v - step * i for i, v in known).most_common(1)[0]
        if offset[1] >= 0.6 * len(known):
            outliers = [i for i, v in known if v - step * i != offset[0]]
    rows: list[int] = []
    src: list[str] = []
    for i, v in enumerate(read):
        if v is not None:
            rows.append(v)
            src.append("ocr")
            continue
        if step is not None and known:
            j, kv = min(known, key=lambda t: abs(t[0] - i))
            guess = kv + step * (i - j)
            if guess >= 1:
                rows.append(guess)
                src.append("inferred")
                continue
        rows.append(i + 1)
        src.append("sequence")
    return rows, src, outliers


def assign_columns(row_seats: list[dict], gap_ratio_threshold: float = 1.5,
                   aisle_mode: str = "continue") -> tuple[list[int], list[dict]]:
    """
    x 오름차순 정렬된 행에 열 번호를 부여한다. 반환: (열 번호 리스트, 통로 리스트).
    통로: 이웃 x 간격이 행 중앙값 간격 * gap_ratio_threshold 이상인 지점.
    aisle_mode=continue: 번호는 이어서(통로 무시), skip: 통로에 빠진 좌석 수만큼 번호를 건너뜀(결번).
    """
    if not row_seats:
        return [], []
    pitches = [b["x"] - a["x"] for a, b in zip(row_seats, row_seats[1:])]
    median = statistics.median(pitches) if pitches else 0
    col = 1
    cols = [col]
    aisles: list[dict] = []
    for k, p in enumerate(pitches):
        if median > 0 and p >= median * gap_ratio_threshold:
            missing = max(1, round(p / median) - 1)
            aisles.append({"afterCol": col, "gapPx": int(p - row_seats[k]["w"]), "missingSlots": missing})
            if aisle_mode == "skip":
                col += missing
        col += 1
        cols.append(col)
    return cols, aisles
