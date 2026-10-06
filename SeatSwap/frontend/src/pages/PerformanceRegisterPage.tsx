import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { Link, useNavigate } from "react-router-dom";
import { getConflictPerformanceId, performancesApi } from "../api/performances";
import { parseApiError } from "../api/errors";
import {
  // TODO: 지우기
  // SESSION_TIME_STEP_HINT,
  SESSION_TIME_STEP_SECONDS,
  compareLocalDateTime,
  formatKstDateTime,
  isPastKst,
  normalizeLocalDateTime,
  validateSessionTimeStep,
} from "../api/dateTime";
import type { Venue } from "../types/performance";
import TextField from "../components/TextField";
import SessionDateTimePicker from "../components/SessionDateTimePicker";
import VenuePicker from "../components/VenuePicker";
import { button, linkButton, liveRegionClass, ui } from "../components/ui";

// FR-02 공연 등록 (보호 라우트 /performances/new) — 단계형:
// ① 티켓팅 링크(이미 등록됐는지 확인) ② 제목 ③ 공연장(검색·추가) ④ 회차(여러 개) ⑤ 확인 후 등록.

const STEPS = ["티켓팅 링크", "공연 제목", "공연장", "회차", "확인"] as const;
type Step = 0 | 1 | 2 | 3 | 4;

const TITLE_MAX = 200;
const URL_MAX = 2048;
const SESSIONS_MAX = 100;

const cls = {
  steps: "flex flex-wrap gap-x-3 gap-y-1",
  /** 단계 표시 — 현재/그 외 중 완성된 문자열 하나. surface 배경 위라 gray-500(4.47:1) 대신 gray-700.
      완료 단계는 색이 아닌 체크 표시로 구분한다 */
  stepCurrent: "text-sm/[normal] font-bold text-primary-700",
  stepOther: "text-sm/[normal] text-gray-700",
  stepBody: "flex flex-col gap-4",
  actions: "flex flex-wrap justify-between gap-2",
  sessionList: "flex flex-col divide-y divide-gray-200",
  sessionRow: "flex min-h-11 items-center justify-between gap-3 py-1.5",
  summary: "grid grid-cols-[auto_1fr] gap-x-4 gap-y-2",
  summaryTerm: "text-sm/[normal] font-semibold text-gray-700",
  summaryDesc: "min-w-0 text-[0.9375rem]/[normal] break-all text-gray-900",
} as const;

const stepClass = (index: number, current: number) => (index === current ? cls.stepCurrent : cls.stepOther);

/** http(s) 링크만 허용 */
function validateSourceUrl(value: string): string | undefined {
  const v = value.trim();
  if (!v) return "티켓팅 링크를 입력해주세요.";
  if (v.length > URL_MAX) return `티켓팅 링크는 ${URL_MAX}자 이하로 입력해주세요.`;
  try {
    const url = new URL(v);
    if (url.protocol !== "http:" && url.protocol !== "https:") return "http 또는 https 링크를 입력해주세요.";
  } catch {
    return "올바른 링크 형식이 아닙니다.";
  }
  return undefined;
}

/** 서버 필드명 → 그 필드를 입력하는 단계 */
const FIELD_STEP: Record<string, Step> = { sourceUrl: 0, title: 1, venueId: 2, sessions: 3 };

