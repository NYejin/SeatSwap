import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  applyEdits,
  diffToChanges,
  isValidLabel,
  mergeEdits,
  rebaseEdits,
  splitDuplicates,
  seatsInSameRow,
  MAX_LABEL,
  MIN_LABEL,
  type SeatEdits,
} from "../components/seatEdit";
import type { SeatCoordinate } from "../types/seatmap";

const RANGE_MESSAGE = `번호는 ${MIN_LABEL}~${MAX_LABEL} 사이의 정수여야 해요.`;

/**
 * 좌석 번호 수정의 로컬 대기 목록. 원본(base)은 건드리지 않고 수정값만 쌓아 미리보기를 만든다.
 * 각 동작은 오류 문구(없으면 null)를 돌려준다.
 */
export function useSeatEdits(base: SeatCoordinate[]) {
  const [edits, setEdits] = useState<SeatEdits>({});

  const previewSeats = useMemo(() => applyEdits(base, edits), [base, edits]);
  const changes = useMemo(() => diffToChanges(base, edits), [base, edits]);
  const changedUids = useMemo(() => new Set(Object.keys(edits)), [edits]);
  const { blocking: duplicates, existing: existingDuplicates } = useMemo(
    () => splitDuplicates(previewSeats, changedUids),
    [previewSeats, changedUids]
  );

  // 기준 좌석이 바뀌면(정정 반영·저장·새로고침) 대기 중인 수정이 낡지 않게 새 기준에 맞춘다
  const prevBase = useRef(base);
  useEffect(() => {
    if (prevBase.current === base) return;
    const old = prevBase.current;
    prevBase.current = base;
    setEdits((prev) => (Object.keys(prev).length === 0 ? prev : rebaseEdits(old, base, prev)));
  }, [base]);

  const apply = useCallback(
    (next: Map<string, { row: number; col: number }>) => setEdits((prev) => mergeEdits(base, prev, next)),
    [base]
  );

  const find = (uid: string) => previewSeats.find((s) => s.uid === uid);

  /** (a) 한 좌석의 열/번을 직접 바꾼다 */
  const setSeatValues = (uid: string, values: { row?: number; col?: number }): string | null => {
    const seat = find(uid);
    if (!seat) return "좌석을 찾을 수 없어요.";
    const row = values.row ?? seat.row;
    const col = values.col ?? seat.col;
    if (!isValidLabel(row) || !isValidLabel(col)) return RANGE_MESSAGE;
    apply(new Map([[uid, { row, col }]]));
    return null;
  };

  /** (b) 같은 (구역, 열) 모든 좌석의 번호를 한 번에 이동 — 선택 좌석이 targetCol번이 되도록 */
  const shiftRowTo = (uid: string, targetCol: number): string | null => {
    const seat = find(uid);
    if (!seat) return "좌석을 찾을 수 없어요.";
    if (!isValidLabel(targetCol)) return RANGE_MESSAGE;
    const delta = targetCol - seat.col;
    if (delta === 0) return null;
    const group = seatsInSameRow(previewSeats, seat);
    const next = new Map<string, { row: number; col: number }>();
    for (const s of group) {
      const col = s.col + delta;
      if (!isValidLabel(col)) return `이동하면 ${MIN_LABEL}~${MAX_LABEL} 범위를 벗어나는 번호가 생겨요.`;
      next.set(s.uid, { row: s.row, col });
    }
    apply(next);
    return null;
  };

  /** (c) 같은 (구역, 열) 전체의 열 번호를 바꾼다 */
  const renameRow = (uid: string, newRow: number): string | null => {
    const seat = find(uid);
    if (!seat) return "좌석을 찾을 수 없어요.";
    if (!isValidLabel(newRow)) return RANGE_MESSAGE;
    if (newRow === seat.row) return null;
    const next = new Map<string, { row: number; col: number }>();
    for (const s of seatsInSameRow(previewSeats, seat)) next.set(s.uid, { row: newRow, col: s.col });
    apply(next);
    return null;
  };

  const reset = useCallback(() => setEdits({}), []);

  return { previewSeats, changes, changedUids, duplicates, existingDuplicates, setSeatValues, shiftRowTo, renameRow, reset };
}
