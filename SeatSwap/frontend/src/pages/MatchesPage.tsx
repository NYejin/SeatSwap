import { useEffect, useRef, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { exchangeApi } from "../api/exchange";
import { exchangeErrorMessage, formatExtra } from "../api/exchangeMessages";
import { formatKstDateTime } from "../api/dateTime";
import { formatSeat } from "../api/seat";
import { button, linkButton, liveRegionClass, ui } from "../components/ui";
import { usePagedList } from "../hooks/usePagedList";
import type { ExchangeMatch, MatchRole, MatchStatus } from "../types/exchange";

// FR-04 내 매칭 목록 (보호 라우트 /exchange/matches): 보낸(내가 제안)/받은 탭, 상태 배지, 예약 동의·거절·취소.
// 채팅 메시지와 '교환 완료'는 아직 없다 (안내 문구만).
// 동작(동의·거절·취소) 뒤에는 제자리 패치 대신 첫 페이지부터 다시 불러온다 (서버가 updated_at 순으로 정렬해 더 보기 목록이 어긋날 수 있음).

const STATUS_LABEL: Record<MatchStatus, string> = {
  CHATTING: "진행 중",
  RESERVED: "예약됨",
  COMPLETED: "교환 완료",
  CANCELED: "취소됨",
};

/** 상태 배지 — 색만으로 구분하지 않고 항상 글자를 함께 쓴다. 모두 대비 AA 이상 */
const STATUS_CLASS: Record<MatchStatus, string> = {
  CHATTING: "bg-primary-50 text-primary-800",
  RESERVED: "bg-accent-100 text-gray-900",
  COMPLETED: "bg-gray-900 text-white",
  CANCELED: "bg-gray-100 text-gray-700",
};

const CANCELED_BY_LABEL = {
  ME: "내가 취소했어요.",
  COUNTERPART: "상대가 취소했어요.",
  SYSTEM: "티켓을 내리는 등의 이유로 자동 취소됐어요.",
} as const;

const TABS: { role: MatchRole; label: string }[] = [
  { role: "SENT", label: "보낸 제안" },
  { role: "RECEIVED", label: "받은 제안" },
];

function readNotice(state: unknown): string | null {
  if (typeof state !== "object" || state === null || !("notice" in state)) return null;
  const notice: unknown = (state as { notice: unknown }).notice;
  return typeof notice === "string" && notice ? notice : null;
}

export default function MatchesPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const [notice] = useState(() => readNotice(location.state));
  const [role, setRole] = useState<MatchRole>("SENT");
  const [announce, setAnnounce] = useState("");
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (notice) navigate(location.pathname, { replace: true, state: null });
    // 최초 1회
  }, []);

  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<ExchangeMatch>(
    (page, signal) => exchangeApi.listMatches({ role, page, size: 20 }, signal),
    role,
    (m) => m.id
  );

  /** 첫 페이지부터 다시 불러온다. message가 있으면 결과 안내로 보여주고, 없으면 이전 안내를 지운다 */
  const refresh = (message = "") => {
    setAnnounce(message);
    // 처리한 카드가 사라지므로 포커스를 제목으로 옮겨 키보드 사용자가 위치를 잃지 않게 한다
    if (message) headingRef.current?.focus();
    reload();
  };

  const statusText =
    state.status === "loading"
      ? "내 매칭을 불러오는 중..."
      : announce
        ? announce
        : state.status === "success"
          ? `${role === "SENT" ? "보낸" : "받은"} 제안 ${state.totalElements}개`
          : "";

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 ref={headingRef} tabIndex={-1} className={`${ui.pageTitle} outline-none`}>
            내 매칭
          </h1>
          <Link to="/tickets" className={linkButton.outline}>
            내 티켓
          </Link>
        </div>

        <p className={liveRegionClass(notice, ui.notice)} role="status">
          {notice ?? ""}
        </p>

        <div role="group" aria-label="제안 구분" className="grid grid-cols-2 gap-2">
          {TABS.map((tab) => (
            <button
              key={tab.role}
              type="button"
              className={role === tab.role ? button.solid : button.outline}
              aria-pressed={role === tab.role}
              onClick={() => {
                setRole(tab.role);
                setAnnounce("");
              }}
            >
              {tab.label}
            </button>
          ))}
        </div>

        <p className={ui.notice}>채팅·교환 완료 기능은 준비 중이에요. 지금은 예약 동의와 취소까지 할 수 있어요.</p>

        <p className={liveRegionClass(statusText, ui.status)} role="status">
          {statusText}
        </p>

        {state.status === "error" && (
          <div className={`${ui.card} flex flex-col gap-3`}>
            <p className={ui.errorBox} role="alert">
              {exchangeErrorMessage(state.error, "내 매칭을 불러오지 못했습니다.").message}
            </p>
            <div>
              <button type="button" className={button.solid} onClick={reload}>
                다시 시도
              </button>
            </div>
          </div>
        )}

        {state.status === "success" && state.items.length === 0 && (
          <div className={`${ui.card} flex flex-col items-start gap-3`}>
            <p className={ui.body}>
              {role === "SENT" ? "아직 보낸 제안이 없어요. 후보 목록에서 마음에 드는 상대에게 제안해 보세요." : "아직 받은 제안이 없어요."}
            </p>
            <Link to="/tickets" className={linkButton.outline}>
              내 티켓에서 후보 찾기
            </Link>
          </div>
        )}

        {state.status === "success" && state.items.length > 0 && (
          <>
            <div>
              <button type="button" className={button.outline} onClick={() => refresh()}>
                새로고침
              </button>
            </div>
            <ul className="flex flex-col gap-3" aria-label={role === "SENT" ? "보낸 제안 목록" : "받은 제안 목록"}>
              {state.items.map((m) => (
                <li key={m.id}>
                  <MatchCard match={m} onChanged={refresh} onStale={() => refresh()} />
                </li>
              ))}
            </ul>
            {moreError && (
              <p className={ui.errorBox} role="alert">
                {moreError}
              </p>
            )}
            {state.page + 1 < state.totalPages && (
              <button type="button" className={button.outline} onClick={loadMore} disabled={loadingMore} aria-busy={loadingMore}>
                {loadingMore ? "불러오는 중..." : "더 보기"}
              </button>
            )}
          </>
        )}
      </div>
    </div>
  );
}

