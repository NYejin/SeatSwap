// 공연·공연장·회차 (FR-02 공연 등록). 백엔드 계약(2026-10-06 기준)과 1:1 대응.
// 시각(startsAt 등)은 KST 현지 시각 문자열 "yyyy-MM-ddTHH:mm" (초가 붙어 와도 api/dateTime.ts가 허용).

export interface Venue {
  id: number;
  name: string;
  address: string | null;
}

/** 목록 등에 쓰이는 공연장 요약 */
export interface VenueSummary {
  id: number;
  name: string;
}

/** POST /api/venues */
export interface VenueCreateRequest {
  name: string;
  address?: string;
}

/** POST /api/venues 결과 — 201이면 새로 생성, 200이면 같은 공연장이 이미 있어 그것을 돌려줌 */
export interface VenueCreateResult {
  venue: Venue;
  created: boolean;
}

export interface PerformanceSession {
  id: number;
  startsAt: string;
}

/** GET /api/performances 목록 항목 */
export interface PerformanceSummary {
  id: number;
  title: string;
  venue: VenueSummary;
  /** 지금 이후 가장 이른 회차. 없으면 null */
  nextSessionStartsAt: string | null;
  sessionCount: number;
}

/** GET /api/performances/{id} */
export interface PerformanceDetail {
  id: number;
  title: string;
  sourceUrl: string;
  venue: Venue;
  registrant: { id: number; nickname: string };
  /** 현재 사용자가 등록자인지 (제목·공연장 수정, 회차 수정·삭제, 공연 삭제 노출용 — 서버도 동일하게 검사) */
  canEdit: boolean;
  sessions: PerformanceSession[];
  createdAt: string;
}

/** GET /api/performances/lookup — 없으면 performanceId가 null이거나 생략될 수 있다 */
export interface PerformanceLookup {
  exists: boolean;
  performanceId?: number | null;
}

export interface PerformanceListParams {
  query?: string;
  venueId?: number;
  /** 0부터 */
  page?: number;
  /**
   * 목록 기준 시각(KST "yyyy-MM-ddTHH:mm"). 첫 페이지 요청 때 고정해 "더 보기"에도 같은 값을 보내
   * 페이지 사이에 "다음 회차" 판정 기준이 바뀌지 않게 한다 (서버가 모르면 무시)
   */
  asOf?: string;
}

/** POST /api/performances */
export interface PerformanceCreateRequest {
  sourceUrl: string;
  title: string;
  venueId: number;
  /** 회차 시작 시각 목록 (1개 이상) */
  sessions: string[];
}

/** PATCH /api/performances/{id} — 보낸 항목만 바뀐다 */
export interface PerformanceUpdateRequest {
  title?: string;
  venueId?: number;
}

/** POST·PATCH 회차 */
export interface SessionRequest {
  startsAt: string;
}
