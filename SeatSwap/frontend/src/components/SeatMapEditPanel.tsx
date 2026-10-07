import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import type { useSeatEdits } from "../hooks/useSeatEdits";
import type { SeatCoordinate } from "../types/seatmap";
import { MAX_LABEL, MIN_LABEL, seatsInSameRow } from "./seatEdit";
import { seatLabel } from "./seatLabel";
import { button, input, ui } from "./ui";

type SeatEditsApi = ReturnType<typeof useSeatEdits>;

interface Props {
  /** 선택 좌석 (수정값이 적용된 미리보기 기준) */
  seat: SeatCoordinate;
  multiSection: boolean;
  edits: SeatEditsApi;
}

const numberProps = { type: "number", inputMode: "numeric", min: MIN_LABEL, max: MAX_LABEL } as const;

/**
 * '번호 수정' 모드에서 선택한 좌석의 편집 도구.
 * (a) 이 좌석의 열/번 직접 입력 (b) 같은 열 전체 번호 한 번에 이동 (c) 같은 열 전체의 열 번호 변경.
 * 모든 동작은 로컬 대기 목록에만 쌓이고, 저장은 SeatMapSavePanel에서 한 번에 한다.
 */
export default function SeatMapEditPanel({ seat, multiSection, edits }: Props) {
  const id = useId();
  const [row, setRow] = useState(String(seat.row));
  const [col, setCol] = useState(String(seat.col));
  const [shiftTo, setShiftTo] = useState(String(seat.col));
  const [newRow, setNewRow] = useState(String(seat.row));
  const firstInputRef = useRef<HTMLInputElement>(null);
  const [message, setMessage] = useState<{ kind: "ok" | "error"; text: string } | null>(null);

  // 좌석을 바꾸거나 수정이 적용돼 값이 바뀌면 입력칸을 현재 값에 맞춘다
  useEffect(() => {
    setRow(String(seat.row));
    setCol(String(seat.col));
    setShiftTo(String(seat.col));
    setNewRow(String(seat.row));
  }, [seat.uid, seat.row, seat.col]);
  useEffect(() => setMessage(null), [seat.uid]);
  // 좌석을 선택해 패널이 열리거나 다른 좌석을 고르면 첫 입력칸으로 포커스를 옮긴다
  useEffect(() => firstInputRef.current?.focus(), [seat.uid]);

  const sameRowCount = seatsInSameRow(edits.previewSeats, seat).length;
  const toNumber = (v: string) => (v.trim() === "" ? NaN : Number(v));

  const run = (error: string | null, okText: string) =>
    setMessage(error ? { kind: "error", text: error } : { kind: "ok", text: okText });
  const same = () => setMessage({ kind: "error", text: "현재 번호와 같아서 바꿀 게 없어요." });

  const applySeat = (e: FormEvent) => {
    e.preventDefault();
    if (toNumber(row) === seat.row && toNumber(col) === seat.col) return same();
    run(
      edits.setSeatValues(seat.uid, { row: toNumber(row), col: toNumber(col) }),
      `이 좌석을 ${row}열 ${col}번으로 적용했어요. (저장 전)`
    );
  };
  const applyShift = (e: FormEvent) => {
    e.preventDefault();
    if (toNumber(shiftTo) === seat.col) return same();
    run(edits.shiftRowTo(seat.uid, toNumber(shiftTo)), `${seat.row}열 ${sameRowCount}석의 번호를 옮겼어요. (저장 전)`);
  };
  const applyRow = (e: FormEvent) => {
    e.preventDefault();
    if (toNumber(newRow) === seat.row) return same();
    run(
      edits.renameRow(seat.uid, toNumber(newRow)),
      `${seat.row}열 ${sameRowCount}석의 열 번호를 ${newRow}열로 바꿨어요. (저장 전)`
    );
  };

  const fieldLabel = "flex flex-1 basis-28 flex-col gap-1";

  return (
    <section className="flex flex-col gap-4 rounded-[10px] border border-gray-200 p-3" aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className={ui.sectionTitle}>
        번호 수정: {seatLabel(seat, multiSection)}
      </h2>

      <form className="flex flex-col gap-2" onSubmit={applySeat} noValidate>
        <p className={ui.label}>이 좌석만 바꾸기</p>
        <div className="flex flex-wrap items-end gap-3">
          <label className={fieldLabel}>
            <span className={ui.label}>열</span>
            <input ref={firstInputRef} className={input.normal} {...numberProps} value={row} onChange={(e) => setRow(e.target.value)} />
          </label>
          <label className={fieldLabel}>
            <span className={ui.label}>번</span>
            <input className={input.normal} {...numberProps} value={col} onChange={(e) => setCol(e.target.value)} />
          </label>
          <button type="submit" className={button.outline}>
            적용
          </button>
        </div>
      </form>

      <form className="flex flex-col gap-2 border-t border-gray-200 pt-3" onSubmit={applyShift} noValidate>
        <p className={ui.label}>이 열 전체 번호 한 번에 옮기기</p>
        <p className={ui.hint}>
          {seat.row}열의 {sameRowCount}석 번호가 같은 간격으로 함께 이동해요.
        </p>
        <div className="flex flex-wrap items-end gap-3">
          <label className={fieldLabel}>
            <span className={ui.label}>이 좌석이 몇 번이 되도록</span>
            <input
              className={input.normal}
              {...numberProps}
              value={shiftTo}
              onChange={(e) => setShiftTo(e.target.value)}
            />
          </label>
          <button type="submit" className={button.outline}>
            열 이동
          </button>
        </div>
      </form>

      <form className="flex flex-col gap-2 border-t border-gray-200 pt-3" onSubmit={applyRow} noValidate>
        <p className={ui.label}>이 열의 열 번호 바꾸기</p>
        <p className={ui.hint}>
          {seat.row}열의 {sameRowCount}석 모두 열 번호가 바뀌어요.
        </p>
        <div className="flex flex-wrap items-end gap-3">
          <label className={fieldLabel}>
            <span className={ui.label}>새 열 번호</span>
            <input
              className={input.normal}
              {...numberProps}
              value={newRow}
              onChange={(e) => setNewRow(e.target.value)}
            />
          </label>
          <button type="submit" className={button.outline}>
            열 번호 변경
          </button>
        </div>
      </form>

      {/* 결과 문구: 성공은 status, 실패는 alert로 읽힌다 */}
      <p className={message?.kind === "ok" ? ui.notice : "sr-only"} role="status">
        {message?.kind === "ok" ? message.text : ""}
      </p>
      {message?.kind === "error" && (
        <p className={ui.errorBox} role="alert">
          {message.text}
        </p>
      )}
    </section>
  );
}
