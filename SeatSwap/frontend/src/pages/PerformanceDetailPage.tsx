import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { getErrorStatus, performancesApi } from "../api/performances";
import { parseApiError } from "../api/errors";
import { seatMapApi } from "../api/seatmap";
import {
  // TODO: 지우기
  // SESSION_TIME_STEP_HINT,
  // SESSION_TIME_STEP_SECONDS,
  compareLocalDateTime,
  formatKstDateTime,
  isPastKst,
  normalizeLocalDateTime,
  validateSessionTimeStep,
} from "../api/dateTime";
import type { PerformanceDetail, PerformanceSession, Venue } from "../types/performance";
import type { SeatMapSummary } from "../types/seatmap";
import TextField from "../components/TextField";
import SessionDateTimePicker from "../components/SessionDateTimePicker";
import VenuePicker from "../components/VenuePicker";
import { button, linkButton, liveRegionClass, ui } from "../components/ui";

// FR-02 공연 상세 (보호 라우트 /performances/:id).
// 로그인 사용자 누구나 회차 추가. 등록자(canEdit)만 제목·공연장 수정, 회차 수정·삭제, 공연 삭제.
// 좌석맵·티켓(교환) 영역은 이후 기능 — "준비 중" 자리만.
// 포커스: 편집·확인 상자가 열리면 입력칸/"취소"로, 닫히면 트리거 버튼(없어졌으면 섹션 제목)으로 돌려준다.

const TITLE_MAX = 200;

/** 백엔드 TicketingSite와 같은 알려진 예매처 호스트 (호스트가 이것이거나 그 하위 도메인) */
const KNOWN_TICKETING_HOSTS = ["interpark.com", "ticket.melon.com", "ticket.yes24.com", "ticketlink.co.kr"];

const cls = {
  infoList: "grid grid-cols-[auto_1fr] items-baseline gap-x-4 gap-y-2",
  term: "text-sm/[normal] font-semibold text-gray-700",
  desc: "min-w-0 text-[0.9375rem]/[normal] break-words text-gray-900",
  descSub: "block text-sm/[normal] text-gray-700",
  title: "text-2xl/[normal] font-bold break-keep text-gray-900",
  /** 프로그램으로 포커스를 옮기는 제목 (tabIndex=-1) — 포커스 링 대신 스크린리더 낭독용 */
  focusTarget: "outline-none",
  editRow: "flex flex-wrap gap-2",
  sessionList: "flex flex-col divide-y divide-gray-200",
  sessionRow: "flex flex-col gap-2 py-3",
  sessionMain: "flex flex-wrap items-center justify-between gap-2",
  /** 회차 시각 — 지난 회차는 gray-500(흰 배경 4.83:1)으로 흐리게 + "지난 회차" 배지 (색만으로 구분하지 않음) */
  sessionTimeUpcoming: "text-[0.9375rem]/[normal] font-semibold text-gray-900",
  sessionTimePast: "text-[0.9375rem]/[normal] text-gray-500",
  comingList: "flex flex-col divide-y divide-gray-200",
  comingItem: "flex min-h-11 items-center justify-between gap-3 text-[0.9375rem]/[normal] text-gray-700",
} as const;

/** http(s) 링크만 링크로 렌더링 (javascript: 등 차단). 표시용 호스트와 알려진 예매처 여부 */
function parseTicketingLink(url: string): { href: string; host: string; known: boolean } | null {
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return null;
    const host = parsed.hostname.toLowerCase();
    const known = KNOWN_TICKETING_HOSTS.some((h) => host === h || host.endsWith(`.${h}`));
    return { href: parsed.href, host, known };
  } catch {
    return null;
  }
}

type LoadState =
  | { status: "loading" }
  | { status: "notFound" }
  | { status: "error"; message: string }
  | { status: "success"; detail: PerformanceDetail };

