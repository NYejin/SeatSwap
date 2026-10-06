"""이미지 입력 방어(해상도 폭탄, 16비트/알파, 헤더 불일치), 검출 상대 필터, OCR 신뢰도 폴백, 상한 테스트.
Tesseract가 없어도 돌도록 pytesseract는 가짜로 바꿔 검증한다."""
import struct
from types import SimpleNamespace

import cv2
import numpy as np
import pytest

from app.core import detection, ocr, pipeline
from app.core.detection import decode_image, detect, detect_seat_blocks, estimate_background
from app.core.pipeline import recognize_image
from app.errors import (ImageTooComplexError, ImageTooLargeError, RecognitionFailedError,
                        UnsupportedImageError)
from tests.synth import make_seatmap


def png(img):
    ok, buf = cv2.imencode(".png", img)
    assert ok
    return buf.tobytes()


# ---- 해상도 폭탄 / 헤더 ----

def test_jpeg_header_bomb_rejected_before_decode():
    sof = struct.pack(">BBHBHHB", 0xFF, 0xC0, 17, 8, 60000, 60000, 3) + b"\x01\x22\x00\x02\x11\x01\x03\x11\x01"
    with pytest.raises(ImageTooLargeError):
        decode_image(b"\xff\xd8" + sof + b"\x00" * 20)


def test_webp_header_bomb_rejected_before_decode():
    chunk = b"VP8X" + struct.pack("<I", 10) + b"\x00" * 4 + (59999).to_bytes(3, "little") * 2
    data = b"RIFF" + struct.pack("<I", 4 + len(chunk)) + b"WEBP" + chunk + b"\x00" * 16
    with pytest.raises(ImageTooLargeError):
        decode_image(data)


def test_png_header_bomb_and_non_ihdr_rejected():
    data = png(np.full((20, 20, 3), 255, np.uint8))
    bomb = data[:16] + struct.pack(">II", 100000, 100000) + data[24:]
    with pytest.raises(ImageTooLargeError):
        decode_image(bomb)
    not_ihdr = data[:12] + b"IDAT" + data[16:]
    with pytest.raises(UnsupportedImageError):
        decode_image(not_ihdr)
    short_len = data[:8] + struct.pack(">I", 7) + data[12:]
    with pytest.raises(UnsupportedImageError):
        decode_image(short_len)


def test_png_with_tampered_ihdr_dimensions_is_rejected():
    data = png(np.full((20, 20, 3), 255, np.uint8))
    tampered = data[:16] + struct.pack(">II", 40, 40) + data[24:]  # CRC 불일치
    with pytest.raises(UnsupportedImageError):
        decode_image(tampered)


def test_header_mismatch_between_parsers_rejected(monkeypatch):
    data = png(np.full((20, 20, 3), 255, np.uint8))
    monkeypatch.setattr(detection, "sniff_dimensions", lambda d: (21, 20))
    with pytest.raises(UnsupportedImageError, match="일관"):
        decode_image(data)


def test_pillow_bomb_guard_maps_to_too_large(monkeypatch):
    data = png(np.full((20, 20, 3), 255, np.uint8))
    monkeypatch.setattr(detection.Image, "MAX_IMAGE_PIXELS", 10)  # Pillow 자체 상한이 2배(20) 초과(400)
    with pytest.raises(ImageTooLargeError):
        decode_image(data)


def test_truncated_image_is_unsupported_not_a_crash():
    data, _ = make_seatmap()
    with pytest.raises(UnsupportedImageError):
        decode_image(data[:200])


def test_cv2_error_becomes_unsupported(monkeypatch):
    data = png(np.full((20, 20, 3), 255, np.uint8))

    def boom(*a, **k):
        raise cv2.error("boom")
    monkeypatch.setattr(detection.cv2, "imdecode", boom)
    with pytest.raises(UnsupportedImageError):
        decode_image(data)


def test_gif_and_text_rejected():
    for bad in (b"GIF89a" + b"\x00" * 40, b"<html></html>", b""):
        with pytest.raises(UnsupportedImageError):
            decode_image(bad)


# ---- 16비트 / 알파 ----

def test_16bit_png_is_normalized_to_8bit():
    img, expected = make_seatmap(raw=True)
    img16 = img.astype(np.uint16) * 257
    out = decode_image(png(img16))
    assert out.dtype == np.uint8 and abs(int(out.astype(int).mean()) - int(img.astype(int).mean())) <= 1
    assert len(detect_seat_blocks(out)) == len(expected) == 208


def test_16bit_gray_png():
    img, _ = make_seatmap(raw=True)
    gray16 = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.uint16) * 257
    out = decode_image(png(gray16))
    assert out.dtype == np.uint8 and out.ndim == 3 and len(detect_seat_blocks(out)) == 208


