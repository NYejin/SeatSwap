// API 시각 문자열 변환·표시 (공연 회차 등).
// 백엔드 계약: 회차 시각은 오프셋 없는 KST 현지 시각 "yyyy-MM-ddTHH:mm".
// 사용자 기기 시간대와 무관하게 KST로 해석·표시한다 (해외 접속이어도 공연 현지 시각 기준).

const LOCAL_DATE_TIME = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::\d{2}(?:\.\d+)?)?$/;
const KST_OFFSET_HOURS = 9;

/** "yyyy-MM-ddTHH:mm[:ss]" → 분 단위 "yyyy-MM-ddTHH:mm". 형식이 아니면 null */
export function normalizeLocalDateTime(value: string): string | null {
  const m = LOCAL_DATE_TIME.exec(value.trim());
  if (!m) return null;
  const [, y, mo, d, h, mi] = m;
  const epoch = toEpoch(+y, +mo, +d, +h, +mi);
  // 2월 30일 같은 존재하지 않는 날짜 거르기
  const check = new Date(epoch + KST_OFFSET_HOURS * 3_600_000);
  if (check.getUTCFullYear() !== +y || check.getUTCMonth() + 1 !== +mo || check.getUTCDate() !== +d) return null;
  if (+h > 23 || +mi > 59) return null;
  return `${y}-${mo}-${d}T${h}:${mi}`;
}

function toEpoch(y: number, mo: number, d: number, h: number, mi: number): number {
  return Date.UTC(y, mo - 1, d, h - KST_OFFSET_HOURS, mi);
}

/** KST 현지 시각 문자열의 epoch ms. 형식이 아니면 null */
export function kstEpoch(value: string): number | null {
  const normalized = normalizeLocalDateTime(value);
  if (!normalized) return null;
  const [date, time] = normalized.split("T");
  const [y, mo, d] = date.split("-").map(Number);
  const [h, mi] = time.split(":").map(Number);
  return toEpoch(y, mo, d, h, mi);
}

const MINUTE_MS = 60_000;

/**
 * 지금보다 이전 시각인지 (형식 오류는 false).
 * 백엔드와 같이 분 단위로 절삭해 비교한다 — 현재 분(예: 19:00:30에 "19:00")은 지난 시각이 아니다.
 */
export function isPastKst(value: string, now: number = Date.now()): boolean {
  const epoch = kstEpoch(value);
  return epoch !== null && epoch < Math.floor(now / MINUTE_MS) * MINUTE_MS;
}

/** 지금(또는 주어진 epoch)을 KST 분 단위 "yyyy-MM-ddTHH:mm"으로 (목록 기준 시각 asOf 등) */
export function kstMinuteString(now: number = Date.now()): string {
  const d = new Date(Math.floor(now / MINUTE_MS) * MINUTE_MS + KST_OFFSET_HOURS * 3_600_000);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
}

const KST_FORMAT = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "long",
  day: "numeric",
  weekday: "short",
  hour: "numeric",
  minute: "2-digit",
});

/** 한국어 표기: "2026년 11월 1일 (일) 오후 7:00". 형식이 아니면 원문 그대로 */
export function formatKstDateTime(value: string): string {
  const epoch = kstEpoch(value);
  if (epoch === null) return value;
  const parts = KST_FORMAT.formatToParts(new Date(epoch));
  const get = (type: Intl.DateTimeFormatPartTypes) => parts.find((p) => p.type === type)?.value ?? "";
  return `${get("year")}년 ${get("month")} ${get("day")}일 (${get("weekday")}) ${get("dayPeriod")} ${get("hour")}:${get("minute")}`;
}

/** 시각 오름차순 비교 */
export function compareLocalDateTime(a: string, b: string): number {
  return (kstEpoch(a) ?? 0) - (kstEpoch(b) ?? 0);
}
