import { getErrorCode, parseApiError, type ParsedApiError } from "./errors";
import type { ExtraType } from "../types/exchange";

// 교환 화면 공용: 서버 오류 코드 -> 사용자 안내 문구, 추가금 표기.
// 코드에 맞는 문구가 없으면 서버 message(한국어)를 그대로 쓴다.

const CODE_MESSAGES: Record<string, string> = {
  MATCH_ALREADY_OPEN: "이미 이 상대와 진행 중인 매칭이 있어요.",
  NOT_A_CANDIDATE: "조건이 바뀌어 더 이상 후보가 아니에요. 후보 목록을 새로 확인해 주세요.",
  TICKET_LOCKED: "내 티켓이 다른 상대와 예약되어 있어요. 그 예약을 취소하기 전에는 새로 제안할 수 없어요.",
  TICKET_ALREADY_RESERVED: "이 티켓이 이미 다른 매칭에서 예약되어 있어요.",
  MATCH_STATE_CONFLICT: "이미 상태가 바뀐 매칭이에요. 목록을 새로 불러와 주세요.",
  ACTIVE_MATCH_EXISTS: "진행 중인 매칭이 있어서 조건을 바꾸거나 지울 수 없어요. 매칭을 먼저 취소해 주세요.",
  MATCH_HISTORY_EXISTS: "완료된 교환 기록이 있어서 지울 수 없어요.",
  TICKET_NOT_ACTIVE: "내린 티켓이거나 닫힌 교환 요청이에요.",
  SESSION_CLOSED: "회차 당일이 지나 마감된 티켓이에요.",
  WANT_INCLUDES_OWN_SEAT: "희망 회차가 내 티켓의 회차뿐이면 희망 좌석에 내 자리를 넣을 수 없어요.",
  TICKET_RESERVED: "예약된 티켓은 내릴 수 없어요. 매칭을 먼저 취소해 주세요.",
  REQUEST_ALREADY_EXISTS: "이 티켓에는 이미 교환 조건이 있어요. 화면을 새로 열어 수정해 주세요.",
  SEAT_ALREADY_REGISTERED: "이미 등록된 좌석이에요.",
  MY_TICKET_ALREADY_REGISTERED: "이미 내가 등록한 좌석이에요.",
  TICKET_LIMIT_REACHED: "등록할 수 있는 티켓 수를 넘었어요. 쓰지 않는 티켓을 내린 뒤 다시 시도해 주세요.",
  BUSY: "요청이 몰려 처리하지 못했어요. 잠시 후 다시 시도해주세요.",
};

/** 코드별 문구가 있으면 그것을, 없으면 서버 message를 쓴다. code는 화면이 분기할 때 쓴다 */
export function exchangeErrorMessage(error: unknown, fallback: string): ParsedApiError & { code: string | null } {
  const parsed = parseApiError(error, fallback);
  const code = getErrorCode(error);
  const mapped = code ? CODE_MESSAGES[code] : undefined;
  return { ...parsed, message: mapped ?? parsed.message, code };
}

export function formatWon(n: number): string {
  return `${n.toLocaleString("ko-KR")}원`;
}

/** 추가금 조건 표기: X='추가금 없이', ANY='상관없음', POS='받고 싶어요 N원', NEG='낼 수 있어요 N원'(절댓값) */
export function formatExtra(type: ExtraType, amount: number | null): string {
  switch (type) {
    case "X":
      return "추가금 없이";
    case "ANY":
      return "상관없음";
    case "POS":
      return amount === null ? "받고 싶어요" : `받고 싶어요 ${formatWon(Math.abs(amount))}`;
    case "NEG":
      return amount === null ? "낼 수 있어요" : `낼 수 있어요 ${formatWon(Math.abs(amount))}`;
  }
}
