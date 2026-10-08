// 교환 희망 조건·후보·매칭 (FR-04). 백엔드 계약(SeatSwap/backend/README.md)과 1:1 대응.
// 좌석표(좌표) 개념은 없다 — 좌석은 (구역, 열, 번) 텍스트다.

/** 추가금 유형: X=추가금 없이 / ANY=상관없음 / POS=받고 싶어요(금액 양수) / NEG=낼 수 있어요(금액 음수로 전송) */
export type ExtraType = "X" | "ANY" | "POS" | "NEG";

export interface WantSessionItem {
  sessionId: number;
  priority: number;
  startsAt: string;
}

/** 희망 범위 1건. 서버 응답의 열·번은 정규화 값(`03열` -> `3`) */
export interface WantRange {
  zone: string;
  rowFrom: string;
  rowTo: string;
  colFrom: string;
  colTo: string;
}

/** GET /api/exchange/requests/me 항목, POST/PATCH 응답 */
export interface ExchangeRequest {
  id: number;
  ticketId: number;
  /** OPEN | CLOSED */
  status: string;
  extraType: ExtraType;
  extraAmount: number | null;
  wantSessions: WantSessionItem[];
  ranges: WantRange[];
  /** 겹침 제거 후 펼친 희망 좌석 수 */
  wantSeatCount: number;
  createdAt: string;
  updatedAt: string;
}

/** PATCH 본문 (POST는 ticketId 추가). extraAmount: POS 양수, NEG 음수, X/ANY는 보내지 않는다 */
export interface ExchangeRequestPayload {
  extraType: ExtraType;
  extraAmount?: number;
  wantSessions: { sessionId: number; priority: number }[];
  ranges: WantRange[];
}

export interface ExchangeRequestCreatePayload extends ExchangeRequestPayload {
  ticketId: number;
}

/** 참고용 금액 구간 (매칭 여부와 무관) */
export interface SettlementHint {
  min: number;
  max: number;
}

/** GET /api/exchange/requests/{id}/candidates 항목 */
export interface ExchangeCandidate {
  requestId: number;
  ticketId: number;
  zone: string;
  row: string;
  col: string;
  sessionId: number;
  startsAt: string;
  nickname: string;
  /** 내 희망 회차 중 상대 티켓 회차의 우선순위 (1이 가장 높음) */
  wantPriority: number;
  /** 상대의 추가금 */
  extraType: ExtraType;
  extraAmount: number | null;
  /** 내 추가금 */
  myExtraType: ExtraType;
  myExtraAmount: number | null;
  settlementHint: SettlementHint | null;
  requestedAt: string;
}

export type MatchStatus = "CHATTING" | "RESERVED" | "COMPLETED" | "CANCELED";
/** A=제안한 쪽, B=제안받은 쪽 */
export type MatchSide = "A" | "B";
export type MatchRole = "SENT" | "RECEIVED";
export type CanceledBy = "ME" | "COUNTERPART" | "SYSTEM";

/** 매칭에 걸린 자리 하나 (목록 응답) */
export interface MatchSeat {
  zone: string;
  row: string;
  col: string;
  sessionId: number;
  startsAt: string;
}

/**
 * 매칭 (호출자 기준). POST proposals/accept/reject/cancel 응답, GET /matches/me 항목, GET /matches/{id}가 같은 모양이다.
 * myReservedAt/counterpartReservedAt이 null이 아니면 그쪽이 '이 사람과 교환할게요'를 누른 것이다.
 */
export interface ExchangeMatch {
  id: number;
  status: MatchStatus;
  mySide: MatchSide;
  role: MatchRole;
  myRequestId: number;
  myTicketId: number;
  mySeat: MatchSeat;
  counterpartRequestId: number;
  counterpartTicketId: number;
  counterpartSeat: MatchSeat;
  counterpartNickname: string;
  myExtraType: ExtraType;
  myExtraAmount: number | null;
  counterpartExtraType: ExtraType;
  counterpartExtraAmount: number | null;
  myReservedAt: string | null;
  counterpartReservedAt: string | null;
  canceledBy: CanceledBy | null;
  canceledAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface MatchListParams {
  role?: MatchRole | "ALL";
  status?: MatchStatus;
  page?: number;
  size?: number;
}
