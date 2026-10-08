import { apiClient } from "./client";
import type { PageResponse } from "../types/page";
import type {
  ExchangeCandidate,
  ExchangeMatch,
  ExchangeRequest,
  ExchangeRequestCreatePayload,
  ExchangeRequestPayload,
  MatchListParams,
} from "../types/exchange";

// 교환 희망 조건·후보·매칭 (FR-04). 모두 로그인 필요. 계약은 SeatSwap/backend/README.md.
// 매칭 응답(제안·동의·거절·취소·목록·단건)은 모두 같은 ExchangeMatch 모양이다.
export const exchangeApi = {
  // ---- 희망 조건 ----
  /** GET /api/exchange/requests/me?ticketId= — 내 요청 목록 (배열, id 오름차순) */
  async listMyRequests(ticketId?: number, signal?: AbortSignal): Promise<ExchangeRequest[]> {
    const { data } = await apiClient.get<ExchangeRequest[]>("/exchange/requests/me", {
      params: { ticketId },
      signal,
    });
    return data;
  },

  /** POST /api/exchange/requests → 201 */
  async createRequest(payload: ExchangeRequestCreatePayload): Promise<ExchangeRequest> {
    const { data } = await apiClient.post<ExchangeRequest>("/exchange/requests", payload);
    return data;
  },

  /** PATCH /api/exchange/requests/{id} — 범위(범위별 추가금 포함)·희망 회차 전체 교체 */
  async updateRequest(id: number, payload: ExchangeRequestPayload): Promise<ExchangeRequest> {
    const { data } = await apiClient.patch<ExchangeRequest>(`/exchange/requests/${id}`, payload);
    return data;
  },

  /** DELETE /api/exchange/requests/{id} → 204. 요청은 소프트 삭제(채팅 단계 매칭은 자동 취소). 예약된 매칭이 있으면 409 ACTIVE_MATCH_EXISTS */
  async deleteRequest(id: number): Promise<void> {
    await apiClient.delete(`/exchange/requests/${id}`);
  },

  // ---- 후보 ----
  /** GET /api/exchange/requests/{id}/candidates?page&size */
  async candidates(
    requestId: number,
    page: number,
    size = 20,
    signal?: AbortSignal
  ): Promise<PageResponse<ExchangeCandidate>> {
    const { data } = await apiClient.get<PageResponse<ExchangeCandidate>>(
      `/exchange/requests/${requestId}/candidates`,
      { params: { page, size }, signal }
    );
    return data;
  },

  // ---- 매칭 ----
  /** POST /api/exchange/requests/{id}/proposals → 201. 후보를 골라 매칭(채팅 단계)을 만든다 */
  async propose(requestId: number, targetRequestId: number): Promise<ExchangeMatch> {
    const { data } = await apiClient.post<ExchangeMatch>(`/exchange/requests/${requestId}/proposals`, {
      targetRequestId,
    });
    return data;
  },

  /** POST /api/exchange/matches/{id}/accept — '이 사람과 교환할게요' (예약 동의) */
  async accept(matchId: number): Promise<ExchangeMatch> {
    const { data } = await apiClient.post<ExchangeMatch>(`/exchange/matches/${matchId}/accept`);
    return data;
  },

  /** POST /api/exchange/matches/{id}/reject — 제안받은 쪽의 거절 (제안한 쪽이 부르면 403) */
  async reject(matchId: number): Promise<ExchangeMatch> {
    const { data } = await apiClient.post<ExchangeMatch>(`/exchange/matches/${matchId}/reject`);
    return data;
  },

  /** POST /api/exchange/matches/{id}/cancel — 참여자 누구나 취소 */
  async cancel(matchId: number): Promise<ExchangeMatch> {
    const { data } = await apiClient.post<ExchangeMatch>(`/exchange/matches/${matchId}/cancel`);
    return data;
  },

  /** GET /api/exchange/matches/me?role=&status=&page=&size= */
  async listMatches(params: MatchListParams, signal?: AbortSignal): Promise<PageResponse<ExchangeMatch>> {
    const { data } = await apiClient.get<PageResponse<ExchangeMatch>>("/exchange/matches/me", {
      params: { role: params.role ?? "ALL", status: params.status, page: params.page ?? 0, size: params.size ?? 20 },
      signal,
    });
    return data;
  },

  /** GET /api/exchange/matches/{id} */
  async getMatch(id: number, signal?: AbortSignal): Promise<ExchangeMatch> {
    const { data } = await apiClient.get<ExchangeMatch>(`/exchange/matches/${id}`, { signal });
    return data;
  },
};
