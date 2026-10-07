import type { SeatCoordinate } from "../types/seatmap";

/** 구역(층) 번호. 서버가 내려주지 않으면 1로 간주 */
export function seatSection(seat: SeatCoordinate): number {
  return seat.section ?? 1;
}

/** 표시용 좌석 문구. 한국 좌석 체계: 열(앞에서부터) · 번(왼쪽부터). 구역이 둘 이상이면 "구역 N · M열 K번" */
export function seatLabel(seat: SeatCoordinate, multiSection: boolean): string {
  const base = `${seat.row}열 ${seat.col}번`;
  return multiSection ? `구역 ${seatSection(seat)} · ${base}` : base;
}

export function hasMultipleSections(seats: SeatCoordinate[]): boolean {
  const first = seats.length ? seatSection(seats[0]) : 1;
  return seats.some((s) => seatSection(s) !== first);
}