export default function PerformanceDetailPage() {
  const { id: idParam } = useParams();
  const id = Number(idParam);
  const validId = Number.isInteger(id) && id > 0;
  const [state, setState] = useState<LoadState>(validId ? { status: "loading" } : { status: "notFound" });
  const [retryKey, setRetryKey] = useState(0);
  /** 변경 후 재조회 요청 — 새 재조회·화면 이탈 시 이전 것은 취소 */
  const refreshController = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!validId) {
      setState({ status: "notFound" });
      return;
    }
    const controller = new AbortController();
    setState({ status: "loading" });
    performancesApi
      .get(id, controller.signal)
      .then((detail) => setState({ status: "success", detail }))
      .catch((err: unknown) => {
        if (controller.signal.aborted) return;
        if (getErrorStatus(err) === 404) setState({ status: "notFound" });
        else setState({ status: "error", message: parseApiError(err, "공연 정보를 불러오지 못했습니다.").message });
      });
    return () => {
      controller.abort();
      refreshController.current?.abort();
    };
  }, [id, validId, retryKey]);

  /** 변경 후 최신 상태로 다시 조회 (화면은 유지한 채 교체). 실패하면 기존 화면 유지 */
  const refresh = useCallback(async () => {
    refreshController.current?.abort();
    const controller = new AbortController();
    refreshController.current = controller;
    try {
      const detail = await performancesApi.get(id, controller.signal);
      if (!controller.signal.aborted) setState({ status: "success", detail });
    } catch {
      // 취소되었거나 실패 — 다음 조작이나 새로고침 때 다시 맞춰진다
    }
  }, [id]);

  const replaceDetail = useCallback((detail: PerformanceDetail) => setState({ status: "success", detail }), []);

  // 로딩 중 안내는 아래 상태 라이브 영역이 담당
  let content: ReactNode = null;
  if (state.status === "notFound") {
    content = (
      <div className={`${ui.card} flex flex-col items-start gap-3`}>
        <h1 className={ui.pageTitle}>공연을 찾을 수 없어요</h1>
        <p className={ui.body}>삭제되었거나 잘못된 주소예요.</p>
        <Link to="/" className={linkButton.outline}>
          공연 목록으로
        </Link>
      </div>
    );
  } else if (state.status === "error") {
    content = (
      <div className={`${ui.card} flex flex-col gap-3`}>
        <p className={ui.errorBox} role="alert">
          {state.message}
        </p>
        <button type="button" className={button.solid} onClick={() => setRetryKey((k) => k + 1)}>
          다시 시도
        </button>
      </div>
    );
  } else if (state.status === "success") {
    const { detail } = state;
    // key: 다른 공연으로 이동하면 섹션의 편집 상태를 초기화
    content = (
      <>
        <InfoSection key={`info-${detail.id}`} detail={detail} onUpdated={replaceDetail} />
        <SessionsSection key={`sessions-${detail.id}`} detail={detail} onChanged={refresh} />
        <SeatMapsSection key={`seatmaps-${detail.venue.id}`} venueId={detail.venue.id} />
        <section className={ui.card} aria-labelledby="perf-coming-title">
          <h2 id="perf-coming-title" className={ui.sectionTitle}>
            티켓
          </h2>
          <ul className={cls.comingList}>
            {["이 공연의 티켓·교환 요청"].map((label) => (
              <li key={label} className={cls.comingItem}>
                <span>{label}</span>
                <span className={ui.badge}>준비 중</span>
              </li>
            ))}
          </ul>
        </section>
        {detail.canEdit && <DeleteSection key={`delete-${detail.id}`} detail={detail} />}
      </>
    );
  }

  const loadingText = state.status === "loading" ? "공연 정보를 불러오는 중..." : "";
  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <p className={liveRegionClass(loadingText, ui.status)} role="status">
          {loadingText}
        </p>
        {content}
      </div>
    </div>
  );
}

// ---- 공연 정보 (제목·공연장 수정) ----

type EditMode = "none" | "title" | "venue";

