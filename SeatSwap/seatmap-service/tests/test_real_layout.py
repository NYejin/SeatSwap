"""
실제 좌석표형 이미지(작은 좌석 ~7px, 색 칠한 좌석이 붙음, 층 3개, 블록 사이 통로에 연한 숫자 라벨) 회귀 테스트.
실제 사이트 이미지는 저작권 때문에 저장소에 두지 않고, 같은 특징을 가진 합성 이미지를 쓴다.
(고해상도로 그린 뒤 INTER_AREA로 줄여 실제처럼 가장자리가 반투명 픽셀이 되게 한다 -> 색 좌석 사이 틈이 배경색 허용범위를 벗어난다.)
"""
import cv2
import numpy as np
import pytest

from app.core import ocr, sections
from app.core.detection import detect
from app.core.pipeline import recognize_image

needs_tesseract = pytest.mark.skipif(not ocr.tesseract_available(), reason="Tesseract not installed")

K = 8  # 슈퍼샘플링 배율


def make_floors(floors=((6, 5), (4, 5)), blocks=(7, 9, 7), seat=6.6, pitch=7.9, row_pitch=8.0,
                floor_gap=50, aisle=38, colored=(), labels=True, label_color=185, restart=True,
                label_side="gap", raw=True):
    """
    floors: 층별 (행 수, ...) 튜플의 길이만 의미 — 각 원소가 한 층의 행 수. blocks: 블록별 좌석 수. 블록 사이 통로 aisle px.
    colored: {(floor_idx, row_idx, col_idx)} 진한 색으로 칠할 좌석(색 자체는 의미 없음).
    반환: (img, expected) expected = [{floor,row,col,cx,cy}] — 열은 행 안에서 왼쪽->오른쪽 1부터(통로 건너 이어서).
    """
    floors = [f if isinstance(f, int) else f[0] for f in floors]
    margin_x, margin_y = 60, 40
    width = int(margin_x * 2 + sum(blocks) * pitch + aisle * (len(blocks) - 1))
    height = int(margin_y * 2 + sum(floors) * row_pitch + floor_gap * (len(floors) - 1))
    big = np.full((height * K, width * K, 3), 255, np.uint8)
    expected = []
    y = float(margin_y)
    for fi, nrows in enumerate(floors):
        for r in range(nrows):
            x = float(margin_x)
            col = 0
            for bi, nseat in enumerate(blocks):
                for c in range(nseat):
                    col += 1
                    color = (200, 90, 150) if (fi, r, col - 1) in colored else (221, 221, 221)
                    x0, y0 = x + c * pitch, y
                    cv2.rectangle(big, (int(x0 * K), int(y0 * K)), (int((x0 + seat) * K), int((y0 + seat) * K)), color, -1)
                    expected.append({"floor": fi + 1, "row": r + 1, "col": col,
                                     "cx": x0 + seat / 2, "cy": y0 + seat / 2})
                gx = x + nseat * pitch  # 블록 오른쪽 끝
                if labels and bi < len(blocks) - 1 and label_side == "gap":
                    num = r + 1 if restart else sum(floors[:fi]) + r + 1
                    cv2.putText(big, str(num), (int((gx + 5) * K), int((y + seat - 0.6) * K)),
                                cv2.FONT_HERSHEY_SIMPLEX, 1.9, (label_color,) * 3, 2, cv2.LINE_AA)
                x = gx + aisle
            y += row_pitch
        y += floor_gap  # 층 사이 추가 공백 (마지막 행 다음 row_pitch는 행 루프가 이미 더함)
    img = cv2.resize(big, (width, height), interpolation=cv2.INTER_AREA)
    if raw:
        return img, expected
    ok, buf = cv2.imencode(".png", img)
    return buf.tobytes(), expected


def match(res_seats, expected, tol=3.0):
    """기대 좌석마다 가장 가까운 검출 좌석을 찾는다(중심 거리 tol 이내). 반환: [(기대, 검출 or None)]"""
    pts = np.array([[s["x"] + s["w"] / 2, s["y"] + s["h"] / 2] for s in res_seats])
    out = []
    for e in expected:
        d = np.hypot(pts[:, 0] - e["cx"], pts[:, 1] - e["cy"])
        j = int(d.argmin())
        out.append((e, res_seats[j] if d[j] <= tol else None))
    return out


def png(img):
    ok, buf = cv2.imencode(".png", img)
    return buf.tobytes()


# ---- B. 붙은 색 좌석 덩어리 분리 ----

def painted_block(rows=3, cols=7, mode="fringe"):
    """회색 좌석 배경 + 색 칠한 rows x cols 덩어리. mode=fringe(반투명 가장자리) / solid(틈이 거의 없음)"""
    img, expected = make_floors(floors=(8,), blocks=(14,), labels=False,
                                colored={(0, r, c) for r in range(2, 2 + rows) for c in range(3, 3 + cols)})
    return img, expected


