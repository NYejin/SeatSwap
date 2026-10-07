import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { parseSeatMapError, seatMapApi } from "../api/seatmap";
import type { SeatChangeField, SeatCoordinate } from "../types/seatmap";
import { isValidLabel, MAX_LABEL, MIN_LABEL } from "./seatEdit";
import { seatLabel } from "./seatLabel";
import { button, input, liveRegionClass, ui } from "./ui";

interface Props {
  seatMapId: number;
  seat: SeatCoordinate;
  /** 좌석표에 구역이 둘 이상이면 true */
  multiSection?: boolean;
  /** 정정이 바로 반영(APPLIED)됐을 때 — 화면을 새로고침한다 */
  onApplied: () => void;
}

/**
 * 정식(OFFICIAL) 좌석표의 오류 신고 (FR-07). 선택 좌석의 올바른 열/번을 입력해 POST /corrections.
 * 같은 정정이 2건 이상 모이면 자동 반영된다. 좌석을 바꾸면 부모에서 key={uid}로 다시 만들어 입력이 초기화된다.
 */
export default function SeatMapErrorReportButton({ seatMapId, seat, multiSection = false, onApplied }: Props) {
  const uid = useId();
  const [open, setOpen] = useState(false);
  const [row, setRow] = useState(String(seat.row));
  const [col, setCol] = useState(String(seat.col));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<string | null>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const rowRef = useRef<HTMLInputElement>(null);
  const moved = useRef(false);

  // 폼이 열리면 첫 입력칸으로, 닫히면 신고 버튼으로 포커스
  useEffect(() => {
    if (!moved.current) return;
    moved.current = false;
    if (open) rowRef.current?.focus();
    else triggerRef.current?.focus();
  }, [open]);

  const toggle = (next: boolean) => {
    moved.current = true;
    if (next) {
      // 정정이 반영돼 좌석 번호가 바뀌었을 수 있어 열 때마다 현재 값으로 채운다
      setRow(String(seat.row));
      setCol(String(seat.col));
      setResult(null);
    }
    setOpen(next);
    setError(null);
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    const newRow = Number(row);
    const newCol = Number(col);
    if (row.trim() === "" || col.trim() === "" || !isValidLabel(newRow) || !isValidLabel(newCol)) {
      setError(`열·번은 ${MIN_LABEL}~${MAX_LABEL} 사이의 정수로 입력해 주세요.`);
      return;
    }
    const fields: { field: SeatChangeField; value: number }[] = [];
    if (newRow !== seat.row) fields.push({ field: "ROW_LABEL", value: newRow });
    if (newCol !== seat.col) fields.push({ field: "COL_LABEL", value: newCol });
    if (fields.length === 0) {
      setError("현재 번호와 같아요. 올바른 번호를 입력해 주세요.");
      return;
    }
    setBusy(true);
    setError(null);
    // 필드별로 순차 접수. 409(이미 같은 내용으로 신고함)는 건너뛰고 계속, 그 밖의 오류는 중단한다.
    const results: string[] = [];
    let applied = false;
    let failure: string | null = null;
    for (const f of fields) {
      const name = f.field === "ROW_LABEL" ? "열" : "번";
      try {
        const res = await seatMapApi.reportCorrection(seatMapId, { uid: seat.uid, field: f.field, value: f.value });
        if (res.status === "APPLIED") {
          applied = true;
          results.push(`${name}: 반영됨`);
        } else results.push(`${name}: 접수됨`);
      } catch (err) {
        const info = parseSeatMapError(err, "신고를 접수하지 못했습니다.");
        if (info.status === 409) results.push(`${name}: 이미 신고함`);
        else {
          failure = `${name}: ${info.message}`;
          break;
        }
      }
    }
    setBusy(false);
    if (failure) {
      const done = results.length > 0 ? ` (앞서 처리됨 — ${results.join(", ")})` : "";
      setError(`${failure}${done}`);
      if (applied) onApplied();
      return;
    }
    setOpen(false);
    moved.current = true;
    const detail = fields.length > 1 ? ` (${results.join(", ")})` : "";
    if (applied) {
      setResult(`정정이 반영됐어요.${detail}`);
      onApplied();
    } else if (results.every((r) => r.endsWith("이미 신고함"))) {
      setResult(`이미 같은 내용으로 신고했어요.${detail}`);
    } else {
      setResult(`신고가 접수됐어요. 같은 정정이 2건 이상 모이면 자동 반영돼요.${detail}`);
    }
  };

  const hintId = `${uid}-hint`;

  return (
    <div className="flex flex-col gap-2">
      <div>
        <button
          ref={triggerRef}
          type="button"
          className={button.outline}
          aria-expanded={open}
          aria-describedby={hintId}
          aria-label={`${seatLabel(seat, multiSection)} 오류 신고`}
          onClick={() => toggle(!open)}
        >
          오류 신고
        </button>
      </div>
      <p id={hintId} className={ui.hint}>
        번호가 실제와 다르면 올바른 번호를 알려 주세요. 같은 정정이 2건 이상 모이면 자동 반영돼요.
      </p>

      {open && (
        <form className="flex flex-col gap-3 rounded-[10px] border border-gray-200 p-3" onSubmit={submit} noValidate>
          <p className={ui.label}>올바른 번호 ({seatLabel(seat, multiSection)}의 정정)</p>
          <div className="flex flex-wrap gap-3">
            <label className="flex flex-1 basis-32 flex-col gap-1">
              <span className={ui.label}>열</span>
              <input
                ref={rowRef}
                className={input.normal}
                type="number"
                inputMode="numeric"
                min={MIN_LABEL}
                max={MAX_LABEL}
                value={row}
                onChange={(e) => setRow(e.target.value)}
              />
            </label>
            <label className="flex flex-1 basis-32 flex-col gap-1">
              <span className={ui.label}>번</span>
              <input
                className={input.normal}
                type="number"
                inputMode="numeric"
                min={MIN_LABEL}
                max={MAX_LABEL}
                value={col}
                onChange={(e) => setCol(e.target.value)}
              />
            </label>
          </div>
          {error && (
            <p className={ui.errorBox} role="alert">
              {error}
            </p>
          )}
          <div className="flex flex-wrap justify-end gap-2">
            <button type="button" className={button.outline} onClick={() => toggle(false)} disabled={busy}>
              취소
            </button>
            <button type="submit" className={button.solid} disabled={busy} aria-busy={busy}>
              {busy ? "접수 중..." : "신고 접수"}
            </button>
          </div>
        </form>
      )}

      <p className={liveRegionClass(result, ui.notice)} role="status">
        {result ?? ""}
      </p>
    </div>
  );
}