function InfoSection({ detail, onUpdated }: { detail: PerformanceDetail; onUpdated: (d: PerformanceDetail) => void }) {
  const [editing, setEditing] = useState<EditMode>("none");
  const [titleValue, setTitleValue] = useState(detail.title);
  const [titleError, setTitleError] = useState<string>();
  const [venueValue, setVenueValue] = useState<Venue | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const link = parseTicketingLink(detail.sourceUrl);

  const titleButtonRef = useRef<HTMLButtonElement>(null);
  const venueButtonRef = useRef<HTMLButtonElement>(null);
  const venueHeadingRef = useRef<HTMLHeadingElement>(null);
  /** 편집을 닫은 뒤 포커스를 돌려줄 트리거 */
  const returnFocusTo = useRef<Exclude<EditMode, "none"> | null>(null);

  useEffect(() => {
    if (editing === "venue") {
      // 공연장 변경 패널이 열리면 패널 제목으로 (바로 아래 검색칸으로 이어짐)
      venueHeadingRef.current?.focus();
      return;
    }
    if (editing === "none" && returnFocusTo.current) {
      const target = returnFocusTo.current === "title" ? titleButtonRef.current : venueButtonRef.current;
      returnFocusTo.current = null;
      target?.focus();
    }
  }, [editing]);

  const open = (mode: Exclude<EditMode, "none">) => {
    setEditing(mode);
    setError(null);
    setNotice(null);
    setTitleValue(detail.title);
    setTitleError(undefined);
    setVenueValue(null);
  };

  /** 편집 닫기(취소·완료) — 트리거 버튼으로 포커스 복귀 */
  const close = () => {
    if (editing !== "none") returnFocusTo.current = editing;
    setEditing("none");
  };

  const save = async (payload: { title?: string; venueId?: number }, doneMessage: string) => {
    setSaving(true);
    setError(null);
    try {
      const updated = await performancesApi.update(detail.id, payload);
      onUpdated(updated);
      close();
      setNotice(doneMessage);
    } catch (err) {
      const parsed = parseApiError(err, "수정하지 못했습니다.");
      if (parsed.fieldErrors.title) setTitleError(parsed.fieldErrors.title);
      else setError(parsed.message);
    } finally {
      setSaving(false);
    }
  };

  const submitTitle = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (saving) return;
    const t = titleValue.trim();
    const err = !t ? "공연 제목을 입력해주세요." : t.length > TITLE_MAX ? `공연 제목은 ${TITLE_MAX}자 이하로 입력해주세요.` : undefined;
    setTitleError(err);
    if (err) return;
    if (t === detail.title) {
      close();
      return;
    }
    void save({ title: t }, "제목을 수정했어요.");
  };

  const submitVenue = () => {
    if (saving || !venueValue) return;
    if (venueValue.id === detail.venue.id) {
      close();
      return;
    }
    void save({ venueId: venueValue.id }, "공연장을 변경했어요.");
  };

  return (
    <section className={`${ui.card} flex flex-col gap-4`} aria-labelledby="perf-title">
      <h1 id="perf-title" className={cls.title}>
        {detail.title}
      </h1>

      {editing === "title" && (
        <form className="flex flex-col gap-3 border-t border-gray-200 pt-4" onSubmit={submitTitle} noValidate>
          <TextField
            id="perf-edit-title"
            label="공연 제목"
            value={titleValue}
            onChange={(e) => setTitleValue(e.target.value)}
            disabled={saving}
            error={titleError}
            maxLength={TITLE_MAX}
            autoFocus
          />
          <div className="flex flex-wrap justify-end gap-2">
            <button type="button" className={button.outline} onClick={close} disabled={saving}>
              취소
            </button>
            <button type="submit" className={button.solid} disabled={saving} aria-busy={saving}>
              {saving ? "저장 중..." : "제목 저장"}
            </button>
          </div>
        </form>
      )}

      <p className={liveRegionClass(notice, ui.notice)} role="status">
        {notice ?? ""}
      </p>
      {error && (
        <p className={ui.errorBox} role="alert">
          {error}
        </p>
      )}

      <dl className={cls.infoList}>
        <dt className={cls.term}>공연장</dt>
        <dd className={cls.desc}>
          {detail.venue.name}
          {detail.venue.address && <span className={cls.descSub}>{detail.venue.address}</span>}
        </dd>
        <dt className={cls.term}>티켓팅</dt>
        <dd className={cls.desc}>
          {link ? (
            <>
              <a href={link.href} target="_blank" rel="noopener noreferrer" className={ui.link}>
                {link.host}
                <span aria-hidden="true"> ↗</span>
                <span className="sr-only"> 티켓팅 페이지 (새 창에서 열림)</span>
              </a>
              {!link.known && <span className={cls.descSub}>공식 예매처가 아닐 수 있어요. 주소를 확인하고 열어 주세요.</span>}
            </>
          ) : (
            <span className="break-all">{detail.sourceUrl}</span>
          )}
        </dd>
        {/* TODO: 등록자 삭제하기 */}
        {/* <dt className={cls.term}>등록자</dt> */}
        {/* <dd className={cls.desc}>{detail.registrant.nickname}</dd> */}
        {/* TODO: 공연 포스터 오른쪽에 추가하기 */}
      </dl>

      {editing === "venue" && (
        <div className="flex flex-col gap-3 border-t border-gray-200 pt-4">
          <h2 ref={venueHeadingRef} tabIndex={-1} className={`${ui.sectionTitle} ${cls.focusTarget}`}>
            공연장 변경
          </h2>
          <VenuePicker idPrefix="perf-edit" selected={venueValue} onSelect={setVenueValue} />
          <div className="flex flex-wrap justify-end gap-2">
            <button type="button" className={button.outline} onClick={close} disabled={saving}>
              취소
            </button>
            <button
              type="button"
              className={button.solid}
              onClick={submitVenue}
              disabled={saving || !venueValue}
              aria-busy={saving}
            >
              {saving ? "저장 중..." : "이 공연장으로 변경"}
            </button>
          </div>
        </div>
      )}

      {detail.canEdit && editing === "none" && (
        <div className={cls.editRow}>
          <button ref={titleButtonRef} type="button" className={button.outline} onClick={() => open("title")}>
            제목 수정
          </button>
          <button ref={venueButtonRef} type="button" className={button.outline} onClick={() => open("venue")}>
            공연장 변경
          </button>
        </div>
      )}
    </section>
  );
}

