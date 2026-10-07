import axios from "axios";
import { apiClient } from "./client";
import { parseApiError } from "./errors";
import type { SeatMap, SeatMapSummary, SeatMapUploadParams } from "../types/seatmap";

// 좌석표 (UC-03/04). 로그인 필요 — 401은 client.ts 인터셉터가 처리한다.
// 원본 이미지는 서버에 저장되지 않는다(인식 중 메모리에서만 처리 후 폐기).

/** 인식은 최대 수십 초 걸릴 수 있어 기본 타임아웃보다 길게 둔다 */
export const RECOGNIZE_TIMEOUT_MS = 90_000;

export const seatMapApi = {
  /** GET /api/seatmaps/{id} — 404면 없는 좌석표 */
  async get(id: number, signal?: AbortSignal): Promise<SeatMap> {
    const { data } = await apiClient.get<SeatMap>(`/seatmaps/${id}`, { signal });
    return data;
  },

  /** GET /api/venues/{venueId}/seatmaps */
  async listByVenue(venueId: number, signal?: AbortSignal): Promise<SeatMapSummary[]> {
    const { data } = await apiClient.get<SeatMapSummary[]>(`/venues/${venueId}/seatmaps`, { signal });
    return data;
  },

  /**
   * POST /api/venues/{venueId}/seatmaps (multipart) → 201.
   * 오류: 400/413/415(파일), 422 {code,message}, 409 {message,seatMapId}, 502/503(일시 장애)
   */
  async upload(venueId: number, params: SeatMapUploadParams, signal?: AbortSignal): Promise<SeatMap> {
    const form = new FormData();
    form.append("file", params.file);
    const zoneName = params.zoneName?.trim();
    if (zoneName) form.append("zoneName", zoneName);
    if (params.aisleMode) form.append("aisleMode", params.aisleMode);
    // Content-Type은 axios가 boundary와 함께 설정한다
    const { data } = await apiClient.post<SeatMap>(`/venues/${venueId}/seatmaps`, form, {
      timeout: RECOGNIZE_TIMEOUT_MS,
      signal,
    });
    return data;
  },

  // TODO: 임시 기능
  // TEMP-DRAFT-DELETE: 테스트용 임시 기능 — DELETE /api/seatmaps/{id} → 204 (DRAFT만. 404=없음/기능 비활성, 409=OFFICIAL·참조 중)
  async remove(id: number): Promise<void> {
    await apiClient.delete(`/seatmaps/${id}`);
  },
};

export interface SeatMapErrorInfo {
  status: number | null;
  /** 422 등에서 백엔드가 내려주는 오류 코드 (예: NO_SEATS_DETECTED, IMAGE_TOO_COMPLEX) */
  code: string | null;
  /** 409(이미 DRAFT 있음)에서 가리키는 기존 좌석표 id */
  seatMapId: number | null;
  /** 서버가 준 메시지 또는 기본 문구 (parseApiError와 동일) */
  message: string;
  /** 타임아웃(응답 없음) 여부 */
  timedOut: boolean;
}

/** 좌석표 API 오류에서 상태·코드·seatMapId를 꺼낸다 (parseApiError 확장) */
export function parseSeatMapError(error: unknown, fallback: string): SeatMapErrorInfo {
  const message = parseApiError(error, fallback).message;
  if (!axios.isAxiosError(error)) return { status: null, code: null, seatMapId: null, message, timedOut: false };

  const timedOut = error.code === "ECONNABORTED" && !error.response;
  const data: unknown = error.response?.data;
  let code: string | null = null;
  let seatMapId: number | null = null;
  if (typeof data === "object" && data !== null && !Array.isArray(data)) {
    const record = data as Record<string, unknown>;
    if (typeof record.code === "string") code = record.code;
    if (typeof record.seatMapId === "number" && Number.isFinite(record.seatMapId)) seatMapId = record.seatMapId;
  }
  return { status: error.response?.status ?? null, code, seatMapId, message, timedOut };
}