def test_merged_colored_seats_are_separated():
    img, expected = painted_block()
    # 전제: 칠한 좌석이 실제로 한 덩어리로 붙어 있다(분리 로직이 없으면 버려지는 상황 재현)
    m = cv2.bitwise_not(cv2.inRange(img, (229, 229, 229), (255, 255, 255)))
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    n, _l, st, _c = cv2.connectedComponentsWithStats(m, connectivity=8)
    assert max(int(s[2]) for s in st[1:]) > 20
    res = detect(img)
    assert len(res.seats) == len(expected)
    assert res.extra["recovered"] >= 10
    matched = match(res.seats, expected)
    assert all(d is not None for _e, d in matched)
    assert len({(s["x"], s["y"]) for s in res.seats}) == len(res.seats)  # 중복 없음(과분할 방지)


def test_solid_clump_falls_back_to_pitch_grid(monkeypatch):
    """틈이 배경색에 가깝지 않고 93% 농도로 남아 임계값 재설정으로는 못 끊는 덩어리 -> 피치 격자 분할(골이 약해도 인정)."""
    img, expected = make_floors(floors=(8,), blocks=(14,), labels=False)
    # 칠할 구역: 4행 x 6열을 하나의 사각형으로 채우되 좌석 사이 틈(경계)은 중간 농도로 남긴다
    x0 = 60 + 3 * 7.9
    y0 = 40 + 2 * 8.0
    X0, Y0 = int(round(x0)), int(round(y0))
    X1, Y1 = int(round(x0 + 6 * 7.9 - 1.3)), int(round(y0 + 4 * 8.0 - 1.4))
    cv2.rectangle(img, (X0, Y0), (X1, Y1), (200, 90, 150), -1)
    for i in range(1, 6):   # 열 사이 틈 = 진한 색의 93% (평평해서 몸통 분리로는 안 끊김)
        gx = int(round(x0 + i * 7.9 - 1.3))
        cv2.line(img, (gx, Y0), (gx, Y1), (205, 102, 158), 1)
    for j in range(1, 4):
        gy = int(round(y0 + j * 8.0 - 1.4))
        cv2.line(img, (X0, gy), (X1, gy), (205, 102, 158), 1)
    from app.core import detection
    calls = []
    real = detection._dip_cuts
    monkeypatch.setattr(detection, "_dip_cuts", lambda *a: calls.append(1) or real(*a))
    res = detect(img)
    assert calls, "격자 분할 경로를 타야 한다"
    painted = [s for s in res.seats if X0 - 1 <= s["x"] <= X1 and Y0 - 1 <= s["y"] <= Y1]
    assert len(painted) == 24
    assert len(res.seats) == len(expected)