def test_alpha_png_8bit_and_16bit_composited_on_white():
    img, _ = make_seatmap(raw=True)
    alpha = np.where((img == 255).all(axis=2), 0, 255).astype(np.uint8)  # 배경은 투명
    img[(alpha == 0)] = 0  # 투명 영역의 RGB는 검정(합성 안 하면 전부 전경)
    bgra = np.dstack([img, alpha])
    assert len(detect_seat_blocks(decode_image(png(bgra)))) == 208
    bgra16 = bgra.astype(np.uint16) * 257
    assert len(detect_seat_blocks(decode_image(png(bgra16)))) == 208


# ---- 검출: 배경 추정, 상대 크기 필터, 제외 수 ----

def test_dark_background_is_estimated_from_border():
    img, expected = make_seatmap(raw=True, bg=20)
    assert estimate_background(img) == (20, 20, 20)
    assert len(detect_seat_blocks(img)) == len(expected)


def test_noisy_border_falls_back_to_white():
    rng = np.random.default_rng(0)
    img = rng.integers(0, 256, (60, 60, 3), dtype=np.uint8)
    assert estimate_background(img) == (255, 255, 255)


@pytest.mark.parametrize("seat,pitch,row_pitch", [(40, 46, 50), (12, 15, 16), (64, 70, 72)])
def test_seat_size_is_relative_not_absolute(seat, pitch, row_pitch):
    img, expected = make_seatmap(raw=True, seat=seat, pitch=pitch, row_pitch=row_pitch, rows=6)
    res = detect(img)
    assert len(res.seats) == len(expected)
    assert abs(res.seat_size[0] - seat) <= 1


def test_minority_sized_blocks_are_discarded_and_reported():
    img, _ = make_seatmap(raw=True, rows=3, left=4, right=4)  # 좌석 24개
    for i in range(8):  # 범례처럼 크기가 다른 사각형 8개
        cv2.rectangle(img, (10 + i * 40, 150), (10 + i * 40 + 29, 179), (0, 0, 200), -1)
    h, w = img.shape[:2]
    canvas = np.full((h + 100, max(w, 340), 3), 255, np.uint8)
    canvas[:h, :w] = img
    res = detect(canvas)
    assert len(res.seats) == 24 and res.discarded >= 8
    ok, buf = cv2.imencode(".png", canvas)
    result = recognize_image(buf.tobytes())
    assert result["stats"]["discardedComponents"] >= 8
    assert any(w_["code"] == "BLOCKS_DISCARDED" for w_ in result["warnings"])


def test_too_few_same_sized_blocks_is_not_a_seatmap():
    img = np.full((80, 200, 3), 255, np.uint8)
    for i in range(5):
        cv2.rectangle(img, (10 + i * 30, 20), (27 + i * 30, 37), (0, 0, 200), -1)
    assert detect(img).seats == []
    with pytest.raises(RecognitionFailedError):
        recognize_image(png(img))


# ---- 상한 ----

def test_component_block_and_row_limits(monkeypatch):
    data, _ = make_seatmap()
    monkeypatch.setattr(detection, "MAX_COMPONENTS", 50)
    with pytest.raises(ImageTooComplexError):
        recognize_image(data)
    monkeypatch.setattr(detection, "MAX_COMPONENTS", 200_000)
    monkeypatch.setattr(detection, "MAX_BLOCKS", 100)
    with pytest.raises(ImageTooComplexError):
        recognize_image(data)
    monkeypatch.setattr(detection, "MAX_BLOCKS", 6_000)
    monkeypatch.setattr(pipeline, "MAX_ROWS", 5)
    with pytest.raises(ImageTooComplexError):
        recognize_image(data)


def test_isolated_pixel_noise_does_not_explode_components():
    rng = np.random.default_rng(1)
    noise = (rng.random((600, 600)) > 0.5).astype(np.uint8) * 255
    img = cv2.merge([noise, noise, noise])
    res = detect(img)  # 3x3 opening이 1px 잡음을 지워 연결요소 폭증을 막는다
    assert res.components < 5_000


# ---- OCR 신뢰도 폴백 (가짜 pytesseract) ----

def fake_tesseract(monkeypatch, text, conf):
    def image_to_data(img, config="", output_type=None, timeout=0):
        return {"text": [text], "conf": [conf]}
    monkeypatch.setattr(ocr, "pytesseract", SimpleNamespace(Output=SimpleNamespace(DICT="dict"),
                                                            image_to_data=image_to_data))