// ---- 좌석표 (공연장 단위, UC-03/04) ----

type SeatMapsState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "success"; items: SeatMapSummary[] };

function SeatMapsSection({ venueId }: { venueId: number }) {
  const [state, setState] = useState<SeatMapsState>({ status: "loading" });
  const [retryKey, setRetryKey] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setState({ status: "loading" });
    seatMapApi
      .listByVenue(venueId, controller.signal)
      .then((items) => setState({ status: "success", items }))
      .catch(() => {
        if (!controller.signal.aborted) setState({ status: "error" });
      });
    return () => controller.abort();
  }, [venueId, retryKey]);

  return (
    <section className={`${ui.card} flex flex-col gap-3`} aria-labelledby="perf-seatmaps-title">
      <h2 id="perf-seatmaps-title" className={ui.sectionTitle}>
        좌석표
      </h2>
      {state.status === "loading" && (
        <p className={ui.status} role="status">
          좌석표를 불러오는 중...
        </p>
      )}
      {state.status === "error" && (
        <div className="flex flex-col items-start gap-2">
          <p className={ui.errorBox} role="alert">
            좌석표를 불러오지 못했어요.
          </p>
          <button type="button" className={button.outline} onClick={() => setRetryKey((k) => k + 1)}>
            다시 시도
          </button>
        </div>
      )}
      {state.status === "success" &&
        (state.items.length === 0 ? (
          <>
            <p className={ui.body}>이 공연장의 좌석표가 아직 없어요. 캡처한 이미지로 등록할 수 있어요.</p>
            <div>
              <Link to={`/venues/${venueId}/seatmaps/new`} className={linkButton.solid}>
                좌석표 등록하기
              </Link>
            </div>
          </>
        ) : (
          <ul className={cls.comingList}>
            {state.items.map((item) => (
              <li key={item.id} className={cls.comingItem}>
                <span className="flex flex-wrap items-center gap-2">
                  <span>{item.zoneName ?? "전체"}</span>
                  <span className={ui.badge}>{item.status === "DRAFT" ? "임시(확인용)" : "정식"}</span>
                  {typeof item.seatCount === "number" && <span className={ui.muted}>{item.seatCount}석</span>}
                </span>
                <Link
                  to={`/seatmaps/${item.id}/select`}
                  className={`${ui.link} inline-flex min-h-11 items-center`}
                  aria-label={`${item.zoneName ?? "전체"} 좌석표 보기`}
                >
                  보기
                </Link>
              </li>
            ))}
          </ul>
        ))}
    </section>
  );
}

// ---- 회차 ----

/** 회차 행 포커스 복귀 대상: 행의 수정/삭제 버튼, 없으면 섹션 제목 */
type SessionFocusTarget = { sessionId: number; button: "edit" | "delete" } | "heading";

