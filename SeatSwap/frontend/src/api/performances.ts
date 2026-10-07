import axios from "axios";
import { apiClient } from "./client";
import type { PageResponse } from "../types/page";
import type {
  PerformanceCreateRequest,
  PerformanceDetail,
  PerformanceListParams,
  PerformanceLookup,
  PerformanceSession,
  PerformanceSummary,
  PerformanceUpdateRequest,
  SessionRequest,
} from "../types/performance";

// 공연·회차 (FR-02). 모두 로그인 필요 — 401은 client.ts 인터셉터가 재발급·재시도 처리.
// 오류: 400(필드/메시지), 403(등록자 아님), 404, 409({message} 또는 {message, performanceId}).
export const performancesApi = {
  /** GET /api/performances?query=&page= */
  async list(params: PerformanceListParams, signal?: AbortSignal): Promise<PageResponse<PerformanceSummary>> {
    const query = params.query?.trim();
    const { data } = await apiClient.get<PageResponse<PerformanceSummary>>("/performances", {
      // 빈 값은 보내지 않는다
      params: { query: query || undefined, page: params.page ?? 0, asOf: params.asOf },
      signal,
    });
    return data;
  },

  async get(id: number, signal?: AbortSignal): Promise<PerformanceDetail> {
    const { data } = await apiClient.get<PerformanceDetail>(`/performances/${id}`, { signal });
    return data;
  },

  /** GET /api/performances/lookup?sourceUrl= — 같은 티켓팅 링크로 이미 등록된 공연이 있는지 */
  async lookup(sourceUrl: string): Promise<PerformanceLookup> {
    const { data } = await apiClient.get<PerformanceLookup>("/performances/lookup", { params: { sourceUrl } });
    return data;
  },

  /** POST /api/performances → 201 상세. 이미 등록된 공연이면 409 {message, performanceId} */
  async create(payload: PerformanceCreateRequest): Promise<PerformanceDetail> {
    const { data } = await apiClient.post<PerformanceDetail>("/performances", payload);
    return data;
  },

  /** PATCH /api/performances/{id} (등록자만) → 200 상세 */
  async update(id: number, payload: PerformanceUpdateRequest): Promise<PerformanceDetail> {
    const { data } = await apiClient.patch<PerformanceDetail>(`/performances/${id}`, payload);
    return data;
  },

  /** DELETE /api/performances/{id} (등록자만) → 204. 티켓이 있으면 409 */
  async remove(id: number): Promise<void> {
    await apiClient.delete(`/performances/${id}`);
  },

  /** POST /api/performances/{id}/sessions (로그인 사용자 누구나) → 201. 같은 시각이 있으면 409 */
  async addSession(id: number, startsAt: string): Promise<PerformanceSession> {
    const body: SessionRequest = { startsAt };
    const { data } = await apiClient.post<PerformanceSession>(`/performances/${id}/sessions`, body);
    return data;
  },

  /** PATCH /api/performances/{id}/sessions/{sessionId} (등록자만). 티켓이 있으면 409. 응답 본문은 쓰지 않는다 */
  async updateSession(id: number, sessionId: number, startsAt: string): Promise<void> {
    const body: SessionRequest = { startsAt };
    await apiClient.patch(`/performances/${id}/sessions/${sessionId}`, body);
  },

  /** DELETE /api/performances/{id}/sessions/{sessionId} (등록자만). 티켓이 있으면 409 */
  async removeSession(id: number, sessionId: number): Promise<void> {
    await apiClient.delete(`/performances/${id}/sessions/${sessionId}`);
  },
};

/** 409 응답이 이미 등록된 공연을 가리키면 그 공연 id, 아니면 null */
export function getConflictPerformanceId(error: unknown): number | null {
  if (!axios.isAxiosError(error) || error.response?.status !== 409) return null;
  const data: unknown = error.response.data;
  if (typeof data !== "object" || data === null) return null;
  const id: unknown = (data as Record<string, unknown>).performanceId;
  return typeof id === "number" && Number.isFinite(id) ? id : null;
}

/** 응답 상태 코드 (axios 오류가 아니거나 응답이 없으면 null) */
export function getErrorStatus(error: unknown): number | null {
  return axios.isAxiosError(error) ? (error.response?.status ?? null) : null;
}
