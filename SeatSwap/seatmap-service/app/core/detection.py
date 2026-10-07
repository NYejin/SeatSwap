"""
좌석 블록 검출 (seatmap-recognition-pattern 스킬 기준선).
딥러닝 불필요 — 배경 제외 + connectedComponentsWithStats.
색상/판매상태 매핑은 하지 않는다 — 좌표만 추출한다.

배경색은 이미지 가장자리 픽셀에서 추정한다(대부분 흰색 계열이라 기본 동작은 기존 240~255 제외와 같음).
좌석 크기는 절대값이 아니라 "가장 흔한 블록 크기"(최빈 크기 군집)를 기준으로 한 상대 필터로 거른다.

붙어 있는 좌석 덩어리: 색이 칠해진 좌석은 안티앨리어싱 가장자리가 배경 허용 범위를 벗어나 이웃과 한 연결요소로
합쳐진다(회색 좌석은 틈이 배경색에 가까워 떨어진다). 좌석보다 큰 요소는 (1) 요소 자신의 색 농도에 맞춰 임계값을 다시
잡아 가장자리 틈을 끊고, (2) 그래도 붙어 있으면 이웃 좌석의 피치(크기+간격)로 격자 분할한다. 분할 결과가 좌석 크기와
맞지 않으면(무대 글자, 층 배지, 범례 등) 통째로 버린다. 색 값 자체는 어디에도 매핑하지 않는다 — 농도 대비만 쓴다.
"""
from __future__ import annotations

import io
import struct
import warnings
from collections import Counter
from dataclasses import dataclass, field

import cv2
import numpy as np
from PIL import Image, UnidentifiedImageError

from app.errors import ImageTooComplexError, ImageTooLargeError, UnsupportedImageError

MAX_PIXELS = 12_000_000   # 예: 4000x3000. 디코딩 전에 검사 (메모리 피크 완화)
MAX_SIDE = 8_000
MAX_COMPONENTS = 200_000  # 연결요소 수 상한 (초과하면 좌석맵이 아니라 노이즈로 보고 거부)
MAX_BLOCKS = 6_000        # 좌석 블록 수 상한
CAND_MIN_SIDE = 6
CAND_MAX_SIDE = 120
MIN_CLUSTER = 8           # 같은 크기 블록이 이만큼은 있어야 좌석으로 인정
BG_TOLERANCE = 15
CLUMP_CORE_RATIO = 0.6    # 덩어리 안에서 "좌석 몸통"으로 보는 최소 농도 (중앙값 농도의 60%)
CLUMP_MIN_EXPLAINED = 0.5 # 분할 조각이 덩어리 전경 면적의 이만큼은 설명해야 좌석 덩어리로 인정(가장자리 반투명 픽셀이 25~30%)
MAX_CLUMPS = 2_000        # 분할을 시도하는 큰 요소 수 상한 (처리 시간 방어)

Image.MAX_IMAGE_PIXELS = MAX_PIXELS
warnings.simplefilter("error", Image.DecompressionBombWarning)

_ALLOWED_FORMATS = {"PNG", "JPEG", "WEBP"}


def sniff_dimensions(data: bytes) -> tuple[int, int]:
    """PNG/JPEG/WebP 헤더에서 (w, h)를 읽는다. 지원하지 않는 형식이면 UnsupportedImageError."""
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        if len(data) < 24 or data[12:16] != b"IHDR" or struct.unpack(">I", data[8:12])[0] != 13:
            raise UnsupportedImageError("PNG 헤더(IHDR)가 올바르지 않습니다.")
        w, h = struct.unpack(">II", data[16:24])
        return w, h
    if data[:2] == b"\xff\xd8":
        i = 2
        while i + 9 < len(data):
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            if marker == 0xFF:
                i += 1
                continue
            if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
                i += 2
                continue
            seglen = struct.unpack(">H", data[i + 2:i + 4])[0]
            if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
                h, w = struct.unpack(">HH", data[i + 5:i + 9])
                return w, h
            i += 2 + seglen
        raise UnsupportedImageError("JPEG 헤더를 해석할 수 없습니다.")
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP" and len(data) >= 30:
        fmt = data[12:16]
        if fmt == b"VP8X":
            return 1 + int.from_bytes(data[24:27], "little"), 1 + int.from_bytes(data[27:30], "little")
        if fmt == b"VP8 ":
            w, h = struct.unpack("<HH", data[26:30])
            return w & 0x3FFF, h & 0x3FFF
        if fmt == b"VP8L":
            bits = int.from_bytes(data[21:25], "little")
            return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
    raise UnsupportedImageError("PNG, JPEG, WebP 이미지만 지원합니다.")