function SessionsSection({ detail, onChanged }: { detail: PerformanceDetail; onChanged: () => Promise<void> }) {
  const sessions = [...detail.sessions].sort((a, b) => compareLocalDateTime(a.startsAt, b.startsAt));

  const [newValue, setNewValue] = useState("");
  const [addError, setAddError] = useState<string>();
  const [adding, setAdding] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const [editingId, setEditingId] = useState<number | null>(null);
  const [editValue, setEditValue] = useState("");
  const [confirmDeleteId, setConfirmDeleteId] = useState<number | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [rowError, setRowError] = useState<{ id: number; message: string } | null>(null);

  const headingRef = useRef<HTMLHeadingElement>(null);
  const confirmCancelRef = useRef<HTMLButtonElement>(null);
  const rowButtons = useRef(new Map<string, HTMLButtonElement>());
  const pendingFocus = useRef<SessionFocusTarget | null>(null);

  const rowButtonRef = (sessionId: number, kind: "edit" | "delete") => (el: HTMLButtonElement | null) => {
    const key = `${sessionId}-${kind}`;
    if (el) rowButtons.current.set(key, el);
    else rowButtons.current.delete(key);
  };

  // 삭제 확인 상자가 열리면 "취소"로
  useEffect(() => {
    if (confirmDeleteId !== null) confirmCancelRef.current?.focus();
  }, [confirmDeleteId]);

  // 편집·확인을 닫은 뒤(또는 재조회로 목록이 바뀐 뒤) 예약된 포커스 복귀
  useEffect(() => {
    const target = pendingFocus.current;
    if (!target || editingId !== null || confirmDeleteId !== null) return;
    pendingFocus.current = null;
    const el = target === "heading" ? null : rowButtons.current.get(`${target.sessionId}-${target.button}`);
    if (el) el.focus();
    else headingRef.current?.focus();
  }, [editingId, confirmDeleteId, detail.sessions]);

  /** 입력값 검사 — 통과하면 정규화된 시각, 아니면 오류 메시지 */
  const check = (value: string, exceptId?: number): { value: string } | { error: string } => {
    // 형식 오류·10분 단위 아님(직접 입력·붙여넣기)
    const stepError = validateSessionTimeStep(value);
    if (stepError) return { error: stepError };
    const normalized = normalizeLocalDateTime(value)!;
    if (isPastKst(normalized)) return { error: "이미 지난 시각이에요. 앞으로 있을 회차만 등록할 수 있어요." };
    const duplicate = sessions.some((s) => s.id !== exceptId && normalizeLocalDateTime(s.startsAt) === normalized);
    if (duplicate) return { error: "이미 있는 회차예요." };
    return { value: normalized };
  };

  const add = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (adding) return;
    const result = check(newValue);
    if ("error" in result) {
      setAddError(result.error);
      return;
    }
    setAdding(true);
    setAddError(undefined);
    setNotice(null);
    try {
      await performancesApi.addSession(detail.id, result.value);
      setNewValue("");
      setNotice(`${formatKstDateTime(result.value)} 회차를 추가했어요.`);
      await onChanged();
    } catch (err) {
      setAddError(parseApiError(err, "회차를 추가하지 못했습니다.").message);
    } finally {
      setAdding(false);
    }
  };

  const startEdit = (session: PerformanceSession) => {
    setEditingId(session.id);
    setEditValue(normalizeLocalDateTime(session.startsAt) ?? "");
    setConfirmDeleteId(null);
    setRowError(null);
    setNotice(null);
  };

  /** 수정 닫기(취소·완료) — 그 행의 "수정" 버튼으로 포커스 복귀 */
  const closeEdit = (sessionId: number) => {
    pendingFocus.current = { sessionId, button: "edit" };
    setEditingId(null);
  };

  const saveEdit = async (e: FormEvent<HTMLFormElement>, session: PerformanceSession) => {
    e.preventDefault();
    if (busyId !== null) return;
    if (normalizeLocalDateTime(editValue) === normalizeLocalDateTime(session.startsAt)) {
      closeEdit(session.id);
      return;
    }
    const result = check(editValue, session.id);
    if ("error" in result) {
      setRowError({ id: session.id, message: result.error });
      return;
    }
    setBusyId(session.id);
    setRowError(null);
    try {
      await performancesApi.updateSession(detail.id, session.id, result.value);
      closeEdit(session.id);
      setNotice("회차 시각을 수정했어요.");
      await onChanged();
    } catch (err) {
      // 403(등록자 아님), 409(티켓이 있는 회차·중복 시각) 등 서버 메시지 그대로
      setRowError({ id: session.id, message: parseApiError(err, "회차를 수정하지 못했습니다.").message });
    } finally {
      setBusyId(null);
    }
  };

  const openDeleteConfirm = (sessionId: number) => {
    setConfirmDeleteId(sessionId);
    setEditingId(null);
    setRowError(null);
  };

  /** 삭제 확인 취소 — 그 행의 "삭제" 버튼으로 포커스 복귀 */
  const cancelDelete = (sessionId: number) => {
    pendingFocus.current = { sessionId, button: "delete" };
    setConfirmDeleteId(null);
  };

  const remove = async (session: PerformanceSession) => {
    if (busyId !== null) return;
    setBusyId(session.id);
    setRowError(null);
    try {
      await performancesApi.removeSession(detail.id, session.id);
      // 행이 사라지므로 섹션 제목으로
      pendingFocus.current = "heading";
      setConfirmDeleteId(null);
      setNotice(`${formatKstDateTime(session.startsAt)} 회차를 삭제했어요.`);
      await onChanged();
    } catch (err) {
      setRowError({ id: session.id, message: parseApiError(err, "회차를 삭제하지 못했습니다.").message });
    } finally {
      setBusyId(null);
    }
  };

  return (
    <section className={`${ui.card} flex flex-col gap-4`} aria-labelledby="perf-sessions-title">
      <h2 id="perf-sessions-title" ref={headingRef} tabIndex={-1} className={`${ui.sectionTitle} ${cls.focusTarget}`}>
        회차 {sessions.length}개
      </h2>

      {sessions.length === 0 ? (
        <p className={ui.body}>등록된 회차가 없어요. 아래에서 추가해 주세요.</p>
      ) : (
        <ul className={cls.sessionList}>
          {sessions.map((session) => {
            const past = isPastKst(session.startsAt);
            const label = formatKstDateTime(session.startsAt);
            const busy = busyId === session.id;
            const errorForRow = rowError?.id === session.id ? rowError.message : null;
            return (
              <li key={session.id} className={cls.sessionRow}>
                {editingId === session.id ? (
                  <form className="flex flex-col gap-2" onSubmit={(e) => saveEdit(e, session)} noValidate>
                    <SessionDateTimePicker
                      legend={`${label} 회차의 새 날짜·시간 (한국 시간)`}
                      value={editValue}
                      onChange={setEditValue}
                      disabled={busy}
                      invalid={!!errorForRow}
                      describedBy={errorForRow ? `session-edit-${session.id}-error` : undefined}
                      autoFocus
                    />
                    {/* TODO: 지우기 */}
                    {/* <p id={`session-edit-${session.id}-hint`} className={ui.hint}>
                      {SESSION_TIME_STEP_HINT}
                    </p> */}
                    <div className="flex flex-wrap justify-end gap-2">
                      <button
                        type="button"
                        className={button.outline}
                        onClick={() => closeEdit(session.id)}
                        disabled={busy}
                      >
                        취소
                      </button>
                      <button type="submit" className={button.solid} disabled={busy} aria-busy={busy}>
                        {busy ? "저장 중..." : "저장"}
                      </button>
                    </div>
                  </form>
                ) : (
                  <div className={cls.sessionMain}>
                    <span className="flex flex-wrap items-center gap-2">
                      <span className={past ? cls.sessionTimePast : cls.sessionTimeUpcoming}>{label}</span>
                      {past && <span className={ui.badge}>지난 회차</span>}
                    </span>
                    {detail.canEdit && confirmDeleteId !== session.id && (
                      <span className="flex gap-2">
                        <button
                          ref={rowButtonRef(session.id, "edit")}
                          type="button"
                          className={button.outline}
                          onClick={() => startEdit(session)}
                          aria-label={`${label} 회차 수정`}
                        >
                          수정
                        </button>
                        <button
                          ref={rowButtonRef(session.id, "delete")}
                          type="button"
                          className={button.dangerOutline}
                          onClick={() => openDeleteConfirm(session.id)}
                          aria-label={`${label} 회차 삭제`}
                        >
                          삭제
                        </button>
                      </span>
                    )}
                  </div>
                )}

                {confirmDeleteId === session.id && (
                  <div className={`${ui.warning} flex flex-col gap-2`} role="group" aria-label={`${label} 회차 삭제 확인`}>
                    <span>이 회차를 삭제할까요? 되돌릴 수 없어요.</span>
                    <span className="flex flex-wrap justify-end gap-2">
                      <button
                        ref={confirmCancelRef}
                        type="button"
                        className={button.outline}
                        onClick={() => cancelDelete(session.id)}
                        disabled={busy}
                      >
                        취소
                      </button>
                      <button
                        type="button"
                        className={button.danger}
                        onClick={() => remove(session)}
                        disabled={busy}
                        aria-busy={busy}
                      >
                        {busy ? "삭제 중..." : "회차 삭제"}
                      </button>
                    </span>
                  </div>
                )}

                {errorForRow && (
                  <p id={`session-edit-${session.id}-error`} className={ui.errorBox} role="alert">
                    {errorForRow}
                  </p>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {/* 회차 추가는 로그인 사용자 누구나 (빠진 회차를 다른 관객이 채울 수 있게) */}
      <form className="flex flex-col gap-2 border-t border-gray-200 pt-4" onSubmit={add} noValidate>
        <div className="flex flex-wrap items-end gap-2">
          <SessionDateTimePicker
            legend="회차 추가 (한국 시간)"
            value={newValue}
            onChange={setNewValue}
            disabled={adding}
            invalid={!!addError}
            describedBy={addError ? "session-new-error" : undefined}
          />
          <button type="submit" className={button.solid} disabled={adding} aria-busy={adding}>
            {adding ? "추가 중..." : "회차 추가"}
          </button>
        </div>
        {addError && (
          <p id="session-new-error" className={ui.fieldError}>
            {addError}
          </p>
        )}
        {/* TODO: 지우기 */}
        {/* <p id="session-new-hint" className={ui.hint}>
          {SESSION_TIME_STEP_HINT}
        </p> */}
      </form>

      <p className={liveRegionClass(notice, ui.notice)} role="status">
        {notice ?? ""}
      </p>
    </section>
  );
}

// ---- 공연 삭제 (등록자) ----

function DeleteSection({ detail }: { detail: PerformanceDetail }) {
  const navigate = useNavigate();
  const [confirming, setConfirming] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  /** 첫 렌더에서는 포커스를 옮기지 않는다 */
  const toggled = useRef(false);

  // 열리면 "취소"로, 닫히면 "공연 삭제" 버튼으로
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
      await performancesApi.remove(detail.id);
      navigate("/", { replace: true, state: { notice: `"${detail.title}" 공연을 삭제했어요.` } });
    } catch (err) {
      // 409: 이 공연에 등록된 티켓이 있음, 403: 등록자 아님
      setError(parseApiError(err, "공연을 삭제하지 못했습니다.").message);
      setDeleting(false);
    }
  };

  return (
    <section className={`${ui.card} flex flex-col gap-3`} aria-labelledby="perf-delete-title">
      <h2 id="perf-delete-title" className={ui.sectionTitle}>
        공연 삭제
      </h2>
      {!confirming ? (
        <>
          <p className={ui.body}>등록한 공연을 삭제해요. 티켓이 등록된 공연은 삭제할 수 없어요.</p>
          <div>
            <button ref={triggerRef} type="button" className={button.dangerOutline} onClick={() => setConfirm(true)}>
              공연 삭제
            </button>
          </div>
        </>
      ) : (
        <div className={`${ui.warning} flex flex-col gap-2`} role="group" aria-label="공연 삭제 확인">
          <span>"{detail.title}" 공연과 모든 회차를 삭제할까요? 되돌릴 수 없어요.</span>
          <span className="flex flex-wrap justify-end gap-2">
            <button
              ref={cancelRef}
              type="button"
              className={button.outline}
              onClick={() => setConfirm(false)}
              disabled={deleting}
            >
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
