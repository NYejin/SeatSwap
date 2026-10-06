"""합성 좌석맵 이미지 생성기 (실제 사이트 이미지는 저작권 때문에 저장소에 두지 않는다)."""
import cv2
import numpy as np


def make_seatmap(rows=13, left=8, right=8, seat=18, pitch=22, aisle=40, label_side="left",
                 first_label=1, descending=False, skip_label_rows=(), bg=255, jpeg=False, row_pitch=26,
                 raw=False):
    """rows x (left+right) 좌석, 가운데 통로(aisle px). 반환: (이미지 bytes, 기대 좌표 리스트). raw=True면 (ndarray, 기대)."""
    margin_x, margin_y = 70, 40
    width = margin_x * 2 + (left + right) * pitch + aisle
    height = margin_y * 2 + rows * row_pitch
    img = np.full((height, width, 3), bg, np.uint8)
    expected = []
    colors = [(180, 134, 190), (255, 118, 144), (180, 180, 60), (70, 140, 230)]
    for r in range(rows):
        y = margin_y + r * row_pitch
        number = first_label + (rows - 1 - r if descending else r)
        xs = [margin_x + c * pitch for c in range(left)] + \
             [margin_x + left * pitch + aisle + c * pitch for c in range(right)]
        for c, x in enumerate(xs):
            cv2.rectangle(img, (x, y), (x + seat - 1, y + seat - 1), colors[r % 4], -1)
            expected.append({"row": number, "col": c + 1, "x": x, "y": y, "w": seat, "h": seat})
        if r not in skip_label_rows:
            tx = margin_x - 36 if label_side == "left" else xs[-1] + seat + 12
            cv2.putText(img, str(number), (tx, y + 14), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (30, 30, 30), 1,
                        cv2.LINE_AA)
    if raw:
        return img, expected
    params = [cv2.IMWRITE_JPEG_QUALITY, 90] if jpeg else []
    ok, buf = cv2.imencode(".jpg" if jpeg else ".png", img, params)
    assert ok
    return buf.tobytes(), expected
