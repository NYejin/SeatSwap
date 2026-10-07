"""
구역(층) 분할: 행 간격이 정상 행 피치보다 크게 벌어지는 수직 구간(층 사이 공백·배지)을 경계로 삼는다.
한 구역 안에서 행 번호는 1부터(또는 OCR 라벨대로) 다시 매긴다. 색/판매상태는 쓰지 않는다 — 좌표만 본다.

확실한 경계: 이웃 행 중심 간격 >= 정상 피치 * SECTION_CERTAIN_RATIO.
애매한 간격: SECTION_UNCERTAIN_RATIO 이상 CERTAIN 미만 (한 층 안의 가로 통로일 수도 있다).
  -> 아래 행 라벨이 1로 다시 시작하면 경계로 인정하고, 아니면 나누지 않고 SECTION_SPLIT_UNCERTAIN 경고만 남긴다.
"""
from __future__ import annotations

import statistics

SECTION_CERTAIN_RATIO = 4.0
SECTION_UNCERTAIN_RATIO = 2.5


def row_gaps(rows: list[list[dict]]) -> list[tuple[int, float]]:
    """(i, 배율): i번째 행과 i-1번째 행 사이가 정상 피치의 배율 이상으로 벌어진 지점들(애매한 구간 이상)."""
    if len(rows) < 2:
        return []
    cys = [statistics.fmean(s["cy"] for s in r) for r in rows]
    diffs = [b - a for a, b in zip(cys, cys[1:])]
    pitch = statistics.median(diffs)
    if pitch <= 0:
        return []
    return [(i + 1, d / pitch) for i, d in enumerate(diffs) if d / pitch >= SECTION_UNCERTAIN_RATIO]


def split_rows(gaps: list[tuple[int, float]], restart_at: set[int]) -> tuple[list[int], list[int]]:
    """
    반환: (구역 시작 행 인덱스 목록(맨 앞은 0), 애매해서 나누지 않은 간격의 행 인덱스 목록).
    restart_at: 라벨이 다시 1로 시작하는 것으로 확인된 행 인덱스(애매한 간격을 경계로 승격).
    """
    starts = [0]
    uncertain: list[int] = []
    for i, ratio in gaps:
        if ratio >= SECTION_CERTAIN_RATIO or i in restart_at:
            starts.append(i)
        else:
            uncertain.append(i)
    return starts, uncertain
