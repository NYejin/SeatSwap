import { useEffect, useRef, useState } from "react"; // TEMP-DRAFT-DELETE: useRef
import { Link, useNavigate, useParams } from "react-router-dom"; // TEMP-DRAFT-DELETE: useNavigate (원래 Link, useParams)
import { parseApiError } from "../api/errors";
import { getErrorStatus } from "../api/performances";
import { seatMapApi } from "../api/seatmap";
import SeatMapOverlay from "../components/SeatMapOverlay";
import SeatMapEditPanel from "../components/SeatMapEditPanel";
import SeatMapErrorReportButton from "../components/SeatMapErrorReportButton";
import SeatMapSavePanel from "../components/SeatMapSavePanel";
import { hasMultipleSections, seatLabel } from "../components/seatLabel";
import { button, linkButton, liveRegionClass, ui } from "../components/ui";
import { useAuth } from "../hooks/useAuth";
import { useSeatEdits } from "../hooks/useSeatEdits";
import type { SeatMap } from "../types/seatmap";

// UC-03/UC-04 좌석표 보기·좌석 선택 (보호 라우트 /seatmaps/:id/select).
// 좌표만 받아 SVG로 그린다(원본 이미지는 서버에 없음). DRAFT 좌석표는 확인용으로만 쓴다.
// 번호 수정: DRAFT는 로그인 사용자 누구나, OFFICIAL은 ADMIN만(UI 분기용, 최종 판정은 서버). 일반 사용자는 OFFICIAL에서 '오류 신고'.

type LoadState =
  | { status: "loading" }
  | { status: "notFound" }
  | { status: "error"; message: string }
  | { status: "success"; seatMap: SeatMap };

export default function SeatMapSelectPage() {
  const { id: idParam } = useParams();
  const id = Number(idParam);
  const validId = Number.isInteger(id) && id > 0;
  const [state, setState] = useState<LoadState>(validId ? { status: "loading" } : { status: "notFound" });
  const [retryKey, setRetryKey] = useState(0);
  const [selectedUid, setSelectedUid] = useState<string | null>(null);

  useEffect(() => {
    if (!validId) {
      setState({ status: "notFound" });
      return;
    }
    const controller = new AbortController();
    setState({ status: "loading" });
    setSelectedUid(null);
    seatMapApi
      .get(id, controller.signal)
      .then((seatMap) => setState({ status: "success", seatMap }))
      .catch((err: unknown) => {
        if (controller.signal.aborted) return;
        if (getErrorStatus(err) === 404) setState({ status: "notFound" });
        else setState({ status: "error", message: parseApiError(err, "좌석표를 불러오지 못했습니다.").message });
      });
    return () => controller.abort();
  }, [id, validId, retryKey]);

  // 정정 반영 등 화면을 유지한 채 최신 좌석표로 조용히 갱신 (실패하면 기존 화면 유지)
  const refresh = () => {
    if (!validId) return;
    seatMapApi
      .get(id)
      .then((seatMap) => setState({ status: "success", seatMap }))
      .catch(() => undefined);
  };

  const loadingText = state.status === "loading" ? "좌석표를 불러오는 중..." : "";

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <p className={liveRegionClass(loadingText, ui.status)} role="status">
          {loadingText}
        </p>

        {state.status === "notFound" && (
          <div className={`${ui.card} flex flex-col items-start gap-3`}>
            <h1 className={ui.pageTitle}>좌석표를 찾을 수 없어요</h1>
            <p className={ui.body}>삭제되었거나 잘못된 주소예요.</p>
            <Link to="/" className={linkButton.outline}>
              공연 목록으로
            </Link>
          </div>
        )}

        {state.status === "error" && (
          <div className={`${ui.card} flex flex-col gap-3`}>
            <p className={ui.errorBox} role="alert">
              {state.message}
            </p>
            <button type="button" className={button.solid} onClick={() => setRetryKey((k) => k + 1)}>
              다시 시도
            </button>
          </div>
        )}

        {state.status === "success" && (
          <SeatMapView
            seatMap={state.seatMap}
            selectedUid={selectedUid}
            onSelect={setSelectedUid}
            onSaved={(seatMap) => setState({ status: "success", seatMap })}
            onRefresh={refresh}
            onReload={() => setRetryKey((k) => k + 1)}
          />
        )}
      </div>
    </div>
  );
}

