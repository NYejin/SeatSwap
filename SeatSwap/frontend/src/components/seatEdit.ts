import type { SeatChange, SeatCoordinate } from "../types/seatmap";
import { seatSection } from "./seatLabel";

// 좌석 번호 수정(미리보기·변경 목록·중복 검사)에 쓰는 순수 함수 모음.

export const MIN_LABEL = 1;
export const MAX_LABEL = 9999;
/** 서버가 한 번에 받는 변경 건수 상한 */
export const MAX_CHANGES = 2000;
export const REASON_MAX = 500;

/** 수정된 (열, 번) 값. 원본과 다른 좌석만 보관한다 */
export type SeatEdits = Readonly<Record<string, { row: number; col: number }>>;

export function isValidLabel(value: number): boolean {
  return Number.isInteger(value) && value >= MIN_LABEL && value <= MAX_LABEL;
}

/** 수정값을 적용한 좌석 목록. 수정 없는 좌석은 같은 객체를 돌려줘 SeatRect memo가 유지된다 */
export function applyEdits(seats: SeatCoordinate[], edits: SeatEdits): SeatCoordinate[] {
  return seats.map((s) => {
    const e = edits[s.uid];
    return e ? { ...s, row: e.row, col: e.col } : s;
  });
}

/** 원본 대비 바뀐 필드만 PATCH changes로 만든다 */
export function diffToChanges(base: SeatCoordinate[], edits: SeatEdits): SeatChange[] {
  const changes: SeatChange[] = [];
  for (const s of base) {
    const e = edits[s.uid];
    if (!e) continue;
    if (e.row !== s.row) changes.push({ uid: s.uid, field: "ROW_LABEL", value: e.row });
    if (e.col !== s.col) changes.push({ uid: s.uid, field: "COL_LABEL", value: e.col });
  }
  return changes;
}

/**
 * 기준(base)이 바뀌었을 때(정정 반영·새로고침) 대기 중인 수정을 새 기준에 맞춘다.
 * 사용자가 건드리지 않은 필드는 새 기준 값을 따르고, 새 기준과 같아진 항목·사라진 좌석은 지운다.
 */
export function rebaseEdits(oldBase: SeatCoordinate[], newBase: SeatCoordinate[], edits: SeatEdits): SeatEdits {
  const oldByUid = new Map(oldBase.map((s) => [s.uid, s]));
  const newByUid = new Map(newBase.map((s) => [s.uid, s]));
  const out: Record<string, { row: number; col: number }> = {};
  for (const [uid, e] of Object.entries(edits)) {
    const o = oldByUid.get(uid);
    const n = newByUid.get(uid);
    if (!o || !n) continue;
    const row = e.row === o.row ? n.row : e.row;
    const col = e.col === o.col ? n.col : e.col;
    if (row !== n.row || col !== n.col) out[uid] = { row, col };
  }
  return out;
}

/** 새 (열, 번) 값을 edits에 반영 — 원본과 같아지면 항목을 지운다 */
export function mergeEdits(
  base: SeatCoordinate[],
  edits: SeatEdits,
  next: Map<string, { row: number; col: number }>
): SeatEdits {
  const baseByUid = new Map(base.map((s) => [s.uid, s]));
  const merged: Record<string, { row: number; col: number }> = { ...edits };
  for (const [uid, v] of next) {
    const orig = baseByUid.get(uid);
    if (!orig) continue;
    if (orig.row === v.row && orig.col === v.col) delete merged[uid];
    else merged[uid] = v;
  }
  return merged;
}

export interface DuplicateSeat {
  section: number;
  row: number;
  col: number;
  uids: string[];
}

/** (구역, 열, 번)이 겹치는 모든 좌석 묶음 (변경과 무관한 기존 중복 포함) */
export function findDuplicates(seats: SeatCoordinate[]): DuplicateSeat[] {
  const groups = new Map<string, DuplicateSeat>();
  for (const s of seats) {
    const section = seatSection(s);
    const key = `${section}:${s.row}:${s.col}`;
    const g = groups.get(key);
    if (g) g.uids.push(s.uid);
    else groups.set(key, { section, row: s.row, col: s.col, uids: [s.uid] });
  }
  return [...groups.values()].filter((g) => g.uids.length > 1);
}

/**
 * 중복을 저장 차단 대상(바뀐 좌석이 관여한 것)과 기존 중복(변경과 무관, 경고만)으로 나눈다.
 * 서버가 '바뀐 좌석의 최종 (구역,열,번)'만 검사하는 것과 같은 기준이다.
 */
export function splitDuplicates(
  seats: SeatCoordinate[],
  changedUids: ReadonlySet<string>
): { blocking: DuplicateSeat[]; existing: DuplicateSeat[] } {
  const blocking: DuplicateSeat[] = [];
  const existing: DuplicateSeat[] = [];
  for (const d of findDuplicates(seats)) (d.uids.some((u) => changedUids.has(u)) ? blocking : existing).push(d);
  return { blocking, existing };
}

export function duplicateLabel(d: DuplicateSeat, multiSection: boolean): string {
  const base = `${d.row}열 ${d.col}번`;
  return multiSection ? `구역 ${d.section} · ${base}` : base;
}

/** 같은 (구역, 열)에 속한 좌석 (미리보기 값 기준) */
export function seatsInSameRow(seats: SeatCoordinate[], seat: SeatCoordinate): SeatCoordinate[] {
  const section = seatSection(seat);
  return seats.filter((s) => seatSection(s) === section && s.row === seat.row);
}
