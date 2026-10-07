import axios from "axios";
import { apiClient } from "./client";
import { parseApiError } from "./errors";
import type {
  SeatCorrectionRequest,
  SeatCorrectionResponse,
  SeatMap,
  SeatMapSummary,
  SeatMapUploadParams,
  UpdateSeatsRequest,
} from "../types/seatmap";

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

  /**
   * PATCH /api/seatmaps/{id}/seats → 200 SeatMapResponse(새 version).
   * DRAFT는 로그인 사용자 누구나, OFFICIAL은 ADMIN만. 오류는 parseSeatEditError로 해석한다.
   */
  async updateSeats(id: number, body: UpdateSeatsRequest): Promise<SeatMap> {
    const { data } = await apiClient.patch<SeatMap>(`/seatmaps/${id}/seats`, body);
    return data;
  },

  /** POST /api/seatmaps/{id}/corrections → 201. OFFICIAL만 (DRAFT는 409 — 직접 수정 안내) */
  async reportCorrection(id: number, body: SeatCorrectionRequest): Promise<SeatCorrectionResponse> {
    const { data } = await apiClient.post<SeatCorrectionResponse>(`/seatmaps/${id}/corrections`, body);
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

export interface SeatEditErrorInfo {
  /** conflict: 다른 사람이 먼저 수정함(새로고침 필요) */
  kind: "conflict" | "message";
  message: string;
}

/** 번호 수정(PATCH) 오류를 한국어 안내로 바꾼다. 403·중복 번호는 서버 메시지를 우선 쓴다 */
export function parseSeatEditError(error: unknown): SeatEditErrorInfo {
  const info = parseSeatMapError(error, "번호를 저장하지 못했습니다.");
  if (info.status === 409 && info.code === "VERSION_CONFLICT") {
    return { kind: "conflict", message: "다른 사용자가 먼저 수정했어요. 새로고침 후 다시 시도해 주세요." };
  }
  if (info.status === 422) {
    if (info.code === "UNKNOWN_SEAT") {
      return { kind: "message", message: "존재하지 않는 좌석이 포함돼 있어요. 새로고침 후 다시 시도해 주세요." };
    }
    if (info.code === "DUPLICATE_SEAT_NUMBER") {
      return { kind: "message", message: info.message || "겹치는 좌석 번호가 있어요. 번호를 확인해 주세요." };
    }
  }
  if (info.status === 403) {
    return { kind: "message", message: info.message || "이 좌석표는 수정할 수 없어요. 정식 좌석표는 오류 신고로 정정해 주세요." };
  }
  return { kind: "message", message: info.message };
}
