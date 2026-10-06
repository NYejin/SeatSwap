import cv2
import numpy as np
import pytest

from app.core import ocr
from app.core.detection import decode_image, detect_seat_blocks, sniff_dimensions
from app.core.pipeline import recognize_image
from app.errors import ImageTooLargeError, RecognitionFailedError, UnsupportedImageError
from tests.synth import make_seatmap

needs_tesseract = pytest.mark.skipif(not ocr.tesseract_available(), reason="Tesseract not installed")


def key(s):
    return (s["x"], s["y"])


def test_detects_all_blocks_regardless_of_color():
    data, expected = make_seatmap()
    blocks = detect_seat_blocks(decode_image(data))
    assert len(blocks) == len(expected) == 208
    got = {(b["x"], b["y"], b["w"], b["h"]) for b in blocks}
    assert got == {(e["x"], e["y"], e["w"], e["h"]) for e in expected}


def test_detection_survives_jpeg_artifacts():
    data, expected = make_seatmap(jpeg=True)
    assert len(detect_seat_blocks(decode_image(data))) == len(expected)


def test_off_white_background_is_treated_as_background():
    data, expected = make_seatmap(bg=245)
    assert len(detect_seat_blocks(decode_image(data))) == len(expected)


def test_digits_are_not_detected_as_seats():
    img = np.full((60, 200, 3), 255, np.uint8)
    cv2.putText(img, "8 1 3 5", (5, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 0), 1)
    assert detect_seat_blocks(img) == []


def test_columns_are_in_x_order_and_aisle_detected():
    data, expected = make_seatmap(rows=2)
    res = recognize_image(data)
    by_row = {}
    for s in res["seats"]:
        by_row.setdefault(s["y"], []).append(s)
    for seats in by_row.values():
        seats.sort(key=lambda s: s["x"])
        assert [s["col"] for s in seats] == list(range(1, 17))
    assert all(r["aisles"] == [{"afterCol": 8, "gapPx": 40 + 4, "missingSlots": 2}] for r in res["rows"])


def test_aisle_skip_mode_leaves_gap_in_numbers():
    data, _ = make_seatmap(rows=1, left=4, right=4, aisle=44)  # 간격 66px -> 피치 22의 3배 -> 결번 2
    res = recognize_image(data, aisle_mode="skip")
    cols = sorted(s["col"] for s in res["seats"])
    assert cols == [1, 2, 3, 4, 7, 8, 9, 10]


def test_no_blocks_raises():
    ok, buf = cv2.imencode(".png", np.full((50, 50, 3), 255, np.uint8))
    with pytest.raises(RecognitionFailedError):
        recognize_image(buf.tobytes())


def test_rejects_non_images_and_huge_dimensions():
    with pytest.raises(UnsupportedImageError):
        decode_image(b"GIF89a" + b"\x00" * 50)
    with pytest.raises(UnsupportedImageError):
        decode_image(b"<html>not an image</html>")
    big = bytearray(b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\rIHDR")
    big += (60000).to_bytes(4, "big") + (60000).to_bytes(4, "big") + b"\x08\x02\x00\x00\x00"
    with pytest.raises(ImageTooLargeError):
        decode_image(bytes(big))


def test_sniff_dimensions_png_jpeg():
    for jpeg in (False, True):
        data, _ = make_seatmap(jpeg=jpeg)
        w, h = sniff_dimensions(data)
        img = decode_image(data)
        assert (h, w) == img.shape[:2]


def test_transparent_png_composited_on_white():
    img = np.zeros((60, 200, 4), np.uint8)  # 완전 투명 (RGB는 검정이라 합성하지 않으면 전부 전경이 됨)
    for i in range(8):
        img[10:30, 10 + i * 22:30 + i * 22] = (200, 50, 50, 255)
    ok, buf = cv2.imencode(".png", img)
    assert len(detect_seat_blocks(decode_image(buf.tobytes()))) == 8


def test_resolve_row_numbers_interpolates_and_falls_back():
    rows, src, out = ocr.resolve_row_numbers([1, 2, None, 4, None])
    assert rows == [1, 2, 3, 4, 5] and src == ["ocr", "ocr", "inferred", "ocr", "inferred"] and out == []
    rows, src, _ = ocr.resolve_row_numbers([9, None, 7])
    assert rows == [9, 8, 7]
    rows, src, _ = ocr.resolve_row_numbers([None, None])
    assert rows == [1, 2] and src == ["sequence", "sequence"]


def test_pipeline_without_tesseract_degrades(monkeypatch):
    monkeypatch.setattr(ocr, "tesseract_available", lambda: False)
    data, _ = make_seatmap(rows=3)
    res = recognize_image(data)
    assert [w["code"] for w in res["warnings"]][0] == "OCR_UNAVAILABLE"
    assert sorted({s["row"] for s in res["seats"]}) == [1, 2, 3]


@needs_tesseract
@pytest.mark.parametrize("kw", [
    {}, {"label_side": "right"}, {"first_label": 5}, {"descending": True}, {"jpeg": True},
])
def test_full_recognition_matches_ground_truth(kw):
    data, expected = make_seatmap(**kw)
    res = recognize_image(data)
    got = {(s["x"], s["y"]): s for s in res["seats"]}
    exp = {(e["x"], e["y"]): e for e in expected}
    assert len(got) >= len(exp) - 2
    wrong = [k for k in exp if k in got and (got[k]["row"], got[k]["col"]) != (exp[k]["row"], exp[k]["col"])]
    assert len(wrong) <= len(exp) * 0.03, f"{len(wrong)} seats mismatched"


@needs_tesseract
def test_unreadable_rows_are_inferred_and_warned():
    data, expected = make_seatmap(skip_label_rows=(3, 7))
    res = recognize_image(data)
    srcs = [r["rowSource"] for r in res["rows"]]
    assert srcs[3] == srcs[7] == "inferred"
    assert any(w["code"] == "ROW_LABEL_UNREAD" for w in res["warnings"])
    rows = sorted({s["row"] for s in res["seats"]})
    assert rows == list(range(1, 14))