@pytest.mark.parametrize("text,conf,expected", [
    ("12", 95, 12), ("7", 60, 7), ("12", 59, None), ("12", 10, None),
    ("0", 99, None), ("000", 99, None), ("007", 99, None), ("1234", 99, None), ("ab", 99, None), ("", 99, None),
])
def test_read_digits_confidence_and_format(monkeypatch, text, conf, expected):
    fake_tesseract(monkeypatch, text, conf)
    value, c = ocr._read_digits(np.full((20, 30), 255, np.uint8))
    assert value == expected and (c > 0) == (expected is not None)


def test_pipeline_low_confidence_labels_fall_back_with_warning(monkeypatch):
    monkeypatch.setattr(ocr, "tesseract_available", lambda: True)
    fake_tesseract(monkeypatch, "5", 20)  # 전부 신뢰도 미달
    data, _ = make_seatmap(rows=4)
    res = recognize_image(data)
    assert [r["rowSource"] for r in res["rows"]] == ["sequence"] * 4
    assert [r["row"] for r in res["rows"]] == [1, 2, 3, 4]
    assert any(w["code"] == "ROW_LABEL_UNREAD" for w in res["warnings"])
    assert res["stats"]["ocrRowsRead"] == 0


def test_pipeline_mixed_confidence_interpolates(monkeypatch):
    monkeypatch.setattr(ocr, "tesseract_available", lambda: True)
    labels = iter([(1, 90.0, "left"), (2, 90.0, "left"), (None, 0.0, ""), (4, 90.0, "left")])
    monkeypatch.setattr(ocr, "read_row_label", lambda *a, **k: next(labels))
    data, _ = make_seatmap(rows=4)
    res = recognize_image(data)
    assert [(r["row"], r["rowSource"]) for r in res["rows"]] == [(1, "ocr"), (2, "ocr"), (3, "inferred"), (4, "ocr")]


def test_ocr_budget_exhaustion_warns(monkeypatch):
    monkeypatch.setattr(ocr, "tesseract_available", lambda: True)
    monkeypatch.setattr(pipeline, "OCR_TOTAL_BUDGET", -1.0)
    monkeypatch.setattr(ocr, "read_row_label", lambda *a, **k: pytest.fail("예산 초과 후에는 호출하면 안 됨"))
    data, _ = make_seatmap(rows=3)
    res = recognize_image(data)
    assert any(w["code"] == "OCR_TIME_BUDGET" for w in res["warnings"])
    assert [r["rowSource"] for r in res["rows"]] == ["sequence"] * 3


def test_tesseract_timeout_is_treated_as_unread(monkeypatch):
    def slow(*a, **k):
        raise RuntimeError("Tesseract process timeout")
    monkeypatch.setattr(ocr, "pytesseract", SimpleNamespace(Output=SimpleNamespace(DICT="dict"), image_to_data=slow))
    assert ocr._read_digits(np.full((20, 30), 255, np.uint8)) == (None, 0.0)


# ---- 행 번호 방향 이상치 ----

def test_row_direction_outlier_is_flagged_not_changed():
    rows, src, out = ocr.resolve_row_numbers([1, 2, 3, 9, 5, 6])
    assert out == [3] and rows[3] == 9 and src[3] == "ocr"
    rows, src, out = ocr.resolve_row_numbers([1, 2, 3, 5])  # 4개 이상, 60% 직선 -> 5가 이상치
    assert out == [3]
    assert ocr.resolve_row_numbers([1, 9, 3])[2] == []     # 읽힌 값 3개 이하는 판단하지 않음
    assert ocr.resolve_row_numbers([6, 5, 4, 9, 2, 1])[2] == [3]  # 감소 방향에서도 동작


def test_pipeline_emits_outlier_warning(monkeypatch):
    monkeypatch.setattr(ocr, "tesseract_available", lambda: True)
    labels = iter([(v, 90.0, "left") for v in [1, 2, 3, 9, 5, 6]])
    monkeypatch.setattr(ocr, "read_row_label", lambda *a, **k: next(labels))
    data, _ = make_seatmap(rows=6)
    res = recognize_image(data)
    assert any(w["code"] == "ROW_LABEL_OUTLIER" for w in res["warnings"])
    assert res["rows"][3]["row"] == 9


def test_tesseract_availability_is_cached(monkeypatch):
    calls = []

    class Fake:
        @staticmethod
        def get_tesseract_version():
            calls.append(1)
            return "5"
    ocr.tesseract_available.cache_clear()
    monkeypatch.setattr(ocr, "pytesseract", Fake)
    assert ocr.tesseract_available() and ocr.tesseract_available() and len(calls) == 1
    ocr.tesseract_available.cache_clear()
