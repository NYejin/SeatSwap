import { apiClient } from "./client";
import type { Venue, VenueCreateRequest, VenueCreateResult } from "../types/performance";

// 공연장 (FR-02). 모두 로그인 필요 — 401은 client.ts 인터셉터가 재발급·재시도 처리.
export const venuesApi = {
  /** GET /api/venues?query= — 이름 검색, 최대 20개 */
  async search(query: string, signal?: AbortSignal): Promise<Venue[]> {
    const { data } = await apiClient.get<Venue[]>("/venues", { params: { query }, signal });
    return data;
  },

  /** POST /api/venues — 201 새로 생성 / 200 같은 공연장이 이미 있음 (둘 다 공연장 반환) */
  async create(payload: VenueCreateRequest): Promise<VenueCreateResult> {
    const response = await apiClient.post<Venue>("/venues", payload);
    return { venue: response.data, created: response.status === 201 };
  },
};
