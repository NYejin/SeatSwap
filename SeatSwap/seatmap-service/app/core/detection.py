"""
좌석 블록 검출 (seatmap-recognition-pattern 스킬 기준선).
딥러닝 불필요 — 배경 제외 + connectedComponentsWithStats.
색상/판매상태 매핑은 하지 않는다 — 좌표만 추출한다.

배경색은 이미지 가장자리 픽셀에서 추정한다(대부분 흰색 계열이라 기본 동작은 기존 240~255 제외와 같음).
좌석 크기는 절대값이 아니라 "가장 흔한 블록 크기"(최빈 크기 군집)를 기준으로 한 상대 필터로 거른다.
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
    num, _labels, stats, centroids = cv2.connectedComponentsWithStats(fg_mask, connectivity=8)
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
    return DetectionResult(seats, components, candidates, candidates - len(keep), bg, (mw, mh))


def detect_seat_blocks(img: np.ndarray, **kw) -> list[dict]:
    return detect(img, **kw).seats
