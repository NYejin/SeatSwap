import { useId, useRef, useState, type FormEvent } from "react";
import { parseSeatEditError, seatMapApi } from "../api/seatmap";
import type { useSeatEdits } from "../hooks/useSeatEdits";
import type { SeatMap } from "../types/seatmap";
import { duplicateLabel, MAX_CHANGES, REASON_MAX } from "./seatEdit";
import { button, liveRegionClass, ui } from "./ui";

type SeatEditsApi = ReturnType<typeof useSeatEdits>;

interface Props {
  seatMap: SeatMap;
  multiSection: boolean;
  edits: SeatEditsApi;
  /** 저장 성공 — 응답(새 version·좌석)으로 화면을 갱신한다 */
  onSaved: (seatMap: SeatMap) => void;
  /** 409 충돌 등에서 서버 최신본을 다시 불러온다 (대기 중인 변경은 버려진다) */
  onReload: () => void;
}

/** 대기 중인 변경 요약 + 수정 사유 입력 + 한 번에 PATCH 저장 */
export default function SeatMapSavePanel({ seatMap, multiSection, edits, onSaved, onReload }: Props) {
  const id = useId();
  const [reason, setReason] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<{ text: string; conflict: boolean } | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);

  const count = edits.changes.length;
  const changedSeats = edits.changedUids.size;
  const dupText = edits.duplicates.map((d) => duplicateLabel(d, multiSection)).join(", ");
  const existingText = edits.existingDuplicates.map((d) => duplicateLabel(d, multiSection)).join(", ");
  const tooMany = count > MAX_CHANGES;
  const blocked = count === 0 || edits.duplicates.length > 0 || tooMany;

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (saving || blocked) return;
    if (reason.trim() === "") {
      setError({ text: "수정 사유를 입력해 주세요.", conflict: false });
      reasonRef.current?.focus();
      return;
    }
    setSaving(true);
    setError(null);
    setDone(null);
    try {
      const updated = await seatMapApi.updateSeats(seatMap.id, {
        expectedVersion: seatMap.version,
        reason: reason.trim(),
        changes: edits.changes,
      });
      edits.reset();
      setReason("");
      setDone(`번호 ${count}건을 저장했어요.`);
      onSaved(updated);
      // 저장 버튼이 비활성이 되므로 포커스를 패널 제목으로 옮긴다
      headingRef.current?.focus();
    } catch (err) {
      const info = parseSeatEditError(err);
      setError({ text: info.message, conflict: info.kind === "conflict" });
    } finally {
      setSaving(false);
    }
  };

  const cancelChanges = () => {
    edits.reset();
    setError(null);
    headingRef.current?.focus();
  };

  // 새로고침하면 서버 최신본으로 바뀌고 대기 중인 변경이 사라지므로 확인을 거친다
  const confirmReload = () => {
    if (count > 0 && !window.confirm(`새로고침하면 저장하지 않은 변경 ${count}건이 사라져요. 계속할까요?`)) return;
    onReload();
  };

  const reasonId = `${id}-reason`;

  return (
    <form className="flex flex-col gap-3 rounded-[10px] border border-gray-200 p-3" onSubmit={save} noValidate>
      <h2 ref={headingRef} tabIndex={-1} className={`${ui.sectionTitle} outline-hidden`}>
        변경 저장
      </h2>
      <p className={ui.body}>
        {count === 0 ? "아직 바꾼 번호가 없어요." : `바꾼 좌석 ${changedSeats}개 (변경 ${count}건) — 저장 전 상태예요.`}
      </p>
      {count > 0 && <p className={ui.hint}>굵은 점선 테두리로 표시된 좌석이 바뀐 좌석이에요.</p>}

      {edits.duplicates.length > 0 && (
        <p className={ui.warning} role="alert">
          번호가 겹쳐서 저장할 수 없어요: {dupText}. 번호를 다시 확인해 주세요.
        </p>
      )}
      {edits.existingDuplicates.length > 0 && (
        <p className={ui.notice} role="status">
          원래부터 번호가 겹쳐 있는 좌석이 있어요: {existingText}. 이번 변경과 무관해서 저장은 할 수 있어요.
        </p>
      )}
      {tooMany && (
        <p className={ui.warning} role="alert">
          한 번에 저장할 수 있는 변경은 {MAX_CHANGES}건까지예요. 나눠서 저장해 주세요.
        </p>
      )}

      <div className="flex flex-col gap-1">
        <label htmlFor={reasonId} className={ui.label}>
          수정 사유 (필수)
        </label>
        <textarea
          id={reasonId}
          ref={reasonRef}
          className="min-h-20 w-full rounded-[10px] border border-gray-300 bg-white px-3.5 py-2.5 text-base/[normal] text-gray-900 outline-hidden placeholder:text-gray-500 focus-visible:border-primary-600 focus-visible:ring-3 focus-visible:ring-primary-600/20"
          maxLength={REASON_MAX}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="예: 실제 좌석 번호가 1번부터 시작해요"
          aria-describedby={`${reasonId}-count`}
        />
        <span id={`${reasonId}-count`} className={ui.hint}>
          {reason.length}/{REASON_MAX}자 · 수정 내용은 기록으로 남아요.
        </span>
      </div>

      {error && (
        <div className="flex flex-col items-start gap-2">
          <p className={ui.errorBox} role="alert">
            {error.text}
          </p>
          {error.conflict && (
            <button type="button" className={button.solid} onClick={confirmReload}>
              새로고침
            </button>
          )}
        </div>
      )}

      <p className={liveRegionClass(done, ui.notice)} role="status">
        {done ?? ""}
      </p>

      <div className="flex flex-wrap justify-end gap-2">
        <button type="button" className={button.outline} onClick={cancelChanges} disabled={saving || count === 0}>
          변경 취소
        </button>
        {/* 저장 중에도 포커스를 잃지 않도록 disabled 대신 aria-disabled로 막는다 */}
        <button
          type="submit"
          className={`${button.solid} ${saving || blocked ? "cursor-not-allowed opacity-60" : ""}`}
          aria-disabled={saving || blocked}
          aria-busy={saving}
        >
          {saving ? "저장 중..." : "저장"}
        </button>
      </div>
    </form>
  );
}