def check_header(data: bytes) -> tuple[int, int]:
    """
    디코딩 전 검사: 직접 파서와 Pillow(검증된 파서)가 같은 형식·크기를 말해야 하고 해상도 상한 이내여야 한다.
    Pillow의 Image.open은 헤더만 읽고 픽셀은 디코딩하지 않는다.
    """
    w, h = sniff_dimensions(data)
    if w <= 0 or h <= 0 or w * h > MAX_PIXELS or max(w, h) > MAX_SIDE:
        raise ImageTooLargeError("이미지 해상도가 상한을 초과합니다.")
    try:
        with Image.open(io.BytesIO(data)) as im:
            fmt, size = im.format, im.size
    except (Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise ImageTooLargeError("이미지 해상도가 상한을 초과합니다.") from None
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError, struct.error):
        raise UnsupportedImageError("이미지 헤더를 해석할 수 없습니다.") from None
    if fmt not in _ALLOWED_FORMATS:
        raise UnsupportedImageError("PNG, JPEG, WebP 이미지만 지원합니다.")
    if size != (w, h):
        raise UnsupportedImageError("이미지 헤더가 일관되지 않습니다.")
    return w, h


def decode_image(data: bytes) -> np.ndarray:
    """바이트 -> 8비트 BGR ndarray. 16비트는 8비트로 낮추고, 알파는 흰 배경에 합성한다."""
    check_header(data)
    try:
        img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_UNCHANGED)
    except cv2.error:
        raise UnsupportedImageError("이미지를 디코딩하지 못했습니다.") from None
    if img is None:
        raise UnsupportedImageError("이미지를 디코딩하지 못했습니다.")
    if img.dtype == np.uint16:
        img = (img >> 8).astype(np.uint8)
    elif img.dtype != np.uint8:
        raise UnsupportedImageError("지원하지 않는 픽셀 형식입니다.")
    try:
        if img.ndim == 2:
            return cv2.cvtColor(img, cv2.COLOR_GRAY2BGR)
        ch = img.shape[2]
        if ch == 1:
            return cv2.cvtColor(img[:, :, 0], cv2.COLOR_GRAY2BGR)
        if ch == 2:  # 회색 + 알파
            gray, alpha = img[:, :, 0], img[:, :, 1]
            bgr = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
        elif ch == 4:
            bgr, alpha = img[:, :, :3], img[:, :, 3]
        elif ch == 3:
            return img
        else:
            raise UnsupportedImageError("지원하지 않는 채널 수입니다.")
        out = np.empty_like(bgr)
        a = alpha.astype(np.uint16)
        for c in range(3):  # 채널별 정수 연산으로 임시 메모리를 줄인다
            out[:, :, c] = ((bgr[:, :, c].astype(np.uint16) * a + 255 * (255 - a) + 127) // 255).astype(np.uint8)
        return out
    except cv2.error:
        raise UnsupportedImageError("이미지를 처리하지 못했습니다.") from None


def estimate_background(img: np.ndarray) -> tuple[int, int, int]:
    """가장자리 픽셀의 최빈 색을 배경으로 본다. 한 색이 절반 미만이면 흰색으로 폴백."""
    h, w = img.shape[:2]
    t = max(1, min(2, h // 2, w // 2))
    border = np.concatenate([img[:t].reshape(-1, 3), img[-t:].reshape(-1, 3),
                             img[:, :t].reshape(-1, 3), img[:, -t:].reshape(-1, 3)])
    q = (border >> 4).astype(np.int32)
    key = q[:, 0] * 256 + q[:, 1] * 16 + q[:, 2]
    vals, counts = np.unique(key, return_counts=True)
    top = int(counts.argmax())
    if counts[top] < 0.5 * len(key):
        return 255, 255, 255
    med = np.median(border[key == vals[top]], axis=0)
    return int(med[0]), int(med[1]), int(med[2])


def _estimate_pitch(seats: list[dict], mw: float, mh: float) -> tuple[float, float]:
    """단독으로 검출된 좌석들의 오른쪽/아래 이웃 간격 중앙값 = (가로 피치, 세로 피치). 못 구하면 크기*1.25."""
    if len(seats) < 2:
        return mw * 1.25, mh * 1.25
    cx = np.array([s["cx"] for s in seats])
    cy = np.array([s["cy"] for s in seats])
    order = np.argsort(cy, kind="stable")
    cx, cy = cx[order], cy[order]
    dxs, dys = [], []
    # 격자 인덱스로 이웃을 찾는다: 셀 크기 = 좌석 크기*2 (이웃은 인접 셀에만 있다)
    cell = max(2.0, 2 * max(mw, mh))
    grid: dict[tuple[int, int], list[int]] = {}
    for i in range(len(cx)):
        grid.setdefault((int(cx[i] // cell), int(cy[i] // cell)), []).append(i)
    for i in range(len(cx)):
        gx, gy = int(cx[i] // cell), int(cy[i] // cell)
        best_x = best_y = None
        for ax in (gx - 1, gx, gx + 1):
            for ay in (gy - 1, gy, gy + 1):
                for j in grid.get((ax, ay), ()):
                    dx, dy = cx[j] - cx[i], cy[j] - cy[i]
                    if abs(dy) <= 0.3 * mh and 0 < dx <= 2 * mw and (best_x is None or dx < best_x):
                        best_x = dx
                    if abs(dx) <= 0.3 * mw and 0 < dy <= 2 * mh and (best_y is None or dy < best_y):
                        best_y = dy
        if best_x is not None:
            dxs.append(best_x)
        if best_y is not None:
            dys.append(best_y)
    px = float(np.median(dxs)) if dxs else mw * 1.25
    py = float(np.median(dys)) if dys else mh * 1.25
    return max(px, mw * 1.05), max(py, mh * 1.05)


def _fits(w: int, h: int, mw: float, mh: float, tol: tuple[float, float]) -> bool:
    return tol[0] * mw <= w <= tol[1] * mw and tol[0] * mh <= h <= tol[1] * mh


def _dip_cuts(profile: np.ndarray, n: int, pitch: float, cell: float) -> bool:
    """격자 분할 근거: 균등 분할 경계마다 농도 프로파일에 (약해도) 골이 있어야 한다. 단색 막대(무대 등)는 골이 없다."""
    if n < 2:
        return True
    ref = float(np.median(profile))
    if ref <= 0:
        return False
    away = np.ones(len(profile), bool)
    for i in range(1, n):
        c = int(round(i * pitch - (pitch - cell) / 2))
        seg = profile[max(0, c - 2):c + 3]
        if seg.size == 0 or float(seg.min()) > 0.95 * ref:
            return False
        away[max(0, c - 2):c + 3] = False
    # 골 사이는 평평해야 한다(좌석 몸통). 종 모양 프로파일(원형 배지 등)은 골이 우연히 있어도 거절한다.
    flat = profile[away]
    return flat.size == 0 or float((np.abs(flat - ref) <= 0.1 * ref).mean()) >= 0.85


def _split_clump(img: np.ndarray, bg: tuple[int, int, int], x: int, y: int, w: int, h: int,
                 comp: np.ndarray, mw: float, mh: float, pitch: tuple[float, float],
                 tol: tuple[float, float]) -> list[dict] | None:
    """
    좌석보다 큰 연결요소 하나를 개별 좌석 상자로 분할한다. 좌석 덩어리가 아니면 None.
    comp: 덩어리 영역의 전경 불리언 마스크(h x w).
    """
    sub = img[y:y + h, x:x + w].astype(np.int16)
    diff = np.abs(sub - np.array(bg, np.int16)).max(axis=2)
    total = int(comp.sum())
    core = float(np.median(diff[comp]))
    if core < 8 or total == 0:
        return None
    # 좌석 몸통 = 농도가 평평한 부분(3x3 안의 농도 차가 작음). 이웃 좌석 사이 반투명 틈 픽셀은 평평하지 않아 끊기고,
    # 색이 다른 좌석(회색 옆 보라 등)도 각자 자기 농도의 몸통으로 남는다. 상자는 몸통을 1px 키워 가장자리를 되찾는다.
    d32 = diff.astype(np.int16)
    k3 = np.ones((3, 3), np.uint8)
    flat = (cv2.dilate(diff, k3).astype(np.int16) - cv2.erode(diff, k3).astype(np.int16)) <= np.maximum(6, (0.08 * d32).astype(np.int16))
    m2 = (comp & flat & (diff >= max(8.0, CLUMP_CORE_RATIO * 0.2 * core))).astype(np.uint8)
    num, _lab, st, cen = cv2.connectedComponentsWithStats(m2, connectivity=4)
    pieces: list[tuple[int, int, int, int, float]] = []   # x, y, w, h, 면적 (덩어리 상대 좌표)
    for k in range(1, num):
        if st[k, 4] < 2:
            continue
        bx0, by0 = max(0, int(st[k, 0]) - 1), max(0, int(st[k, 1]) - 1)
        bx1, by1 = min(w, int(st[k, 0] + st[k, 2]) + 1), min(h, int(st[k, 1] + st[k, 3]) + 1)
        pieces.append((bx0, by0, bx1 - bx0, by1 - by0, float(st[k, 4])))
    out: list[tuple[int, int, int, int]] = []
    explained = 0.0
    px, py = pitch
    for (bx, by, bw, bh, area) in pieces:
        if _fits(bw, bh, mw, mh, tol):
            out.append((bx, by, bw, bh))
            explained += bw * bh
            continue
        # 아직 붙어 있는 조각: 피치 격자 분할. 가로/세로 개수를 반올림으로 구하고, 오차가 작을 때만 인정한다.
        kx = max(1, int(round((bw + (px - mw)) / px)))
        ky = max(1, int(round((bh + (py - mh)) / py)))
        if kx * ky < 2 or kx * ky > 4000:
            continue
        if abs(bw - (kx * px - (px - mw))) > 0.25 * px or abs(bh - (ky * py - (py - mh))) > 0.25 * py:
            continue
        if comp[by:by + bh, bx:bx + bw].mean() < 0.75:
            continue
        piece = diff[by:by + bh, bx:bx + bw].astype(np.float32)
        if not (_dip_cuts(piece.mean(axis=0), kx, (bw + (px - mw)) / kx, mw)
                and _dip_cuts(piece.mean(axis=1), ky, (bh + (py - mh)) / ky, mh)):
            continue
        sx, sy = (bw + (px - mw)) / kx, (bh + (py - mh)) / ky
        cw, ch = int(round(sx - (px - mw))), int(round(sy - (py - mh)))
        if not _fits(cw, ch, mw, mh, tol):
            continue
        for j in range(ky):
            for i in range(kx):
                out.append((bx + int(round(i * sx)), by + int(round(j * sy)), cw, ch))
        explained += bw * bh
    if not out or explained < CLUMP_MIN_EXPLAINED * total:
        return None
    seats = []
    for (bx, by, bw, bh) in out:
        seats.append({"x": x + bx, "y": y + by, "w": bw, "h": bh,
                      "cx": x + bx + (bw - 1) / 2.0, "cy": y + by + (bh - 1) / 2.0})
    return seats


@dataclass
class DetectionResult:
    seats: list[dict]
    components: int = 0         # 전경 연결요소 총 수
    candidates: int = 0         # 최소 크기·면적을 넘긴 후보 수
    discarded: int = 0          # 후보 중 채움 비율/크기 이상치로 버린 수
    background: tuple = (255, 255, 255)
    seat_size: tuple = (0, 0)   # 기준(최빈) 블록 크기
    extra: dict = field(default_factory=dict)


def detect(img: np.ndarray, *, min_fill: float = 0.4,
           size_tolerance: tuple[float, float] = (0.75, 1.35)) -> DetectionResult:
    bg = estimate_background(img)
    lo = np.clip(np.array(bg) - BG_TOLERANCE, 0, 255).astype(np.uint8)
    hi = np.clip(np.array(bg) + BG_TOLERANCE, 0, 255).astype(np.uint8)
    fg_mask = cv2.bitwise_not(cv2.inRange(img, tuple(int(v) for v in lo), tuple(int(v) for v in hi)))
    # JPEG 링잉 등으로 이웃 블록이 얇게 이어지는 것을 끊는다 (3x3 opening, 6px 이상 블록은 영향 없음).
    fg_mask = cv2.morphologyEx(fg_mask, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    num, labels, stats, centroids = cv2.connectedComponentsWithStats(fg_mask, connectivity=8)
    components = num - 1
    if components > MAX_COMPONENTS:
        raise ImageTooComplexError("이미지에 구분되는 요소가 너무 많습니다. 좌석맵 이미지가 맞는지 확인하세요.")
    if components == 0:
        return DetectionResult([], 0, 0, 0, bg)

    st = stats[1:]
    w, h, area = st[:, 2], st[:, 3], st[:, 4]
    size_ok = (w >= CAND_MIN_SIDE) & (h >= CAND_MIN_SIDE) & (w <= CAND_MAX_SIDE) & (h <= CAND_MAX_SIDE) & (area > 20)
    cand_idx = np.nonzero(size_ok & (area >= min_fill * w * h))[0]
    candidates = int(size_ok.sum())
    if len(cand_idx) < MIN_CLUSTER:
        return DetectionResult([], components, candidates, candidates, bg)

    # 최빈 크기 군집: (w,h)를 2px 칸으로 묶고 이웃 칸까지 합쳐 가장 많은 곳을 기준 크기로 삼는다.
    bins = Counter(zip(((w[cand_idx] + 1) // 2).tolist(), ((h[cand_idx] + 1) // 2).tolist()))
    best, best_n = None, 0
    for (bw, bh) in bins:
        n = sum(bins.get((bw + dx, bh + dy), 0) for dx in (-1, 0, 1) for dy in (-1, 0, 1))
        if n > best_n:
            best, best_n = (bw, bh), n
    if best is None or best_n < MIN_CLUSTER:
        return DetectionResult([], components, candidates, candidates, bg)
    near = [i for i in cand_idx
            if abs((w[i] + 1) // 2 - best[0]) <= 1 and abs((h[i] + 1) // 2 - best[1]) <= 1]
    mw, mh = float(np.median(w[near])), float(np.median(h[near]))
    lo_r, hi_r = size_tolerance
    keep = [i for i in cand_idx if lo_r * mw <= w[i] <= hi_r * mw and lo_r * mh <= h[i] <= hi_r * mh]
    if len(keep) > MAX_BLOCKS:
        raise ImageTooComplexError("좌석 블록이 너무 많습니다. 좌석맵 이미지가 맞는지 확인하세요.")
    seats = [{"x": int(st[i, 0]), "y": int(st[i, 1]), "w": int(w[i]), "h": int(h[i]),
              "cx": float(centroids[i + 1][0]), "cy": float(centroids[i + 1][1])} for i in keep]

    # 붙어 있는 좌석 덩어리 복원: 좌석 크기를 넘는 요소를 분할해 본다.
    keep_set = set(int(i) for i in keep)
    pitch = _estimate_pitch(seats, mw, mh)
    big = [i for i in range(components)
           if i not in keep_set and area[i] > 20 and w[i] >= lo_r * mw and h[i] >= lo_r * mh
           and (w[i] > hi_r * mw or h[i] > hi_r * mh)]
    big.sort(key=lambda i: int(area[i]))
    recovered: list[dict] = []
    split_ok = 0
    rejected: list[tuple[int, int, int, int]] = []
    for i in big[:MAX_CLUMPS]:
        x0, y0, bw, bh = int(st[i, 0]), int(st[i, 1]), int(w[i]), int(h[i])
        comp = labels[y0:y0 + bh, x0:x0 + bw] == i + 1
        got = _split_clump(img, bg, x0, y0, bw, bh, comp, mw, mh, pitch, size_tolerance)
        if got:
            recovered.extend(got)
            split_ok += 1
        else:
            rejected.append((x0, y0, bw, bh))
        if len(seats) + len(recovered) > MAX_BLOCKS:
            raise ImageTooComplexError("좌석 블록이 너무 많습니다. 좌석맵 이미지가 맞는지 확인하세요.")
    rejected += [(int(st[i, 0]), int(st[i, 1]), int(w[i]), int(h[i])) for i in big[MAX_CLUMPS:]]
    seats += recovered
    discarded = max(0, candidates - len(keep) - split_ok)
    res = DetectionResult(seats, components, candidates, discarded, bg, (mw, mh))
    res.extra = {"recovered": len(recovered), "clumps": split_ok, "rejected": rejected, "pitch": pitch}
    return res


def detect_seat_blocks(img: np.ndarray, **kw) -> list[dict]:
    return detect(img, **kw).seats
