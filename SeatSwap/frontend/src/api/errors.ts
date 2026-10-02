import axios from "axios";
import type { ApiErrorBody } from "../types/auth";

export interface ParsedApiError {
  /** 화면 상단에 보여줄 대표 메시지 */
  message: string;
  /** @Valid 실패 시 필드별 메시지 (필드명 -> 메시지) */
  fieldErrors: Record<string, string>;
}

function isStringRecord(value: unknown): value is Record<string, string> {
  return (
    typeof value === "object" &&
    value !== null &&
    !Array.isArray(value) &&
    Object.values(value).every((v) => typeof v === "string")
  );
}

/** 백엔드 GlobalExceptionHandler 포맷({message} 또는 {field: msg})을 화면용으로 변환 */
export function parseApiError(error: unknown, fallback = "요청을 처리하지 못했습니다."): ParsedApiError {
  if (!axios.isAxiosError(error)) return { message: fallback, fieldErrors: {} };

  if (!error.response) {
    return { message: "서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.", fieldErrors: {} };
  }

  const data: unknown = error.response.data;
  if (isStringRecord(data)) {
    const body: ApiErrorBody = data;
    if ("message" in body && typeof body.message === "string") {
      return { message: body.message, fieldErrors: {} };
    }
    const fieldErrors: Record<string, string> = { ...body };
    const first = Object.values(fieldErrors)[0];
    return { message: first ?? fallback, fieldErrors };
  }

  if (error.response.status >= 500) {
    return { message: "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", fieldErrors: {} };
  }
  return { message: fallback, fieldErrors: {} };
}