def test_big_non_seat_objects_are_not_split():
    """층 배지(원), 무대 막대, 범례는 격자 분할되지 않고 계속 제외된다."""
    img, expected = make_floors(floors=(6,), blocks=(10, 10), labels=False)
    h, w = img.shape[:2]
    canvas = np.full((h + 120, w, 3), 255, np.uint8)
    canvas[120:] = img
    cv2.rectangle(canvas, (20, 6), (w - 20, 22), (170, 170, 170), -1)     # 무대 막대
    cv2.circle(canvas, (w // 2, 52), 15, (136, 136, 136), -1)             # 층 배지
    cv2.rectangle(canvas, (20, 90), (60, 104), (90, 160, 200), -1)        # 범례
    res = detect(canvas)
    assert len(res.seats) == len(expected)
    assert all(s["y"] >= 120 for s in res.seats)
    assert len(res.extra["rejected"]) >= 3


# ---- C. 구역(층) 분할 + 구역별 행 번호 ----

def test_floors_split_into_sections_with_rows_restarting():
    img, expected = make_floors(floors=(6, 4, 3), colored={(0, 2, 5), (0, 2, 6), (1, 1, 4)})
    res = recognize_image(png(img))
    assert [s["rowCount"] for s in res["sections"]] == [6, 4, 3]
    assert [s["section"] for s in res["sections"]] == [1, 2, 3]
    assert res["stats"]["sectionCount"] == 3
    assert not any(w["code"] == "SECTION_SPLIT_UNCERTAIN" for w in res["warnings"])
    matched = match(res["seats"], expected)
    assert all(d is not None for _e, d in matched)
    for e, d in matched:
        assert d["section"] == e["floor"] and d["row"] == e["row"] and d["col"] == e["col"], (e, d)
    assert {r["section"] for r in res["rows"]} == {1, 2, 3}


def test_single_floor_is_section_one():
    img, expected = make_floors(floors=(5,))
    res = recognize_image(png(img))
    assert {s["section"] for s in res["seats"]} == {1}
    assert len(res["sections"]) == 1 and res["sections"][0]["seatCount"] == len(expected)
    b = res["sections"][0]["bbox"]
    assert b["w"] > 0 and b["h"] > 0


def test_without_ocr_rows_restart_per_section(monkeypatch):
    monkeypatch.setattr(ocr, "tesseract_available", lambda: False)
    img, _ = make_floors(floors=(4, 3))
    res = recognize_image(png(img))
    assert [r["row"] for r in res["rows"]] == [1, 2, 3, 4, 1, 2, 3]
    assert [r["section"] for r in res["rows"]] == [1, 1, 1, 1, 2, 2, 2]


def test_row_gaps_thresholds():
    def rows(cys):
        return [[{"cy": c}] for c in cys]
    assert sections.row_gaps(rows([0, 8, 16, 24])) == []
    g = sections.row_gaps(rows([0, 8, 16, 24, 74, 82]))          # 50px = 6.25배
    assert [i for i, _ in g] == [4] and g[0][1] >= sections.SECTION_CERTAIN_RATIO
    starts, unc = sections.split_rows(g, set())
    assert starts == [0, 4] and unc == []
    g = sections.row_gaps(rows([0, 8, 16, 24, 48, 56]))          # 24px = 3배 -> 애매
    starts, unc = sections.split_rows(g, set())
    assert starts == [0] and unc == [4]
    starts, unc = sections.split_rows(g, {4})                      # 라벨이 1로 다시 시작 -> 경계로 승격
    assert starts == [0, 4] and unc == []


@needs_tesseract
def test_uncertain_gap_split_only_when_labels_restart():
    # 한 층 안의 가로 통로(간격 약 3배): 라벨이 이어지면 나누지 않고 경고
    img, _ = make_floors(floors=(5, 5), floor_gap=16, restart=False)
    res = recognize_image(png(img))
    assert res["stats"]["sectionCount"] == 1
    assert any(w["code"] == "SECTION_SPLIT_UNCERTAIN" for w in res["warnings"])
    assert [r["row"] for r in res["rows"]] == list(range(1, 11))
    # 같은 간격이라도 아래 라벨이 1부터 다시 시작하면 층 경계로 인정
    img, _ = make_floors(floors=(5, 5), floor_gap=16, restart=True)
    res = recognize_image(png(img))
    assert res["stats"]["sectionCount"] == 2
    assert [r["row"] for r in res["rows"]] == [1, 2, 3, 4, 5, 1, 2, 3, 4, 5]


# ---- D. 블록 사이 통로 라벨 OCR ----

@needs_tesseract
def test_small_light_labels_between_blocks_are_read():
    img, expected = make_floors(floors=(8, 6))
    res = recognize_image(png(img))
    assert res["stats"]["rowCount"] == 14
    assert res["stats"]["ocrRowsRead"] >= 10, res["stats"]
    assert [r["row"] for r in res["rows"]] == list(range(1, 9)) + list(range(1, 7))


@needs_tesseract
def test_misread_labels_are_corrected_by_section_sequence():
    """한 행의 판독이 규칙과 어긋나면(OCR 오판독) 구역의 1,2,3... 규칙으로 바로잡는다."""
    assert ocr.consensus_row_numbers([[(1, 90.0)], [(2, 90.0)], [(32, 80.0), (3, 50.0)], [(4, 90.0)], [(5, 90.0)]]) \
        == [1, 2, 3, 4, 5]
    # 규칙에 맞는 후보가 없는 행은 None(호출측이 보간)
    assert ocr.consensus_row_numbers([[(1, 90.0)], [(2, 90.0)], [(44, 80.0)], [(4, 90.0)], [(5, 90.0)]]) \
        == [1, 2, None, 4, 5]
    # 내림차순 규칙도 인식
    assert ocr.consensus_row_numbers([[(6, 90.0)], [(5, 90.0)], [(4, 90.0)], [(3, 90.0)]]) == [6, 5, 4, 3]
    # 근거가 부족하면(서로 다른 행 3개 미만) 표결 포기
    assert ocr.consensus_row_numbers([[(7, 90.0)], [], [(9, 90.0)], [], [], []]) == [None] * 6


def test_label_tokens_ignore_seats_and_big_objects():
    img, _ = make_floors(floors=(5,))
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    det = detect(img)
    toks = ocr.find_label_tokens(gray, det.seats, det.extra["rejected"], *det.seat_size)
    assert len(toks) == 10  # 행 5개 x 통로 2곳
    xs = sorted({t[0] // 10 for t in toks})
    assert len(xs) <= 4 and all(t[3] <= 9 for t in toks)


# ---- E. 열 번호: 행 안에서 왼쪽->오른쪽, 통로를 건너 이어서 ----

def test_columns_continue_left_to_right_across_aisles_per_section():
    img, expected = make_floors(floors=(3, 3), colored={(1, 1, 2), (1, 1, 3)})
    res = recognize_image(png(img))
    by = {}
    for s in res["seats"]:
        by.setdefault((s["section"], s["row"]), []).append(s)
    assert len(by) == 6
    for ss in by.values():
        ss.sort(key=lambda s: s["x"])
        assert [s["col"] for s in ss] == list(range(1, 24))   # 7+9+7, 통로를 건너 이어서
    assert all(r["aisles"] and r["aisles"][0]["afterCol"] == 7 for r in res["rows"])
    skip = recognize_image(png(img), aisle_mode="skip")
    assert max(s["col"] for s in skip["seats"]) > 23           # skip 모드는 결번 -> 번호가 더 커짐
