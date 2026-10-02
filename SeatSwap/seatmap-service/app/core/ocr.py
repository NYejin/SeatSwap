"""
행 번호 인식 (Tesseract OCR) + 열 번호 자동 부여.
seatmap-recognition-pattern 스킬 기준선: y좌표 클러스터링 → 행 묶기 → OCR로 행 라벨 인식
→ 같은 행 내 x좌표 순 정렬로 열 번호 부여, 통로(간격 급증 구간)는 결번 처리.
"""


def cluster_rows(seats: list, y_threshold: int = 10):
    # TODO: cy 기준 정렬 + 클러스터링
    raise NotImplementedError


def recognize_row_labels(image_path: str, row_clusters: list):
    # TODO: pytesseract.image_to_string(..., config="--psm 7 digits")
    raise NotImplementedError


def assign_columns(row_seats: list, gap_ratio_threshold: float = 1.5):
    # TODO: x좌표 순 정렬 + 간격 급증 구간 결번 처리
    raise NotImplementedError
