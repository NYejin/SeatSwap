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
CONSENSUS_MIN_CONF = 35.0 # 구역 내 번호 규칙(1,2,3...)과 맞는지 표결할 때만 쓰는 낮은 문턱 (단독으로는 채택하지 않음)
TOKEN_OCR_VARIANTS = ((6, None), (8, 128))  # (확대 배율, 고정 임계값; None이면 Otsu)
CONSENSUS_MIN_ROWS = 3    # 표결에 몇 개 이상의 서로 다른 행이 동의해야 하는지
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


def _read_digits(gray_crop: np.ndarray, timeout: float = OCR_CALL_TIMEOUT, *,
                 scale: int = 3, stretch: bool = False,
                 min_conf: float = MIN_OCR_CONF, thr: int | None = None) -> tuple[int | None, float]:
    """stretch=True: 작고 연한 글자용 대비 보정(2~98 백분위를 0~255로 늘림). scale: 확대 배율."""
    if gray_crop.size == 0 or pytesseract is None:
        return None, 0.0
    big = cv2.resize(gray_crop, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
    if stretch:
        lo, hi = float(np.percentile(big, 2)), float(np.percentile(big, 98))
        if hi - lo < 12:   # 대비가 거의 없으면 글자가 아니다
            return None, 0.0
        big = np.clip((big.astype(np.float32) - lo) * 255.0 / (hi - lo), 0, 255).astype(np.uint8)
    if thr is None:
        _, bw = cv2.threshold(big, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)
    else:
        _, bw = cv2.threshold(big, thr, 255, cv2.THRESH_BINARY)
    if bw.mean() < 127:  # 어두운 배경이면 글자가 흰색 -> 반전
        bw = cv2.bitwise_not(bw)
    pad = 12 if scale <= 3 else 20
    bw = cv2.copyMakeBorder(bw, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=255)
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
    if conf < min_conf:
        return None, 0.0
    return int(text), conf


def find_label_tokens(gray: np.ndarray, seats: list[dict], rejected: list[tuple], mw: float, mh: float) -> list[tuple]:
    """
    좌석 상자 밖에 있는 작은 글자 덩어리(행 번호 라벨 후보)를 찾는다: (x, y, w, h) 목록.
    라벨은 행 양끝이 아니라 블록 사이 통로에 있을 수도 있으므로 위치를 가정하지 않고 "좌석이 아닌 작은 잉크"를 모두 모은다.
    잉크 = 주변 배경보다 어두운 부분(blackhat) — 연한 회색 글자도 잡힌다. 좌석과 큰 비좌석 요소(층 배지, 무대 글자)는 제외.
    """
    bh = cv2.morphologyEx(gray, cv2.MORPH_BLACKHAT, np.ones((7, 7), np.uint8))
    ink = (bh >= 12).astype(np.uint8)
    for s in seats:
        cv2.rectangle(ink, (s["x"] - 1, s["y"] - 1), (s["x"] + s["w"], s["y"] + s["h"]), 0, -1)
    for (x, y, w, h) in rejected:
        cv2.rectangle(ink, (x - 3, y - 3), (x + w + 3, y + h + 3), 0, -1)
    n, _l, st, _c = cv2.connectedComponentsWithStats(ink, connectivity=8)
    if n > 20_000:
        return []
    comps = [tuple(int(v) for v in st[i][:4]) for i in range(1, n)
             if st[i][4] >= 4 and 0.35 * mh <= st[i][3] <= 1.8 * mh and st[i][2] <= 1.6 * mw]
    comps.sort()
    toks: list[list[int]] = []
    for b in comps:
        for t in toks:
            if (min(t[1] + t[3], b[1] + b[3]) - max(t[1], b[1]) > 0.5 * min(t[3], b[3])
                    and 0 <= b[0] - (t[0] + t[2]) <= 0.6 * mw + 1):
                x0, y0 = min(t[0], b[0]), min(t[1], b[1])
                x1, y1 = max(t[0] + t[2], b[0] + b[2]), max(t[1] + t[3], b[1] + b[3])
                t[:] = [x0, y0, x1 - x0, y1 - y0]
                break
        else:
            toks.append(list(b))
    return [tuple(t) for t in toks]


def read_row_label(gray: np.ndarray, row: list[dict], pitch: float,
                   timeout: float = OCR_CALL_TIMEOUT, tokens: list[tuple] | None = None,
                   cands: list | None = None) -> tuple[int | None, float, str]:
    """
    행 라벨 읽기. 반환: (값, 신뢰도, 위치 gap/left/right/빈 문자열).
    1) tokens(좌석 밖 글자 덩어리) 중 이 행 높이에 놓인 것을 대비 보정·확대해 읽는다(블록 사이 통로 라벨 포함).
    2) 못 읽으면 기존 방식: 행 왼쪽, 안 되면 오른쪽 라벨 영역.
    cands가 주어지면 낮은 신뢰도(>= CONSENSUS_MIN_CONF) 후보도 (값, 신뢰도)로 모은다(구역 내 번호 규칙 표결용).
    """
    H, W = gray.shape[:2]
    y0 = max(0, min(s["y"] for s in row) - 2)
    y1 = min(H, max(s["y"] + s["h"] for s in row) + 2)
    best: tuple[int | None, float, str] = (None, 0.0, "")
    if tokens:
        cy = sum(s["cy"] for s in row) / len(row)
        tol = max(2.0, statistics.median(s["h"] for s in row) * 0.6)
        for (tx, ty, tw, th) in tokens:
            if abs(ty + (th - 1) / 2.0 - cy) > tol:
                continue
            cx0, cy0 = max(0, tx - 2), max(0, ty - 2)
            crop = gray[cy0:ty + th + 2, cx0:tx + tw + 2].copy()
            fill = int(crop.max())
            for s in row:  # 여백에 걸친 이웃 좌석 가장자리가 숫자로 읽히지 않게 배경색으로 지운다
                crop[max(0, s["y"] - 1 - cy0):max(0, s["y"] + s["h"] + 1 - cy0),
                     max(0, s["x"] - 1 - cx0):max(0, s["x"] + s["w"] + 1 - cx0)] = fill
            val, conf = None, 0.0
            for scale, thr in TOKEN_OCR_VARIANTS:  # 앞 변형이 실패하면 다음 변형으로 재시도
                val, conf = _read_digits(crop, timeout, scale=scale, stretch=True, min_conf=CONSENSUS_MIN_CONF, thr=thr)
                if val is not None:
                    break
            if val is None:
                continue
            if cands is not None:
                cands.append((val, conf))
            if conf >= MIN_OCR_CONF and conf > best[1]:
                best = (val, conf, "gap")
        if best[0] is not None and best[1] >= 90:   # 아주 확실하면 끝, 아니면 양끝 영역도 읽어 표결 후보를 늘린다
            return best
    span = int(max(60, pitch * 3.5))
    first, last = row[0], row[-1]
    candidates = [
        ("left", max(0, first["x"] - span), max(0, first["x"] - 2)),
        ("right", min(W, last["x"] + last["w"] + 2), min(W, last["x"] + last["w"] + span)),
    ]
    for side, x0, x1 in candidates:
        if x1 - x0 < 6:
            continue
        val, conf = _read_digits(gray[y0:y1, x0:x1], timeout)
        if val is not None and cands is not None:
            cands.append((val, conf))
        if val is not None and conf > best[1]:
            best = (val, conf, side)
        if val is not None and conf >= 70:
            break
    return best


def consensus_row_numbers(cands: list[list[tuple[int, float]]]) -> list[int | None]:
    """
    한 구역(위->아래 순 행들)의 후보 판독들로 "행 번호 = 시작값 + 방향*인덱스" 규칙을 표결로 찾고,
    각 행의 값을 규칙과 맞는 후보로 확정한다(맞는 후보가 없으면 None -> 호출측이 보간).
    규칙을 지지하는 서로 다른 행이 CONSENSUS_MIN_ROWS개 미만이거나 전체 행의 25% 미만이면 모두 None(표결 포기).
    """
    n = len(cands)
    if n == 0:
        return []
    best_key, best_rows, best_w = None, 0, 0.0
    for step in (1, -1):
        votes: dict[int, set] = {}
        weight: Counter = Counter()
        for i, cs in enumerate(cands):
            for v, c in cs:
                off = v - step * i
                votes.setdefault(off, set()).add(i)
                weight[off] += c
        for off, rows in votes.items():
            if (len(rows), weight[off]) > (best_rows, best_w):
                best_key, best_rows, best_w = (step, off), len(rows), weight[off]
    out: list[int | None] = [None] * n
    if best_key is None or best_rows < min(CONSENSUS_MIN_ROWS, n) or best_rows < 0.25 * n:
        return out
    step, off = best_key
    # 시작이 1 미만이 되는 규칙은 번호가 될 수 없다
    for i, cs in enumerate(cands):
        want = off + step * i
        if want >= 1 and any(v == want for v, _ in cs):
            out[i] = want
    return out


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