type Action = "accept" | "reject" | "cancel";

function MatchCard({
  match: m,
  onChanged,
  onStale,
}: {
  match: ExchangeMatch;
  onChanged: (message: string) => void;
  onStale: () => void;
}) {
  const [confirming, setConfirming] = useState<"reject" | "cancel" | null>(null);
  const [busy, setBusy] = useState<Action | null>(null);
  const [error, setError] = useState<{ message: string; stale: boolean } | null>(null);
  /** 더블 탭 방지 (state는 다음 렌더 전까지 갱신되지 않는다) */
  const busyRef = useRef(false);
  const confirmRef = useRef<HTMLDivElement>(null);

  // 거절·취소 확인 영역이 열리면 포커스를 그리로 옮긴다 (누른 버튼이 사라지므로)
  useEffect(() => {
    if (confirming) confirmRef.current?.focus();
  }, [confirming]);

  const run = async (action: Action) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(action);
    setError(null);
    try {
      const res =
        action === "accept"
          ? await exchangeApi.accept(m.id)
          : action === "reject"
            ? await exchangeApi.reject(m.id)
            : await exchangeApi.cancel(m.id);
      setConfirming(null);
      onChanged(
        action === "accept"
          ? res.status === "RESERVED"
            ? `${m.counterpartNickname}님과 예약됐어요.`
            : `${m.counterpartNickname}님에게 동의를 전했어요. 상대의 동의를 기다려요.`
          : action === "reject"
            ? `${m.counterpartNickname}님의 제안을 거절했어요.`
            : `${m.counterpartNickname}님과의 매칭을 취소했어요.`
      );
    } catch (err) {
      const parsed = exchangeErrorMessage(err, "처리하지 못했습니다.");
      setError({ message: parsed.message, stale: parsed.code === "MATCH_STATE_CONFLICT" });
    } finally {
      busyRef.current = false;
      setBusy(null);
    }
  };

  const titleId = `match-${m.id}-title`;
  const open = m.status === "CHATTING" || m.status === "RESERVED";
  const canAccept = m.status === "CHATTING" && !m.myReservedAt;
  const canReject = m.status === "CHATTING" && m.role === "RECEIVED";

  return (
    <article className={`${ui.card} flex flex-col gap-3`} aria-labelledby={titleId}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={titleId} className="text-lg/[normal] font-bold text-gray-900">
          {m.counterpartNickname}
        </h2>
        <span
          className={`inline-flex shrink-0 items-center rounded-full px-2.5 py-0.5 text-[0.8125rem]/[normal] font-semibold ${STATUS_CLASS[m.status]}`}
        >
          {STATUS_LABEL[m.status]}
        </span>
      </div>

      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5">
        <dt className={ui.muted}>내 자리</dt>
        <dd className="min-w-0 text-sm/[normal] text-gray-900">
          <span className="font-semibold">{formatSeat(m.mySeat)}</span>
          <span className="block text-gray-700">{formatKstDateTime(m.mySeat.startsAt)}</span>
        </dd>
        <dt className={ui.muted}>상대 자리</dt>
        <dd className="min-w-0 text-sm/[normal] text-gray-900">
          <span className="font-semibold">{formatSeat(m.counterpartSeat)}</span>
          <span className="block text-gray-700">{formatKstDateTime(m.counterpartSeat.startsAt)}</span>
        </dd>
        <dt className={ui.muted}>내 추가금</dt>
        <dd className="text-sm/[normal] text-gray-900">{formatExtra(m.myExtraType, m.myExtraAmount)}</dd>
        <dt className={ui.muted}>상대 추가금</dt>
        <dd className="text-sm/[normal] text-gray-900">{formatExtra(m.counterpartExtraType, m.counterpartExtraAmount)}</dd>
      </dl>

      {m.status === "CHATTING" && (
        <ul className="flex flex-col gap-1 text-sm/[normal] text-gray-700" aria-label="교환 동의 현황">
          <li>나: {m.myReservedAt ? "‘이 사람과 교환할게요’ 동의함" : "아직 동의하지 않았어요"}</li>
          <li>상대: {m.counterpartReservedAt ? "‘이 사람과 교환할게요’ 동의함" : "아직 동의하지 않았어요"}</li>
        </ul>
      )}
      {m.status === "RESERVED" && (
        <p className={ui.notice}>두 사람 모두 동의했어요. 두 티켓이 예약되어 다른 매칭에서는 쓸 수 없어요.</p>
      )}
      {m.status === "CANCELED" && (
        <p className={ui.muted}>{m.canceledBy ? CANCELED_BY_LABEL[m.canceledBy] : "취소됐어요."}</p>
      )}

      {open && (
        <div className="flex flex-wrap gap-2">
          {canAccept && (
            <button type="button" className={button.solid} onClick={() => run("accept")} disabled={busy !== null} aria-busy={busy === "accept"}>
              {busy === "accept" ? "처리 중..." : "이 사람과 교환할게요"}
            </button>
          )}
          {canReject && confirming !== "reject" && (
            <button
              type="button"
              className={button.outline}
              onClick={() => setConfirming("reject")}
              disabled={busy !== null}
              aria-label={`${m.counterpartNickname}님 제안 거절`}
            >
              거절
            </button>
          )}
          {confirming !== "cancel" && (
            <button
              type="button"
              className={button.dangerOutline}
              onClick={() => setConfirming("cancel")}
              disabled={busy !== null}
              aria-label={`${m.counterpartNickname}님과 매칭 취소`}
            >
              취소
            </button>
          )}
        </div>
      )}

      {confirming && (
        <div ref={confirmRef} tabIndex={-1} className="flex flex-col gap-3 outline-none">
          <p className={ui.warning}>
            {confirming === "reject"
              ? "이 제안을 거절할까요?"
              : m.status === "RESERVED"
                ? "취소하면 예약이 풀리고 두 티켓을 다시 쓸 수 있어요. 취소할까요?"
                : "이 매칭을 취소할까요?"}
          </p>
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              className={button.danger}
              onClick={() => run(confirming)}
              disabled={busy !== null}
              aria-busy={busy === confirming}
            >
              {busy === confirming ? "처리 중..." : confirming === "reject" ? "거절할게요" : "취소할게요"}
            </button>
            <button type="button" className={button.outline} onClick={() => setConfirming(null)} disabled={busy !== null}>
              돌아가기
            </button>
          </div>
        </div>
      )}

      {error && (
        <div className={`${ui.errorBox} flex flex-col items-start gap-2`} role="alert">
          <span>{error.message}</span>
          {error.stale && (
            <button type="button" className={button.outline} onClick={onStale}>
              목록 새로고침
            </button>
          )}
        </div>
      )}

      {open && <p className={ui.hint}>채팅·교환 완료 기능은 준비 중이에요.</p>}
    </article>
  );
}