function SeatMapView({
  seatMap,
  selectedUid,
  onSelect,
  onSaved,
  onRefresh,
  onReload,
}: {
  seatMap: SeatMap;
  selectedUid: string | null;
  onSelect: (uid: string) => void;
  onSaved: (seatMap: SeatMap) => void;
  onRefresh: () => void;
  onReload: () => void;
}) {
  const { profile } = useAuth();
  const isDraft = seatMap.status === "DRAFT";
  const canEdit = isDraft || profile?.role === "ADMIN";
  const [editMode, setEditMode] = useState(false);
  const edits = useSeatEdits(seatMap.seats);
  const { previewSeats, changedUids } = edits;
  const pendingCount = edits.changes.length;
  const selected = previewSeats.find((s) => s.uid === selectedUid) ?? null;
  const multiSection = hasMultipleSections(seatMap.seats);

  // 저장하지 않은 변경이 있으면 탭 닫기·새로고침 전에 브라우저 경고
  useEffect(() => {
    if (pendingCount === 0) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [pendingCount]);

  // 수정 시작/끝 후 포커스가 body로 빠지지 않게 반대편 토글 버튼으로 옮긴다
  const startRef = useRef<HTMLButtonElement>(null);
  const endRef = useRef<HTMLButtonElement>(null);
  const modeToggled = useRef(false);
  useEffect(() => {
    if (!modeToggled.current) return;
    modeToggled.current = false;
    (editMode ? endRef : startRef).current?.focus();
  }, [editMode]);

  const toggleEditMode = () => {
    if (editMode && pendingCount > 0 && !window.confirm(`저장하지 않은 변경 ${pendingCount}건이 사라져요. 수정을 끝낼까요?`)) {
      return;
    }
    if (editMode) edits.reset();
    modeToggled.current = true;
    setEditMode(!editMode);
  };
  const canRender = seatMap.imageWidth > 0 && seatMap.imageHeight > 0 && seatMap.seats.length > 0;

  return (
    <>
      <section className={`${ui.card} flex flex-col gap-3`} aria-labelledby="seatmap-title">
        <div className="flex flex-wrap items-center gap-2">
          <h1 id="seatmap-title" className={ui.pageTitle}>
            {seatMap.venueName}
          </h1>
          {/* 색만으로 구분하지 않고 글자로 표시 */}
          <span className={ui.badge}>{isDraft ? "임시(확인용)" : "정식"}</span>
        </div>
        {seatMap.zoneName && <p className={ui.body}>구역: {seatMap.zoneName}</p>}
        <p className={ui.muted}>좌석 {seatMap.seats.length}개</p>
        {isDraft && <p className={ui.notice}>정식 등록 전 좌석표입니다. 확인용으로만 사용돼요.</p>}
      </section>

      <section className={`${ui.card} flex flex-col gap-4`} aria-label="좌석표">
        {canRender ? (
          <SeatMapOverlay
            imageWidth={seatMap.imageWidth}
            imageHeight={seatMap.imageHeight}
            seats={previewSeats}
            selectedUid={selected?.uid ?? null}
            changedUids={changedUids}
            onSelectSeat={(seat) => onSelect(seat.uid)}
          />
        ) : (
          <p className={ui.body}>표시할 좌석이 없어요.</p>
        )}

        <div className="flex flex-col gap-2 border-t border-gray-200 pt-4">
          {/* 라이브 영역에는 선택 좌석 번호 문구만 둔다 */}
          <p
            className={selected ? "text-lg/[normal] font-bold text-gray-900" : ui.body}
            role="status"
            aria-live="polite"
          >
            {selected ? seatLabel(selected, multiSection) : "좌석을 눌러 열·번을 확인하세요."}
          </p>
          {/* 오류 신고는 선택 좌석이 있으면 항상 함께 노출 — 정식은 정정 신고, 임시는 번호 수정 안내 */}
          {selected && !isDraft && (
            <SeatMapErrorReportButton
              key={selected.uid}
              seatMapId={seatMap.id}
              seat={selected}
              multiSection={multiSection}
              onApplied={onRefresh}
            />
          )}
          {selected && isDraft && (
            <p className={ui.notice}>
              번호가 실제와 다르면 아래 &apos;번호 수정&apos;으로 직접 고칠 수 있어요. 수정 내용은 기록으로 남아요.
            </p>
          )}
        </div>
      </section>

      {canEdit && canRender && (
        <section className={`${ui.card} flex flex-col gap-3`} aria-labelledby="seatmap-edit-title">
          <h2 id="seatmap-edit-title" className={ui.sectionTitle}>
            번호 수정
          </h2>
          {!editMode ? (
            <>
              <p className={ui.body}>좌석 번호(열·번)가 실제와 다르면 고칠 수 있어요.</p>
              <div>
                <button ref={startRef} type="button" className={button.solid} onClick={toggleEditMode}>
                  번호 수정 시작
                </button>
              </div>
            </>
          ) : (
            <>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className={ui.body}>좌석을 눌러 선택한 뒤 번호를 바꾸세요. 저장하기 전에는 반영되지 않아요.</p>
                <button ref={endRef} type="button" className={button.outline} onClick={toggleEditMode}>
                  수정 끝내기
                </button>
              </div>
              {selected ? (
                <SeatMapEditPanel seat={selected} multiSection={multiSection} edits={edits} />
              ) : (
                <p className={ui.notice}>수정할 좌석을 먼저 눌러 선택해 주세요.</p>
              )}
              <SeatMapSavePanel
                seatMap={seatMap}
                multiSection={multiSection}
                edits={edits}
                onSaved={onSaved}
                onReload={onReload}
              />
            </>
          )}
        </section>
      )}

      {/* TODO: 임시 기능 */}
      {/* TEMP-DRAFT-DELETE: 테스트용 임시 기능 — DRAFT만 삭제 후 재업로드. 제거 시 이 줄과 아래 TempDraftDeleteSection 삭제 */}
      {isDraft && seatMap.canDelete === true && <TempDraftDeleteSection seatMap={seatMap} />}
    </>
  );
}

// TODO: 임시 기능

// TEMP-DRAFT-DELETE-START: 테스트용 임시 기능. 'TEMP-DRAFT-DELETE'로 grep해 관련 코드를 한 번에 제거한다.
function TempDraftDeleteSection({ seatMap }: { seatMap: SeatMap }) {
  const navigate = useNavigate();
  const [confirming, setConfirming] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const toggled = useRef(false);

  // 확인 상자가 열리면 "취소"로, 닫히면 트리거 버튼으로 포커스
  useEffect(() => {
    if (!toggled.current) return;
    toggled.current = false;
    if (confirming) cancelRef.current?.focus();
    else triggerRef.current?.focus();
  }, [confirming]);

  const setConfirm = (next: boolean) => {
    toggled.current = true;
    setConfirming(next);
  };

  const remove = async () => {
    if (deleting) return;
    setDeleting(true);
    setError(null);
    try {
      await seatMapApi.remove(seatMap.id);
      const zone = seatMap.zoneName ? `?zone=${encodeURIComponent(seatMap.zoneName)}` : "";
      navigate(`/venues/${seatMap.venueId}/seatmaps/new${zone}`);
    } catch (err) {
      const status = getErrorStatus(err);
      if (status === 403) setError(parseApiError(err, "작성자 또는 관리자만 삭제할 수 있어요.").message);
      else if (status === 404) setError("삭제할 좌석표를 찾을 수 없어요. 이미 삭제됐을 수 있어요.");
      else if (status === 409) setError(parseApiError(err, "삭제할 수 없는 좌석표예요.").message);
      else setError(parseApiError(err, "좌석표를 삭제하지 못했습니다.").message);
      setDeleting(false);
    }
  };

  return (
    <section className={`${ui.card} flex flex-col gap-3`} aria-labelledby="temp-draft-delete-title">
      <h2 id="temp-draft-delete-title" className={ui.sectionTitle}>
        좌석표 삭제
      </h2>
      {!confirming ? (
        <>
          <p className={ui.body}>작성자 또는 관리자만 삭제할 수 있어요. 삭제하면 다시 올릴 수 있어요.</p>
          <div>
            <button ref={triggerRef} type="button" className={button.dangerOutline} onClick={() => setConfirm(true)}>
              삭제하고 다시 올리기
            </button>
          </div>
        </>
      ) : (
        <div className={`${ui.warning} flex flex-col gap-2`} role="group" aria-label="임시 좌석표 삭제 확인">
          <span>이 임시 좌석표를 삭제하고 다시 올릴까요? 되돌릴 수 없어요.</span>
          <span className="flex flex-wrap justify-end gap-2">
            <button ref={cancelRef} type="button" className={button.outline} onClick={() => setConfirm(false)} disabled={deleting}>
              취소
            </button>
            <button type="button" className={button.danger} onClick={remove} disabled={deleting} aria-busy={deleting}>
              {deleting ? "삭제 중..." : "삭제"}
            </button>
          </span>
        </div>
      )}
      {error && (
        <p className={ui.errorBox} role="alert">
          {error}
        </p>
      )}
    </section>
  );
}
// TEMP-DRAFT-DELETE-END