export default function PerformanceRegisterPage() {
  const navigate = useNavigate();
  const [step, setStep] = useState<Step>(0);
  const headingRef = useRef<HTMLHeadingElement>(null);
  /** 사용자가 단계를 옮겼을 때만 제목으로 포커스 (최초 진입·StrictMode 재실행에서는 하지 않음) */
  const focusHeading = useRef(false);

  // ① 링크
  const [sourceUrl, setSourceUrl] = useState("");
  const [urlError, setUrlError] = useState<string>();
  const [checking, setChecking] = useState(false);
  const [existingId, setExistingId] = useState<number | null>(null);
  const [lookupError, setLookupError] = useState<string | null>(null);
  // ② 제목
  const [title, setTitle] = useState("");
  const [titleError, setTitleError] = useState<string>();
  // ③ 공연장
  const [venue, setVenue] = useState<Venue | null>(null);
  const [venueError, setVenueError] = useState<string>();
  // ④ 회차
  const [sessionInput, setSessionInput] = useState("");
  const [sessions, setSessions] = useState<string[]>([]);
  const [sessionError, setSessionError] = useState<string>();
  // ⑤ 등록
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<{ message: string; step?: Step } | null>(null);
  const [conflictId, setConflictId] = useState<number | null>(null);

  // 단계가 바뀌면 단계 제목으로 포커스 (스크린리더가 새 단계를 읽도록)
  useEffect(() => {
    if (!focusHeading.current) return;
    focusHeading.current = false;
    headingRef.current?.focus();
  }, [step]);

  const goTo = (next: Step) => {
    focusHeading.current = true;
    setStep(next);
  };

  const submitUrl = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (checking) return;
    const error = validateSourceUrl(sourceUrl);
    setUrlError(error);
    setExistingId(null);
    setLookupError(null);
    if (error) return;
    setChecking(true);
    try {
      const result = await performancesApi.lookup(sourceUrl.trim());
      if (result.exists && typeof result.performanceId === "number") {
        setExistingId(result.performanceId);
        return;
      }
      goTo(1);
    } catch (err) {
      setLookupError(parseApiError(err, "링크를 확인하지 못했습니다.").message);
    } finally {
      setChecking(false);
    }
  };

  const submitTitle = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const t = title.trim();
    const error = !t ? "공연 제목을 입력해주세요." : t.length > TITLE_MAX ? `공연 제목은 ${TITLE_MAX}자 이하로 입력해주세요.` : undefined;
    setTitleError(error);
    if (!error) goTo(2);
  };

  const confirmVenue = () => {
    if (!venue) {
      setVenueError("공연장을 선택해주세요.");
      return;
    }
    setVenueError(undefined);
    goTo(3);
  };

  const addSession = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    // 10분 단위가 아니면(직접 입력·붙여넣기) 거부
    const stepError = validateSessionTimeStep(sessionInput);
    if (stepError) {
      setSessionError(stepError);
      return;
    }
    const normalized = normalizeLocalDateTime(sessionInput)!;
    if (isPastKst(normalized)) {
      // 백엔드도 지난 일시는 거부한다 — 미리 막는다
      setSessionError("이미 지난 시각이에요. 앞으로 있을 회차만 등록할 수 있어요.");
      return;
    }
    if (sessions.includes(normalized)) {
      setSessionError("이미 추가한 회차예요.");
      return;
    }
    if (sessions.length >= SESSIONS_MAX) {
      setSessionError(`회차는 한 번에 ${SESSIONS_MAX}개까지 등록할 수 있어요.`);
      return;
    }
    setSessions((prev) => [...prev, normalized].sort(compareLocalDateTime));
    setSessionInput("");
    setSessionError(undefined);
  };

  const removeSession = (value: string) => setSessions((prev) => prev.filter((s) => s !== value));

  const confirmSessions = () => {
    if (sessions.length === 0) {
      setSessionError("회차를 1개 이상 추가해주세요.");
      return;
    }
    // 단계를 오가는 사이 지난 회차가 생겼으면 알려준다
    if (sessions.some((s) => isPastKst(s))) {
      setSessionError("지난 회차가 있어요. 삭제한 뒤 진행해주세요.");
      return;
    }
    setSessionError(undefined);
    goTo(4);
  };

  const submit = async () => {
    if (submitting || !venue) return;
    setSubmitting(true);
    setSubmitError(null);
    setConflictId(null);
    try {
      const created = await performancesApi.create({
        sourceUrl: sourceUrl.trim(),
        title: title.trim(),
        venueId: venue.id,
        sessions,
      });
      navigate(`/performances/${created.id}`, { replace: true });
    } catch (err) {
      const existing = getConflictPerformanceId(err);
      const parsed = parseApiError(err, "공연을 등록하지 못했습니다.");
      if (existing !== null) setConflictId(existing);
      const field = Object.keys(parsed.fieldErrors).find((k) => k in FIELD_STEP);
      setSubmitError({ message: parsed.message, step: field ? FIELD_STEP[field] : undefined });
      setSubmitting(false);
    }
  };

  let body: ReactNode;
  if (step === 0) {
    body = (
      <form className={cls.stepBody} onSubmit={submitUrl} noValidate>
        <TextField
          id="perf-source-url"
          label="티켓팅 페이지 링크"
          type="url"
          inputMode="url"
          autoComplete="url"
          autoCapitalize="none"
          spellCheck={false}
          value={sourceUrl}
          onChange={(e) => {
            setSourceUrl(e.target.value);
            setExistingId(null);
          }}
          disabled={checking}
          error={urlError}
          hint="공연을 예매한 티켓팅 사이트의 공연 페이지 주소를 붙여 넣어 주세요."
          placeholder="https://"
        />
        {/* 안내 문구는 항상 있는 라이브 영역, 이동 버튼은 별도로 조건부 렌더링 */}
        <p className={liveRegionClass(existingId !== null ? "exists" : "", ui.notice)} role="status">
          {existingId !== null
            ? "이미 등록된 공연이에요. 새로 등록하지 않고 기존 공연에서 회차를 추가하거나 교환할 수 있어요."
            : ""}
        </p>
        {existingId !== null && (
          <div>
            <Link to={`/performances/${existingId}`} className={linkButton.solid}>
              등록된 공연 보기
            </Link>
          </div>
        )}
        {lookupError && (
          <p className={ui.errorBox} role="alert">
            {lookupError}
          </p>
        )}
        <div className="flex justify-end">
          <button type="submit" className={button.solid} disabled={checking} aria-busy={checking}>
            {checking ? "확인 중..." : "다음"}
          </button>
        </div>
      </form>
    );
  } else if (step === 1) {
    body = (
      <form className={cls.stepBody} onSubmit={submitTitle} noValidate>
        <TextField
          id="perf-title"
          label="공연 제목"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          error={titleError}
          hint={`티켓팅 사이트에 표시된 제목 그대로 (${TITLE_MAX}자 이하)`}
          maxLength={TITLE_MAX}
        />
        <div className={cls.actions}>
          <button type="button" className={button.outline} onClick={() => goTo(0)}>
            이전
          </button>
          <button type="submit" className={button.solid}>
            다음
          </button>
        </div>
      </form>
    );
  } else if (step === 2) {
    // VenuePicker 안에 공연장 추가 <form>이 있으므로 이 단계는 form으로 감싸지 않는다
    body = (
      <div className={cls.stepBody}>
        <VenuePicker
          idPrefix="perf-new"
          selected={venue}
          onSelect={(v) => {
            setVenue(v);
            setVenueError(undefined);
          }}
          error={venueError}
        />
        <div className={cls.actions}>
          <button type="button" className={button.outline} onClick={() => goTo(1)}>
            이전
          </button>
          <button type="button" className={button.solid} onClick={confirmVenue}>
            다음
          </button>
        </div>
      </div>
    );
  } else if (step === 3) {
    body = (
      <div className={cls.stepBody}>
        <form className="flex flex-col gap-2" onSubmit={addSession} noValidate>
          <div className="flex flex-wrap items-end gap-2">
            <SessionDateTimePicker
              legend="회차 날짜·시간 (한국 시간)"
              value={sessionInput}
              onChange={setSessionInput}
              invalid={!!sessionError}
              describedBy={`perf-session-hint${sessionError ? " perf-session-error" : ""}`}
            />
            <button type="submit" className={button.outline}>
              회차 추가
            </button>
          </div>
          {sessionError && (
            <p id="perf-session-error" className={ui.fieldError}>
              {sessionError}
            </p>
          )}
          {/* 안내는 오류가 있어도 계속 보이고 aria-describedby로 연결된다 */}
          <p id="perf-session-hint" className={ui.hint}>
            {/* TODO: Hint 지우기, 교환은 같은 공연의 다른 회차랑도 가능. */}
            {/* {SESSION_TIME_STEP_HINT}  */}같은 공연의 여러 회차를 모두 추가해 주세요. 교환은 같은 회차끼리만 할 수 있어요.
          </p>
        </form>

        <p className={ui.status} role="status">
          {sessions.length > 0 ? `추가한 회차 ${sessions.length}개` : "아직 추가한 회차가 없어요."}
        </p>
        {sessions.length > 0 && (
          <ul className={cls.sessionList} aria-label="추가한 회차">
            {sessions.map((s) => (
              <li key={s} className={cls.sessionRow}>
                <span className={ui.body}>{formatKstDateTime(s)}</span>
                <button
                  type="button"
                  className={button.outline}
                  onClick={() => removeSession(s)}
                  aria-label={`${formatKstDateTime(s)} 회차 삭제`}
                >
                  삭제
                </button>
              </li>
            ))}
          </ul>
        )}

        <div className={cls.actions}>
          <button type="button" className={button.outline} onClick={() => goTo(2)}>
            이전
          </button>
          <button type="button" className={button.solid} onClick={confirmSessions}>
            다음
          </button>
        </div>
      </div>
    );
  } else {
    body = (
      <div className={cls.stepBody}>
        <dl className={cls.summary}>
          <dt className={cls.summaryTerm}>링크</dt>
          <dd className={cls.summaryDesc}>{sourceUrl.trim()}</dd>
          <dt className={cls.summaryTerm}>제목</dt>
          <dd className={cls.summaryDesc}>{title.trim()}</dd>
          <dt className={cls.summaryTerm}>공연장</dt>
          <dd className={cls.summaryDesc}>
            {venue?.name}
            {venue?.address ? ` (${venue.address})` : ""}
          </dd>
          <dt className={cls.summaryTerm}>회차</dt>
          <dd className={cls.summaryDesc}>
            <ul>
              {sessions.map((s) => (
                <li key={s}>{formatKstDateTime(s)}</li>
              ))}
            </ul>
          </dd>
        </dl>

        {submitError && (
          <div className={`${ui.errorBox} flex flex-col items-start gap-2`} role="alert">
            <span>{submitError.message}</span>
            {conflictId !== null && (
              <Link to={`/performances/${conflictId}`} className={linkButton.outline}>
                등록된 공연 보기
              </Link>
            )}
            {submitError.step !== undefined && (
              <button type="button" className={button.outline} onClick={() => goTo(submitError.step!)}>
                {STEPS[submitError.step]} 단계로 이동
              </button>
            )}
          </div>
        )}

        <div className={cls.actions}>
          <button type="button" className={button.outline} onClick={() => goTo(3)} disabled={submitting}>
            이전
          </button>
          <button type="button" className={button.solid} onClick={submit} disabled={submitting} aria-busy={submitting}>
            {submitting ? "등록 중..." : "공연 등록"}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>공연 등록</h1>

        <ol className={cls.steps} aria-label="등록 단계">
          {STEPS.map((name, index) => (
            <li key={name} className={stepClass(index, step)} aria-current={index === step ? "step" : undefined}>
              {index < step && (
                <>
                  <span aria-hidden="true">✓ </span>
                  <span className="sr-only">(완료) </span>
                </>
              )}
              {index + 1}. {name}
            </li>
          ))}
        </ol>

        <section className={`${ui.card} flex flex-col gap-4`} aria-labelledby="perf-step-title">
          <h2 id="perf-step-title" ref={headingRef} tabIndex={-1} className={`${ui.sectionTitle} outline-none`}>
            {step + 1}단계 · {STEPS[step]}
          </h2>
          {body}
        </section>
      </div>
    </div>
  );
}
